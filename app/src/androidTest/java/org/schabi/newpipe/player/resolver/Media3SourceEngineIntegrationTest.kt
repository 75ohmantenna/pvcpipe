package org.schabi.newpipe.player.resolver

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.helper.LoadController
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.mediaitem.MediaItemTag

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
    private lateinit var isolatedContext: Context
    private lateinit var preferenceName: String
    private var videoSurface: PlaceholderSurface? = null
    private val cues = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "media3-source-${UUID.randomUUID()}").apply { mkdirs() }
        preferenceName = "media3-source-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        previousCache = cacheField.get(null)
        @Suppress("DEPRECATION")
        cache = SimpleCache(File(directory, "cache"), LeastRecentlyUsedCacheEvictor(4L * 1024 * 1024))
        cacheField.set(null, cache)
        data = PlayerDataSource(isolatedContext, DefaultBandwidthMeter.Builder(context).build())
        onMain {
            player = ExoPlayer.Builder(context, org.schabi.newpipe.player.helper.PlaybackRenderersFactory(context))
                .setLoadControl(LoadController()).build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    engineError.set(error)
                }

                override fun onCues(cueGroup: CueGroup) {
                    cues.addAll(cueGroup.cues.mapNotNull { it.text?.toString() })
                }
            })
        }
    }

    @After
    fun tearDown() {
        if (::player.isInitialized) onMain { player.release() }
        videoSurface?.release()
        if (::preferenceName.isInitialized) context.deleteSharedPreferences(preferenceName)
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

    @Test
    fun unselectedMissingSubtitleDoesNotLoadOrBlockPlayback() {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { /* Drain headers. */ }
                        requests.incrementAndGet()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
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
            optionalSubtitleKeepsPlaybackReady(Uri.parse("http://127.0.0.1:${server.localPort}/missing.vtt"), false)
            assertEquals("An unselected optional track must not be fetched", 0, requests.get())
        } finally {
            server.close()
            worker.join(6000)
        }
    }

    @Test
    fun unselectedMalformedSubtitleDoesNotBlockPlayback() {
        val file = File(directory, "malformed-unselected.vtt").apply { writeText("not a WebVTT file") }
        optionalSubtitleKeepsPlaybackReady(Uri.fromFile(file), false)
    }

    @Test
    fun selectedMissingSubtitleDoesNotStopPlayback() {
        optionalSubtitleKeepsPlaybackReady(Uri.fromFile(File(directory, "missing-selected.vtt")), true)
    }

    @Test
    fun selectedMalformedSubtitleDoesNotStopPlayback() {
        val file = File(directory, "malformed-selected.vtt").apply { writeText("not a WebVTT file") }
        optionalSubtitleKeepsPlaybackReady(Uri.fromFile(file), true)
    }

    @Test
    fun customVideoRendererRetainsOptionalSubRipDecoding() {
        onMain {
            player.release()
            player = ExoPlayer.Builder(context, org.schabi.newpipe.player.helper.CustomRenderersFactory(context))
                .setLoadControl(LoadController()).build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    engineError.set(error)
                }
            })
        }
        subtitlesProduceRealCues(MediaFormat.SRT, "1\n00:00:00,000 --> 00:00:03,000\nMedia3 controlled caption\n\n")
    }

    @Test
    fun kickNativeMasterDecodesBothLanguagesAndKeepsCaptionsAcrossSeekAndQualityChange() {
        withKickFixture { fixture ->
            val info = fixture.info()
            val sources = kickSources()
            selectText("en")
            prepareKick(sources, info, "180p")
            awaitDecodedAudioVideo(180)
            await { cues.contains("Kick English first") }
            onMain { player.pause() }
            assertEquals(0, fixture.count("/low/index.m3u8"))
            assertTrue(fixture.count("/high/index.m3u8") > 0)

            cues.clear()
            selectText("fr")
            onMain {
                player.seekTo(250)
                player.play()
            }
            await { cues.contains("Kick French first") }
            onMain { player.pause() }
            assertSelectedLanguage("fr")

            cues.clear()
            onMain {
                player.seekTo(4250)
                player.play()
            }
            await { cues.contains("Kick French later") }
            onMain { player.pause() }
            var position = 0L
            onMain { position = player.currentPosition }
            assertTrue("The later cue is reached by seeking", position in 4250..6500)

            cues.clear()
            prepareKick(sources, info, "90p", position)
            awaitDecodedAudioVideo(90)
            await { cues.contains("Kick French later") }
            onMain {
                player.pause()
                assertTrue("Quality reload retains position", player.currentPosition >= position)
                assertTrue("Quality reload does not restart or skip ahead", player.currentPosition < position + 1500)
            }
            assertSelectedLanguage("fr")
            assertTrue(fixture.count("/low/index.m3u8") > 0)
            assertTrue(fixture.count("/low/kick-low.ts") > 0)
            assertTrue(fixture.count("/high/kick-ts.ts") > 0)
            assertTrue(fixture.count("/audio/index.m3u8") > 0)
            // The real resolver must return one native HlsMediaSource, not a second merged audio
            // source. Also check that the first audio window was not loaded by two source owners.
            assertEquals(
                1,
                fixture.requests.count {
                    it.path == "/audio/kick-audio.ts" && it.range?.startsWith("bytes=0-") == true
                }
            )
        }
    }

    @Test
    fun kickDisabledCaptionsDoNotFetchTextSegmentsWhileVideoAndAudioAdvance() {
        withKickFixture { fixture ->
            selectText(null)
            prepareKick(kickSources(), fixture.info(), "90p")
            awaitDecodedAudioVideo(90)
            await { player.currentPosition > 5500 }
            assertEquals(0, fixture.requests.count { it.path.endsWith(".vtt") })
            assertTrue(cues.isEmpty())
            assertEquals("Unselected quality is not fetched", 0, fixture.count("/high/index.m3u8"))
        }
    }

    @Test
    fun kickNativeLiveFactoryAlsoDecodesTheSelectedMasterRendition() {
        withKickFixture { fixture ->
            selectText("en")
            prepareKick(kickSources(), fixture.info(StreamType.LIVE_STREAM), "180p")
            awaitDecodedAudioVideo(180)
            await { cues.contains("Kick English first") }
            assertTrue(fixture.count("/master.m3u8") > 0)
            assertTrue(fixture.count("/high/kick-ts.ts") > 0)
            assertEquals(0, fixture.count("/low/index.m3u8"))
        }
    }

    @Test
    fun kickAudioModeUsesTheActualAudioRenditionWithoutLoadingVideoOrMaster() {
        withKickFixture { fixture ->
            val sources = kickSources()
            onMain {
                val source = requireNotNull(sources.resolve(fixture.info(), true, true))
                assertTrue(source is HlsMediaSource)
                assertEquals(fixture.url("/audio/index.m3u8"), source.mediaItem.localConfiguration!!.uri.toString())
                player.setMediaSource(source)
                player.prepare()
                player.play()
            }
            await {
                player.playbackState == Player.STATE_READY && player.currentPosition > 500 &&
                    (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            assertEquals(0, fixture.count("/master.m3u8"))
            assertEquals(0, fixture.requests.count { it.path.startsWith("/high/") || it.path.startsWith("/low/") })
        }
    }

    @Test
    fun kickSelectedCaptionFailureSurfacesAsAnActualEngineError() {
        kickFailure("/text/en/first.vtt", "en")
    }

    @Test
    fun kickMasterFailureSurfacesAsAnActualEngineError() {
        kickFailure("/master.m3u8", null)
    }

    @Test
    fun kickSelectedVideoFailureDoesNotFallBackToUnselectedQuality() {
        kickFailure("/high/kick-ts.ts", null)
    }

    @Test
    fun kickMissingSelectedRenditionSurfacesTheParserError() {
        withKickFixture { fixture ->
            fixture.omitHigh = true
            selectText(null)
            prepareKick(kickSources(), fixture.info(), "180p")
            awaitEngineError()
            val causes = generateSequence(engineError.get()) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(
                causes.toString(),
                causes.any {
                    it.contains("Selected Kick HLS rendition is no longer available")
                }
            )
            assertEquals(0, fixture.count("/low/index.m3u8"))
        }
    }

    private fun kickFailure(path: String, language: String?) {
        withKickFixture { fixture ->
            fixture.missing = path
            selectText(language)
            prepareKick(kickSources(), fixture.info(), "180p")
            awaitEngineError()
            assertTrue("The failing resource was actually requested", fixture.count(path) > 0)
            val response = generateSequence(engineError.get()) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            assertEquals("The native media failure retains its HTTP status", 404, response?.responseCode)
            assertEquals("The error identifies the selected failed resource", path, response?.dataSpec?.uri?.path)
            assertEquals("A failure must not silently select the other quality", 0, fixture.count("/low/index.m3u8"))
            assertFalse(
                "Failed native media must not become a successful ended stream",
                onMainValue {
                    player.playbackState == Player.STATE_ENDED
                }
            )
        }
    }

    private fun awaitEngineError() {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (engineError.get() != null) return
            Thread.sleep(50)
        }
        throw AssertionError("Native HLS failure did not reach Player.onPlayerError")
    }

    private fun selectText(language: String?) = onMain {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguage(language)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, language == null)
            .build()
    }

    private fun assertSelectedLanguage(language: String) = onMain {
        val selected = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        assertEquals(1, selected.size)
        assertEquals(language, selected.single().getTrackFormat(0).language)
    }

    private fun awaitDecodedAudioVideo(height: Int) {
        await {
            player.playbackState == Player.STATE_READY && player.currentPosition > 300 &&
                player.videoFormat?.height == height && player.videoSize.height == height &&
                (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
        }
        onMain {
            assertEquals(1, player.currentTracks.groups.count { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
            assertEquals(1, player.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO && it.isSelected })
        }
    }

    private fun kickSources(): PlaybackSources = PlaybackSources(
        isolatedContext,
        data,
        object : PlaybackSources.QualityResolver {
            override fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>): Int = 0
            override fun getOverrideResolutionIndex(sortedVideos: List<VideoStream>, playbackQuality: String): Int = sortedVideos.indexOfFirst { it.resolution == playbackQuality }
        }
    ).apply { setAudioTrack("fixture-audio") }

    private fun prepareKick(sources: PlaybackSources, info: StreamInfo, quality: String, position: Long = 0) {
        onMain {
            if (videoSurface == null) {
                videoSurface = PlaceholderSurface.newInstance(context, false)
                player.setVideoSurface(videoSurface)
            }
            sources.setPlaybackQuality(quality)
            val source = requireNotNull(sources.resolve(info, false, false))
            assertTrue("Native audio and HLS text must not be merged as sidecar sources", source is HlsMediaSource)
            assertEquals("Native HLS loads the original master", info.hlsUrl, source.mediaItem.localConfiguration!!.uri.toString())
            val tag = MediaItemTag.from(source.mediaItem).get()
            assertEquals(quality, tag.maybeQuality.get().selectedVideoStream!!.resolution)
            assertEquals("fixture-audio", tag.maybeAudioTrack.get().selectedAudioStream!!.audioTrackId)
            player.setMediaSource(source, position)
            player.prepare()
            player.play()
        }
    }

    private fun <T> onMainValue(action: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(action()) }
        return value.get()
    }

    private fun withKickFixture(action: (KickFixture) -> Unit) {
        KickFixture().use { fixture ->
            try {
                action(fixture)
            } finally {
                onMain {
                    player.stop()
                    player.clearMediaItems()
                }
            }
        }
    }

    private data class HttpRequest(val path: String, val range: String?)

    /** Bounded fixture server: real byte ranges, strict routes, and no fallback media responses. */
    private inner class KickFixture : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<HttpRequest>()

        @Volatile var missing: String? = null

        @Volatile var omitHigh = false
        private val routes = mutableMapOf<String, Pair<String, ByteArray>>()
        private val serverFailure = AtomicReference<Throwable>()
        private val worker: Thread

        init {
            for ((route, asset) in listOf("high" to "kick-ts", "low" to "kick-low", "audio" to "kick-audio")) {
                val playlist = instrumentation.context.assets.open("media3-engine/$asset.m3u8").use { it.readBytes() }
                val media = instrumentation.context.assets.open("media3-engine/$asset.ts").use { it.readBytes() }
                routes["/$route/index.m3u8"] = "application/vnd.apple.mpegurl" to playlist
                routes["/$route/$asset.ts"] = "video/mp2t" to media
            }
            for (language in listOf("en", "fr")) {
                val name = if (language == "en") "English" else "French"
                routes["/text/$language/index.m3u8"] = "application/vnd.apple.mpegurl" to (
                    "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4,\nfirst.vtt\n#EXTINF:4,\nlater.vtt\n#EXT-X-ENDLIST\n"
                    ).toByteArray()
                // ffprobe of kick-ts.ts: AAC starts at 1.400000s; video at 1.421333s.
                // Use the actual first media PTS (1.4 * 90000), not a zero-origin TS assumption.
                for ((file, timing, cue) in listOf(
                    Triple("first", "00:00:00.250 --> 00:00:03.750", "first"),
                    Triple("later", "00:00:04.250 --> 00:00:07.750", "later")
                )) {
                    routes["/text/$language/$file.vtt"] = "text/vtt" to (
                        "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:126000\n\n" +
                            "$timing\nKick $name $cue\n\n"
                        ).toByteArray()
                }
            }
            worker = Thread {
                while (!server.isClosed) {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader()
                            val request = input.readLine().orEmpty().split(' ')
                            var range: String? = null
                            while (true) {
                                val line = input.readLine()
                                if (line.isNullOrEmpty()) break
                                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                            }
                            val path = Uri.parse(request.getOrElse(1) { "/" }).path.orEmpty()
                            requests.add(HttpRequest(path, range))
                            val route = if (path == missing) {
                                null
                            } else if (path == "/master.m3u8") {
                                "application/vnd.apple.mpegurl" to master().toByteArray()
                            } else {
                                routes[path]
                            }
                            val bytes = route?.second ?: ByteArray(0)
                            val bounds = range?.removePrefix("bytes=")?.split('-', limit = 2)
                            val start = bounds?.getOrNull(0)?.toLongOrNull() ?: 0L
                            val end = bounds?.getOrNull(1)?.toLongOrNull() ?: (bytes.size - 1).toLong()
                            val validRange = start >= 0 && end >= start && start < bytes.size
                            val status = when {
                                route == null -> "404 Not Found"
                                range != null && !validRange -> "416 Range Not Satisfiable"
                                range != null -> "206 Partial Content"
                                else -> "200 OK"
                            }
                            val last = minOf(end, (bytes.size - 1).toLong())
                            val body = when {
                                route == null || (range != null && !validRange) -> ByteArray(0)
                                range != null -> bytes.copyOfRange(start.toInt(), last.toInt() + 1)
                                else -> bytes
                            }
                            val contentRange = if (status.startsWith("206")) {
                                "Content-Range: bytes $start-$last/${bytes.size}\r\n"
                            } else {
                                ""
                            }
                            try {
                                socket.getOutputStream().apply {
                                    write(
                                        (
                                            "HTTP/1.1 $status\r\nContent-Type: ${route?.first ?: "text/plain"}\r\n" +
                                                "Accept-Ranges: bytes\r\n$contentRange" +
                                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                            ).toByteArray()
                                    )
                                    write(body)
                                    flush()
                                }
                            } catch (_: IOException) {
                                // Media3 cancels in-flight HTTP responses on seek/quality changes.
                            }
                        }
                    } catch (failure: Exception) {
                        if (!server.isClosed) serverFailure.compareAndSet(null, failure)
                    }
                }
            }.apply {
                name = "kick-engine-loopback"
                isDaemon = true
                start()
            }
        }

        fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
        fun count(path: String) = requests.count { it.path == path }

        private fun master(): String = "#EXTM3U\n#EXT-X-VERSION:4\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Fixture audio\",LANGUAGE=\"en\"," +
            "DEFAULT=YES,AUTOSELECT=YES,URI=\"audio/index.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"text\",NAME=\"English\",LANGUAGE=\"en\"," +
            "DEFAULT=NO,AUTOSELECT=YES,URI=\"text/en/index.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"text\",NAME=\"French\",LANGUAGE=\"fr\"," +
            "DEFAULT=NO,AUTOSELECT=YES,URI=\"text/fr/index.m3u8\"\n" +
            (
                if (omitHigh) {
                    ""
                } else {
                    "#EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=320x180," +
                        "CODECS=\"avc1.42c00c,mp4a.40.2\",AUDIO=\"audio\",SUBTITLES=\"text\"\nhigh/index.m3u8\n"
                }
                ) +
            "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=160x90," +
            "CODECS=\"avc1.42c00b,mp4a.40.2\",AUDIO=\"audio\",SUBTITLES=\"text\"\nlow/index.m3u8\n"

        fun info(type: StreamType = StreamType.VIDEO_STREAM): StreamInfo {
            val masterUrl = url("/master.m3u8")
            fun video(path: String, quality: String) = VideoStream.Builder()
                .setId("fixture-$quality").setContent(url(path), true).setManifestUrl(url(path))
                .setMediaFormat(MediaFormat.MPEG_4).setDeliveryMethod(DeliveryMethod.HLS)
                .setIsVideoOnly(true).setResolution(quality).build()
            val audio = AudioStream.Builder()
                .setId("fixture-audio").setContent(url("/audio/index.m3u8"), true)
                .setManifestUrl(masterUrl).setMediaFormat(MediaFormat.M4A)
                .setDeliveryMethod(DeliveryMethod.HLS).setAverageBitrate(64)
                .setAudioTrackId("fixture-audio").setAudioTrackName("Fixture audio").build()
            return StreamInfo(
                ServiceList.Kick.serviceId,
                "https://kick.com/fixture/videos/fixture",
                "https://kick.com/fixture/videos/fixture",
                type,
                "fixture",
                "Generated Kick engine fixture",
                0
            ).apply {
                hlsUrl = masterUrl
                duration = 8
                uploaderName = "Generated fixture"
                videoOnlyStreams = listOf(video("/high/index.m3u8", "180p"), video("/low/index.m3u8", "90p"))
                audioStreams = listOf(audio)
                subtitles = listOf("en", "fr").map { language ->
                    SubtitlesStream.Builder().setId("text/$language")
                        .setContent(url("/text/$language/index.m3u8"), true).setManifestUrl(masterUrl)
                        .setDeliveryMethod(DeliveryMethod.HLS).setMediaFormat(MediaFormat.VTT)
                        .setLanguageCode(language).setAutoGenerated(false).build()
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(6000)
            assertFalse("Loopback worker terminates", worker.isAlive)
            serverFailure.get()?.let { throw AssertionError("Fixture HTTP server failed", it) }
        }
    }

    private fun optionalSubtitleKeepsPlaybackReady(uri: Uri, selected: Boolean) {
        val subtitles = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT).setLanguage("en").build()
        val wave = waveFile()
        onMain {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage("en")
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !selected).build()
            val text = requireNotNull(data.createSubtitleSource(subtitles))
            val audio = data.progressiveMediaSourceFactory.createMediaSource(MediaItem.fromUri(Uri.fromFile(wave)))
            player.setMediaSource(MergingMediaSource(audio, text))
            player.prepare()
            player.play()
        }
        await { player.playbackState == Player.STATE_READY && player.currentPosition > 500 }
    }

    private fun waveFile(): File {
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
        return wave
    }

    private fun subtitlesProduceRealCues(format: MediaFormat, content: String) {
        val wave = waveFile()
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
