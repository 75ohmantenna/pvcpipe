package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@Tag("offline")
class RumbleStreamMetadataTest {
    private static final String WATCH = "https://rumble.com/v7hnjfy-metadata.html";
    private static final String DETAILS = "\"title\":\"Details title\","
            + "\"upload_date\":\"2026-10-02T12:00:00+00:00\",\"duration\":123,"
            + "\"res\":2160,\"thumb\":\"https://cdn.example/details.jpg\","
            + "\"by\":{\"name\":\"Details creator\",\"url\":\"https://rumble.com/c/Details\","
            + "\"thumb\":\"https://cdn.example/avatar.jpg\",\"verified_badge\":true,"
            + "\"followers\":456,\"subscribers\":999},\"description\":\"Details description\","
            + "\"views\":789,\"rumble_votes\":{\"num_votes_up\":12,\"num_votes_down\":3}";
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;
    private Map<String, Long> cache;
    private Map<String, Long> previousCache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void saveConfiguration() throws Exception {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        final Field field = RumbleMediaResolver.class.getDeclaredField("VIDEO_IDS_CACHE");
        field.setAccessible(true);
        cache = (Map<String, Long>) field.get(null);
        synchronized (cache) {
            previousCache = new LinkedHashMap<>(cache);
            cache.clear();
        }
    }

    @AfterEach
    void restoreConfiguration() {
        synchronized (cache) {
            cache.clear();
            cache.putAll(previousCache);
        }
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @Test
    void validEmbedFieldsWinWhileDetailsSupplyDescriptionAndCreatorExtras() throws Exception {
        final StreamExtractor extractor = extractor(new FixtureDownloader(DETAILS,
                "\"title\":\"Embed &amp; title\",\"pubDate\":\"2026-10-01T12:00:00+00:00\","
                        + "\"duration\":42,\"i\":\"https://cdn.example/embed.jpg\","
                        + "\"t\":[{\"i\":\"https://cdn.example/embed.jpg\",\"h\":720,\"w\":1280}],"
                        + "\"author\":{\"name\":\"Embed creator\","
                        + "\"url\":\"https://rumble.com/c/Embed\"}"), WATCH);
        assertEquals("Embed & title", extractor.getName());
        assertEquals("2026-10-01T12:00:00+00:00", extractor.getTextualUploadDate());
        assertNotNull(extractor.getUploadDate());
        assertEquals(42, extractor.getLength());
        assertEquals("720p", extractor.getVideoStreams().get(0).getResolution());
        assertEquals("https://cdn.example/embed.jpg", extractor.getThumbnails().get(0).getUrl());
        assertEquals(720, extractor.getThumbnails().get(0).getHeight());
        assertEquals("Embed creator", extractor.getUploaderName());
        assertEquals("https://rumble.com/c/Embed", extractor.getUploaderUrl());
        assertEquals("Details description", extractor.getDescription().getContent());
        assertEquals(Description.PLAIN_TEXT, extractor.getDescription().getType());
        assertEquals("https://cdn.example/avatar.jpg",
                extractor.getUploaderAvatars().get(0).getUrl());
        assertTrue(extractor.isUploaderVerified());
        assertEquals(456, extractor.getUploaderSubscriberCount());
        assertEquals(789, extractor.getViewCount());
        assertEquals(12, extractor.getLikeCount());
        assertEquals(3, extractor.getDislikeCount());
    }

    @Test
    void emptyOrInvalidEmbedMetadataFallsBackToDetails() throws Exception {
        final StreamExtractor extractor = extractor(new FixtureDownloader(DETAILS,
                "\"title\":\"\",\"pubDate\":\"invalid\",\"duration\":-1,\"i\":null,"
                        + "\"author\":{\"name\":\"\",\"url\":null}"), WATCH);
        assertEquals("Details title", extractor.getName());
        assertEquals("2026-10-02T12:00:00+00:00", extractor.getTextualUploadDate());
        assertNotNull(extractor.getUploadDate());
        assertEquals(123, extractor.getLength());
        final Image cover = extractor.getThumbnails().get(0);
        assertEquals("https://cdn.example/details.jpg", cover.getUrl());
        assertEquals(Image.HEIGHT_UNKNOWN, cover.getHeight());
        assertEquals(Image.WIDTH_UNKNOWN, cover.getWidth());
        assertEquals("Details creator", extractor.getUploaderName());
        assertEquals("https://rumble.com/c/Details", extractor.getUploaderUrl());
    }

    @Test
    void nullOptionalDetailsDoNotInventImagesOrUploader() throws Exception {
        final StreamExtractor extractor = extractor(new FixtureDownloader(
                "\"title\":\"No optional metadata\",\"thumb\":null,\"by\":null", ""), WATCH);
        assertTrue(extractor.getThumbnails().isEmpty());
        assertTrue(extractor.getUploaderAvatars().isEmpty());
        assertEquals("", extractor.getUploaderName());
        assertEquals("", extractor.getUploaderUrl());
        assertFalse(extractor.isUploaderVerified());
        assertEquals(-1, extractor.getUploaderSubscriberCount());
        assertEquals(-1, extractor.getViewCount());
        assertEquals(-1, extractor.getLikeCount());
        assertEquals(-1, extractor.getDislikeCount());
        assertEquals("", extractor.getDescription().getContent());
    }

    @Test
    void explicitEmptyDescriptionIsPreservedAndAgeRequiresTrue() throws Exception {
        for (final String restricted : List.of("true", "false", "null")) {
            final StreamExtractor extractor = extractor(new FixtureDownloader(
                    "\"description\":\"\",\"is_age_restricted\":" + restricted, ""), WATCH);
            assertEquals("", extractor.getDescription().getContent());
            assertEquals("true".equals(restricted) ? 18 : 0, extractor.getAgeLimit());
        }
    }

    @Test
    void numericEmbedLiveIsAuthoritativeAndOnlyTwoMeansActive() throws Exception {
        for (final int live : List.of(0, 1, 2)) {
            final StreamExtractor extractor = extractor(new FixtureDownloader(
                    "\"live\":true,\"watching_now\":34,\"views\":987", "\"live\":" + live),
                    WATCH);
            assertEquals(live == 2 ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM,
                    extractor.getStreamType());
            assertEquals(live == 2 ? 34 : 987, extractor.getViewCount());
        }
        final StreamExtractor missing = extractor(new FixtureDownloader("\"live\":true", ""),
                WATCH);
        assertEquals(StreamType.LIVE_STREAM, missing.getStreamType());
        assertEquals(-1, missing.getViewCount());
    }

    @Test
    void coldBareLiveIgnoresHtmlViewerIdentityAndRecordedViewCounts() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader("", "\"live\":2");
        final StreamExtractor extractor = extractor(downloader, "https://rumble.com/vbarelive");
        assertEquals(StreamType.LIVE_STREAM, extractor.getStreamType());
        assertEquals(-1, extractor.getViewCount());
        assertFalse(downloader.requests.stream().anyMatch(url -> url.contains("watching-now")));
    }

