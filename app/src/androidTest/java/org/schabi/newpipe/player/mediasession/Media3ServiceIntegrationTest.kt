package org.schabi.newpipe.player.mediasession

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.notification.NotificationUtil

/** Real Media3 binder/browser integration. Requires a disposable app installation. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3ServiceIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var service: PlayerService
    private lateinit var browser: MediaBrowser
    private var bound = false
    private val sessionErrors = LinkedBlockingQueue<SessionError>()
    private val connected = CountDownLatch(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlayerService.LocalBinder).service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    @Before
    fun setUp() {
        check(InstrumentationRegistry.getArguments().getString("media3DisposableEnvironment") == "true") {
            "Run this service-lifecycle suite only on a disposable installation"
        }
        bound = context.bindService(
            Intent(context, PlayerService::class.java).setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION),
            connection,
            Context.BIND_AUTO_CREATE
        )
        assertTrue(bound)
        assertTrue(connected.await(20, TimeUnit.SECONDS))
        browser = request {
            MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java)))
                .setListener(object : MediaBrowser.Listener {
                    override fun onError(controller: MediaController, sessionError: SessionError) {
                        sessionErrors.offer(sessionError)
                    }
                }).buildAsync()
        }
    }

    @After
    fun tearDown() {
        onMain {
            if (::browser.isInitialized) browser.release()
            if (bound) context.unbindService(connection)
            context.stopService(Intent(context, PlayerService::class.java))
        }
    }

    @Test
    fun browsingKeepsTheDecoderAndNotificationIdle() {
        val root = request { browser.getLibraryRoot(null) }
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        assertEquals("//${BuildConfig.APPLICATION_ID}", root.value!!.mediaId)
        val first = children(root.value!!.mediaId, 0, 1)
        val second = children(root.value!!.mediaId, 1, 1)
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertTrue(first[0].mediaMetadata.isBrowsable == true)
        assertFalse(first[0].mediaMetadata.isPlayable == true)
        assertFalse(first[0].mediaId == second[0].mediaId)
        assertTrue(children(root.value!!.mediaId, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        onMain {
            assertNull(service.player)
            assertTrue(
                context.getSystemService(NotificationManager::class.java).activeNotifications
                    .none { it.id == NotificationUtil.NOTIFICATION_ID }
            )
        }
    }

    @Test
    fun frameworkLegacyBrowserRetainsRootAndBrowsableChildren() {
        // Media3's framework bridge learns the real browser identity from API 28 onward.
        // On API 26/27 use the compat messenger protocol, covered by the test below.
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 28)
        val ready = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        val children = AtomicReference<List<android.media.browse.MediaBrowser.MediaItem>>()
        lateinit var legacy: android.media.browse.MediaBrowser
        onMain {
            legacy = android.media.browse.MediaBrowser(
                context,
                ComponentName(context, PlayerService::class.java),
                object : android.media.browse.MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        ready.countDown()
                    }
                },
                null
            )
            legacy.connect()
        }
        try {
            assertTrue("Legacy browser connects", ready.await(20, TimeUnit.SECONDS))
            onMain {
                assertEquals("//${BuildConfig.APPLICATION_ID}", legacy.root)
                assertTrue(legacy.extras!!.getBoolean("android.media.browse.SEARCH_SUPPORTED"))
                legacy.subscribe(
                    legacy.root,
                    object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                        override fun onChildrenLoaded(
                            parentId: String,
                            items: MutableList<android.media.browse.MediaBrowser.MediaItem>
                        ) {
                            children.set(items)
                            loaded.countDown()
                        }
                    }
                )
            }
            assertTrue("Legacy children load", loaded.await(20, TimeUnit.SECONDS))
            assertEquals(2, children.get().size)
            assertTrue(children.get().all { it.isBrowsable && !it.isPlayable })
        } finally {
            onMain { legacy.disconnect() }
        }
    }

    @Test
    @android.annotation.SuppressLint("RestrictedApi")
    fun compatLegacyBrowserRetainsRootAndBrowsableChildren() {
        val ready = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        val children = AtomicReference<List<androidx.media3.session.legacy.MediaBrowserCompat.MediaItem>>()
        lateinit var legacy: androidx.media3.session.legacy.MediaBrowserCompat
        onMain {
            legacy = androidx.media3.session.legacy.MediaBrowserCompat(
                context,
                ComponentName(context, PlayerService::class.java),
                object : androidx.media3.session.legacy.MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        ready.countDown()
                    }
                },
                null
            )
            legacy.connect()
        }
        try {
            assertTrue("Compat browser connects", ready.await(20, TimeUnit.SECONDS))
            onMain {
                assertEquals("//${BuildConfig.APPLICATION_ID}", legacy.root)
                assertTrue(legacy.extras!!.getBoolean("android.media.browse.SEARCH_SUPPORTED"))
                legacy.subscribe(
                    legacy.root,
                    android.os.Bundle.EMPTY,
                    object : androidx.media3.session.legacy.MediaBrowserCompat.SubscriptionCallback() {
                        override fun onChildrenLoaded(
                            parentId: String?,
                            items: MutableList<androidx.media3.session.legacy.MediaBrowserCompat.MediaItem>?,
                            options: android.os.Bundle?
                        ) {
                            children.set(items.orEmpty())
                            loaded.countDown()
                        }
                    }
                )
            }
            assertTrue("Compat children load", loaded.await(20, TimeUnit.SECONDS))
            assertEquals(2, children.get().size)
            assertTrue(children.get().all { it.isBrowsable && !it.isPlayable })
        } finally {
            onMain { legacy.disconnect() }
        }
    }

    @Test
    fun unauthenticatedPlaybackExtrasCannotInitializeTheApplicationPlayer() {
        onMain {
            service.onStartCommand(
                Intent(context, PlayerService::class.java)
                    .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true),
                0,
                0
            )
            assertNull(service.player)
        }
    }

    @Test
    fun malformedBrowserPlaybackReportsAnErrorWithoutStartingAnEngine() {
        onMain {
            browser.setMediaItem(MediaItem.Builder().setMediaId("invalid-browser-id").build())
            browser.prepare()
            browser.play()
        }
        assertEquals(SessionError.ERROR_UNKNOWN, sessionErrors.poll(20, TimeUnit.SECONDS)?.code)
        onMain { assertNull(service.player) }
    }

    @Test
    fun arbitraryUriPlaybackIsRejectedWithoutStartingAnEngine() {
        onMain {
            browser.setMediaItem(MediaItem.fromUri("https://example.invalid/unsupported.mp4"))
            browser.prepare()
            browser.play()
        }
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, sessionErrors.poll(20, TimeUnit.SECONDS)?.code)
        onMain { assertNull(service.player) }
    }

    @Test
    fun closeCancelsHeldBrowserPreparationAndRejectsItsLateStart() {
        val pending = com.google.common.util.concurrent.SettableFuture.create<Void>()
        val disposed = java.util.concurrent.atomic.AtomicBoolean()
        val resolution = io.reactivex.rxjava3.disposables.Disposable.fromRunnable { disposed.set(true) }
        val requestId = java.util.UUID.randomUUID().toString()
        onMain {
            for ((name, value) in listOf(
                "browserPreparation" to pending,
                "browserResolution" to resolution,
                "browserRequest" to requestId
            )) {
                PlayerService::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .set(service, value)
            }
        }
        val closed = request {
            browser.sendCustomCommand(
                androidx.media3.session.SessionCommand(
                    org.schabi.newpipe.player.notification.NotificationConstants.ACTION_CLOSE,
                    android.os.Bundle.EMPTY
                ),
                android.os.Bundle.EMPTY
            )
        }
        assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS, closed.resultCode)
        assertTrue(pending.isCancelled)
        assertTrue(disposed.get())
        onMain {
            val stale = PlayerService.authenticateInternalIntent(
                context,
                Intent(context, PlayerService::class.java)
                    .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true)
                    .putExtra("browser_request", requestId)
            )
            service.onStartCommand(stale, 0, 0)
            assertNull(service.player)
        }
    }

    @Test
    fun realServicePlaybackAndSessionControlsKeepOneNotification() {
        val file = waveFile()
        val info = org.schabi.newpipe.extractor.stream.StreamInfo(
            org.schabi.newpipe.extractor.ServiceList.Bitchute.serviceId,
            "https://example.invalid/${file.name}",
            "https://example.invalid/${file.name}",
            org.schabi.newpipe.extractor.stream.StreamType.AUDIO_STREAM,
            file.name,
            "Media3 controlled local audio",
            0
        ).apply {
            duration = 20
            uploaderName = "Disposable service integration"
            audioStreams = listOf(
                org.schabi.newpipe.extractor.stream.AudioStream.Builder()
                    .setId("pcm").setContent(android.net.Uri.fromFile(file).toString(), true)
                    .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.WAV)
                    .setAverageBitrate(128).build()
            )
        }
        val item = object : org.schabi.newpipe.player.playqueue.PlayQueueItem(info) {
            override fun getStream(): io.reactivex.rxjava3.core.Single<org.schabi.newpipe.extractor.stream.StreamInfo> = io.reactivex.rxjava3.core.Single.just(info)
        }
        val queue = org.schabi.newpipe.player.playqueue.SinglePlayQueue(item)
        try {
            onMain { org.schabi.newpipe.util.NavigationHelper.playOnBackgroundPlayer(context, queue, false) }
            awaitCondition {
                service.player?.let {
                    it.isCurrentSourceSynchronized &&
                        it.exoPlayer.playbackState == androidx.media3.common.Player.STATE_READY
                } == true
            }
            onMain { browser.seekTo(8000) }
            awaitCondition { service.player!!.currentPosition == 8000L }
            onMain { browser.play() }
            awaitCondition { service.player!!.playWhenReady }
            onMain {
                assertEquals("Media3 controlled local audio", browser.mediaMetadata.title)
                assertEquals(2, browser.mediaButtonPreferences.size)
                val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
                assertEquals(1, notifications.count { it.id == NotificationUtil.NOTIFICATION_ID })
            }
            onMain { browser.pause() }
            awaitCondition { !service.player!!.playWhenReady }
            val closed = request {
                browser.sendCustomCommand(
                    androidx.media3.session.SessionCommand(
                        org.schabi.newpipe.player.notification.NotificationConstants.ACTION_CLOSE,
                        android.os.Bundle.EMPTY
                    ),
                    android.os.Bundle.EMPTY
                )
            }
            assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS, closed.resultCode)
            awaitCondition { service.player == null && browser.mediaItemCount == 0 }
        } finally {
            onMain { if (service.player != null) service.destroyPlayerAndStopService() }
            file.delete()
        }
    }

    @Test
    fun independentLocalFilePlayerPreparesSeeksAndPublishesPausedState() {
        val file = waveFile()
        val preferenceName = "media3-local-${java.util.UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        preferences.edit().putString(
            context.getString(org.schabi.newpipe.R.string.autoplay_key),
            context.getString(org.schabi.newpipe.R.string.autoplay_never_key)
        ).commit()
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        val paused = java.util.concurrent.atomic.AtomicInteger()
        lateinit var local: org.schabi.newpipe.player.LocalPlayer
        onMain {
            local = org.schabi.newpipe.player.LocalPlayer(isolated)
            local.setListener(object : org.schabi.newpipe.player.LocalPlayerListener {
                override fun onBlocked(player: androidx.media3.exoplayer.ExoPlayer) = Unit
                override fun onPlaying(player: androidx.media3.exoplayer.ExoPlayer) = Unit
                override fun onBuffering(player: androidx.media3.exoplayer.ExoPlayer) = Unit
                override fun onPaused(player: androidx.media3.exoplayer.ExoPlayer) {
                    paused.incrementAndGet()
                }
                override fun onPausedSeek(player: androidx.media3.exoplayer.ExoPlayer) = Unit
                override fun onCompleted(player: androidx.media3.exoplayer.ExoPlayer) = Unit
            })
            local.initialize(android.net.Uri.fromFile(file).toString(), emptyArray())
            local.exoPlayer.volume = 0f
        }
        try {
            awaitCondition { local.exoPlayer.playbackState == androidx.media3.common.Player.STATE_READY }
            onMain {
                assertEquals(20000L, local.exoPlayer.duration)
                assertTrue(paused.get() > 0)
                assertFalse(local.exoPlayer.playWhenReady)
                local.setPlaybackParameters(1.25f, 1f, false)
                assertEquals(1.25f, local.exoPlayer.playbackParameters.speed)
                local.exoPlayer.seekTo(7000)
            }
            awaitCondition { local.exoPlayer.currentPosition == 7000L }
            val activity = instrumentation.startActivitySync(
                Intent(context, org.schabi.newpipe.LocalPlayerActivity::class.java)
                    .setData(android.net.Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            try {
                onMain {
                    val view = activity.findViewById<androidx.media3.ui.PlayerView>(
                        org.schabi.newpipe.R.id.player_view
                    )
                    assertTrue(view.player != null)
                }
            } finally {
                onMain { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            onMain { local.destroy() }
            context.deleteSharedPreferences(preferenceName)
            file.delete()
        }
    }

    private fun waveFile(): java.io.File {
        val file = java.io.File(context.cacheDir, "media3-session-${java.util.UUID.randomUUID()}.wav")
        val rate = 8000 * 2
        val size = rate * 20
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(rate)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(size).array()
        file.outputStream().use {
            it.write(header)
            it.write(ByteArray(size))
        }
        return file
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var matched = false
            onMain { matched = condition() }
            if (matched) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for the real service/session condition")
    }

    private fun children(parent: String, page: Int, pageSize: Int): List<MediaItem> {
        val result = request { browser.getChildren(parent, page, pageSize, null) }
        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        return result.value!!.toList()
    }

    private fun <T> request(block: () -> ListenableFuture<T>): T {
        val future = AtomicReference<ListenableFuture<T>>()
        onMain { future.set(block()) }
        return future.get().get(20, TimeUnit.SECONDS)
    }

    private fun onMain(block: () -> Unit) {
        val error = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }
        error.get()?.let { throw it }
    }
}
