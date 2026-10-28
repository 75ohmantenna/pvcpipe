/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.extractor.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the real StreamInfo assembly path with synthetic metadata and no network access. */
@Tag("offline")
class StreamInfoTest {
    private static final String URL = "https://example.invalid/watch/fixture";
    private static final String DASH = "https://example.invalid/manifest.mpd";
    private static final String HLS = "https://example.invalid/manifest.m3u8";
    private static final AudioStream AUDIO = new AudioStream.Builder()
            .setId("audio").setContent("https://example.invalid/audio", true).build();
    private static final VideoStream VIDEO = new VideoStream.Builder()
            .setId("video").setContent("https://example.invalid/video", true)
            .setResolution("720p").setIsVideoOnly(false).build();
    private static final VideoStream VIDEO_ONLY = new VideoStream.Builder()
            .setId("video-only").setContent("https://example.invalid/silent", true)
            .setResolution("720p").setIsVideoOnly(true).build();

    @BeforeEach
    void setUp() {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(final Request request) {
                throw new AssertionError("Unexpected network request: " + request.url());
            }
        });
    }

    static Stream<Arguments> manifestCases() {
        return IntStream.range(0, 8).boxed().flatMap(mask -> Stream.of(
                Arguments.of(mask, DASH, ""), Arguments.of(mask, "", HLS)));
    }

    @ParameterizedTest
    @MethodSource("manifestCases")
    void manifestOnlySourcesAcceptEveryNullListCombination(final int mask, final String dash,
                                                           final String hls) throws Exception {
        final Fixture extractor = new Fixture(mask);
        extractor.dash = dash;
        extractor.hls = hls;
        final StreamInfo info = StreamInfo.getInfo(extractor);

        assertEmptyLists(info);
        assertEquals(dash, info.getDashMpdUrl());
        assertEquals(hls, info.getHlsUrl());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {DASH, HLS})
    void liveManifestOnlySourcesSurviveNullLists(final String manifest) throws Exception {
        final Fixture extractor = new Fixture(7);
        extractor.streamType = StreamType.LIVE_STREAM;
        if (manifest.equals(DASH)) {
            extractor.dash = manifest;
        } else {
            extractor.hls = manifest;
        }

        final StreamInfo info = StreamInfo.getInfo(extractor);
        assertEmptyLists(info);
        assertEquals(StreamType.LIVE_STREAM, info.getStreamType());
        assertEquals(manifest, manifest.equals(DASH) ? info.getDashMpdUrl() : info.getHlsUrl());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void missingSourcesProduceExtractionFailureInsteadOfNullPointer(final int mask) {
        assertThrows(StreamInfo.StreamExtractException.class,
                () -> StreamInfo.getInfo(new Fixture(mask)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void videoOnlyDoesNotSatisfySourceAdmission(final int mask) {
        final Fixture extractor = new Fixture(mask);
        extractor.videoOnly = List.of(VIDEO_ONLY);
        assertThrows(StreamInfo.StreamExtractException.class,
                () -> StreamInfo.getInfo(extractor));
    }

    @Test
    void audioSourceSurvivesNullVideoLists() throws Exception {
        final Fixture extractor = new Fixture(6);
        extractor.audio = List.of(AUDIO);
        final StreamInfo info = StreamInfo.getInfo(extractor);

        assertEquals(List.of(AUDIO), info.getAudioStreams());
        assertTrue(info.getVideoStreams().isEmpty());
        assertTrue(info.getVideoOnlyStreams().isEmpty());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void combinedVideoSurvivesNullAudioAndVideoOnlyLists() throws Exception {
        final Fixture extractor = new Fixture(5);
        extractor.video = List.of(VIDEO);
        final StreamInfo info = StreamInfo.getInfo(extractor);

        assertEquals(List.of(VIDEO), info.getVideoStreams());
        assertTrue(info.getAudioStreams().isEmpty());
        assertTrue(info.getVideoOnlyStreams().isEmpty());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void nonNullListsRetainTheirMedia() throws Exception {
        final Fixture extractor = new Fixture(0);
        extractor.audio = List.of(AUDIO);
        extractor.video = List.of(VIDEO);
        extractor.videoOnly = List.of(VIDEO_ONLY);
        final StreamInfo info = StreamInfo.getInfo(extractor);

        assertEquals(List.of(AUDIO), info.getAudioStreams());
        assertEquals(List.of(VIDEO), info.getVideoStreams());
        assertEquals(List.of(VIDEO_ONLY), info.getVideoOnlyStreams());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void explicitUnsupportedAudioStillFailsWithOtherPlayableSources() {
        final Fixture extractor = new Fixture(0);
        final ContentNotSupportedException refusal = new ContentNotSupportedException("refused");
        extractor.audioFailure = refusal;
        extractor.video = List.of(VIDEO);
        extractor.dash = DASH;
        extractor.hls = HLS;

        assertSame(refusal, assertThrows(ContentNotSupportedException.class,
                () -> StreamInfo.getInfo(extractor)));
    }

    @ParameterizedTest
    @EnumSource(ListKind.class)
    void optionalGetterFailuresRetainDiagnosticsWithAPlayableManifest(final ListKind kind)
            throws Exception {
        final Fixture extractor = new Fixture(0);
        extractor.hls = HLS;
        final ParsingException failure = new ParsingException("synthetic failure");
        switch (kind) {
            case AUDIO -> extractor.audioFailure = failure;
            case VIDEO -> extractor.videoFailure = failure;
            case VIDEO_ONLY -> extractor.videoOnlyFailure = failure;
        }

        final StreamInfo info = StreamInfo.getInfo(extractor);
        assertEmptyLists(info);
        assertEquals(HLS, info.getHlsUrl());
        assertEquals(1, info.getErrors().size());
        assertSame(failure, info.getErrors().get(0).getCause());
    }

    @Test
    void publicSettersCanClearPreviouslyPopulatedListsWithNull() {
        final StreamInfo info = new StreamInfo(0, URL, URL, StreamType.VIDEO_STREAM,
                "fixture", "Fixture", StreamExtractor.NO_AGE_LIMIT);
        info.setAudioStreams(List.of(AUDIO));
        info.setVideoStreams(List.of(VIDEO));
        info.setVideoOnlyStreams(List.of(VIDEO_ONLY));

        info.setAudioStreams(null);
        info.setVideoStreams(null);
        info.setVideoOnlyStreams(null);

        assertEmptyLists(info);
    }

    private static void assertEmptyLists(final StreamInfo info) {
        assertNotNull(info.getAudioStreams());
        assertNotNull(info.getVideoStreams());
        assertNotNull(info.getVideoOnlyStreams());
        assertTrue(info.getAudioStreams().isEmpty());
        assertTrue(info.getVideoStreams().isEmpty());
        assertTrue(info.getVideoOnlyStreams().isEmpty());
    }

    private enum ListKind { AUDIO, VIDEO, VIDEO_ONLY }

    private static final class Fixture extends StreamExtractor {
        private List<AudioStream> audio;
        private List<VideoStream> video;
        private List<VideoStream> videoOnly;
        private String dash = "";
        private String hls = "";
        private StreamType streamType = StreamType.VIDEO_STREAM;
        private ExtractionException audioFailure;
        private ExtractionException videoFailure;
        private ExtractionException videoOnlyFailure;

        Fixture(final int nullMask) {
            super(ServiceList.YouTube, new LinkHandler(URL, URL, "fixture"));
            audio = (nullMask & 1) == 0 ? List.of() : null;
            video = (nullMask & 2) == 0 ? List.of() : null;
            videoOnly = (nullMask & 4) == 0 ? List.of() : null;
        }

        @Override public void onFetchPage(final Downloader downloader) { }
        @Override public String getName() { return "Fixture"; }
        @Override public List<Image> getThumbnails() { return List.of(); }
        @Override public String getUploaderUrl() { return "https://example.invalid/channel"; }
        @Override public String getUploaderName() { return "Fixture uploader"; }
        @Override public StreamType getStreamType() { return streamType; }
        @Override public String getDashMpdUrl() { return dash; }
        @Override public String getHlsUrl() { return hls; }

        @Override
        public List<AudioStream> getAudioStreams() throws ExtractionException {
            if (audioFailure != null) {
                throw audioFailure;
            }
            return audio;
        }

        @Override
        public List<VideoStream> getVideoStreams() throws ExtractionException {
            if (videoFailure != null) {
                throw videoFailure;
            }
            return video;
        }

        @Override
        public List<VideoStream> getVideoOnlyStreams() throws ExtractionException {
            if (videoOnlyFailure != null) {
                throw videoOnlyFailure;
            }
            return videoOnly;
        }
    }
}