    @Test
    void recordedBareLinksKeepHtmlDescriptionViewsAndVotes() throws Exception {
        final StreamExtractor extractor = extractor(new FixtureDownloader("", "\"live\":1"),
                "https://rumble.com/vbaremetadata");
        assertEquals("HTML description", extractor.getDescription().getContent());
        assertEquals(1234, extractor.getViewCount());
        assertEquals(9, extractor.getLikeCount());
        assertEquals(2, extractor.getDislikeCount());
    }

    @Test
    void premiumDenialIsExplicitAndPublicAccessFlagAloneDoesNotDeny() throws Exception {
        final StreamExtractor publicVideo = extractor(new FixtureDownloader(
                "\"visibility\":\"public\",\"has_access_to_premium\":false", ""), WATCH);
        assertFalse(publicVideo.getVideoStreams().isEmpty());
        synchronized (cache) {
            cache.clear();
        }
        assertThrows(PaidContentException.class, () -> extractor(new FixtureDownloader(
                "\"availability\":\"premium\",\"has_access_to_premium\":false", ""), WATCH));
    }

    @Test
    void relatedAutoplayWithoutHtmlExcludesSelfAndDuplicatesAndCachesCollector() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader("", "");
        downloader.autoplay = "{\"data\":{\"videos\":["
                + video(449259064, WATCH, "Current") + ","
                + video(123, "https://rumble.com/vrelated-one.html", "Other") + ","
                + video(123, "https://rumble.com/vrelated-one.html", "Duplicate") + "]}}";
        final StreamExtractor extractor = extractor(downloader, WATCH);
        final var related = extractor.getRelatedItems();
        assertEquals(1, related.getItems().size());
        assertEquals("Other", related.getItems().get(0).getName());
        downloader.autoplay = null;
        assertSame(related, extractor.getRelatedItems());
    }

    @Test
    void htmlRelatedListRemainsPreferred() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader("", "");
        downloader.htmlRelated = "<ul class='mediaList-list'></ul>";
        final StreamExtractor extractor = extractor(downloader, "https://rumble.com/vbarerelated");
        assertTrue(extractor.getRelatedItems().getItems().isEmpty());
        assertFalse(downloader.requests.stream().anyMatch(url -> url.contains("video.autoplay")));
    }

    @Test
    void malformedRelatedDataAndIoRemainOptionalExtractionErrors() throws Exception {
        for (final String response : new String[] {null, "{\"data\":{}}",
                "{\"data\":{\"videos\":{}}}", "{\"error\":\"Related unavailable\"}"}) {
            final FixtureDownloader downloader =
                    new FixtureDownloader("\"title\":\"Playable\"", "");
            downloader.autoplay = response;
            final StreamExtractor extractor = extractor(downloader, WATCH);
            final ParsingException error = assertThrows(ParsingException.class,
                    extractor::getRelatedItems);
            if (response == null) {
                assertTrue(error.getCause() instanceof IOException);
            }
            final StreamInfo info = StreamInfo.getInfo(extractor);
            assertFalse(info.getVideoStreams().isEmpty());
            assertFalse(info.getErrors().isEmpty());
        }
    }

    @Test
    void captionsSupplementEmbedAndKeepManualAutomaticIdentities() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader("\"cc\":["
                + "{\"language_code\":\"en-auto\",\"url\":\"https://cdn.example/auto.vtt\"},"
                + "{\"language_code\":\"de\",\"url\":\"https://cdn.example/de.vtt\"},null,"
                + "{\"language_code\":\"-auto\",\"url\":\"https://cdn.example/bad.vtt\"},"
                + "{\"language_code\":\"fr\",\"url\":null}]", "\"cc\":{"
                + "\"en\":{\"path\":\"https://cdn.example/manual.vtt\"},"
                + "\"en-auto\":{\"path\":\"https://cdn.example/auto.vtt\"},"
                + "\"es-AUTO\":{\"path\":\"https://cdn.example/es.vtt\"},"
                + "\"bad_code\":{\"path\":null},\"fr\":null}");
        final StreamExtractor extractor = extractor(downloader, WATCH);
        final List<SubtitlesStream> tracks = extractor.getSubtitlesDefault();
        assertEquals(List.of("en.vtt", "en-auto.vtt", "es-AUTO.vtt", "de.vtt"),
                tracks.stream().map(SubtitlesStream::getId).toList());
        assertEquals("en", tracks.get(1).getLanguageTag());
        assertTrue(tracks.get(1).isAutoGenerated());
        assertFalse(tracks.get(0).isAutoGenerated());
        assertTrue(tracks.get(2).isAutoGenerated());
        assertEquals(tracks, extractor.getSubtitles(MediaFormat.VTT));
        assertTrue(extractor.getSubtitles(MediaFormat.SRT).isEmpty());
    }

    @Test
    void absentOrEmptyArrayEmbedCaptionsStillAllowDetailsTracks() throws Exception {
        for (final String embed : List.of("", "\"cc\":null", "\"cc\":[]")) {
            final StreamExtractor extractor = extractor(new FixtureDownloader(
                    "\"cc\":[{\"language_code\":\"en\",\"url\":\"https://cdn.example/en.vtt\"}]",
                    embed), WATCH);
            assertEquals(1, extractor.getSubtitlesDefault().size());
        }
    }

    private static String video(final long id, final String url, final String title) {
        return "{\"id\":" + id + ",\"url\":\"" + url + "\",\"title\":\"" + title + "\"}";
    }

    private static StreamExtractor extractor(final FixtureDownloader downloader, final String url)
            throws Exception {
        NewPipe.init(downloader);
        final StreamExtractor extractor = Rumble.getStreamExtractor(url);
        extractor.fetchPage();
        return extractor;
    }

    private static final class FixtureDownloader extends Downloader {
        private final String details;
        private final String embed;
        private final List<String> requests = new ArrayList<>();
        private String autoplay = "{\"data\":{\"videos\":[]}}";
        private String htmlRelated = "";

        private FixtureDownloader(final String details, final String embed) {
            this.details = details;
            this.embed = embed;
        }

        @Override
        public Response execute(@Nonnull final Request request) throws IOException {
            final String url = request.url();
            requests.add(url);
            final String body;
            if (url.contains("name=media.details")) {
                body = "{\"data\":{\"id\":449259064,\"url\":\"" + WATCH + "\""
                        + (details.isEmpty() ? "" : "," + details) + "}}";
            } else if (url.startsWith("https://rumble.com/embedJS/u3/")) {
                if (!url.endsWith("&v=v7fh6ig")) {
                    throw new IOException("Wrong embed identity: " + url);
                }
                body = "{\"vid\":449259064,\"ua\":{\"mp4\":[{"
                        + "\"url\":\"https://cdn.example/video.mp4\",\"meta\":{\"h\":720}}]}"
                        + (embed.isEmpty() ? "" : "," + embed) + "}";
            } else if (url.contains("name=video.autoplay")) {
                final String query = URLDecoder.decode(URI.create(url).getRawQuery(),
                        StandardCharsets.UTF_8);
                if (!query.contains("api=8") || !query.contains("video_id=449259064")) {
                    throw new IOException("Wrong related identity: " + url);
                }
                if (autoplay == null) {
                    throw new IOException("Related fixture refused");
                }
                body = autoplay;
            } else if (url.startsWith("https://rumble.com/vbare")) {
                body = "<html><body><script src='https://rumble.com/embed/v7fh6ig/'></script>"
                        + "<script>viewer_id: \"must-not-send\"</script>"
                        + "<p class='media-description'>HTML description</p>"
                        + "<div class='media-description-info-views'>1,234 views</div>"
                        + "<span data-js='rumbles_up_votes'>9</span>"
                        + "<span data-js='rumbles_down_votes'>2</span>" + htmlRelated
                        + "</body></html>";
            } else {
                throw new IOException("No fixture for " + url);
            }
            return new Response(200, "OK", Map.of(), body, url);
        }
    }
}
