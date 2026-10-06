package org.schabi.newpipe.player.resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import java.util.List;

public class PlaybackSourcesSafetyTest {
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
    public void muxedNeighborCannotRemoveTheCurrentSeparatedVideoSourceInBackground() {
        final StreamInfo current = separated("current");
        assertNotNull(sources.resolve(current, false, false));
        assertNotNull(sources.resolve(muxed("neighbor"), false, false));
        environment.streams.clear();

        assertNotNull(sources.resolve(current, false, true));

        assertEquals(List.of(current.getVideoOnlyStreams().get(0),
                current.getAudioStreams().get(0)), environment.streams);
    }

    @Test
    public void separatedNeighborCannotFetchTheCurrentMuxedVideoInBackground() {
        final StreamInfo current = muxed("current");
        assertNotNull(sources.resolve(current, false, false));
        assertNotNull(sources.resolve(separated("neighbor"), false, false));
        environment.streams.clear();

        assertNotNull(sources.resolve(current, false, true));

        assertEquals(current.getAudioStreams(), environment.streams);
    }

    @Test
    public void muxedNeighborCannotChangeTheCurrentSeparatedSourceReloadDecision() {
        final MediaSource current = sources.resolve(separated("current"), false, false);
        assertFalse(sources.requiresReload(tag(current), true));

        assertNotNull(sources.resolve(muxed("neighbor"), false, false));

        assertFalse(sources.requiresReload(tag(current), true));
        assertFalse(sources.requiresReload(tag(current).withExtras(new Object()), true));
    }

    @Test
    public void separatedNeighborCannotChangeTheCurrentMuxedSourceReloadDecision() {
        final MediaSource current = sources.resolve(muxed("current"), false, false);
        assertTrue(sources.requiresReload(tag(current), true));

        assertNotNull(sources.resolve(separated("neighbor"), false, false));

        assertTrue(sources.requiresReload(tag(current), true));
    }

    @Test
    public void failedResolutionCannotChangeAnExistingSourceReloadDecision() {
        final MediaSource current = sources.resolve(separated("current"), false, false);
        assertFalse(sources.requiresReload(tag(current), true));

        assertNull(sources.resolve(info("empty-neighbor", StreamType.VIDEO_STREAM), false, false));

        assertFalse(sources.requiresReload(tag(current), true));
    }

    @Test
    public void rumbleLiveStreamWithoutQualityVariantsKeepsItsOriginalManifest() {
        final StreamInfo current = rumble("empty");
        current.setHlsUrl("https://example.com/original.m3u8");
        environment.liveInfos.add(current);

        assertNotNull(sources.resolve(current, false, false));

        assertEquals("https://example.com/original.m3u8", current.getHlsUrl());
        assertEquals(List.of("https://example.com/original.m3u8"), environment.liveManifests);
    }

    @Test
    public void rumbleLiveStreamWithAnInvalidPreferredIndexKeepsItsOriginalManifest() {
        final StreamInfo current = rumble("invalid-index");
        current.setHlsUrl("https://example.com/original.m3u8");
        current.setVideoStreams(List.of(rumbleVariant()));
        quality.defaultIndex = -1;
        environment.liveInfos.add(current);

        assertNotNull(sources.resolve(current, false, false));

        assertEquals("https://example.com/original.m3u8", current.getHlsUrl());
        assertEquals(List.of("https://example.com/original.m3u8"), environment.liveManifests);
    }

    @Test
    public void rumbleQualitySelectionDoesNotMutateCachedExtractorInformation() {
        final StreamInfo current = rumble("quality");
        current.setHlsUrl("https://example.com/original.m3u8");
        current.setVideoStreams(List.of(rumbleVariant()));
        environment.liveInfos.add(current);

        assertNotNull(sources.resolve(current, false, false));

        assertEquals("https://example.com/original.m3u8", current.getHlsUrl());
        assertEquals(List.of("https://example.com/variant.m3u8"), environment.liveManifests);
    }

    private static StreamInfo separated(final String id) {
        final StreamInfo current = info(id, StreamType.VIDEO_STREAM);
        current.setVideoOnlyStreams(List.of(video(id + "-video", "720p", true)));
        current.setAudioStreams(List.of(audio(id + "-audio")));
        return current;
    }

    private static StreamInfo muxed(final String id) {
        final StreamInfo current = info(id, StreamType.VIDEO_STREAM);
        current.setVideoStreams(List.of(video(id + "-video", "720p", false)));
        current.setAudioStreams(List.of(audio(id + "-audio")));
        return current;
    }

    private static StreamInfo rumble(final String id) {
        final String url = "https://example.com/rumble/" + id;
        return new StreamInfo(ServiceList.Rumble.getServiceId(), url, url,
                StreamType.LIVE_STREAM, id, id, 0);
    }

    private static VideoStream rumbleVariant() {
        return new VideoStream.Builder().setId("variant")
                .setContent("https://example.com/variant.m3u8", true)
                .setManifestUrl("https://example.com/variant.m3u8")
                .setMediaFormat(MediaFormat.MPEG_4).setDeliveryMethod(DeliveryMethod.HLS)
                .setResolution("720p").setIsVideoOnly(false).build();
    }
}
