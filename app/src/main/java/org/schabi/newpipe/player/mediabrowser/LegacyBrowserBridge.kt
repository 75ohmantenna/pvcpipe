package org.schabi.newpipe.player.mediabrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.Bundle
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.session.legacy.MediaBrowserCompat
import androidx.media3.session.legacy.MediaBrowserServiceCompat
import androidx.media3.session.legacy.MediaDescriptionCompat
import androidx.media3.session.legacy.MediaSessionCompat
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.SerialDisposable
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * API 26/27 browser transport using the same catalog, caller policy and platform session.
 * Media3's library bridge loses native browser identity after onGetRoot on these APIs.
 * This delegate validates the real identity at the root and lets the compat service retain
 * each authorized connection. Playback commands still use the one Media3 session token.
 * The legacy types are bundled with our pinned Media3 session artifact, not a direct
 * dependency on the retired support Media library.
 */
@SuppressLint("RestrictedApi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LegacyBrowserBridge(
    context: Context,
    private val catalog: MediaBrowserImpl,
    private val platformToken: MediaSession.Token
) : MediaBrowserServiceCompat() {
    private val validator = PackageValidator(context)
    private val requests = CompositeDisposable()
    private val pending = mutableSetOf<Result<List<MediaBrowserCompat.MediaItem>>>()

    init {
        attachToBaseContext(context)
    }

    override fun onCreate() {
        super.onCreate()
        // Compat controllers negotiate their extra binder through COMMAND_GET_EXTRA_BINDER.
        // The public platform token keeps this delegate on the same session without using
        // Media3's private full-token accessor.
        setSessionToken(MediaSessionCompat.Token.fromToken(platformToken))
    }

    fun bindBrowser(intent: Intent): IBinder? = super.onBind(intent)

    fun notifyCatalogChanged(parentId: String) = notifyChildrenChanged(parentId)

    override fun onGetRoot(clientPackageName: String?, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (clientPackageName == null ||
            !runCatching { validator.isKnownCaller(clientPackageName, clientUid) }.getOrDefault(false) ||
            rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT, false) == true
        ) {
            return null
        }
        return BrowserRoot(
            ID_ROOT,
            Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }
        )
    }

    override fun onLoadChildren(parentId: String?, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        if (parentId == null) {
            result.sendResult(null)
        } else {
            load(catalog.loadChildren(parentId), result)
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        load(catalog.search(query), result)
    }

    private fun load(source: Single<List<MediaItem>>, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        pending.add(result)
        val slot = SerialDisposable()
        requests.add(slot)
        slot.set(
            source.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .doFinally {
                    pending.remove(result)
                    requests.delete(slot)
                }.subscribe(
                    { items -> result.sendResult(items.map(::legacyItem)) },
                    { result.sendResult(null) }
                )
        )
    }

    override fun onDestroy() {
        val unfinished = pending.toList()
        requests.dispose()
        unfinished.forEach { it.sendResult(null) }
        pending.clear()
        super.onDestroy()
    }

    private fun legacyItem(item: MediaItem): MediaBrowserCompat.MediaItem {
        val metadata = item.mediaMetadata
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(item.mediaId).setTitle(metadata.title).setSubtitle(metadata.subtitle)
            .setDescription(metadata.description).setIconUri(metadata.artworkUri)
            .setExtras(metadata.extras).build()
        val flags = (if (metadata.isBrowsable == true) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else 0) or
            (if (metadata.isPlayable == true) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else 0)
        return MediaBrowserCompat.MediaItem(description, flags)
    }
}
