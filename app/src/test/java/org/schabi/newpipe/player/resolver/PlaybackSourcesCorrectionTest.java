package org.schabi.newpipe.player.resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.schabi.newpipe.player.resolver.PlaybackSourcesTestEnvironment.audio;
import static org.schabi.newpipe.player.resolver.PlaybackSourcesTestEnvironment.info;
import static org.schabi.newpipe.player.resolver.PlaybackSourcesTestEnvironment.tag;
import static org.schabi.newpipe.player.resolver.PlaybackSourcesTestEnvironment.video;

import com.google.android.exoplayer2.source.MediaSource;

import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class PlaybackSourcesCorrectionTest {
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
    public void unusedVideoOnlyVariantsDoNotForceTheSelectedMuxedVideoIntoBackground() {
        final StreamInfo current = mixed("embedded-preferred");
        quality.defaultIndex = 0;

        final MediaSource source = sources.resolve(current, false, true);

        assertNotNull(source);
        assertEquals(current.getAudioStreams(), environment.streams);
        assertFalse(tag(source).getMaybeQuality().isPresent());
        assertEquals(0, environment.subtitleRequests);
    }

    @Test
    public void selectingAVideoOnlyVariantKeepsVideoAvailableWhileItsRendererIsDisabled() {
        final StreamInfo current = mixed("separated-preferred");
        quality.defaultIndex = 1;

        final MediaSource source = sources.resolve(current, false, true);

        assertEquals(List.of(current.getVideoOnlyStreams().get(0),
                current.getAudioStreams().get(0)), environment.streams);
        assertFalse(sources.requiresReload(tag(source), true));
    }

    @Test
    public void qualityChangesReevaluateWhichIncomingSourceNeedsSeparateVideo() {
        final StreamInfo current = mixed("quality-change");
        assertNotNull(sources.resolve(current, false, false));
        environment.streams.clear();
        quality.overrideIndex = 1;
        sources.setPlaybackQuality("1080p");

        final MediaSource source = sources.resolve(current, false, true);

        assertEquals(List.of(current.getVideoOnlyStreams().get(0),
                current.getAudioStreams().get(0)), environment.streams);
        assertFalse(sources.requiresReload(tag(source), true));
    }

    @Test
    public void explicitlySelectedAlternateAudioKeepsVideoAvailableInBackground() {
        final StreamInfo current = muxed("alternate");
        sources.setAudioTrack("alternate-language");

        final MediaSource source = sources.resolve(current, false, true);

        assertEquals(List.of(current.getVideoStreams().get(0),
                current.getAudioStreams().get(0)), environment.streams);
        assertFalse(sources.requiresReload(tag(source), true));
    }

    @Test
    public void anUnavailableAlternateAudioTrackDoesNotForceVideoResolution() {
        final StreamInfo current = muxed("missing-alternate");
        current.setAudioStreams(List.of());
        sources.setAudioTrack("unavailable-language");

        final MediaSource source = sources.resolve(current, false, true);

        assertNotNull(source);
        assertFalse(tag(source).getMaybeQuality().isPresent());
        assertEquals(0, environment.subtitleRequests);
    }

    @Test
    public void unavailableLiveConstructionUsesTheOrdinarySourceReloadDecision() {
        final StreamInfo previous = info("live-previous", StreamType.LIVE_STREAM);
        environment.liveInfos.add(previous);
        assertNotNull(sources.resolve(previous, false, false));
        final StreamInfo current = muxed("live-fallback");
        current.setStreamType(StreamType.LIVE_STREAM);

        final MediaSource source = sources.resolve(current, false, false);

        assertEquals(current.getVideoStreams(), environment.streams);
        assertTrue(sources.requiresReload(tag(source), true));
        assertFalse(sources.requiresReload(tag(sources.resolve(previous, false, false)), true));
        assertTrue(sources.requiresReload(tag(source), true));
    }

    @Test
    public void overlappingResolutionsKeepBothSourcesTransitionDecisions() throws Exception {
        final StreamInfo current = separated("current");
        final CountDownLatch currentVideoEntered = new CountDownLatch(1);
        final CountDownLatch finishCurrentVideo = new CountDownLatch(1);
        environment.onStreamConstruction = stream -> {
            if (stream == current.getVideoOnlyStreams().get(0)) {
                currentVideoEntered.countDown();
                try {
                    assertTrue(finishCurrentVideo.await(5, TimeUnit.SECONDS));
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }
        };
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<MediaSource> pendingCurrent =
                    executor.submit(() -> sources.resolve(current, false, false));
            assertTrue(currentVideoEntered.await(5, TimeUnit.SECONDS));
            final MediaSource neighbor = sources.resolve(muxed("neighbor"), false, false);
            assertTrue(sources.requiresReload(tag(neighbor), true));
            finishCurrentVideo.countDown();

            final MediaSource currentSource = pendingCurrent.get(5, TimeUnit.SECONDS);

            assertFalse(sources.requiresReload(tag(currentSource), true));
            assertTrue(sources.requiresReload(tag(neighbor), true));
        } finally {
            finishCurrentVideo.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void videoMetadataWithoutSourceFactsRequiresConservativeReload() {
        final StreamInfo current = info("unknown", StreamType.VIDEO_STREAM);
        assertTrue(sources.requiresReload(StreamInfoTag.of(current), true));
    }

    @Test
    public void rumblePreferredManifestSurvivesForegroundAndBackgroundResolution() {
        final String original = "https://example.com/original.m3u8";
        final String preferred = "https://example.com/preferred.m3u8";
        final StreamInfo current = new StreamInfo(ServiceList.Rumble.getServiceId(),
                "https://example.com/rumble/live", "https://example.com/rumble/live",
                StreamType.LIVE_STREAM, "rumble", "rumble", 0);
        current.setHlsUrl(original);
        current.setVideoStreams(List.of(new VideoStream.Builder().setId("preferred")
                .setContent(preferred, true).setManifestUrl(preferred)
                .setMediaFormat(MediaFormat.MPEG_4).setDeliveryMethod(DeliveryMethod.HLS)
                .setResolution("720p").setIsVideoOnly(false).build()));
        environment.liveInfos.add(current);

        final MediaSource foreground = sources.resolve(current, false, false);
        final MediaSource background = sources.resolve(current, false, true);

        assertFalse(sources.requiresReload(tag(foreground), true));
        assertFalse(sources.requiresReload(tag(background), true));
        assertEquals(List.of(preferred, preferred), environment.liveManifests);
        assertEquals(original, current.getHlsUrl());
        assertTrue(environment.streams.isEmpty());
    }

    @Test
    public void failedLiveConstructionIsAttemptedOnceBeforeBackgroundAudioFallback() {
        final StreamInfo current = muxed("live-fallback");
        current.setStreamType(StreamType.LIVE_STREAM);
        current.setHlsUrl("https://example.com/unavailable.m3u8");

        final MediaSource source = sources.resolve(current, false, true);

        assertNotNull(source);
        assertEquals(current.getAudioStreams(), environment.streams);
        assertEquals(List.of("https://example.com/unavailable.m3u8"), environment.liveManifests);
        assertTrue(sources.requiresReload(tag(source), true));
    }

    private static StreamInfo mixed(final String id) {
        final StreamInfo current = muxed(id);
        current.setVideoOnlyStreams(List.of(video(id + "-separate", "1080p", true)));
        return current;
    }

    private static StreamInfo muxed(final String id) {
        final StreamInfo current = info(id, StreamType.VIDEO_STREAM);
        current.setVideoStreams(List.of(video(id + "-video", "720p", false)));
        current.setAudioStreams(List.of(audio(id + "-audio")));
        return current;
    }

    private static StreamInfo separated(final String id) {
        final StreamInfo current = info(id, StreamType.VIDEO_STREAM);
        current.setVideoOnlyStreams(List.of(video(id + "-video", "720p", true)));
        current.setAudioStreams(List.of(audio(id + "-audio")));
        return current;
    }
}
