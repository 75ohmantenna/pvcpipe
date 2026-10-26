package org.schabi.newpipe.player.mediabrowser

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.ConcurrentHashMap
import org.schabi.newpipe.R
import org.schabi.newpipe.player.PlayerService

/** Media3 and legacy browsers share the existing catalog and caller policy. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SessionLibraryCallback(
    private val service: PlayerService,
    private val catalog: MediaBrowserImpl
) : MediaLibrarySession.Callback {
    private val validator = PackageValidator(service)
    private val disposables = CompositeDisposable()
    private val pending = ConcurrentHashMap.newKeySet<SettableFuture<*>>()
    private val searchRequests = mutableMapOf<MediaSession.ControllerInfo, ListenableFuture<*>>()
    private val searches = mutableMapOf<MediaSession.ControllerInfo, Pair<String, List<MediaItem>>>()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val accepted = session.isMediaNotificationController(controller) || controller.isTrusted ||
            runCatching { validator.isKnownCaller(controller.packageName, controller.uid) }
                .getOrDefault(false)
        return if (accepted) {
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(service.sessionCommands())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        } else {
            MediaSession.ConnectionResult.reject()
        }
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        searchRequests.remove(controller)?.cancel(false)
        searches.remove(controller)
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        catalog.root(params)?.let { LibraryResult.ofItem(it, params) }
            ?: LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = result(
        catalog.loadChildren(parentId).map { paginate(it, page, pageSize, params) }
    )

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> = result(
        catalog.loadChildren(parentId).observeOn(AndroidSchedulers.mainThread()).map {
            session.notifyChildrenChanged(browser, parentId, it.size, params)
            LibraryResult.ofVoid(params)
        }
    )

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        searchRequests.remove(browser)?.cancel(false)
        val request = result(
            catalog.search(query).observeOn(AndroidSchedulers.mainThread()).map {
                searches[browser] = query to it
                session.notifySearchResultChanged(browser, query, it.size, params)
                LibraryResult.ofVoid(params)
            }
        )
        searchRequests[browser] = request
        return request
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val cached = searches[browser]?.takeIf { it.first == query }?.second
        return result(
            (cached?.let { Single.just(it) } ?: catalog.search(query))
                .map { paginate(it, page, pageSize, params) }
        )
    }

    override fun onSetMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (mediaItems.size != 1 || mediaItems[0].requestMetadata.searchQuery != null ||
            mediaItems[0].requestMetadata.mediaUri != null ||
            mediaItems[0].localConfiguration != null || startIndex > 0
        ) {
            session.sendError(
                controller,
                SessionError(
                    SessionError.ERROR_NOT_SUPPORTED,
                    service.getString(R.string.content_not_supported),
                    Bundle.EMPTY
                )
            )
            return Futures.immediateFailedFuture(UnsupportedOperationException("Browser ID required"))
        }
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> = Futures.immediateFuture(
        SessionResult(
            if (service.dispatchSessionAction(customCommand.customAction)) {
                SessionResult.RESULT_SUCCESS
            } else {
                SessionError.ERROR_NOT_SUPPORTED
            }
        )
    )

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent
    ): Boolean = PreferenceManager.getDefaultSharedPreferences(service).getBoolean(
        service.getString(R.string.ignore_hardware_media_buttons_key),
        false
    )

    fun notifyChildrenChanged(session: MediaLibrarySession, parentId: String) {
        disposables.add(
            catalog.loadChildren(parentId).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).subscribe(
                    { session.notifyChildrenChanged(parentId, it.size, null) },
                    { /* A failed refresh must not publish an invented empty catalog. */ }
                )
        )
    }

    private fun <T : Any> result(source: Single<LibraryResult<T>>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        pending.add(future)
        val disposable = source.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread()).subscribe(
                { future.set(it) },
                { future.set(LibraryResult.ofError<T>(SessionError.ERROR_UNKNOWN)) }
            )
        disposables.add(disposable)
        future.addListener({
            disposables.remove(disposable)
            pending.remove(future)
        }, Runnable::run)
        return future
    }

    fun dispose() {
        pending.forEach { it.cancel(false) }
        disposables.dispose()
        searches.clear()
        searchRequests.clear()
    }

    companion object {
        internal fun paginate(
            items: List<MediaItem>,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): LibraryResult<ImmutableList<MediaItem>> {
            if (page < 0 || pageSize < 1) return LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            val start = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
            val end = (start.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt()
            return LibraryResult.ofItemList(items.subList(start, end), params)
        }
    }
}
