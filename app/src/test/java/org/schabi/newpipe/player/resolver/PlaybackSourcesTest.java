package org.schabi.newpipe.player.resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.schabi.newpipe.player.resolver.PlaybackSourcesTestEnvironment.audio;
import static org.schabi.newpipe.player.resolver.PlaybackSourcesTestEnvironment.info;
import static org.schabi.newpipe.player.resolver.PlaybackSourcesTestEnvironment.tag;
import static org.schabi.newpipe.player.resolver.PlaybackSourcesTestEnvironment.video;

import androidx.media3.exoplayer.source.MediaSource;

import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;

import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackSourcesTest {
    private PlaybackSourcesTestEnvironment environment;
    private PlaybackSourcesTestEnvironment.Quality quality;
    private PlaybackSources sources;

    @Before
    public void setUp() {
        environment = new PlaybackSourcesTestEnvironment();
        quality = new PlaybackSourcesTestEnvironment.Quality();
        sources = new PlaybackSources(environment, quality);
    }

    @Test
    public void audioPlayerSelectsAudioEvenAfterSeparatedVideoResolution() {
        final StreamInfo separate = separated("previous");
        assertNotNull(sources.resolve(separate, false, false));
        environment.streams.clear();
        final StreamInfo current = muxed("current", true);
        final MediaSource source = sources.resolve(current, true, false);

        assertEquals(current.getAudioStreams(), environment.streams);
        assertSame(current.getAudioStreams().get(0), tag(source).getMaybeAudioTrack()
                .orElseThrow().getSelectedAudioStream());
        assertFalse(tag(source).getMaybeQuality().isPresent());
    }

    @Test
    public void audioPlayerFallsBackToAVideoContainingAudio() {
        final StreamInfo current = muxed("fallback", false);
        final VideoStream second = video("fallback-small", "240p", false);
        current.setVideoStreams(List.of(current.getVideoStreams().get(0), second));
        environment.fallbackVideoIndex = 1;

        final MediaSource source = sources.resolve(current, true, false);

        assertEquals(List.of(second), environment.streams);
        assertSame(current, tag(source).getMaybeStreamInfo().orElseThrow());
    }

    @Test
    public void audioPlayerDoesNotUseAVideoOnlyStreamAsAudioFallback() {
        final StreamInfo current = info("silent", StreamType.VIDEO_STREAM);
        current.setVideoOnlyStreams(List.of(video("silent", "720p", true)));

        assertNull(sources.resolve(current, true, true));
        assertTrue(environment.streams.isEmpty());
    }

    @Test
    public void videoResolutionUsesTheDefaultQualityAndRetainsItsSelection() {
        final StreamInfo current = muxed("quality", false);
        final VideoStream preferred = video("preferred", "1080p", false);
        current.setVideoStreams(List.of(current.getVideoStreams().get(0), preferred));
        quality.defaultIndex = 1;

        final MediaSource source = sources.resolve(current, false, false);

        assertEquals(List.of(preferred), environment.streams);
        assertSame(preferred, tag(source).getMaybeQuality().orElseThrow().getSelectedVideoStream());
        assertTrue(quality.requestedOverrides.isEmpty());
    }

    @Test
    public void qualityOverridesApplyUntilCleared() {
        final StreamInfo current = muxed("quality", false);
        final VideoStream preferred = video("preferred", "1080p", false);
        current.setVideoStreams(List.of(current.getVideoStreams().get(0), preferred));
        quality.overrideIndex = 1;
        sources.setPlaybackQuality("1080p");

        assertNotNull(sources.resolve(current, false, false));
        assertEquals(List.of(preferred), environment.streams);
        assertEquals(List.of("1080p"), quality.requestedOverrides);
        environment.streams.clear();
        sources.setPlaybackQuality(null);

        assertNotNull(sources.resolve(current, false, false));
        assertEquals(List.of(current.getVideoStreams().get(0)), environment.streams);
        assertEquals(List.of("1080p"), quality.requestedOverrides);
    }

    @Test
    public void videoOnlyAndSelectedAudioBecomeOnePlaybackSource() {
        final StreamInfo current = separated("separate");

        final MediaSource source = sources.resolve(current, false, false);

        assertEquals(List.of(current.getVideoOnlyStreams().get(0),
                current.getAudioStreams().get(0)), environment.streams);
        assertEquals(1, environment.merges.size());
        assertEquals(2, environment.merges.get(0).size());
        assertSame(current.getAudioStreams().get(0), tag(source).getMaybeAudioTrack()
                .orElseThrow().getSelectedAudioStream());
    }

    @Test
    public void embeddedAudioAvoidsFetchingASeparateAudioStreamByDefault() {
        final StreamInfo current = muxed("muxed", true);

        final MediaSource source = sources.resolve(current, false, false);

        assertEquals(current.getVideoStreams(), environment.streams);
        assertTrue(environment.merges.isEmpty());
        assertNotNull(source);
    }

    @Test
    public void selectedAlternateAudioAppliesInBothPlaybackModes() {
        final StreamInfo current = muxed("alternate", true);
        final AudioStream alternate = audio("alternate-language");
        current.setAudioStreams(List.of(current.getAudioStreams().get(0), alternate));
        environment.selectedAudioIndex = 1;
        sources.setAudioTrack("alternate-language");

        final MediaSource videoSource = sources.resolve(current, false, false);

        assertEquals(List.of(current.getVideoStreams().get(0), alternate), environment.streams);
        assertSame(alternate, tag(videoSource).getMaybeAudioTrack()
                .orElseThrow().getSelectedAudioStream());
        environment.streams.clear();
        final MediaSource audioSource = sources.resolve(current, true, false);

        assertEquals(List.of(alternate), environment.streams);
        assertSame(alternate, tag(audioSource).getMaybeAudioTrack()
                .orElseThrow().getSelectedAudioStream());
        assertEquals(List.of("alternate-language", "alternate-language"),
                environment.requestedAudioTracks);
    }

    @Test
    public void clearingAlternateAudioRestoresEmbeddedAudio() {
        final StreamInfo current = muxed("alternate", true);
        sources.setAudioTrack("alternate-language");
        assertNotNull(sources.resolve(current, false, false));
        environment.streams.clear();
        sources.setAudioTrack(null);

        assertNotNull(sources.resolve(current, false, false));
        assertEquals(current.getVideoStreams(), environment.streams);
        assertNull(environment.requestedAudioTracks.get(1));
    }

    @Test
    public void subtitlesAreMergedWithThePlayableVideo() {
        final StreamInfo current = muxed("subtitles", false);
        final MediaSource subtitle =
                PlaybackSourcesTestEnvironment.source(StreamInfoTag.of(current));
        environment.subtitles.add(subtitle);

        assertNotNull(sources.resolve(current, false, false));

        assertEquals(1, environment.subtitleRequests);
        assertEquals(2, environment.merges.get(0).size());
        assertSame(subtitle, environment.merges.get(0).get(1));
    }

    @Test
    public void audioPlaybackOmitsSubtitleSources() {
        final StreamInfo current = muxed("subtitles", true);
        environment.subtitles.add(PlaybackSourcesTestEnvironment.source(StreamInfoTag.of(current)));

        assertNotNull(sources.resolve(current, true, false));

        assertEquals(0, environment.subtitleRequests);
        assertTrue(environment.merges.isEmpty());
    }

    @Test
    public void successfulLiveConstructionPrecedesOrdinaryStreamSelection() {
        final StreamInfo current = info("live", StreamType.LIVE_STREAM);
        current.setVideoStreams(List.of(video("unused", "720p", false)));
        current.setAudioStreams(List.of(audio("unused")));
        environment.liveInfos.add(current);

        final MediaSource source = sources.resolve(current, false, false);

        assertNotNull(source);
        assertTrue(environment.streams.isEmpty());
        assertEquals(0, environment.subtitleRequests);
        assertFalse(sources.requiresReload(tag(source), true));
    }

    @Test
    public void audioPlayerAlsoUsesAnAvailableLiveSource() {
        final StreamInfo current = info("live-audio", StreamType.AUDIO_LIVE_STREAM);
        environment.liveInfos.add(current);

        assertNotNull(sources.resolve(current, true, false));
        assertTrue(environment.streams.isEmpty());
    }

    @Test
    public void unavailableLiveConstructionFallsBackToOrdinaryStreams() {
        final StreamInfo current = muxed("fallback-live", false);
        current.setStreamType(StreamType.LIVE_STREAM);

        assertNotNull(sources.resolve(current, false, false));

        assertEquals(current.getVideoStreams(), environment.streams);
    }

    @Test
    public void absentStreamsDoNotProduceAnEmptyOrSubtitleOnlyPlaybackSource() {
        final StreamInfo current = info("empty", StreamType.VIDEO_STREAM);
        environment.subtitles.add(PlaybackSourcesTestEnvironment.source(StreamInfoTag.of(current)));

        assertNull(sources.resolve(current, false, false));
        assertEquals(0, environment.subtitleRequests);
        assertTrue(environment.merges.isEmpty());
    }

    @Test
    public void videoConstructionFailureDoesNotFallThroughToAudio() {
        final StreamInfo current = separated("broken-video");
        environment.failedStreams.add(current.getVideoOnlyStreams().get(0));

        assertNull(sources.resolve(current, false, false));

        assertEquals(List.of(current.getVideoOnlyStreams().get(0)), environment.streams);
        assertEquals(1, environment.errors.size());
        assertTrue(environment.merges.isEmpty());
    }

    @Test
    public void audioConstructionFailureDoesNotReturnAPartialVideoSource() {
        final StreamInfo current = separated("broken-audio");
        environment.failedStreams.add(current.getAudioStreams().get(0));

        assertNull(sources.resolve(current, false, false));

        assertEquals(2, environment.streams.size());
        assertEquals(1, environment.errors.size());
        assertEquals(0, environment.subtitleRequests);
        assertTrue(environment.merges.isEmpty());
    }

    @Test
    public void audioPlayerConstructionFailureReturnsNoSource() {
        final StreamInfo current = muxed("broken-audio", true);
        environment.failedStreams.add(current.getAudioStreams().get(0));

        assertNull(sources.resolve(current, true, false));

        assertEquals(1, environment.errors.size());
    }

    @Test
    public void disabledVideoKeepsSeparatelyDeliveredVideoReadyForTheForeground() {
        final StreamInfo current = separated("separate");
        assertNotNull(sources.resolve(current, false, false));
        environment.streams.clear();

        assertNotNull(sources.resolve(current, false, true));

        assertEquals(List.of(current.getVideoOnlyStreams().get(0),
                current.getAudioStreams().get(0)), environment.streams);
    }

    @Test
    public void disabledVideoSwitchesMuxedContentToItsAudioSource() {
        final StreamInfo current = muxed("muxed", true);
        assertNotNull(sources.resolve(current, false, false));
        environment.streams.clear();

        assertNotNull(sources.resolve(current, false, true));

        assertEquals(current.getAudioStreams(), environment.streams);
    }

    @Test
    public void missingMetadataOrMissingStreamInformationNeedsReload() {
        assertTrue(sources.requiresReload(null, true));
        assertTrue(sources.requiresReload(org.mockito.Mockito.mock(MediaItemTag.class), true));
    }

    @Test
    public void audioContentDoesNotNeedVideoRendererOrReload() {
        for (final StreamType type : List.of(StreamType.AUDIO_STREAM,
                StreamType.AUDIO_LIVE_STREAM, StreamType.POST_LIVE_AUDIO_STREAM)) {
            final StreamInfo current = info(type.name(), type);
            assertFalse(sources.requiresReload(StreamInfoTag.of(current), false));
        }
    }

    @Test
    public void videoContentNeedsReloadIfThereIsNoVideoRenderer() {
        final MediaSource source = sources.resolve(separated("separate"), false, false);
        assertTrue(sources.requiresReload(tag(source), false));
    }

    @Test
    public void separatelyDeliveredVideoCanToggleRenderersWithoutReload() {
        final MediaSource source = sources.resolve(separated("separate"), false, false);
        final MediaItemTag metadata = tag(source);

        assertFalse(sources.requiresReload(metadata, true));
        assertFalse(sources.requiresReload(metadata.withExtras(new Object()), true));
    }

    @Test
    public void embeddedAudioWithAvailableSeparateAudioNeedsReloadOnVideoToggle() {
        final MediaSource source = sources.resolve(muxed("muxed", true), false, false);
        assertTrue(sources.requiresReload(tag(source), true));
    }

    @Test
    public void embeddedAudioWithoutSeparateAudioDoesNotNeedReload() {
        final MediaSource source = sources.resolve(muxed("muxed", false), false, false);
        assertFalse(sources.requiresReload(tag(source), true));
    }

    @Test
    public void kickLiveVideoRetainsQualityAndNativeAudioOwnership() {
        final StreamInfo current = kick(StreamType.LIVE_STREAM, true);
        environment.liveInfos.add(current);

        final MediaSource source = sources.resolve(current, false, false);

        assertEquals(current.getVideoOnlyStreams(), environment.streams);
        assertTrue(environment.liveManifests.isEmpty());
        assertTrue(environment.merges.isEmpty());
        assertSame(current.getVideoOnlyStreams().get(0), tag(source).getMaybeQuality()
                .orElseThrow().getSelectedVideoStream());
        assertNull(tag(source).getMaybeAudioTrack().orElseThrow().getSelectedAudioStream());
        assertTrue(sources.requiresReload(tag(source), true));
    }

    @Test
    public void kickExplicitAudioStaysInNativeVideoTagWithoutDuplicateMerge() {
        final StreamInfo current = kick(StreamType.VIDEO_STREAM, true);
        sources.setAudioTrack("kick-audio");

        final MediaSource source = sources.resolve(current, false, false);

        assertEquals(current.getVideoOnlyStreams(), environment.streams);
        assertTrue(environment.merges.isEmpty());
        assertSame(current.getAudioStreams().get(0), tag(source).getMaybeAudioTrack()
                .orElseThrow().getSelectedAudioStream());
        assertTrue(PlaybackResolver.isKickNativeVideoMaster(current,
                current.getVideoOnlyStreams().get(0), tag(source)));
    }

    @Test
    public void kickAudioModesPreferRealAudioBeforeTheLiveMaster() {
        final StreamInfo current = kick(StreamType.LIVE_STREAM, true);
        environment.liveInfos.add(current);
        for (final boolean audioPlayer : List.of(false, true)) {
            environment.streams.clear();
            final MediaSource source = sources.resolve(current, audioPlayer, true);

            assertEquals(current.getAudioStreams(), environment.streams);
            assertFalse(tag(source).getMaybeQuality().isPresent());
            assertTrue(environment.liveManifests.isEmpty());
        }
    }

    @Test
    public void kickMuxedAudioFallbackDoesNotClaimNativeVideoOrSeparateAudio() {
        final StreamInfo current = kick(StreamType.LIVE_STREAM, false);
        environment.liveInfos.add(current);

        final MediaSource source = sources.resolve(current, true, true);

        assertEquals(current.getVideoStreams(), environment.streams);
        assertFalse(tag(source).getMaybeQuality().isPresent());
        assertFalse(PlaybackResolver.isKickNativeVideoMaster(current,
                current.getVideoStreams().get(0), tag(source)));
        assertTrue(environment.liveManifests.isEmpty());
        assertFalse(sources.requiresReload(tag(source), true));
    }

    @Test
    public void kickProgressiveAndMissingMasterStillMergeRequiredAudio() {
        for (final boolean progressive : List.of(false, true)) {
            final StreamInfo current = kick(StreamType.VIDEO_STREAM, true);
            if (progressive) {
                current.setVideoOnlyStreams(List.of(video("progressive", "720p", true)));
            } else {
                current.setHlsUrl("");
            }
            environment.streams.clear();
            assertNotNull(sources.resolve(current, false, false));
            assertEquals(2, environment.streams.size());
            assertSame(current.getAudioStreams().get(0), environment.streams.get(1));
        }
    }

    private static StreamInfo kick(final StreamType type, final boolean externalAudio) {
        final String url = "https://kick.com/channel/videos/recording";
        final StreamInfo current = new StreamInfo(ServiceList.Kick.getServiceId(),
                url, url, type, "recording", "Recording", 0);
        current.setHlsUrl("https://media.example/master.m3u8");
        final VideoStream rendition = new VideoStream.Builder().setId("kick-video")
                .setContent("https://media.example/video.m3u8", true)
                .setMediaFormat(MediaFormat.MPEG_4).setDeliveryMethod(DeliveryMethod.HLS)
                .setResolution("720p60").setIsVideoOnly(externalAudio).build();
        if (externalAudio) {
            current.setVideoOnlyStreams(List.of(rendition));
            current.setAudioStreams(List.of(new AudioStream.Builder().setId("kick-audio")
                    .setContent("https://media.example/audio.m3u8", true)
                    .setMediaFormat(MediaFormat.M4A).setDeliveryMethod(DeliveryMethod.HLS)
                    .setAverageBitrate(AudioStream.UNKNOWN_BITRATE)
                    .setAudioTrackId("kick-audio").build()));
        } else {
            current.setVideoStreams(List.of(rendition));
        }
        return current;
    }

    private static StreamInfo separated(final String id) {
        final StreamInfo current = info(id, StreamType.VIDEO_STREAM);
        current.setVideoOnlyStreams(List.of(video(id + "-video", "720p", true)));
        current.setAudioStreams(List.of(audio(id + "-audio")));
        return current;
    }

    private static StreamInfo muxed(final String id, final boolean separateAudio) {
        final StreamInfo current = info(id, StreamType.VIDEO_STREAM);
        current.setVideoStreams(List.of(video(id + "-video", "720p", false)));
        if (separateAudio) {
            current.setAudioStreams(List.of(audio(id + "-audio")));
        }
        return current;
    }
}
