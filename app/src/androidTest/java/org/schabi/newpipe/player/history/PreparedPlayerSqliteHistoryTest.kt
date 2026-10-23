/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.history

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.support.v4.media.session.MediaSessionCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player as EnginePlayer
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.mediaitem.StreamInfoTag
import org.schabi.newpipe.player.mediasource.LoadedMediaSource
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.player.ui.PlayerUiList

/**
 * Real prepared local PCM, Player listeners and the production history adapter against a uniquely
 * named on-disk Room database. No record manager, engine clock or completion event is fabricated.
 *
 * The service is context-attached, not Android-started; notification/media-session UIs are omitted.
 * The source manager and extractor are bypassed by installing the controlled source directly.
 * Its completion/navigation scenario intentionally uses one engine window under a two-item queue:
 * real STATE_ENDED drives the production completion policy, but that navigation is fixture-shaped,
 * not proof of normal manager-driven auto-transition scheduling.
 * These tests establish SQLite commits and application-process write lifetime, not process-death
 * durability, background-service survival, network extraction, audible output or receiver behavior.
 */
@RunWith(AndroidJUnit4::class)
class PreparedPlayerSqliteHistoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val singleton = NewPipeDatabase::class.java.getDeclaredField("databaseInstance").apply {
        isAccessible = true
    }
    private val cacheField = PlayerDataSource::class.java.getDeclaredField("cache").apply {
        isAccessible = true
    }
    private var previousCache: Any? = null
    private var cacheInstalled = false
    private lateinit var isolatedCache: SimpleCache
    private lateinit var directory: File
    private lateinit var application: Context
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var database: AppDatabase
    private var previousDatabase: Any? = null
    private var databaseInstalled = false
    private lateinit var preferenceName: String
    private lateinit var databaseName: String
    private lateinit var media: File
    private lateinit var session: MediaSessionCompat
    private lateinit var player: Player
    private lateinit var engine: ExoPlayer
    private lateinit var history: PlaybackHistory
    private lateinit var queue: SinglePlayQueue
    private lateinit var first: LocalItem
    private lateinit var second: LocalItem
    private var destroyed = false

    @Before
    fun setUp() {
        previousDatabase = singleton.get(null)
        previousCache = cacheField.get(null)
        application = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        preferenceName = "prepared-history-$id"
        databaseName = "prepared-history-$id.db"
        preferences = application.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        directory = File(application.cacheDir, "prepared-history-$id").apply {
            assertTrue("Create isolated cache directory", mkdir())
        }
        isolatedCache = createCache(File(directory, "media-cache"))
        cacheField.set(null, isolatedCache)
        cacheInstalled = true
        context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        setRecording(true)
        database = openDatabase()
        singleton.set(null, database)
        databaseInstalled = true
        media = File(directory, "source.wav")
        writePcm(media)
        first = LocalItem(info("$id/a"))
        second = LocalItem(info("$id/b"))
        queue = SinglePlayQueue(first).apply {
            append(listOf(second))
            init()
            setRecovery(1, 12000)
        }
        history = PlaybackHistory(context)
        onMain {
            val service = PlayerService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            session = MediaSessionCompat(context, "prepared-history-$id")
            player = Player(service, session, MediaSessionConnector(session))
            // Retain the real Player, history, casting and application listeners, without creating
            // notifications or binding external UI/media-session behavior to this isolated fixture.
            setPlayerField("UIs", PlayerUiList())
            setPlayerField("playQueue", queue)
            setPlayerField("currentItem", first)
            Player::class.java.getDeclaredMethod("initPlayer", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(player, false)
            engine = player.exoPlayer
            engine.volume = 0f
        }
        awaitEngine("prepared local source and delivered ownership", {
            val child = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(media)))
            engine.setMediaSource(
                LoadedMediaSource(child, StreamInfoTag.of(first.info), first, Long.MAX_VALUE)
            )
            engine.prepare()
        }) {
            val timeline = engine.currentTimeline
            !timeline.isEmpty &&
                !timeline.getWindow(0, Timeline.Window()).isPlaceholder &&
                engine.playbackState == EnginePlayer.STATE_READY &&
                player.currentMetadata === MediaItemTag.from(engine.currentMediaItem).orElse(null)
        }
        onMain {
            assertEquals(20000L, engine.duration)
            assertTrue(player.isCurrentSourceSynchronized)
            assertSame(
                first,
                MediaItemTag.from(engine.currentMediaItem).get()
                    .getMaybeExtras(LoadedMediaSource::class.java).get().stream
            )
            assertFalse(engine.playWhenReady)
        }
        // Also drains the metadata VIEWED operation delivered by the real onEvents callback.
        assertEquals(PlayQueueItem.RECOVERY_UNSET, resume(first))
    }

    @After
    fun tearDown() {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                val earlier = failure
                if (earlier == null) failure = error else earlier.addSuppressed(error)
            }
        }
        attempt { if (::player.isInitialized) onMain { destroyPlayer() } }
        attempt { if (::history.isInitialized && ::first.isInitialized) resume(first) }
        attempt { if (::session.isInitialized) onMain { session.release() } }
        attempt { if (databaseInstalled) singleton.set(null, previousDatabase) }
        attempt { if (cacheInstalled) cacheField.set(null, previousCache) }
        attempt { if (::database.isInitialized) database.close() }
        attempt { if (::isolatedCache.isInitialized) isolatedCache.release() }
        attempt {
            if (::application.isInitialized && ::databaseName.isInitialized) application.deleteDatabase(databaseName)
        }
        attempt {
            if (::application.isInitialized && ::preferenceName.isInitialized) application.deleteSharedPreferences(preferenceName)
        }
        attempt {
            if (::directory.isInitialized) {
                assertTrue("Delete isolated media and cache", directory.deleteRecursively())
            }
        }
        failure?.let { throw it }
    }

    @Test
    fun nonzeroCheckpointIsAttributedAndZeroIsFilteredWithoutErasingResume() {
        onMain { player.saveStreamProgressState() }
        assertEquals(PlayQueueItem.RECOVERY_UNSET, resume(first))
        assertNull(state(first))
        assertEquals(0L, first.recoveryPosition)

        seek(8000)
        onMain { player.saveStreamProgressState() }
        assertEquals(8000L, resume(first))
        assertEquals(8000L, state(first)!!.progressMillis)
        assertEquals(8000L, first.recoveryPosition)
        assertNull(state(second))
        assertEquals(12000L, second.recoveryPosition)

        seek(0)
        onMain { player.saveStreamProgressState() }
        assertEquals(8000L, resume(first))
        assertEquals(8000L, state(first)!!.progressMillis)
        assertEquals(
            "Recovery accepts the real zero; SQLite save rules do not",
            0L,
            first.recoveryPosition
        )

        setRecording(false)
        seek(9000)
        onMain { player.saveStreamProgressState() }
        assertEquals(8000L, resume(first))
        assertEquals(8000L, state(first)!!.progressMillis)
        assertEquals(9000L, first.recoveryPosition)
        assertNull(state(second))
        assertEquals(
            1L,
            database.streamHistoryDAO().getAll().firstOrError()
                .timeout(30, TimeUnit.SECONDS).blockingGet().single().repeatCount
        )
    }

    @Test
    fun acceptedCheckpointAndReplacementResumeOutliveNavigationAndPlayerDestruction() {
        withHeldHistoryWrites { release ->
            seek(8000)
            onMain {
                player.saveStreamProgressState()
                assertEquals(8000L, first.recoveryPosition)
                player.selectQueueItem(second)
                destroyPlayer()
                assertEquals(12000L, second.recoveryPosition)
            }
            assertNull("Accepted checkpoint is still held before SQLite", state(first))
            val replacementResume = PlaybackHistory(context).resumePosition(first).test()
            try {
                replacementResume.assertNoValues().assertNotComplete()
                release()
                assertTrue(replacementResume.await(30, TimeUnit.SECONDS))
                replacementResume.assertValue(8000L).assertComplete().assertNoErrors()
                assertEquals(8000L, state(first)!!.progressMillis)
                assertNull(state(second))
                assertEquals(8000L, first.recoveryPosition)
                assertEquals(12000L, second.recoveryPosition)
            } finally {
                replacementResume.dispose()
            }
        }
        // Reopening the uniquely named file distinguishes a committed SQLite state from queue
        // recovery, a fake repository, or a value retained only by the history adapter.
        database.close()
        database = openDatabase()
        singleton.set(null, database)
        history = PlaybackHistory(context)
        assertEquals(8000L, state(first)!!.progressMillis)
        assertEquals(8000L, resume(first))
    }

    @Test
    fun realEndOfSourceCompletionFollowsHeldCheckpointsAndSurvivesDestruction() {
        val laterExecutor = Executors.newSingleThreadExecutor()
        try {
            withHeldHistoryWrites { release ->
                seek(8000)
                onMain { player.saveStreamProgressState() }
                val laterScheduler = Schedulers.from(laterExecutor)
                RxJavaPlugins.setIoSchedulerHandler { laterScheduler }
                seek(19900)
                awaitEngine("decoder reaches actual end of local PCM", { engine.play() }) {
                    engine.playbackState == EnginePlayer.STATE_ENDED && queue.index == 1
                }
                onMain {
                    assertEquals(Player.STATE_COMPLETED, player.currentState)
                    assertSame(second, queue.item)
                    destroyPlayer()
                    assertEquals(12000L, second.recoveryPosition)
                }
                // Later observations have an independent runnable executor. Give any subscribed
                // late writes a complete execution opportunity before releasing the early one:
                // FIFO in the test executor must not stand in for PlaybackHistory's ordering.
                laterExecutor.submit {}.get(30, TimeUnit.SECONDS)
                assertNull("Later writes cannot overtake the held checkpoint", state(first))
                val replacementResume = PlaybackHistory(context).resumePosition(first).test()
                try {
                    replacementResume.assertNoValues().assertNotComplete()
                    release()
                    assertTrue(replacementResume.await(30, TimeUnit.SECONDS))
                    replacementResume.assertValue(PlayQueueItem.RECOVERY_UNSET)
                        .assertComplete().assertNoErrors()
                    assertEquals(
                        "Completion must follow every earlier checkpoint",
                        21000L,
                        state(first)!!.progressMillis
                    )
                    assertEquals(
                        "Recovery is not the persisted completion marker",
                        19900L,
                        first.recoveryPosition
                    )
                    assertEquals(12000L, second.recoveryPosition)
                    assertNull(state(second))
                } finally {
                    replacementResume.dispose()
                }
            }
        } finally {
            try {
                awaitHistoryWrites()
            } finally {
                laterExecutor.shutdown()
                assertTrue(
                    "Later history executor finished",
                    laterExecutor.awaitTermination(30, TimeUnit.SECONDS)
                )
            }
        }
    }

    private fun seek(position: Long) {
        awaitEngine("seek to $position", { engine.seekTo(position) }) {
            engine.playbackState == EnginePlayer.STATE_READY && engine.currentPosition == position
        }
    }

    private fun awaitEngine(description: String, action: () -> Unit, condition: () -> Boolean) {
        val arrived = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException>()
        val listener = object : EnginePlayer.Listener {
            override fun onEvents(player: EnginePlayer, events: EnginePlayer.Events) {
                if (condition()) arrived.countDown()
            }

            override fun onPlayerError(error: PlaybackException) {
                failure.set(error)
                arrived.countDown()
            }
        }
        onMain {
            engine.addListener(listener)
            action()
            if (condition()) arrived.countDown()
        }
        try {
            assertTrue("Timed out waiting for $description", arrived.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Engine failed during $description", it) }
            onMain { assertTrue(description, condition()) }
        } finally {
            onMain { engine.removeListener(listener) }
        }
    }

    private fun withHeldHistoryWrites(block: (() -> Unit) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val previous = RxJavaPlugins.getIoSchedulerHandler()
        val blocker = executor.submit {
            entered.countDown()
            check(released.await(180, TimeUnit.SECONDS)) { "History write gate was never released" }
        }
        try {
            assertTrue("History executor gate entered", entered.await(30, TimeUnit.SECONDS))
            val scheduler = Schedulers.from(executor)
            RxJavaPlugins.setIoSchedulerHandler { scheduler }
            block {
                RxJavaPlugins.setIoSchedulerHandler(previous)
                released.countDown()
            }
        } finally {
            RxJavaPlugins.setIoSchedulerHandler(previous)
            released.countDown()
            try {
                awaitHistoryWrites()
            } finally {
                executor.shutdown()
                assertTrue("History executor finished", executor.awaitTermination(30, TimeUnit.SECONDS))
                blocker.get(30, TimeUnit.SECONDS)
            }
        }
    }

    private fun awaitHistoryWrites() {
        // Queued operations capture their scheduler before subscription. Keep both executors
        // alive until concatMap has subscribed and completed every accepted write, even on failure.
        assertTrue(
            "Accepted history writes finish before executor shutdown",
            AndroidPlaybackHistoryEnvironment.WRITES.afterPrevious().blockingAwait(30, TimeUnit.SECONDS)
        )
    }

    private fun resume(item: LocalItem): Long = history.resumePosition(item)
        .timeout(30, TimeUnit.SECONDS).blockingGet()

    private fun state(item: LocalItem): StreamStateEntity? {
        val stream = database.streamDAO().getStream(item.serviceId.toLong(), item.url)
            .firstOrError().timeout(30, TimeUnit.SECONDS).blockingGet().firstOrNull()
        return stream?.let { database.streamStateDAO().getStateSync(it.uid) }
    }

    private fun setRecording(enabled: Boolean) {
        assertTrue(
            preferences.edit().putBoolean(
                application.getString(R.string.enable_watch_history_key),
                enabled
            ).commit()
        )
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        databaseName
    ).build()

    private fun destroyPlayer() {
        if (!destroyed) {
            player.destroy()
            destroyed = true
        }
    }

    @Suppress("DEPRECATION")
    private fun createCache(directory: File): SimpleCache = SimpleCache(directory, LeastRecentlyUsedCacheEvictor(4L * 1024 * 1024))

    private fun setPlayerField(name: String, value: Any) {
        Player::class.java.getDeclaredField(name).apply { isAccessible = true }.set(player, value)
    }

    private fun onMain(block: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
    }

    private fun info(id: String): StreamInfo = StreamInfo(
        ServiceList.Bitchute.serviceId,
        "https://example.invalid/prepared-history/$id",
        "https://example.invalid/prepared-history/$id",
        StreamType.AUDIO_STREAM,
        id,
        "Local PCM $id",
        0
    ).apply {
        duration = 20
        uploaderName = "Isolated instrumentation"
    }

    private class LocalItem(val info: StreamInfo) : PlayQueueItem(info) {
        // Only extraction is controlled; production resume, Room lookup and filtering stay real.
        override fun getStream(): Single<StreamInfo> = Single.just(info)
    }

    private fun writePcm(file: File) {
        val bytesPerSecond = 8000 * 2
        val dataSize = bytesPerSecond * 20
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(bytesPerSecond)
            .putShort(2).putShort(16)
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize).array()
        FileOutputStream(file).use { output ->
            output.write(header)
            val secondOfSilence = ByteArray(bytesPerSecond)
            repeat(20) { output.write(secondOfSilence) }
        }
    }
}
