package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import java.nio.charset.StandardCharsets;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.pvc.PvcCloudFlareChallengeException;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleTrendingLinkHandlerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@Tag("offline")
class RumbleWebsiteInteractionTest {
    @Test
    void challengedBrowsePagesRaiseAnActionableErrorInsteadOfShowingNoResults() throws Exception {
        NewPipe.init(new FixtureDownloader(403, "<title>Just a moment...</title>"));
        for (final String kiosk : List.of(RumbleTrendingLinkHandlerFactory.LIVE,
                RumbleTrendingLinkHandlerFactory.LATEST,
                RumbleTrendingLinkHandlerFactory.EDITOR_PICKS,
                RumbleTrendingLinkHandlerFactory.DEFAULT_TRENDING)) {
            final var extractor = Rumble.getKioskList().getExtractorById(kiosk, null);
            assertThrows(PvcCloudFlareChallengeException.class, extractor::fetchPage);
        }
    }

    @Test
    void challengedSearchAndChannelPaginationDoNotSilentlyEndTheList() throws Exception {
        NewPipe.init(new FixtureDownloader(403, "<title>Just a moment...</title>"));
        final Page next = new Page("https://rumble.com/c/Example?page=2");
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getSearchExtractor("space", List.of(), null).getPage(next));
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getChannelTabExtractorFromId("c/Example",
                        org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.VIDEOS).getPage(next));
    }

    @Test
    void jsonEndpointsReportChallengesBeforeTryingToParseHtmlAsJson() throws Exception {
        NewPipe.init(new FixtureDownloader(403, "<title>Just a moment...</title>"));
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getStreamExtractor("https://rumble.com/embed/vtest/").fetchPage());
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getStreamExtractor("https://rumble.com/shorts/vtest").fetchPage());
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getSuggestionExtractor().suggestionList("space"));
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> shorts().fetchPage());
    }

    @Test
    void shortsPaginationFetchesOffsetTenAndStopsOnAnEmptyFeed() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader(200,
                "{\"data\":{\"items\":[{\"object_type\":\"ad\"}]}}");
        NewPipe.init(downloader);
        final var extractor = shorts();
        extractor.fetchPage();
        final var initial = extractor.getInitialPage();
        assertEquals("10", initial.getNextPage().getId());
        assertTrue(initial.getNextPage().getUrl().contains("offset=10&"));
        downloader.body = "{\"data\":{\"items\":[]}}";
        final var next = extractor.getPage(initial.getNextPage());
        assertTrue(downloader.urls.get(1).contains("offset=10&"));
        assertTrue(next.getItems().isEmpty());
        assertNull(next.getNextPage());
    }

    @Test
    void embeddedVideoCommentsUseTheEmbedIdWithoutFetchingAnInvalidWatchUrl() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader(200,
                "{\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":\"\"}");
        NewPipe.init(downloader);
        final var extractor = Rumble.getCommentsExtractor("https://rumble.com/embed/u3.vtest123/");
        extractor.fetchPage();
        assertEquals(List.of("https://rumble.com/service.php?video=test123&name=comment.list"),
                downloader.urls);
        assertFalse(extractor.isCommentsDisabled());
        assertTrue(extractor.getInitialPage().getItems().isEmpty());
        assertEquals(1, downloader.urls.size());
    }

    @Test
    void shortsCommentsUseTheInternalVideoIdRatherThanThePublicPermalink() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader(200,
                "<rum-shorts><script type='application/json'>{\"items\":["
                        + "{\"permalink_id\":\"v7g09by\",\"id\":446493256}]}"
                        + "</script></rum-shorts>");
        NewPipe.init(downloader);
        final var extractor = Rumble.getCommentsExtractor("https://rumble.com/shorts/v7g09by");
        assertEquals("https://rumble.com/service.php?video=7dtweg&name=comment.list",
                extractor.getUrl());
        assertEquals("https://rumble.com/service.php?video=7dtweg&name=comment.list",
                extractor.getUrl());
        assertEquals(List.of("https://rumble.com/shorts/v7g09by"), downloader.urls);
    }

    @Test
    void commentChallengesFailDuringFetchInsteadOfBeingMarkedDisabled() throws Exception {
        NewPipe.init(new FixtureDownloader(403, "<title>Just a moment...</title>"));
        final var extractor = Rumble.getCommentsExtractor("https://rumble.com/embed/vtest123/");
        assertThrows(PvcCloudFlareChallengeException.class, extractor::fetchPage);
    }

    @Test
    void commentsPaginationAndRepliesSurviveFreshExtractors() throws Exception {
        NewPipe.init(new FixtureDownloader(200,
                "<iframe src=\"https://rumble.com/embed/vcommentembed/\"></iframe>"));
        final StringBuilder html = new StringBuilder("<ul class='comments-1'>");
        for (int i = 1; i <= 16; i++) {
            html.append(comment(i, i == 1
                    ? "<div class='comment-replies'><ul class='comments-2'>"
                            + comment(101, "") + "</ul></div>" : ""));
        }
        html.append("</ul>");
        final JsonObject response = new JsonObject();
        response.put("html", html.toString());
        response.put("css_libs", "");
        final byte[] body = JsonWriter.string(response).getBytes(StandardCharsets.UTF_8);
        final var firstExtractor = Rumble.getCommentsExtractor("https://rumble.com/vcomments-test.html");
        final var first = firstExtractor.getPage(new Page("1", body));
        assertEquals(15, first.getItems().size());
        assertTrue(first.getErrors().isEmpty(), first.getErrors().toString());
        assertEquals("1", first.getItems().get(0).getCommentId());
        assertEquals("16", first.getNextPage().getUrl());
        assertEquals("https://rumble.com/vcomments-test.html", first.getItems().get(0).getUrl());
        final var freshExtractor = Rumble.getCommentsExtractor(first.getItems().get(0).getUrl());
        final var last = freshExtractor.getPage(first.getNextPage());
        assertEquals(1, last.getItems().size());
        assertEquals("16", last.getItems().get(0).getCommentId());
        assertFalse(last.hasNextPage());
        final var replies = freshExtractor.getPage(first.getItems().get(0).getReplies());
        assertEquals("101", replies.getItems().get(0).getCommentId());
        assertFalse(replies.hasNextPage());
    }

    private static String comment(final int id, final String replies) {
        return "<li class='comment-item' data-comment-id='" + id + "'>"
                + "<div class='comments-meta'><a class='comments-meta-author' href='/user/Example'>"
                + "Example</a><a class='comments-meta-post-time' "
                + "title='Monday, October 5, 2026 1:00 PM +00'></a></div>"
                + "<p class='comment-text'>Comment " + id + "</p>"
                + "<div class='rumbles-vote'><span class='rumbles-up-votes'>1</span></div>"
                + replies + "</li>";
    }

    @Test
    void channelSearchHandlesBothCardLayoutsAndSkipsUnrelatedArticles() throws Exception {
        NewPipe.init(new FixtureDownloader(200,
                "<style>i.user-image--img--id-0 {background-image: "
                        + "url(https://cdn.example/avatar.jpg);}</style>"
                        + "<article>Advertisement</article>"
                        + "<article class='channel-item'><a class='channel-item--a' href='/c/Legacy'>"
                        + "<i class='user-image user-image--img user-image--img--id-0'></i>"
                        + "<h3 class='channel-item--title'>Legacy"
                        + "<svg class='verification-badge-icon'></svg></h3>"
                        + "<span class='channel-item--subscribers'>1,200 subscribers</span>"
                        + "</a></article>"
                        + "<article><a href='/c/Modern?tracking=value'><h3>"
                        + "<span class='block truncate'>Modern</span>"
                        + "<svg class='verification-badge-icon'><title>Verified</title></svg>"
                        + "</h3></a>"
                        + "<span class='text-sm text-fjord'>2,000 followers</span>"
                        + "<i class='user-image user-image--letter'></i></article>"));
        final var channels = org.schabi.newpipe.extractor.services.DefaultSearchExtractorTest
                .getFilterItem(Rumble,
                        org.schabi.newpipe.extractor.services.rumble.search.filter.RumbleFilters
                                .ID_CF_MAIN_CHANNELS);
        final var extractor = Rumble.getSearchExtractor("space", List.of(channels), null);
        extractor.fetchPage();
        final var page = extractor.getInitialPage();
        assertEquals(2, page.getItems().size());
        assertTrue(page.getErrors().isEmpty(), page.getErrors().toString());
        final var legacy = (org.schabi.newpipe.extractor.channel.ChannelInfoItem) page.getItems().get(0);
        assertEquals("https://rumble.com/c/Legacy", legacy.getUrl());
        assertEquals(1200, legacy.getSubscriberCount());
        assertTrue(legacy.isVerified());
        assertEquals("https://cdn.example/avatar.jpg", legacy.getThumbnails().get(0).getUrl());
        final var modern = (org.schabi.newpipe.extractor.channel.ChannelInfoItem) page.getItems().get(1);
        assertEquals("https://rumble.com/c/Modern", modern.getUrl());
        assertEquals("Modern", modern.getName());
        assertTrue(modern.isVerified());
        assertEquals(2000, modern.getSubscriberCount());
        assertTrue(modern.getThumbnails().isEmpty());
    }

    @Test
    void channelWithoutABannerReturnsNoImages() throws Exception {
        NewPipe.init(new FixtureDownloader(200,
                "<title>Example</title><div data-slug='Example' data-type='channel'></div>"));
        final var extractor = Rumble.getChannelExtractor("https://rumble.com/c/Example");
        extractor.fetchPage();
        assertEquals("c/Example", extractor.getId());
        assertTrue(extractor.getBanners().isEmpty());
    }

    @Test
    void emptyShortsFeedHasNoNextPage() throws Exception {
        NewPipe.init(new FixtureDownloader(200, "{\"data\":{\"items\":[]}}"));
        final var extractor = shorts();
        extractor.fetchPage();
        assertFalse(extractor.getInitialPage().hasNextPage());
    }

    @Test
    void pagesKeepTheFinalUrlForResolvingRelativeLinks() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader(200,
                "<link rel='next' href='?page=2'><a href='/c/Example/about'>About</a>");
        downloader.latestUrl = "https://rumble.com/c/Example/videos";
        final var doc = RumbleParsingHelper.fetchParseValidate(downloader,
                "https://rumble.com/c/Example");
        assertEquals("https://rumble.com/c/Example/videos?page=2",
                new RumbleCommonCodeTrendingAndSearching(new RumbleSearchTrendingItemsExtractorImpl())
                        .getNewPageIfThereAreMoreThanOnePageResults(1, doc).getUrl());
        assertEquals("https://rumble.com/c/Example/about", doc.selectFirst("a").absUrl("href"));
    }

    @Test
    void otherHttpErrorsCannotMasqueradeAsEmptyResults() {
        for (final int status : new int[]{403, 404, 429, 500, 503}) {
            final FixtureDownloader downloader = new FixtureDownloader(status,
                    "<title>Unavailable</title>");
            assertThrows(ContentNotAvailableException.class,
                    () -> RumbleParsingHelper.fetchResponse(downloader, "https://rumble.com/"));
        }
    }

    private static org.schabi.newpipe.extractor.kiosk.KioskExtractor<?> shorts() throws Exception {
        return Rumble.getKioskList().getExtractorById(RumbleTrendingShortsExtractor.KIOSK_SHORTS, null);
    }

    private static final class FixtureDownloader extends Downloader {
        private final int code;
        private String body;
        private String latestUrl;
        private final List<String> urls = new ArrayList<>();

        private FixtureDownloader(final int code, final String body) {
            this.code = code;
            this.body = body;
        }

        @Override
        public Response execute(@Nonnull final Request request) {
            urls.add(request.url());
            return new Response(code, "Fixture", Map.of(), body,
                    latestUrl == null ? request.url() : latestUrl);
        }
    }
}
