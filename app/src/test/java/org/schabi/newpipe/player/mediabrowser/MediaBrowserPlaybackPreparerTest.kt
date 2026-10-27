package org.schabi.newpipe.player.mediabrowser

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.session.SessionError
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.functions.Function as RxFunction
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.Callable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.MockedStatic
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.NavigationHelper

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaBrowserPlaybackPreparerTest {
    private val context = mock(Context::class.java)
    private val database = mock(AppDatabase::class.java)
    private val playlistStreams = mock(PlaylistStreamDAO::class.java)
    private val errors = mutableListOf<Int>()
    private val started = mutableListOf<Int>()
    private lateinit var statics: List<MockedStatic<*>>
    private var previousDatabase: AppDatabase? = null
    private var previousInitMain: RxFunction<Callable<Scheduler>, Scheduler>? = null
    private var previousMain: RxFunction<Scheduler, Scheduler>? = null
    private var previousIo: RxFunction<in Scheduler, out Scheduler>? = null

    @Before
    fun setUp() {
        `when`(context.getText(anyInt())).thenReturn("error")
        `when`(database.playlistStreamDAO()).thenReturn(playlistStreams)
        previousDatabase = databaseField().get(null) as AppDatabase?
        databaseField().set(null, database)
        val contexts = mockStatic(ContextCompat::class.java)
        contexts.`when`<Context> { ContextCompat.getContextForLanguage(context) }
            .thenReturn(context)
        val navigation = mockStatic(NavigationHelper::class.java)
        navigation.`when`<Unit> {
            NavigationHelper.playOnBackgroundPlayer(any(), any(), anyBoolean())
        }.thenAnswer { call ->
            started.add(call.getArgument<PlayQueue>(1).index)
            null
        }
        statics = listOf(contexts, navigation, mockStatic(Uri::class.java), mockStatic(Log::class.java))
        previousInitMain = RxAndroidPlugins.getInitMainThreadSchedulerHandler()
        previousMain = RxAndroidPlugins.getOnMainThreadSchedulerHandler()
        previousIo = RxJavaPlugins.getIoSchedulerHandler()
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun tearDown() {
        databaseField().set(null, previousDatabase)
        statics.forEach { it.close() }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(previousInitMain)
        RxAndroidPlugins.setMainThreadSchedulerHandler(previousMain)
        RxJavaPlugins.setIoSchedulerHandler(previousIo)
    }

    // The singleton opens a Room database, which needs a device; hand it the mock instead.
    private fun databaseField() = NewPipeDatabase::class.java.getDeclaredField("databaseInstance")
        .apply { isAccessible = true }

    private fun prepare(mediaId: String, path: List<String>, url: String? = null) {
        val uri = mock(Uri::class.java)
        `when`(uri.pathSegments).thenReturn(path)
        `when`(uri.getQueryParameter(ID_URL)).thenReturn(url)
        `when`(Uri.parse(mediaId)).thenReturn(uri)
        MediaBrowserPlaybackPreparer(context).resolve(mediaId).subscribe(
            { started.add(it.index) },
            { errors.add(SessionError.ERROR_UNKNOWN) }
        )
    }

    private fun playlistOf(size: Int) {
        val entries = (0 until size).map { index ->
            PlaylistStreamEntry(
                StreamEntity(
                    serviceId = 0,
                    url = "https://www.youtube.com/watch?v=dQw4w9WgXc$index",
                    title = "title",
                    streamType = StreamType.VIDEO_STREAM,
                    duration = 60,
                    uploader = "uploader"
                ),
                0,
                index.toLong(),
                index
            )
        }
        `when`(playlistStreams.getOrderedStreamsOf(1))
            .thenReturn(Flowable.just(entries.toMutableList()))
    }

    @Test
    fun aValidPlaylistIndexStartsPlayback() {
        playlistOf(2)
        prepare("local-valid", listOf(ID_BOOKMARKS, ID_LOCAL, "1", "1"))
        assertEquals(listOf(1), started)
        assertEquals(emptyList<Int>(), errors)
    }

    @Test
    fun anIndexPastTheEndOfThePlaylistReportsAPlaybackError() {
        playlistOf(2)
        prepare("local-stale", listOf(ID_BOOKMARKS, ID_LOCAL, "1", "2"))
        assertEquals(listOf(SessionError.ERROR_UNKNOWN), errors)
        assertEquals(emptyList<Int>(), started)
    }

    @Test
    fun malformedIdsReportPlaybackErrorsInsteadOfThrowing() {
        prepare("type", listOf(ID_INFO_ITEM, "comment", "0"), "https://www.youtube.com")
        prepare("overflow", listOf(ID_BOOKMARKS, ID_LOCAL, "99999999999999999999", "0"))
        prepare("service", listOf(ID_INFO_ITEM, ID_STREAM, "2147483648"), "https://youtu.be/x")
        prepare("history", listOf(ID_HISTORY, "x"))
        assertEquals(List(4) { SessionError.ERROR_UNKNOWN }, errors)
        assertEquals(emptyList<Int>(), started)
    }

    @Test
    fun anUnparseableUriReportsAPlaybackError() {
        // Uri.parse is left unstubbed and answers null, so the deferred work fails inside Rx.
        MediaBrowserPlaybackPreparer(context).resolve("unstubbed").subscribe(
            { started.add(it.index) },
            { errors.add(SessionError.ERROR_UNKNOWN) }
        )
        assertEquals(listOf(SessionError.ERROR_UNKNOWN), errors)
    }
}
