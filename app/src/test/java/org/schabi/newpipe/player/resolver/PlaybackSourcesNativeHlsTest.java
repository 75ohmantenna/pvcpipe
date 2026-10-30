package org.schabi.newpipe.player.resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackSourcesNativeHlsTest {
    @Test
    public void nativeVideoLoadsOriginalMasterAndKeepsQualityAndAudioMetadata() throws Exception {
        for (final StreamType type : List.of(StreamType.VIDEO_STREAM, StreamType.LIVE_STREAM)) {
            final StreamInfo info = info(type);
            final VideoStream video = video();
            final AudioStream audio = new AudioStream.Builder().setId("audio")
                    .setContent("https://media.example/fr.m3u8", true)
                    .setDeliveryMethod(DeliveryMethod.HLS).setMediaFormat(MediaFormat.M4A)
                    .setAverageBitrate(AudioStream.UNKNOWN_BITRATE).setAudioTrackId("fr").build();
            final StreamInfoTag tag = StreamInfoTag.of(info, List.of(video), 0, List.of(audio), 0);
            final PlayerDataSource dataSource = mock(PlayerDataSource.class);
            when(dataSource.getKickHlsMediaSourceFactory(video.getContent(), audio.getContent(),
                    type == StreamType.LIVE_STREAM)).thenReturn(hlsFactory());

            final MediaSource source = PlaybackResolver.buildMediaSource(dataSource, video,
                    info, "selection-key", tag);

            assertEquals(info.getHlsUrl(), source.getMediaItem().localConfiguration.uri.toString());
            assertSame(tag, MediaItemTag.from(source.getMediaItem()).orElseThrow());
            assertEquals("selection-key", source.getMediaItem().localConfiguration.customCacheKey);
            assertSame(video, tag.getMaybeQuality().orElseThrow().getSelectedVideoStream());
            assertSame(audio, tag.getMaybeAudioTrack().orElseThrow().getSelectedAudioStream());
            if (type == StreamType.LIVE_STREAM) {
                assertEquals(PlaybackResolver.LIVE_STREAM_EDGE_GAP_MILLIS,
                        source.getMediaItem().liveConfiguration.targetOffsetMs);
            }
        }
    }

    @Test
    public void audioModeCombinedFallbackLoadsActualMediaPlaylistNotVideoMaster() throws Exception {
        final StreamInfo info = info(StreamType.VIDEO_STREAM);
        final VideoStream video = video();
        final StreamInfoTag tag = StreamInfoTag.of(info);
        final PlayerDataSource dataSource = mock(PlayerDataSource.class);
        when(dataSource.getHlsMediaSourceFactory(null)).thenReturn(hlsFactory());

        final MediaSource source = PlaybackResolver.buildMediaSource(dataSource, video,
                info, "audio-fallback-key", tag);

        assertEquals(video.getContent(), source.getMediaItem().localConfiguration.uri.toString());
        assertFalse(MediaItemTag.from(source.getMediaItem()).orElseThrow()
                .getMaybeQuality().isPresent());
        verify(dataSource, never()).getKickHlsMediaSourceFactory(any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    public void hlsTextStaysNativeWhileProgressiveTextRemainsAnOptionalSidecar() throws Exception {
        final StreamInfo info = info(StreamType.VIDEO_STREAM);
        final SubtitlesStream hls = subtitle(DeliveryMethod.HLS, "native.m3u8");
        final SubtitlesStream progressive =
                subtitle(DeliveryMethod.PROGRESSIVE_HTTP, "sidecar.vtt");
        info.setSubtitles(List.of(hls, progressive));
        final PlayerDataSource dataSource = mock(PlayerDataSource.class);
        final MediaSource sidecar = mock(MediaSource.class);
        when(dataSource.createSubtitleSource(any())).thenReturn(sidecar);
        final AndroidPlaybackEnvironment environment = new AndroidPlaybackEnvironment(
                RuntimeEnvironment.getApplication(), dataSource);

        assertEquals(List.of(sidecar), environment.subtitleSources(info));

        final org.mockito.ArgumentCaptor<MediaItem.SubtitleConfiguration> configurations =
                org.mockito.ArgumentCaptor.forClass(MediaItem.SubtitleConfiguration.class);
        verify(dataSource).createSubtitleSource(configurations.capture());
        assertEquals(progressive.getContent(), configurations.getValue().uri.toString());
        assertEquals(MediaFormat.VTT.getMimeType(), configurations.getValue().mimeType);
        info.setSubtitles(List.of(hls));
        assertEquals(List.of(), environment.subtitleSources(info));
    }

    @Test
    public void defaultNativeAudioDoesNotFilterAwayCompatibleLanguageChoices() throws Exception {
        final StreamInfo info = info(StreamType.VIDEO_STREAM);
        final VideoStream video = video();
        final StreamInfoTag tag = StreamInfoTag.of(info, List.of(video), 0, List.of(), -1);
        final PlayerDataSource dataSource = mock(PlayerDataSource.class);
        when(dataSource.getKickHlsMediaSourceFactory(video.getContent(), null, false))
                .thenReturn(hlsFactory());

        PlaybackResolver.buildMediaSource(dataSource, video, info, "key", tag);

        verify(dataSource).getKickHlsMediaSourceFactory(
                org.mockito.ArgumentMatchers.eq(video.getContent()), isNull(),
                org.mockito.ArgumentMatchers.eq(false));
    }

    private static HlsMediaSource.Factory hlsFactory() {
        return new HlsMediaSource.Factory(() -> mock(DataSource.class));
    }

    private static StreamInfo info(final StreamType type) {
        final String url = "https://kick.com/channel/videos/recording";
        final StreamInfo info = new StreamInfo(ServiceList.Kick.getServiceId(), url, url,
                type, "recording", "Recording", 0);
        info.setHlsUrl("https://media.example/master.m3u8");
        return info;
    }

    private static VideoStream video() {
        return new VideoStream.Builder().setId("video")
                .setContent("https://media.example/video.m3u8", true)
                .setDeliveryMethod(DeliveryMethod.HLS).setMediaFormat(MediaFormat.MPEG_4)
                .setResolution("720p60").setIsVideoOnly(false).build();
    }

    private static SubtitlesStream subtitle(final DeliveryMethod method, final String filename)
            throws Exception {
        return new SubtitlesStream.Builder().setContent("https://media.example/" + filename, true)
                .setDeliveryMethod(method).setMediaFormat(MediaFormat.VTT)
                .setLanguageCode("fr").setAutoGenerated(false).build();
    }
}
