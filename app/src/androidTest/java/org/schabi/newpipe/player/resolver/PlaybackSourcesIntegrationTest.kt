/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.resolver

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.mediasource.LoadedMediaSource
import org.schabi.newpipe.player.playqueue.PlayQueueItem

/** Constructs real ExoPlayer sources without preparing them or opening network connections. */
class PlaybackSourcesIntegrationTest {
    private val transfers = AtomicInteger()
    private lateinit var application: Context
    private lateinit var preferenceName: String
    private lateinit var preferences: SharedPreferences
    private lateinit var sources: PlaybackSources

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        preferenceName = "playback-sources-test-${UUID.randomUUID()}"
        preferences = application.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        val listener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                transfers.incrementAndGet()
            }
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                transfers.incrementAndGet()
            }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                transfers.incrementAndGet()
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                transfers.incrementAndGet()
            }
        }
        sources = PlaybackSources(
            context,
            PlayerDataSource(context, listener),
            object : PlaybackSources.QualityResolver {
                override fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>): Int = 0
                override fun getOverrideResolutionIndex(sortedVideos: List<VideoStream>, playbackQuality: String): Int = sortedVideos.indexOfFirst { it.resolution == playbackQuality }
            }
        )
    }

    @After
    fun tearDown() {
        application.deleteSharedPreferences(preferenceName)
        assertEquals("Source construction must not open a transfer", 0, transfers.get())
    }

    @Test
    fun embeddedVideoUsesRealProgressiveSourceAndPreservesSelectedQuality() {
        val video = video("embedded", videoOnly = false)
        val info = info("embedded").apply { videoStreams = listOf(video) }
        val source = requireNotNull(sources.resolve(info, false, false))

        assertTrue(source is ProgressiveMediaSource)
        val tag = tag(source)
        assertSame(info, tag.maybeStreamInfo.get())
        assertSame(video, tag.maybeQuality.get().selectedVideoStream)
        assertTrue(tag.maybeAudioTrack.get().audioStreams.isEmpty())
    }

    @Test
    fun selectedAudioTrackIsSharedByRealVideoAndAudioConstruction() {
        val video = video("separate", videoOnly = true)
        val english = audio("english")
        val french = audio("french")
        val info = info("separate").apply {
            videoOnlyStreams = listOf(video)
            audioStreams = listOf(english, french)
        }
        sources.setAudioTrack("french")
        val videoSource = requireNotNull(sources.resolve(info, false, false))

        assertTrue(videoSource is MergingMediaSource)
        val videoTag = tag(videoSource)
        assertSame(video, videoTag.maybeQuality.get().selectedVideoStream)
        assertSame(french, videoTag.maybeAudioTrack.get().selectedAudioStream)

        sources.setAudioTrack("english")
        val audioSource = requireNotNull(sources.resolve(info, true, true))
        assertTrue(audioSource is ProgressiveMediaSource)
        assertSame(english, tag(audioSource).maybeAudioTrack.get().selectedAudioStream)
        assertFalse(tag(audioSource).maybeQuality.isPresent)
    }

    @Test
    fun liveSelectionUsesTheRealHlsFactoryAndKeepsStreamMetadata() {
        val info = info("live", StreamType.LIVE_STREAM).apply {
            hlsUrl = "https://example.invalid/fixture/live.m3u8"
        }
        val source = requireNotNull(sources.resolve(info, false, false))

        assertTrue(source is HlsMediaSource)
        assertEquals(info.hlsUrl, source.mediaItem.localConfiguration!!.uri.toString())
        assertSame(info, tag(source).maybeStreamInfo.get())
        assertFalse(sources.requiresReload(tag(source), true))
    }

    @Test
    fun rumbleLiveSelectionConstructsTheSelectedQualityManifest() {
        val selected = "https://example.invalid/rumble/720.m3u8"
        val stream = VideoStream.Builder()
            .setId("rumble-720")
            .setContent(selected, true)
            .setManifestUrl(selected)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setDeliveryMethod(DeliveryMethod.HLS)
            .setIsVideoOnly(false)
            .setResolution("720p")
            .build()
        val info = info("rumble", StreamType.LIVE_STREAM, ServiceList.Rumble.serviceId).apply {
            hlsUrl = "https://example.invalid/rumble/original.m3u8"
            videoStreams = listOf(stream)
        }
        val source = requireNotNull(sources.resolve(info, false, false))

        assertTrue(source is HlsMediaSource)
        assertEquals(selected, source.mediaItem.localConfiguration!!.uri.toString())
    }

    @Test
    fun realLoadedSourcePreservesSelectionsWhileReplacingExtras() {
        val video = video("wrapped", videoOnly = true)
        val audio = audio("english")
        val info = info("wrapped").apply {
            videoOnlyStreams = listOf(video)
            audioStreams = listOf(audio)
        }
        val source = requireNotNull(sources.resolve(info, false, false))
        val originalTag = tag(source).withExtras("previous extra")
        val item = PlayQueueItem(info)
        val loaded = LoadedMediaSource(source, originalTag, item, Long.MAX_VALUE)
        val loadedTag = tag(loaded)

        assertSame(loaded, loadedTag.getMaybeExtras(LoadedMediaSource::class.java).get())
        assertSame(info, loadedTag.maybeStreamInfo.get())
        assertSame(video, loadedTag.maybeQuality.get().selectedVideoStream)
        assertSame(audio, loadedTag.maybeAudioTrack.get().selectedAudioStream)
        assertSame(item, loaded.stream)
    }

    @Test
    fun emptyStreamInfoCannotConstructARealSource() {
        assertNull(sources.resolve(info("empty"), false, false))
        assertNull(sources.resolve(info("empty-audio"), true, true))
    }

    @Test
    fun neighborCannotChangeReloadDecisionOfLoadedEmbeddedVideo() {
        val current = info("current-embedded").apply {
            videoStreams = listOf(video("embedded", videoOnly = false))
            audioStreams = listOf(audio("english"))
        }
        val currentSource = requireNotNull(sources.resolve(current, false, false))
        val loaded = LoadedMediaSource(currentSource, tag(currentSource), PlayQueueItem(current), Long.MAX_VALUE)
        val currentTag = tag(loaded)
        assertSame(loaded, currentTag.getMaybeExtras(LoadedMediaSource::class.java).get())
        assertTrue(sources.requiresReload(currentTag, true))

        val neighbor = info("neighbor-separate").apply {
            videoOnlyStreams = listOf(video("separate", videoOnly = true))
            audioStreams = listOf(audio("english"))
        }
        requireNotNull(sources.resolve(neighbor, false, false))

        assertTrue(sources.requiresReload(currentTag, true))
    }

    @Test
    fun neighborCannotChangeReloadDecisionOfLoadedSeparateVideo() {
        val current = info("current-separate").apply {
            videoOnlyStreams = listOf(video("separate", videoOnly = true))
            audioStreams = listOf(audio("english"))
        }
        val currentSource = requireNotNull(sources.resolve(current, false, false))
        val loaded = LoadedMediaSource(currentSource, tag(currentSource), PlayQueueItem(current), Long.MAX_VALUE)
        val currentTag = tag(loaded)
        assertSame(loaded, currentTag.getMaybeExtras(LoadedMediaSource::class.java).get())
        assertFalse(sources.requiresReload(currentTag, true))

        val neighbor = info("neighbor-embedded").apply {
            videoStreams = listOf(video("embedded", videoOnly = false))
            audioStreams = listOf(audio("english"))
        }
        requireNotNull(sources.resolve(neighbor, false, false))

        assertFalse(sources.requiresReload(currentTag, true))
    }

    @Test
    fun rumbleLiveSelectionUsesQualityManifestWithoutChangingCachedInfo() {
        val original = "https://example.invalid/rumble/original.m3u8"
        val selected = "https://example.invalid/rumble/720.m3u8"
        val stream = VideoStream.Builder()
            .setId("rumble-720")
            .setContent(selected, true)
            .setManifestUrl(selected)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setDeliveryMethod(DeliveryMethod.HLS)
            .setIsVideoOnly(false)
            .setResolution("720p")
            .build()
        val info = info("rumble", StreamType.LIVE_STREAM, ServiceList.Rumble.serviceId).apply {
            hlsUrl = original
            videoStreams = listOf(stream)
        }
        val source = requireNotNull(sources.resolve(info, false, false))

        assertTrue(source is HlsMediaSource)
        assertEquals(selected, source.mediaItem.localConfiguration!!.uri.toString())
        assertEquals(original, info.hlsUrl)
    }

    @Test
    fun rumbleLiveWithoutQualityStreamsUsesItsOriginalManifest() {
        val original = "https://example.invalid/rumble/original.m3u8"
        val info = info("rumble-empty", StreamType.LIVE_STREAM, ServiceList.Rumble.serviceId).apply {
            hlsUrl = original
        }
        val source = requireNotNull(sources.resolve(info, false, false))

        assertTrue(source is HlsMediaSource)
        assertEquals(original, source.mediaItem.localConfiguration!!.uri.toString())
        assertEquals(original, info.hlsUrl)
    }

    @Test
    fun rumblePreferredLiveManifestSurvivesVideoDisablingAndNeighborResolution() {
        val original = "https://example.invalid/rumble/original.m3u8"
        val preferred = "https://example.invalid/rumble/720.m3u8"
        val preferredVideo = VideoStream.Builder()
            .setId("rumble-720")
            .setContent(preferred, true)
            .setManifestUrl(preferred)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setDeliveryMethod(DeliveryMethod.HLS)
            .setIsVideoOnly(false)
            .setResolution("720p")
            .build()
        val current = info("rumble-background", StreamType.LIVE_STREAM, ServiceList.Rumble.serviceId).apply {
            hlsUrl = original
            videoStreams = listOf(preferredVideo)
            audioStreams = listOf(audio("english"))
        }
        val foreground = requireNotNull(sources.resolve(current, false, false))
        assertTrue(foreground is HlsMediaSource)
        assertEquals(preferred, foreground.mediaItem.localConfiguration!!.uri.toString())
        assertEquals(original, current.hlsUrl)

        val background = requireNotNull(sources.resolve(current, false, true))
        assertTrue(background is HlsMediaSource)
        assertEquals(preferred, background.mediaItem.localConfiguration!!.uri.toString())
        assertEquals(original, current.hlsUrl)

        val neighbor = info("neighbor-embedded").apply {
            videoStreams = listOf(video("embedded", videoOnly = false))
            audioStreams = listOf(audio("english"))
        }
        requireNotNull(sources.resolve(neighbor, false, false))

        assertFalse(sources.requiresReload(tag(foreground), true))
        assertFalse(sources.requiresReload(tag(background), true))
        assertEquals(original, current.hlsUrl)
    }

    private fun tag(source: MediaSource): MediaItemTag = MediaItemTag.from(source.mediaItem).get()

    private fun info(
        id: String,
        type: StreamType = StreamType.VIDEO_STREAM,
        serviceId: Int = ServiceList.MediaCCC.serviceId
    ): StreamInfo = StreamInfo(
        serviceId,
        "https://example.invalid/watch/$id",
        "https://example.invalid/watch/$id",
        type,
        id,
        "Fixture $id",
        0
    ).apply {
        duration = 120
        uploaderName = "Fixture uploader"
    }

    private fun video(id: String, videoOnly: Boolean): VideoStream = VideoStream.Builder()
        .setId(id)
        .setContent("https://example.invalid/video/$id.mp4", true)
        .setMediaFormat(MediaFormat.MPEG_4)
        .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
        .setIsVideoOnly(videoOnly)
        .setResolution("720p")
        .build()

    private fun audio(id: String): AudioStream = AudioStream.Builder()
        .setId(id)
        .setContent("https://example.invalid/audio/$id.m4a", true)
        .setMediaFormat(MediaFormat.M4A)
        .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
        .setAverageBitrate(128)
        .setAudioTrackId(id)
        .setAudioTrackName(id)
        .build()
}
