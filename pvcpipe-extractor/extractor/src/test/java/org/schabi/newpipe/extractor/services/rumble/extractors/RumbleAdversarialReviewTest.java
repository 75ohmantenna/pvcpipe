package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.pvc.PvcCloudFlareChallengeException;
import org.schabi.newpipe.extractor.services.DefaultSearchExtractorTest;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.services.rumble.search.filter.RumbleFilters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@Tag("offline")
class RumbleAdversarialReviewTest {
    @Test
    void inlinePlayersAcceptScriptAttributesAndEitherQuoteStyle() throws Exception {
        assertEquals("review123", RumbleParsingHelper.getEmbedVideoId(
                "https://rumble.com/vreview-inline.html",
                () -> "<script type='text/javascript'>\nRumble('play', {\n"
                        + "video: 'vreview123', div: 'player'});</script>"));
    }

    @Test
    void commentRoutingUsesTheVideoPathInsteadOfQueryText() throws Exception {
        final List<String> requests = new ArrayList<>();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                requests.add(request.url());
                return response(request, 200,
                        "<iframe src='https://rumble.com/embed/vreview456/'></iframe>");
            }
        });
        for (final String misleadingQuery : List.of("/embed/vother", "/shorts/vother")) {
            final String url = "https://rumble.com/vreview-watch.html?next=" + misleadingQuery;
            assertEquals("https://rumble.com/service.php?video=review456&name=comment.list",
                    Rumble.getCommentsExtractor(url).getUrl());
            assertTrue(requests.contains(url));
        }
    }

    @Test
    void unavailableAboutMetadataDoesNotPreventLoadingTheChannel() throws Exception {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                return request.url().endsWith("/about")
                        ? response(request, 403, "<title>Just a moment...</title>")
                        : response(request, 200, "<title>Example</title>"
                                + "<div data-slug='Example' data-type='channel'></div>"
                                + "<a href='/c/Example/about'>About</a>");
            }
        });
        final var extractor = Rumble.getChannelExtractor("https://rumble.com/c/Example");
        extractor.fetchPage();
        assertEquals("c/Example", extractor.getId());
        assertThrows(PvcCloudFlareChallengeException.class, extractor::getDescription);
        assertFalse(extractor.getTabs().isEmpty());
    }

    @Test
    void channelCardsWithoutOptionalCountsOrAvatarsRemainUsable() throws Exception {
        final String html = "<article><a href='/c/Example'><h3>Example</h3></a></article>";
        final var item = search(html).getItems().get(0);
        assertEquals("Example", item.getName());
        assertEquals("https://rumble.com/c/Example", item.getUrl());
        assertTrue(item.getThumbnails().isEmpty());
        assertEquals(-1, ((org.schabi.newpipe.extractor.channel.ChannelInfoItem) item)
                .getSubscriberCount());
    }

    @Test
    void unrelatedHeadingLinksDoNotHideTheActualChannelLink() throws Exception {
        final String html = "<article><a href='/help'><h3>Help</h3></a>"
                + "<a href='/c/Example'><h3><span>Example</span>"
                + "<svg><title>Verified</title></svg></h3></a>"
                + "<span class='channel-item--subscribers'>12 followers</span>"
                + "<i class='user-image--letter'></i></article>";
        final var item = search(html).getItems().get(0);
        assertEquals("Example", item.getName());
        assertEquals("https://rumble.com/c/Example", item.getUrl());
    }

    private static org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<
            org.schabi.newpipe.extractor.InfoItem> search(final String html) throws Exception {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                return response(request, 200, html);
            }
        });
        final var filter = DefaultSearchExtractorTest.getFilterItem(Rumble,
                RumbleFilters.ID_CF_MAIN_CHANNELS);
        final var extractor = Rumble.getSearchExtractor("example", List.of(filter), null);
        extractor.fetchPage();
        return extractor.getInitialPage();
    }

    private static Response response(final Request request, final int status, final String body) {
        return new Response(status, "Fixture", Map.of(), body, request.url());
    }
}
