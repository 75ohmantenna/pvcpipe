package org.schabi.newpipe.extractor.services.bitchute.extractor;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.pvc.PvcCloudFlareChallengeException;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteParserHelper;
import org.schabi.newpipe.extractor.services.bitchute.search.filter.BitchuteFilters;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import static org.junit.jupiter.api.Assertions.*;
import static org.schabi.newpipe.extractor.ServiceList.Bitchute;

@Tag("offline")
class BitchuteWebsiteInteractionTest {
    private static final String VIDEO = "https://www.bitchute.com/video/e-eScuPupHE";

    @Test
    void videoPaginationKeepsItsOwnFiltersAfterChannelSearch() throws Exception {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader);
        final var filters = Bitchute.getSearchQHFactory().getSearchFilters();
        final var videos = Bitchute.getSearchExtractor("space",
                List.of(filters.getFilterItem(BitchuteFilters.ID_CF_MAIN_VIDEOS)),
                List.of(filters.getFilterItem(BitchuteFilters.ID_SF_SORT_BY_OLDEST),
                        filters.getFilterItem(BitchuteFilters.ID_SF_DURATION_SHORT)));
        downloader.body = "{\"video_count\":40,\"videos\":[" + video() + "]}";
        final Page next = videos.getInitialPage().getNextPage();
        Bitchute.getSearchExtractor("other",
                List.of(filters.getFilterItem(BitchuteFilters.ID_CF_MAIN_CHANNELS)), List.of());
        videos.getPage(next);
        assertTrue(downloader.url.endsWith("/search/videos"));
        assertEquals("space", downloader.payload.getString("query"));
        assertEquals("old", downloader.payload.getString("sort"));
        assertEquals("short", downloader.payload.getString("duration"));
        assertEquals(20, downloader.payload.getInt("offset"));
    }

    @Test
    void channelSearchShowsReadableDescriptionsAndDropsVideoOnlyFilters() throws Exception {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader);
        downloader.body = "{\"channel_count\":1,\"channels\":[{\"channel_id\":\"channel\","
                + "\"channel_name\":\"Space\",\"channel_url\":\"/channel/channel/\","
                + "\"thumbnail_url\":\"https://static-3.bitchute.com/image.jpg\","
                + "\"description\":\"<p>Space &amp; science</p>\"}]}";
        final var filters = Bitchute.getSearchQHFactory().getSearchFilters();
        final var page = Bitchute.getSearchExtractor("space",
                List.of(filters.getFilterItem(BitchuteFilters.ID_CF_MAIN_CHANNELS)),
                List.of(filters.getFilterItem(BitchuteFilters.ID_SF_SORT_BY_OLDEST),
                        filters.getFilterItem(BitchuteFilters.ID_SF_DURATION_SHORT))).getInitialPage();
        assertFalse(downloader.payload.has("sort"));
        assertFalse(downloader.payload.has("duration"));
        assertEquals("Space & science", ((org.schabi.newpipe.extractor.channel.ChannelInfoItem)
                page.getItems().get(0)).getDescription());
        assertFalse(page.hasNextPage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 40})
    void searchStopsAtExactTotal(final int total) throws Exception {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader);
        final List<String> items = new ArrayList<>();
        for (int i = 0; i < Math.min(total, 20); i++) { items.add(video()); }
        downloader.body = "{\"video_count\":" + total + ",\"videos\":["
                + String.join(",", items) + "]}";
        final var extractor = Bitchute.getSearchExtractor("space", List.of(), List.of());
        final var first = extractor.getInitialPage();
        if (total <= 20) { assertFalse(first.hasNextPage()); }
        else { assertFalse(extractor.getPage(first.getNextPage()).hasNextPage()); }
    }

    @Test
    void legacySafeFilterUsesLowestCurrentSensitivity() throws Exception {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader);
        downloader.body = "{\"videos\":[],\"video_count\":0}";
        final var filters = Bitchute.getSearchQHFactory().getSearchFilters();
        Bitchute.getSearchExtractor("space", List.of(),
                List.of(filters.getFilterItem(14))).getInitialPage();
        assertEquals("normal", downloader.payload.getString("sensitivity_id"));
        assertTrue(filters.getContentFilterSortFilterVariant(BitchuteFilters.ID_CF_MAIN_VIDEOS)
                .getFilterGroups().stream().flatMap(g -> g.getFilterItems().stream())
                .noneMatch(item -> item.getIdentifier() == 14));
    }

    @Test
    void plainChannelTabResolvesMetadataAndPaginates() throws Exception {
        final RecordingDownloader downloader = new RecordingDownloader() {
            @Override public Response execute(@Nonnull final Request request) {
                body = request.url().endsWith("/channel")
                        ? "{\"channel_id\":\"actual-id\",\"channel_name\":\"Channel\","
                            + "\"channel_url\":\"/channel/actual-id/\"}"
                        : "{\"videos\":[" + String.join(",", java.util.Collections.nCopies(20, video())) + "]}";
                return super.execute(request);
            }
        };
        NewPipe.init(downloader);
        final var extractor = Bitchute.getChannelTabExtractorFromId("slug", ChannelTabs.VIDEOS);
        final var first = extractor.getInitialPage();
        assertEquals("actual-id", downloader.payload.getString("channel_id"));
        assertTrue(first.getErrors().isEmpty());
        extractor.getPage(first.getNextPage());
        assertEquals(20, downloader.payload.getInt("offset"));
    }

    @Test
    void autocompleteDecodesMarkupAndDeduplicates() throws Exception {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader);
        downloader.body = "[{\"message\":\"<mark>Space</mark> &amp; science\"},"
                + "{\"message\":\"Space &amp; science\"},{\"message\":\"\"}]";
        assertEquals(List.of("Space & science"), Bitchute.getSuggestionExtractor().suggestionList("space"));
        assertTrue(downloader.url.endsWith("/search2/videos/autocomplete"));
        assertEquals(10, downloader.payload.getInt("limit"));
        assertTrue(Bitchute.getSuggestionExtractor().suggestionList("s").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/channel/test", "https://bitchute.com.evil.example/channel/test",
            "ftp://www.bitchute.com/channel/test", "https://www.bitchute.com/channel/"})
    void rejectsNonChannelWebsiteLinks(final String url) throws Exception {
        assertFalse(Bitchute.getChannelLHFactory().acceptUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.bitchute.com/api/beta9/embed/e-eScuPupHE/",
            "https://www.bitchute.com/api/beta9/selfembed/e-eScuPupHE/",
            "https://old.bitchute.com/embed/e-eScuPupHE/"})
    void acceptsCurrentAndLegacyEmbedLinks(final String url) throws Exception {
        assertEquals("e-eScuPupHE", Bitchute.getStreamLHFactory().fromUrl(url).getId());
    }

    @ParameterizedTest
    @CsvSource({"safe,0", "normal,12", "nsfw,15", "nsfl,18"})
    void ageLabelsMatchCurrentWebsiteRatings(final String sensitivity, final int age) throws Exception {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader);
        downloader.body = "{\"sensitivity_id\":\"" + sensitivity + "\",\"videos\":[],\"hashtags\":[],\"channel\":{}}";
        final var extractor = Bitchute.getStreamExtractor(VIDEO);
        extractor.fetchPage();
        assertEquals(age, extractor.getAgeLimit());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-05 23:53:52.285778+00:00", "2026-10-05T23:53:52Z", "2026-10-05 23:53:52.123+00:00"})
    void parsesCommentDatesAndAbsoluteAvatars(final String date) throws Exception {
        final var item = new BitchuteCommentsInfoItemExtractor(JsonObject.builder()
                .value("created", date).value("profile_picture_url", "https://bcmedia.bitchute.com/img/blank-profile.png").done(), VIDEO);
        assertEquals("https://bcmedia.bitchute.com/img/blank-profile.png", item.getUploaderAvatars().get(0).getUrl());
        assertEquals(Instant.parse("2026-10-05T23:53:52Z").getEpochSecond(), item.getUploadDate().getInstant().getEpochSecond());
    }

    @Test
    void findsLocationErrorAfterGenericHttpError() {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader); downloader.code = 403;
        downloader.body = "{\"errors\":[{\"context\":\"HTTP\",\"message\":\"Forbidden\"},"
                + "{\"context\":\"reason\",\"message\":\"Content access is restricted based on the users location\"}]}";
        assertThrows(GeographicRestrictionException.class,
                () -> BitchuteParserHelper.callJsonApi(JsonObject.builder(), "https://api.bitchute.com/test"));
    }

    @Test
    void htmlChallengeProducesActionableError() {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader); downloader.code = 403;
        downloader.body = "<html><title>Just a moment...</title></html>";
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> BitchuteParserHelper.callJsonApi(JsonObject.builder(), "https://api.bitchute.com/test"));
    }

    @Test
    void htmlServerErrorPreservesHttpStatus() {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader); downloader.code = 503;
        downloader.body = "<html>Unavailable</html>";
        assertTrue(assertThrows(ContentNotAvailableException.class,
                () -> BitchuteParserHelper.callJsonApi(JsonObject.builder(), "https://api.bitchute.com/test"))
                .getMessage().contains("503"));
    }

    @Test
    void missingCommentAuthFailsInsteadOfReportingEmptyComments() {
        final RecordingDownloader downloader = new RecordingDownloader();
        NewPipe.init(downloader); downloader.body = "{}";
        assertThrows(ParsingException.class, () -> BitchuteParserHelper.getComments("e-eScuPupHE", VIDEO, 0));
    }

    @Test
    void commentsKeepRepliesUnderTheirParentsAcrossFreshExtractors() throws Exception {
        final RecordingDownloader downloader = new RecordingDownloader() {
            @Override public Response execute(@Nonnull final Request request) {
                if (request.url().endsWith("/apps/commentfreely/video/")) {
                    body = "{\"auth\":\"token\"}";
                    return super.execute(request);
                }
                assertTrue(new String(request.dataToSend(), StandardCharsets.UTF_8).contains("cf_auth=token"));
                return new Response(200, "OK", Map.of(), "["
                        + comment("root", null) + "," + comment("child", "root") + ","
                        + comment("grandchild", "child") + "," + comment("orphan", "deleted") + "]", request.url());
            }
        };
        NewPipe.init(downloader);
        final var first = Bitchute.getCommentsExtractor(VIDEO).getInitialPage();
        assertEquals(2, first.getItems().size());
        assertTrue(first.getErrors().isEmpty());
        final var root = first.getItems().get(0);
        assertEquals(1, root.getReplyCount());
        final var replies = Bitchute.getCommentsExtractor(root.getUrl()).getPage(root.getReplies());
        assertEquals(1, replies.getItems().size());
        final var child = replies.getItems().get(0);
        assertEquals("child", child.getCommentId());
        assertEquals(1, child.getReplyCount());
        assertEquals("grandchild", Bitchute.getCommentsExtractor(child.getUrl())
                .getPage(child.getReplies()).getItems().get(0).getCommentId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.bitchute.com/bitchute",
            "https://bitchute.com/bitchute/", "https://bitchute.com/channel/space.science"})
    void acceptsChannelSlugs(final String url) throws Exception {
        assertEquals(url.contains("space.science") ? "space.science" : "bitchute",
                Bitchute.getChannelLHFactory().fromUrl(url).getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://bitchute.com/", "https://bitchute.com/search",
            "https://bitchute.com/popular", "https://bitchute.com/SETTINGS",
            "https://bitchute.com/api", "https://bitchute.com/video",
            "https://bitchute.com/.", "https://bitchute.com/..",
            "https://bitchute.com/channel/.."})
    void rejectsReservedWebsiteRoutesAsChannels(final String url) throws Exception {
        assertFalse(Bitchute.getChannelLHFactory().acceptUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"counts", "suggested"})
    void optionalApiFailuresDoNotPreventPlayback(final String failedEndpoint) throws Exception {
        final RecordingDownloader downloader = new RecordingDownloader() {
            @Override public Response execute(@Nonnull final Request request) {
                final String endpoint = request.url();
                code = 200;
                if (("counts".equals(failedEndpoint) && endpoint.endsWith("/counts"))
                        || ("suggested".equals(failedEndpoint) && endpoint.endsWith("/videos"))) {
                    code = 503;
                    body = "<html>Unavailable</html>";
                } else if (endpoint.endsWith("/media")) {
                    body = "{\"media_url\":\"https://seed131b.bitchute.com/channel/video.mp4\","
                            + "\"media_type\":\"video/mp4\"}";
                } else if (endpoint.endsWith("/counts")) {
                    body = "{\"view_count\":12,\"like_count\":1,\"dislike_count\":0}";
                } else if (endpoint.endsWith("/videos")) {
                    body = "{\"videos\":[]}";
                } else {
                    body = "{\"video_name\":\"Space\",\"duration\":\"2:55\","
                            + "\"sensitivity_id\":\"normal\",\"hashtags\":[],\"channel\":{}}";
                }
                return super.execute(request);
            }
        };
        NewPipe.init(downloader);
        final var extractor = Bitchute.getStreamExtractor(VIDEO);
        extractor.fetchPage();
        assertEquals("Space", extractor.getName());
        assertEquals(1, extractor.getVideoStreams().size());
        if ("counts".equals(failedEndpoint)) {
            assertThrows(ParsingException.class, extractor::getViewCount);
            assertThrows(ParsingException.class, extractor::getLikeCount);
            assertThrows(ParsingException.class, extractor::getDislikeCount);
            assertTrue(extractor.getRelatedItems().getItems().isEmpty());
        } else {
            assertEquals(12, extractor.getViewCount());
            assertThrows(ExtractionException.class, extractor::getRelatedItems);
        }
    }

    private static String comment(final String id, final String parent) {
        return "{\"id\":\"" + id + "\",\"parent\":" + (parent == null ? "null" : "\"" + parent + "\"")
                + ",\"fullname\":\"Person\",\"content\":\"Example\",\"created\":\"2026-10-05 23:53:52Z\","
                + "\"profile_picture_url\":\"https://bcmedia.bitchute.com/img/blank-profile.png\"}";
    }

    private static String video() {
        return "{\"video_id\":\"e-eScuPupHE\",\"video_name\":\"Space\",\"video_url\":\"/video/e-eScuPupHE/\","
                + "\"duration\":\"2:55\",\"thumbnail_url\":\"https://static-3.bitchute.com/image.jpg\","
                + "\"channel\":{\"channel_name\":\"Channel\",\"channel_url\":\"/channel/test/\"}}";
    }

    private static class RecordingDownloader extends Downloader {
        String body;
        int code = 200;
        String url;
        JsonObject payload;
        @Override public Response execute(@Nonnull final Request request) {
            url = request.url();
            assertEquals("POST", request.httpMethod());
            try { payload = JsonParser.object().from(new String(request.dataToSend(), StandardCharsets.UTF_8)); }
            catch (final Exception e) { throw new AssertionError(e); }
            return new Response(code, "Response", Map.of(), body, request.url());
        }
    }
}
