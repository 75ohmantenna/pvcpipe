/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player as EnginePlayer
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.SingleSubject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.history.PlaybackHistory
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.mediasource.LoadedMediaSource
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.player.ui.PlayerUiList

/**
 * Real Player, MediaSourceManager, source resolution, ExoPlayer preparation and audio rendering.
 * Only extracted metadata is gated: a pending Single models extraction without contacting a site.
 * The service is context-attached, not started; notification/media-session UIs are excluded.
 * Quality and mode changes exercise the production recovery/reload path on audio, not video
 * resolution, activity rotation, popup window/surface behavior or intent play/pause defaults.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerRecoveryEngineIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var application: Context
    private lateinit var preferenceName: String
    private lateinit var directory: File
    private lateinit var media: File
    private lateinit var database: AppDatabase
    private var previousDatabase: Any? = null
    private var databaseInstalled = false
    private lateinit var cache: SimpleCache
    private var previousCache: Any? = null
    private var cacheInstalled = false
    private lateinit var history: PlaybackHistory
    private var player: Player? = null
    private var queue: PlayQueue? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        preferenceName = "player-recovery-engine-$id"
        directory = File(application.cacheDir, preferenceName).apply { mkdirs() }
        media = File(directory, "playable.wav")
        writeWave(media)
        val preferences = application.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        preferences.edit()
            .putBoolean(application.getString(R.string.enable_watch_history_key), false)
            .commit()
        val context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = directory
        }
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databaseField().let {
            previousDatabase = it.get(null)
            it.set(null, database)
            databaseInstalled = true
        }
        // Use the file-backed index, not the application's persistent ExoPlayer SQLite DB.
        @Suppress("DEPRECATION")
        val isolatedCache = SimpleCache(File(directory, "media-cache"), LeastRecentlyUsedCacheEvictor(4 * 1024 * 1024L))
        cache = isolatedCache
        cacheField().let {
            previousCache = it.get(null)
            it.set(null, cache)
            cacheInstalled = true
        }
        history = PlaybackHistory(context)
        onMain {
            val service = PlayerService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }
                .invoke(service, context)
            player = Player(service).also {
                // UIs have not been initialized: do not start notifications or bind a service.
                Player::class.java.getDeclaredField("UIs").apply { isAccessible = true }
                    .set(it, PlayerUiList())
            }
        }
    }

    @After
    fun tearDown() {
        try {
            onMain {
                try {
                    player?.destroy()
                } finally {
                    try {
                        // This fixture does not create a system session.
                    } finally {
                        queue?.dispose()
                    }
                }
            }
        } finally {
            if (cacheInstalled) cacheField().set(null, previousCache)
            if (databaseInstalled) databaseField().set(null, previousDatabase)
            try {
                if (::cache.isInitialized) cache.release()
            } finally {
                try {
                    if (::database.isInitialized) database.close()
                } finally {
                    if (::application.isInitialized && ::preferenceName.isInitialized) {
                        application.deleteSharedPreferences(preferenceName)
                    }
                    if (::directory.isInitialized) directory.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun pendingDistinctSelectionThenQualityStartsAtSelectedRecovery() = pendingSelection(quality = true, removePlaying = false, sameUrl = false)

    @Test
    fun pendingDistinctSelectionThenAudioStartsAtSelectedRecovery() = pendingSelection(quality = false, removePlaying = false, sameUrl = false)

    @Test
    fun pendingSameUrlOccurrenceThenQualityStartsAtSelectedRecovery() = pendingSelection(quality = true, removePlaying = false, sameUrl = true)

    @Test
    fun pendingSameUrlOccurrenceThenAudioStartsAtSelectedRecovery() = pendingSelection(quality = false, removePlaying = false, sameUrl = true)

    @Test
    fun removedDistinctPlayingOccurrenceThenQualityStartsAtReindexedRecovery() = pendingSelection(quality = true, removePlaying = true, sameUrl = false)

    @Test
    fun removedDistinctPlayingOccurrenceThenAudioStartsAtReindexedRecovery() = pendingSelection(quality = false, removePlaying = true, sameUrl = false)

    @Test
    fun removedSameUrlPlayingOccurrenceThenQualityStartsAtReindexedRecovery() = pendingSelection(quality = true, removePlaying = true, sameUrl = true)

    @Test
    fun removedSameUrlPlayingOccurrenceThenAudioStartsAtReindexedRecovery() = pendingSelection(quality = false, removePlaying = true, sameUrl = true)

    @Test
    fun pendingSameUrlQualityDoesNotManufactureUnsetRecovery() = pendingSelection(quality = true, removePlaying = false, sameUrl = true, recovery = PlayQueueItem.RECOVERY_UNSET)

    @Test
    fun removedSameUrlAudioDoesNotManufactureUnsetRecovery() = pendingSelection(quality = false, removePlaying = true, sameUrl = true, recovery = PlayQueueItem.RECOVERY_UNSET)

    @Test
    fun pendingTimestampSurvivesSourceReloadAndStartsAtLatestRequestedPosition() = pendingSelection(quality = true, removePlaying = false, sameUrl = false, timestampSeconds = 30)

    @Test
    fun repeatedModeReloadsPreservePlayingPositionWithoutCallerSnapshots() = modeChanges(playWhenReady = true)

    @Test
    fun repeatedModeReloadsPreservePausedPositionWithoutCallerSnapshots() = modeChanges(playWhenReady = false)

    // A playing clock can advance while an emulated device schedules callbacks. Bound that
    // advancement by actual elapsed time; the no-rewind lower bound remains exact.
    private fun modeChanges(playWhenReady: Boolean) {
        val firstInfo = info("first")
        val first = MetadataItem(firstInfo).apply { metadata.onSuccess(firstInfo) }
        val selectedInfo = info("selected")
        val selected = MetadataItem(selectedInfo).apply { metadata.onSuccess(selectedInfo) }
        val playbackQueue = SinglePlayQueue(first).apply {
            append(listOf(selected))
            setIndex(1)
            setRecovery(1, PLAYING_POSITION)
        }
        queue = playbackQueue
        onMain {
            Player::class.java.getDeclaredMethod("initPlayback", PlayQueue::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(requireNotNull(player), playbackQueue, playWhenReady)
            requireNotNull(player).exoPlayer.volume = 0f
        }
        val engine = onMain { requireNotNull(player).exoPlayer }
        awaitEngine(engine, "Initial source must be ready at its recovery") {
            engine.playbackState == EnginePlayer.STATE_READY && owner(engine) === selected && requireNotNull(player).isCurrentSourceSynchronized
        }
        for (mode in listOf(PlayerType.POPUP, PlayerType.MAIN, PlayerType.AUDIO, PlayerType.POPUP, PlayerType.MAIN, PlayerType.AUDIO, PlayerType.MAIN)) {
            val (position, timeline, capturedAt) = onMain {
                val active = requireNotNull(player)
                val before = Triple(engine.currentPosition, engine.currentTimeline, SystemClock.elapsedRealtime())
                val previousMode = active.playerType
                Player::class.java.getDeclaredField("playerType").apply { isAccessible = true }.set(active, mode)
                Player::class.java.getDeclaredField("isAudioOnly").apply { isAccessible = true }.setBoolean(active, mode == PlayerType.AUDIO)
                // Exercise the service's production post-intent reload, without a UI saving first.
                active.handleIntentPost(previousMode)
                before
            }
            val sample = awaitEngine(engine, "$mode must restore position after replacing its timeline") {
                engine.currentTimeline !== timeline && engine.playbackState == EnginePlayer.STATE_READY &&
                    owner(engine) === selected && requireNotNull(player).isCurrentSourceSynchronized
            }
            val elapsed = if (playWhenReady) sample.sampledAt - capturedAt else 0
            val resumedAt = sample.position
            assertTrue("$mode resumed at $resumedAt instead of $position after ${elapsed}ms", resumedAt >= position && resumedAt < position + elapsed + POSITION_TOLERANCE)
            onMain {
                assertEquals(1, playbackQueue.index)
                assertSame(selected, playbackQueue.item)
                assertEquals(playWhenReady, engine.playWhenReady)
                assertEquals(PlayQueueItem.RECOVERY_UNSET, selected.recoveryPosition)
            }
        }
    }

    private fun pendingSelection(
        quality: Boolean,
        removePlaying: Boolean,
        sameUrl: Boolean,
        recovery: Long = SELECTED_RECOVERY,
        timestampSeconds: Int? = null
    ) {
        val expectedRecovery = timestampSeconds?.times(1000L) ?: recovery
        val firstInfo = info("playing")
        val selectedInfo = if (sameUrl) firstInfo else info("selected")
        val first = MetadataItem(firstInfo).apply { metadata.onSuccess(firstInfo) }
        val selected = MetadataItem(selectedInfo)
        val followingInfo = info("following")
        val following = MetadataItem(followingInfo).apply { metadata.onSuccess(followingInfo) }
        val playbackQueue = SinglePlayQueue(first).apply {
            append(listOf(selected, following))
            setRecovery(0, PLAYING_POSITION)
            if (recovery != PlayQueueItem.RECOVERY_UNSET) {
                setRecovery(1, recovery)
            }
        }
        queue = playbackQueue
        val playbackRequestedAt = SystemClock.elapsedRealtime()
        onMain {
            Player::class.java.getDeclaredMethod("initPlayback", PlayQueue::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(requireNotNull(player), playbackQueue, true)
            requireNotNull(player).exoPlayer.volume = 0f
        }
        val engine = onMain { requireNotNull(player).exoPlayer }
        awaitEngine(engine, "A must be prepared, owned and actually playing") {
            engine.isPlaying && owner(engine) === first && requireNotNull(player).isCurrentSourceSynchronized
        }
        assertTrue("B metadata must have been requested by real preloading", selected.firstSubscription.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        onMain {
            assertTrue(engine.currentPosition >= PLAYING_POSITION)
            val elapsed = SystemClock.elapsedRealtime() - playbackRequestedAt
            assertTrue("A position=${engine.currentPosition} after ${elapsed}ms", engine.currentPosition < PLAYING_POSITION + elapsed + POSITION_TOLERANCE)
            assertNotSame(first, selected)
            assertEquals(sameUrl, first.url == selected.url)
            requireNotNull(player).selectQueueItem(selected)
            if (removePlaying) {
                playbackQueue.remove(0)
            }
            assertSame(selected, playbackQueue.item)
            assertSame("Before manager delivery the real prepared window still belongs to A", first, owner(engine))
            assertEquals(if (removePlaying) 0 else 1, playbackQueue.index)
            assertEquals(0, engine.currentMediaItemIndex)
            if (timestampSeconds != null) {
                Player::class.java.getDeclaredMethod("playFromTimestamp", StreamInfo::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(requireNotNull(player), selectedInfo, timestampSeconds, true)
            }
            if (quality) {
                requireNotNull(player).setPlaybackQuality("720p")
            } else {
                requireNotNull(player).setAudioTrack("french")
            }
            assertEquals("Track change must preserve B's latest request, not A's clock", expectedRecovery, selected.recoveryPosition)
        }
        assertTrue("Replacement manager must request B's still-pending metadata", selected.replacementSubscription.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val sourceReleasedAt = onMain {
            assertFalse("Metadata must still be pending", selected.metadata.hasValue())
            assertSame(selected, playbackQueue.item)
            assertEquals("Recovery must survive asynchronous manager rebuilding", expectedRecovery, selected.recoveryPosition)
            if (timestampSeconds == null) {
                assertSame("The stopped prepared engine source is still A, not selected B", first, owner(engine))
            } else {
                // The indexed timestamp seek can already expose B's unresolved placeholder.
                assertNotSame("Pending B must not own a prepared source yet", selected, owner(engine))
                assertFalse(requireNotNull(player).isCurrentSourceSynchronized)
            }
            val releasedAt = SystemClock.elapsedRealtime()
            selected.metadata.onSuccess(selectedInfo)
            releasedAt
        }
        val sample = awaitEngine(engine, "B must actually start from its own recovery") {
            engine.isPlaying && owner(engine) === selected && requireNotNull(player).isCurrentSourceSynchronized
        }
        val expected = if (expectedRecovery == PlayQueueItem.RECOVERY_UNSET) 0 else expectedRecovery
        val elapsed = sample.sampledAt - sourceReleasedAt
        val startedAt = sample.position
        assertTrue("B started at $startedAt, expected $expected after ${elapsed}ms", startedAt >= expected && startedAt < expected + elapsed + POSITION_TOLERANCE)
        val advanced = CountDownLatch(1)
        onMain {
            assertSame(selected, playbackQueue.item)
            assertEquals(if (removePlaying) 0 else 1, engine.currentMediaItemIndex)
            assertEquals("Synchronization consumes B's recovery", PlayQueueItem.RECOVERY_UNSET, selected.recoveryPosition)
            val tag = MediaItemTag.from(engine.currentMediaItem).get()
            assertSame(selectedInfo, tag.maybeStreamInfo.get())
            if (!quality) {
                assertEquals("french", requireNotNull(tag.maybeAudioTrack.get().selectedAudioStream).audioTrackId)
            }
            engine.createMessage { _, _ -> advanced.countDown() }
                .setPosition(engine.currentMediaItemIndex, engine.currentPosition + 300)
                .send()
        }
        assertTrue("B must advance on the actual playback thread", advanced.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        onMain {
            assertSame(selected, owner(engine))
            assertTrue(engine.isPlaying)
            engine.pause()
        }
        // A resume lookup is an application-write barrier, not merely a synchronous DAO read.
        history.resumePosition(selected).timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).blockingGet()
        assertTrue("Disabled recording must leave isolated history empty", database.streamHistoryDAO().getAll().blockingFirst().isEmpty())
        assertTrue("Disabled recording must leave isolated recovery storage empty", database.streamStateDAO().getAll().blockingFirst().isEmpty())
    }

    private data class EngineSample(val position: Long, val sampledAt: Long)

    /** Samples only real engine events (with a registration-time check for an already-ready player). */
    private fun awaitEngine(engine: ExoPlayer, description: String, condition: () -> Boolean): EngineSample {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        var position = -1L
        var sampledAt = -1L
        val listener = object : EnginePlayer.Listener {
            override fun onEvents(player: EnginePlayer, events: EnginePlayer.Events) {
                try {
                    engine.playerError?.let { throw AssertionError(description, it) }
                    if (done.count > 0 && condition()) {
                        position = engine.currentPosition
                        sampledAt = SystemClock.elapsedRealtime()
                        done.countDown()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                    done.countDown()
                }
            }
        }
        onMain {
            engine.addListener(listener)
            engine.playerError?.let {
                failure.set(AssertionError(description, it))
                done.countDown()
            }
            if (condition()) {
                position = engine.currentPosition
                sampledAt = SystemClock.elapsedRealtime()
                done.countDown()
            }
        }
        try {
            val arrived = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!arrived) {
                val diagnostic = onMain {
                    "state=${engine.playbackState}, loading=${engine.isLoading}, " +
                        "position=${engine.currentPosition}, buffered=${engine.bufferedPosition}, " +
                        "duration=${engine.duration}, playing=${engine.isPlaying}, " +
                        "suppression=${engine.playbackSuppressionReason}, owner=${owner(engine)?.title}, " +
                        "synchronized=${requireNotNull(player).isCurrentSourceSynchronized}, " +
                        "tracks=${engine.currentTracks.groups.map { it.mediaTrackGroup.getFormat(0) }}"
                }
                throw AssertionError("$description: $diagnostic")
            }
            failure.get()?.let { throw it }
            return EngineSample(position, sampledAt)
        } finally {
            onMain { engine.removeListener(listener) }
        }
    }

    private fun owner(engine: ExoPlayer): PlayQueueItem? = MediaItemTag.from(engine.currentMediaItem)
        .flatMap { it.getMaybeExtras(Any::class.java) }
        .filter { it is LoadedMediaSource }
        .map { (it as LoadedMediaSource).stream }
        .orElse(null)

    private fun info(name: String): StreamInfo {
        val url = "https://example.invalid/recovery/${directory.name}/$name"
        return StreamInfo(ServiceList.SoundCloud.serviceId, url, url, StreamType.AUDIO_STREAM, name, name, 0).apply {
            duration = DURATION_SECONDS.toLong()
            audioStreams = listOf("english", "french").map { language ->
                AudioStream.Builder()
                    .setId(language)
                    .setContent(Uri.fromFile(media).toString(), true)
                    .setMediaFormat(MediaFormat.WAV)
                    .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                    .setAverageBitrate(128)
                    .setAudioTrackId(language)
                    .setAudioTrackName(language)
                    .build()
            }
        }
    }

    private class MetadataItem(info: StreamInfo) : PlayQueueItem(info) {
        val metadata: SingleSubject<StreamInfo> = SingleSubject.create()
        val firstSubscription = CountDownLatch(1)
        val replacementSubscription = CountDownLatch(2)

        override fun getStream(): Single<StreamInfo> = Single.defer {
            firstSubscription.countDown()
            replacementSubscription.countDown()
            metadata
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                result.set(action())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun databaseField() = NewPipeDatabase::class.java.getDeclaredField("databaseInstance").apply { isAccessible = true }

    private fun cacheField() = PlayerDataSource::class.java.getDeclaredField("cache").apply { isAccessible = true }

    private fun writeWave(file: File) {
        val sampleRate = 8000
        val bytes = sampleRate * DURATION_SECONDS * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + bytes)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1.toShort()).putShort(1.toShort()).putInt(sampleRate)
            .putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes).array()
        file.outputStream().use { output ->
            output.write(header)
            val silence = ByteArray(16000)
            repeat(DURATION_SECONDS) { output.write(silence) }
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 20L
        private const val DURATION_SECONDS = 90
        private const val PLAYING_POSITION = 42000L
        private const val SELECTED_RECOVERY = 12000L
        private const val POSITION_TOLERANCE = 1500L
    }
}
