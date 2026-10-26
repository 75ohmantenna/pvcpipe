package org.schabi.newpipe.player.resolver

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.player.helper.LoadController
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.helper.PlayerHelper

/** Actual HLS and subtitle loading with generated local media; no site or receiver dependency. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3SourceEngineIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val cacheField = PlayerDataSource::class.java.getDeclaredField("cache").apply { isAccessible = true }
    private var previousCache: Any? = null
    private lateinit var directory: File
    private lateinit var cache: SimpleCache
    private lateinit var data: PlayerDataSource
    private lateinit var player: ExoPlayer
    private val engineError = AtomicReference<Throwable>()

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "media3-source-${UUID.randomUUID()}").apply { mkdirs() }
        previousCache = cacheField.get(null)
        @Suppress("DEPRECATION")
        cache = SimpleCache(File(directory, "cache"), LeastRecentlyUsedCacheEvictor(4L * 1024 * 1024))
        cacheField.set(null, cache)
        data = PlayerDataSource(context, DefaultBandwidthMeter.Builder(context).build())
        onMain {
            player = ExoPlayer.Builder(context).setLoadControl(LoadController()).build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    engineError.set(error)
                }
            })
        }
    }

    @After
    fun tearDown() {
        if (::player.isInitialized) onMain { player.release() }
        cacheField.set(null, previousCache)
        if (::cache.isInitialized) cache.release()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun productionLiveHlsFactoryPreparesControlledBoundedMedia() {
        val segment = instrumentation.context.assets.open("media3-engine/silence.ts").use { it.readBytes() }
        val manifest = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n" +
            "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4.0,\nsegment.ts\n#EXT-X-ENDLIST\n"
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { /* Drain request headers. */ }
                        val playlist = request.contains("/playlist.m3u8 ")
                        val body = if (playlist) manifest.toByteArray(Charsets.UTF_8) else segment
                        val type = if (playlist) "application/vnd.apple.mpegurl" else "video/mp2t"
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray()
                            )
                            write(body)
                            flush()
                        }
                    }
                } catch (failure: Exception) {
                    if (!server.isClosed) engineError.compareAndSet(null, failure)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            onMain {
                player.setMediaSource(
                    data.liveHlsMediaSourceFactory.createMediaSource(
                        MediaItem.fromUri("http://127.0.0.1:${server.localPort}/playlist.m3u8")
                    )
                )
                player.prepare()
            }
            await { player.playbackState == Player.STATE_READY && player.duration > 0 }
            onMain { assertTrue(player.currentTracks.groups.isNotEmpty()) }
        } finally {
            server.close()
            worker.join(6000)
        }
    }

    @Test
    fun productionSubRipSubtitlesProduceRealCues() = subtitlesProduceRealCues(
        MediaFormat.SRT,
        "1\n00:00:00,000 --> 00:00:03,000\nMedia3 controlled caption\n\n"
    )

    @Test
    fun productionWebVttSubtitlesProduceRealCues() = subtitlesProduceRealCues(
        MediaFormat.VTT,
        "WEBVTT\n\n00:00:00.000 --> 00:00:03.000\nMedia3 controlled caption\n\n"
    )

    @Test
    fun productionTtmlSubtitlesProduceRealCues() = subtitlesProduceRealCues(
        MediaFormat.TTML,
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0s" end="3s">Media3 controlled caption</p></div></body></tt>"""
    )

    private fun subtitlesProduceRealCues(format: MediaFormat, content: String) {
        val wave = File(directory, "audio.wav")
        val rate = 8000 * 2
        val size = rate * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(rate)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(size).array()
        wave.outputStream().use {
            it.write(header)
            it.write(ByteArray(size))
        }
        val subtitles = File(directory, "captions.${format.suffix}").apply { writeText(content) }
        val configuration = SubtitlesStream.Builder().setId("captions").setContent(Uri.fromFile(subtitles).toString(), true)
            .setMediaFormat(format).setLanguageCode("en").setAutoGenerated(false).build()
        val info = StreamInfo(0, "https://example.invalid/media3-caption", "https://example.invalid/media3-caption", StreamType.VIDEO_STREAM, "caption-test", "Caption test", 0)
        info.subtitles = listOf(configuration)
        val expectedLanguage = androidx.media3.common.Format.Builder()
            .setLanguage(PlayerHelper.captionLanguageOf(context, configuration)).build().language
        val received = CountDownLatch(1)
        onMain {
            player.addListener(object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    if (cueGroup.cues.any { it.text?.toString() == "Media3 controlled caption" }) {
                        received.countDown()
                    }
                }
            })
            val media = data.progressiveMediaSourceFactory.createMediaSource(MediaItem.fromUri(Uri.fromFile(wave)))
            val text = AndroidPlaybackEnvironment(context, data).subtitleSources(info).single()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(expectedLanguage).build()
            player.setMediaSource(MergingMediaSource(media, text))
            player.prepare()
            player.play()
        }
        assertTrue("The real text renderer emits a decoded subtitle cue", received.await(20, TimeUnit.SECONDS))
        engineError.get()?.let { throw AssertionError("Media3 source error", it) }
        onMain {
            val text = player.currentTracks.groups.single { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSelected }
                .getTrackFormat(0)
            assertEquals("Caption display language is retained", expectedLanguage, text.language)
            assertEquals("Caption role is retained", androidx.media3.common.C.ROLE_FLAG_CAPTION, text.roleFlags)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            engineError.get()?.let { throw AssertionError("Media3 source error", it) }
            var matched = false
            onMain { matched = condition() }
            if (matched) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out preparing controlled Media3 source")
    }

    private fun onMain(action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
    }
}
