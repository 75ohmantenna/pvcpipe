package org.schabi.newpipe.extractor.services.odysee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Odysee;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFakeDownloader.params;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.CHANNEL_ID;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.items;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.resolved;
import static org.schabi.newpipe.extractor.services.odysee.OdyseeFixtures.withTags;

import com.grack.nanojson.JsonObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.odysee.search.filter.OdyseeFilters;
import org.schabi.newpipe.extractor.services.odysee.settings.OdyseeSettings;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.List;

@Tag("offline")
class OdyseeListExtractorsTest {

    private static final String CHANNEL_URI = "lbry://@Example#8";
    private static final String PLAYLIST_ID = "ffef782f27486f0ac138bde8777f72ebdd0548c2";

    private OdyseeFakeDownloader downloader;

    @BeforeEach
    void setUp() {
        downloader = new OdyseeFakeDownloader();
        NewPipe.init(downloader);
        OdyseeApi.clearCaches();
    }

    private static List<JsonObject> videos(final int count) {
        final List<JsonObject> videos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            videos.add(OdyseeFixtures.video("video-" + i, String.format("%040x", i + 1),
                    "Video " + i));
        }
        return videos;
    }

    @Test
    void channelShowsWhatItsClaimSays() throws Exception {
        downloader.onProxy("resolve", resolved(CHANNEL_URI, OdyseeFixtures.channel()));

        final ChannelInfo channel = ChannelInfo.getInfo(Odysee, "https://odysee.com/@Example:8");

        assertTrue(channel.getErrors().isEmpty(), channel.getErrors().toString());
        assertEquals("Example Channel", channel.getName());
        assertEquals("@Example:8", channel.getId());
        assertEquals("https://odysee.com/@Example:8", channel.getUrl());
        assertEquals("About Example Channel", channel.getDescription());
        assertEquals(-1, channel.getSubscriberCount());
        assertEquals(List.of("odysee"), channel.getTags());
        assertEquals("https://thumbnails.odycdn.com/optimize/s:160:160/quality:85/plain/"
                + "https://thumbs.example/avatar.webp", channel.getAvatars().get(0).getUrl());
        // a banner keeps its own shape, so only its width is known
        assertEquals(1280, channel.getBanners().get(0).getWidth());
        assertEquals(-1, channel.getBanners().get(0).getHeight());

        final List<ListLinkHandler> tabs = channel.getTabs();
        assertEquals(List.of(ChannelTabs.VIDEOS, ChannelTabs.LIVESTREAMS, ChannelTabs.PLAYLISTS),
                List.of(tabs.get(0).getContentFilters().get(0),
                        tabs.get(1).getContentFilters().get(0),
                        tabs.get(2).getContentFilters().get(0)));
        assertEquals("https://odysee.com/@Example:8", tabs.get(0).getUrl());
        assertEquals("https://odysee.com/@Example:8?view=playlists", tabs.get(2).getUrl());
    }

    @Test
    void channelOpenedByAnotherAddressGetsItsUsualOne() throws Exception {
        final String fullId = "@Example:" + CHANNEL_ID;
        downloader.onProxy("resolve",
                resolved("lbry://@Example#" + CHANNEL_ID, OdyseeFixtures.channel()));

        final ChannelInfo channel = ChannelInfo.getInfo(Odysee, "https://odysee.com/" + fullId);

        assertEquals("@Example:8", channel.getId());
        assertEquals("https://odysee.com/@Example:8", channel.getUrl());
        assertEquals("https://odysee.com/" + fullId, channel.getOriginalUrl());
        assertEquals("@Example:8", channel.getTabs().get(0).getId());
    }

    @Test
    void channelWhoseUsualAddressCannotBeReadKeepsTheOneItWasOpenedWith() throws Exception {
        final JsonObject channel = OdyseeFixtures.channel();
        channel.put("canonical_url", "lbry://@Exa[mple#8");
        downloader.onProxy("resolve", resolved(CHANNEL_URI, channel));

        final ChannelInfo info = ChannelInfo.getInfo(Odysee, "https://odysee.com/@Example:8");

        assertEquals("Example Channel", info.getName());
        assertEquals("@Example:8", info.getId());
        assertEquals("https://odysee.com/@Example:8", info.getUrl());
        assertEquals("https://odysee.com/@Example:8", info.getTabs().get(0).getUrl());
    }

    @Test
    void addressOfAVideoIsNotAChannel() {
        downloader.onProxy("resolve", resolved(CHANNEL_URI, OdyseeFixtures.video()));

        assertThrows(ContentNotSupportedException.class,
                () -> ChannelInfo.getInfo(Odysee, "https://odysee.com/@Example:8"));
    }

    @Test
    void videosTabListsUploadsNewestFirstAndPages() throws Exception {
        downloader.onProxy("resolve", resolved(CHANNEL_URI, OdyseeFixtures.channel()))
                .onProxy("claim_search", request ->
                        items(params(request).getInt("page") == 1 ? videos(20) : videos(3)));
        final ChannelInfo channel = ChannelInfo.getInfo(Odysee, "https://odysee.com/@Example:8");

        final ChannelTabInfo tab = ChannelTabInfo.getInfo(Odysee, channel.getTabs().get(0));

        assertEquals(20, tab.getRelatedItems().size());
        final StreamInfoItem first = (StreamInfoItem) tab.getRelatedItems().get(0);
        assertEquals("Video 0", first.getName());
        assertEquals("https://odysee.com/@Example:8/video-0:0", first.getUrl());
        assertEquals("Example Channel", first.getUploaderName());
        assertEquals("https://odysee.com/@Example:8", first.getUploaderUrl());
        assertEquals(413, first.getDuration());
        assertEquals(-1, first.getViewCount());
        assertEquals(StreamType.VIDEO_STREAM, first.getStreamType());
        assertFalse(first.isShortFormContent());
        // the tab reuses the claim the channel was opened with
        assertEquals(1, downloader.requestsTo("?m=resolve").size());

        final JsonObject search = params(downloader.requestsTo("?m=claim_search").get(0));
        assertEquals(List.of(CHANNEL_ID), search.getArray("channel_ids"));
        assertEquals(List.of("stream"), search.getArray("claim_type"));
        assertEquals(List.of("video", "audio"), search.getArray("stream_types"));
        assertTrue(search.getBoolean("has_source"));
        assertEquals(List.of("release_time"), search.getArray("order_by"));
        assertEquals(List.of("c:unlisted", "c:scheduled:hide"), search.getArray("not_tags"));
        assertEquals(20, search.getInt("page_size"));
        assertTrue(search.getBoolean("no_totals"));

        assertTrue(tab.hasNextPage());
        final InfoItemsPage<InfoItem> second =
                ChannelTabInfo.getMoreItems(Odysee, channel.getTabs().get(0), tab.getNextPage());
        assertEquals(3, second.getItems().size());
        assertFalse(second.hasNextPage());
        assertEquals(2, params(downloader.requestsTo("?m=claim_search").get(1)).getInt("page"));
    }

    @Test
    void playlistsTabAsksForTheChannelsCollections() throws Exception {
        downloader.onProxy("resolve", resolved(CHANNEL_URI, OdyseeFixtures.channel()))
                .onProxy("claim_search", items(OdyseeFixtures.playlist("a", "b", "c")));
        final ChannelInfo channel = ChannelInfo.getInfo(Odysee, "https://odysee.com/@Example:8");

        final ChannelTabInfo playlists = ChannelTabInfo.getInfo(Odysee, channel.getTabs().get(2));

        final PlaylistInfoItem playlist = (PlaylistInfoItem) playlists.getRelatedItems().get(0);
        assertEquals("A playlist", playlist.getName());
        assertEquals("https://odysee.com/$/playlist/" + PLAYLIST_ID, playlist.getUrl());
        assertEquals(3, playlist.getStreamCount());
        assertEquals("Example Channel", playlist.getUploaderName());
        final JsonObject search = params(downloader.requestsTo("?m=claim_search").get(0));
        assertEquals(List.of("collection"), search.getArray("claim_type"));
        assertFalse(search.has("has_source"));
        assertFalse(search.has("stream_types"));
    }

    @Test
    void livestreamsTabListsOnlyTheBroadcastThatIsOnAir() throws Exception {
        final JsonObject onAir = claimWithId(OdyseeFixtures.livestream(), "live-now");
        downloader.onProxy("resolve", resolved(CHANNEL_URI, OdyseeFixtures.channel()))
                .on(OdyseeApi.LIVESTREAM_URL + "/is_live?channel_claim_id=" + CHANNEL_ID,
                        "{\"success\":true,\"error\":null,\"data\":{\"Live\":true,"
                                + "\"ViewerCount\":58,\"VideoURL\":\"https://x/master.m3u8\","
                                + "\"ActiveClaim\":{\"ClaimID\":\"live-now\"}}}")
                .onProxy("claim_search", items(onAir));
        final ChannelInfo channel = ChannelInfo.getInfo(Odysee, "https://odysee.com/@Example:8");

        final ChannelTabInfo live = ChannelTabInfo.getInfo(Odysee, channel.getTabs().get(1));

        assertEquals(1, live.getRelatedItems().size());
        final StreamInfoItem item = (StreamInfoItem) live.getRelatedItems().get(0);
        assertEquals(StreamType.LIVE_STREAM, item.getStreamType());
        assertEquals(58, item.getViewCount());
        assertEquals(-1, item.getDuration());
        assertFalse(live.hasNextPage());
        assertEquals(List.of("live-now"),
                params(downloader.requestsTo("?m=claim_search").get(0)).getArray("claim_ids"));
    }

    @Test
    void livestreamsTabOfAChannelThatIsOffAirIsEmpty() throws Exception {
        downloader.onProxy("resolve", resolved(CHANNEL_URI, OdyseeFixtures.channel()))
                .on(OdyseeApi.LIVESTREAM_URL, "{\"success\":true,\"error\":null,\"data\":"
                        + "{\"Live\":false,\"ViewerCount\":0,\"VideoURL\":\"\","
                        + "\"ActiveClaim\":{\"ClaimID\":\"\"}}}");
        final ChannelInfo channel = ChannelInfo.getInfo(Odysee, "https://odysee.com/@Example:8");

        final ChannelTabInfo live = ChannelTabInfo.getInfo(Odysee, channel.getTabs().get(1));

        assertTrue(live.getRelatedItems().isEmpty());
        assertTrue(live.getErrors().isEmpty(), live.getErrors().toString());
        assertTrue(downloader.requestsTo("?m=claim_search").isEmpty());
    }

    @Test
    void livestreamPagesAreLeftOutOfSearchResults() throws Exception {
        // most of them belong to a broadcast that is over and cannot be played
        downloader.on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"l1\",\"name\":\"a\"},"
                        + "{\"claimId\":\"v1\",\"name\":\"b\"}]")
                .onProxy("claim_search", items(
                        claimWithId(OdyseeFixtures.livestream(), "l1"),
                        OdyseeFixtures.video("b", "v1", "A video")));

        final SearchInfo search = SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("x"));

        assertEquals(1, search.getRelatedItems().size());
        assertEquals("A video", search.getRelatedItems().get(0).getName());
    }

    @Test
    void tabOpenedWithoutItsChannelLooksTheChannelUp() throws Exception {
        downloader.onProxy("resolve", resolved(CHANNEL_URI, OdyseeFixtures.channel()))
                .onProxy("claim_search", items(videos(2)));

        final ChannelTabInfo tab = ChannelTabInfo.getInfo(Odysee,
                Odysee.getChannelTabLHFactory().fromQuery("@Example:8",
                        List.of(ChannelTabs.VIDEOS), List.of()));

        assertEquals(2, tab.getRelatedItems().size());
        assertEquals(1, downloader.requestsTo("?m=resolve").size());
    }

    @Test
    void playlistLooksItsVideosUpInOrderAFullPageAtATime() throws Exception {
        final String[] ids = new String[60];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = String.format("%040x", i + 1);
        }
        downloader.onProxy("claim_search", request -> {
            final List<Object> asked = params(request).getArray("claim_ids");
            if (asked.equals(List.of(PLAYLIST_ID))) {
                return items(OdyseeFixtures.playlist(ids));
            }
            // answer in reverse, the way the API returns claims in no particular order
            final List<JsonObject> found = new ArrayList<>();
            for (int i = asked.size() - 1; i >= 0; i--) {
                final String id = (String) asked.get(i);
                found.add(OdyseeFixtures.video("video-" + id.substring(38), id, "Video " + id));
            }
            return items(found);
        });

        final PlaylistInfo playlist = PlaylistInfo.getInfo(Odysee,
                "https://odysee.com/$/playlist/" + PLAYLIST_ID);

        assertTrue(playlist.getErrors().isEmpty(), playlist.getErrors().toString());
        assertEquals("A playlist", playlist.getName());
        assertEquals("Things to watch", playlist.getDescription().getContent());
        assertEquals("Example Channel", playlist.getUploaderName());
        assertEquals("https://odysee.com/@Example:8", playlist.getUploaderUrl());
        assertEquals(60, playlist.getStreamCount());
        assertEquals(50, playlist.getRelatedItems().size());
        assertEquals("Video " + ids[0], playlist.getRelatedItems().get(0).getName());
        assertEquals("Video " + ids[49], playlist.getRelatedItems().get(49).getName());

        final InfoItemsPage<StreamInfoItem> rest = PlaylistInfo.getMoreItems(Odysee,
                "https://odysee.com/$/playlist/" + PLAYLIST_ID, playlist.getNextPage());
        assertEquals(10, rest.getItems().size());
        assertEquals("Video " + ids[50], rest.getItems().get(0).getName());
        assertFalse(rest.hasNextPage());
    }

    @Test
    void playlistThatListsAVideoTwiceShowsItTwice() throws Exception {
        final String first = String.format("%040x", 1);
        final String second = String.format("%040x", 2);
        downloader.onProxy("claim_search", request ->
                params(request).getArray("claim_ids").equals(List.of(PLAYLIST_ID))
                        ? items(OdyseeFixtures.playlist(first, second, first))
                        : items(OdyseeFixtures.video("one", first, "One"),
                        OdyseeFixtures.video("two", second, "Two")));

        final PlaylistInfo playlist = PlaylistInfo.getInfo(Odysee,
                "https://odysee.com/$/playlist/" + PLAYLIST_ID);

        assertEquals(3, playlist.getStreamCount());
        assertEquals(List.of("One", "Two", "One"), List.of(
                playlist.getRelatedItems().get(0).getName(),
                playlist.getRelatedItems().get(1).getName(),
                playlist.getRelatedItems().get(2).getName()));
    }

    @Test
    void searchHitThatComesTwiceIsListedOnce() throws Exception {
        downloader.on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"v1\",\"name\":\"a\"},"
                        + "{\"claimId\":\"v1\",\"name\":\"a\"}]")
                .onProxy("claim_search", items(OdyseeFixtures.video("a", "v1", "A")));

        final SearchInfo search = SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("x"));

        assertEquals(1, search.getRelatedItems().size());
        assertEquals(List.of("v1"),
                params(downloader.requestsTo("?m=claim_search").get(0)).getArray("claim_ids"));
    }

    @Test
    void homepageAnswerWithoutCategoriesIsAskedForAgain() throws Exception {
        final int[] asked = {0};
        downloader.on(OdyseeApi.HOMEPAGE_URL, request -> OdyseeFakeDownloader.ok(request,
                        ++asked[0] == 1 ? "{\"status\":\"error\"}"
                                : "{\"status\":\"success\",\"data\":{\"en\":{\"categories\":"
                                + "{\"PRIMARY_CONTENT\":{\"channelIds\":[\"c1\"]}}}}}"))
                .onProxy("claim_search", items(videos(2)));

        final KioskInfo failed = KioskInfo.getInfo(Odysee, "https://odysee.com/$/featured");
        assertTrue(failed.getRelatedItems().isEmpty());
        assertEquals(1, failed.getErrors().size());

        final KioskInfo recovered = KioskInfo.getInfo(Odysee, "https://odysee.com/$/featured");
        assertEquals(2, recovered.getRelatedItems().size());
        assertTrue(recovered.getErrors().isEmpty(), recovered.getErrors().toString());
        assertEquals(2, asked[0]);
    }

    @Test
    void claimThatNamesAChannelItWasNotSignedByIsAnonymous() throws Exception {
        final JsonObject forged = OdyseeFixtures.video("forged", "v1", "Forged");
        forged.put("is_channel_signature_valid", false);
        final JsonObject signed = OdyseeFixtures.video("signed", "v2", "Signed");
        signed.put("is_channel_signature_valid", true);
        downloader.on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"v1\",\"name\":\"a\"},"
                        + "{\"claimId\":\"v2\",\"name\":\"b\"}]")
                .onProxy("claim_search", items(forged, signed));

        final SearchInfo search = SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("x"));

        final StreamInfoItem first = (StreamInfoItem) search.getRelatedItems().get(0);
        assertEquals("Anonymous", first.getUploaderName());
        assertEquals("", first.getUploaderUrl());
        assertTrue(first.getUploaderAvatars().isEmpty());
        final StreamInfoItem second = (StreamInfoItem) search.getRelatedItems().get(1);
        assertEquals("Example Channel", second.getUploaderName());
        assertEquals("https://odysee.com/@Example:8", second.getUploaderUrl());
    }

    @Test
    void addressOfAMissingPlaylistIsNotAvailable() {
        downloader.onProxy("claim_search", items());

        assertThrows(ContentNotAvailableException.class, () -> PlaylistInfo.getInfo(Odysee,
                "https://odysee.com/$/playlist/" + PLAYLIST_ID));
    }

    @Test
    void searchKeepsTheOrderOfTheHitsAndLeavesOutWhatCannotBePlayed() throws Exception {
        downloader.on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"c1\",\"name\":\"@Example\"},"
                        + "{\"claimId\":\"v1\",\"name\":\"first\"},"
                        + "{\"claimId\":\"d1\",\"name\":\"a-document\"},"
                        + "{\"claimId\":\"gone\",\"name\":\"deleted\"},"
                        + "{\"claimId\":\"v2\",\"name\":\"second\"}]")
                .onProxy("claim_search", items(
                        OdyseeFixtures.video("second", "v2", "Second"),
                        withTags(OdyseeFixtures.video("first", "v1", "First"), "c:members-only"),
                        claimWithId(OdyseeFixtures.document(), "d1"),
                        OdyseeFixtures.channel("@Example", "c1", "Example Channel")));

        final SearchInfo search = SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("free software"));

        assertTrue(search.getErrors().isEmpty(), search.getErrors().toString());
        assertEquals("https://odysee.com/$/search?q=free+software&claimType=file,channel",
                search.getUrl());
        assertEquals(3, search.getRelatedItems().size());
        final ChannelInfoItem channel = (ChannelInfoItem) search.getRelatedItems().get(0);
        assertEquals("Example Channel", channel.getName());
        assertEquals("https://odysee.com/@Example:c", channel.getUrl());
        assertEquals(137, channel.getStreamCount());
        assertEquals(-1, channel.getSubscriberCount());
        final StreamInfoItem first = (StreamInfoItem) search.getRelatedItems().get(1);
        assertEquals("First", first.getName());
        assertEquals(ContentAvailability.MEMBERSHIP, first.getContentAvailability());
        assertEquals("Second", search.getRelatedItems().get(2).getName());
        // fewer hits than a full page: there is nothing more to load
        assertFalse(search.hasNextPage());

        final String request = downloader.requestsTo(OdyseeApi.SEARCH_URL).get(0).url();
        assertEquals(OdyseeApi.SEARCH_URL + "?s=free+software&size=20&from=0&nsfw=false"
                + "&claimType=file,channel", request);
        assertEquals(List.of("c1", "v1", "d1", "gone", "v2"),
                params(downloader.requestsTo("?m=claim_search").get(0)).getArray("claim_ids"));
    }

    @Test
    void paidVideosAreLeftOutOfListsWhenTheUserHidesThem() throws Exception {
        downloader.on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"v1\",\"name\":\"free\"},"
                        + "{\"claimId\":\"v2\",\"name\":\"bought\"},"
                        + "{\"claimId\":\"v3\",\"name\":\"members\"}]")
                .onProxy("claim_search", items(
                        OdyseeFixtures.video("free", "v1", "Free"),
                        withTags(OdyseeFixtures.video("bought", "v2", "Bought"),
                                "c:purchase", "c:purchase:5.00"),
                        withTags(OdyseeFixtures.video("members", "v3", "Members"),
                                "c:members-only")));

        assertEquals(3, SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("x")).getRelatedItems().size());

        OdyseeSettings.getInstance().setting(true, OdyseeSettings.HIDE_PAID_STREAMS);
        try {
            final List<InfoItem> shown = SearchInfo.getInfo(Odysee,
                    Odysee.getSearchQHFactory().fromQuery("x")).getRelatedItems();
            assertEquals(1, shown.size());
            assertEquals("Free", shown.get(0).getName());
        } finally {
            OdyseeSettings.getInstance().setting(false, OdyseeSettings.HIDE_PAID_STREAMS);
        }
        assertSame(OdyseeSettings.getInstance(), Odysee.getServiceSettings());
    }

    @Test
    void searchAppliesItsFiltersAndPagesByOffset() throws Exception {
        final StringBuilder hits = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            hits.append(i == 0 ? "" : ",").append("{\"claimId\":\"")
                    .append(String.format("%040x", i + 1)).append("\",\"name\":\"n\"}");
        }
        downloader.on(OdyseeApi.SEARCH_URL, hits + "]")
                .onProxy("claim_search", items(videos(20)));
        final List<FilterItem> videosOnly = List.of(filter(OdyseeFilters.ID_CF_MAIN_VIDEOS));
        final List<FilterItem> newestThisWeekLong = List.of(
                filter(OdyseeFilters.ID_SF_SORT_BY_NEWEST),
                filter(OdyseeFilters.ID_SF_DATE_THIS_WEEK),
                filter(OdyseeFilters.ID_SF_DURATION_LONG));

        final SearchInfo search = SearchInfo.getInfo(Odysee, Odysee.getSearchQHFactory()
                .fromQuery("linux", videosOnly, newestThisWeekLong));

        assertEquals(OdyseeApi.SEARCH_URL + "?s=linux&size=20&from=0&nsfw=false"
                        + "&claimType=file&mediaType=video&sort_by=release_time"
                        + "&time_filter=thisweek&min_duration=1200",
                downloader.requestsTo(OdyseeApi.SEARCH_URL).get(0).url());
        assertEquals(20, search.getRelatedItems().size());
        assertTrue(search.hasNextPage());

        SearchInfo.getMoreItems(Odysee, Odysee.getSearchQHFactory()
                .fromQuery("linux", videosOnly, newestThisWeekLong), search.getNextPage());
        assertTrue(downloader.requestsTo(OdyseeApi.SEARCH_URL).get(1).url()
                .contains("&size=20&from=20&"));
    }

    @Test
    void channelSearchIgnoresSortFiltersBecauseOdyseeWouldReturnNothing() throws Exception {
        downloader.on(OdyseeApi.SEARCH_URL, "[]");

        SearchInfo.getInfo(Odysee, Odysee.getSearchQHFactory().fromQuery("linux",
                List.of(filter(OdyseeFilters.ID_CF_MAIN_CHANNELS)),
                List.of(filter(OdyseeFilters.ID_SF_SORT_BY_NEWEST))));

        assertEquals(OdyseeApi.SEARCH_URL + "?s=linux&size=20&from=0&nsfw=false"
                + "&claimType=channel", downloader.requestsTo(OdyseeApi.SEARCH_URL).get(0).url());
        // no hits, so no claims to look up
        assertTrue(downloader.requestsTo("?m=claim_search").isEmpty());
    }

    @Test
    void suggestionsAreTheTitlesOfTheFirstResults() throws Exception {
        downloader.on(OdyseeApi.SEARCH_URL, "[{\"claimId\":\"a\",\"name\":\"a\",\"title\":\"Linux news\"},"
                + "{\"claimId\":\"b\",\"name\":\"b\",\"title\":\" Linux news \"},"
                + "{\"claimId\":\"c\",\"name\":\"c\",\"title\":null},"
                + "{\"claimId\":\"d\",\"name\":\"d\",\"title\":\"Linux distros\"}]");

        assertEquals(List.of("Linux news", "Linux distros"),
                Odysee.getSuggestionExtractor().suggestionList("linu"));
        assertEquals(OdyseeApi.SEARCH_URL + "?s=linu&size=10&from=0&nsfw=false&resolve=true",
                downloader.requestsTo(OdyseeApi.SEARCH_URL).get(0).url());

        // one letter is not worth a request
        assertEquals(List.of(), Odysee.getSuggestionExtractor().suggestionList("l"));
        assertEquals(1, downloader.requests.size());
    }

    @Test
    void featuredKioskListsRecentUploadsOfTheHomepageChannels() throws Exception {
        downloader.on(OdyseeApi.HOMEPAGE_URL, "{\"status\":\"success\",\"data\":{\"en\":"
                        + "{\"categories\":{\"PRIMARY_CONTENT\":{\"name\":\"featured\","
                        + "\"channelIds\":[\"c1\",\"c2\"],\"channelLimit\":\"1\","
                        + "\"daysOfContent\":60}}}}}")
                .onProxy("claim_search", items(videos(20)));

        final KioskInfo kiosk = KioskInfo.getInfo(Odysee, "https://odysee.com/$/featured");

        assertEquals("Featured", kiosk.getName());
        assertEquals(20, kiosk.getRelatedItems().size());
        assertTrue(kiosk.hasNextPage());
        final JsonObject search = params(downloader.requestsTo("?m=claim_search").get(0));
        assertEquals(List.of("c1", "c2"), search.getArray("channel_ids"));
        assertEquals(1, search.getInt("limit_claims_per_channel"));
        assertEquals(List.of("trending_group", "trending_mixed"), search.getArray("order_by"));
        assertEquals("<=0", search.getString("fee_amount"));
        assertTrue(search.getArray("not_tags").containsAll(
                List.of("mature", "c:members-only", "c:purchase", "c:rental", "c:unlisted")));
        final long cutoff = Long.parseLong(search.getString("release_time").substring(1));
        final long sixtyDaysAgo = System.currentTimeMillis() / 1000 - 60 * 24 * 60 * 60;
        assertTrue(search.getString("release_time").startsWith(">"));
        assertTrue(Math.abs(cutoff - sixtyDaysAgo) < 60, search.getString("release_time"));

        // the homepage is read once for both pages
        KioskInfo.getMoreItems(Odysee, "https://odysee.com/$/featured", kiosk.getNextPage());
        assertEquals(1, downloader.requestsTo(OdyseeApi.HOMEPAGE_URL).size());
        assertEquals(2, params(downloader.requestsTo("?m=claim_search").get(1)).getInt("page"));
    }

    @Test
    void trendingKioskSearchesTheWholeSite() throws Exception {
        downloader.onProxy("claim_search", items(videos(5)));

        final KioskInfo kiosk = KioskInfo.getInfo(Odysee, "https://odysee.com/$/wildwest");

        assertEquals("Trending", kiosk.getName());
        assertEquals(5, kiosk.getRelatedItems().size());
        assertFalse(kiosk.hasNextPage());
        final JsonObject search = params(downloader.requestsTo("?m=claim_search").get(0));
        assertFalse(search.has("channel_ids"));
        assertFalse(search.has("release_time"));
        assertEquals(List.of("trending_group", "trending_mixed"), search.getArray("order_by"));
    }

    @Test
    void liveKioskListsWhoIsOnAirMostWatchedFirst() throws Exception {
        downloader.on(OdyseeApi.LIVESTREAM_URL + "/all", "{\"success\":true,\"error\":null,\"data\":["
                        + "{\"Live\":true,\"ViewerCount\":3,\"ActiveClaim\":{\"ClaimID\":\"few\"}},"
                        + "{\"Live\":false,\"ViewerCount\":900,\"ActiveClaim\":{\"ClaimID\":\"off\"}},"
                        + "{\"Live\":true,\"ViewerCount\":58,\"ActiveClaim\":{\"ClaimID\":\"many\"}},"
                        + "{\"Live\":true,\"ViewerCount\":7,\"ActiveClaim\":{\"ClaimID\":\"\"}}]}")
                .onProxy("claim_search", items(
                        claimWithId(OdyseeFixtures.livestream(), "few"),
                        claimWithId(OdyseeFixtures.livestream(), "many")));

        final KioskInfo kiosk = KioskInfo.getInfo(Odysee, "https://odysee.com/$/livenow");

        assertEquals(List.of("many", "few"),
                params(downloader.requestsTo("?m=claim_search").get(0)).getArray("claim_ids"));
        assertEquals(2, kiosk.getRelatedItems().size());
        assertEquals(StreamType.LIVE_STREAM, kiosk.getRelatedItems().get(0).getStreamType());
        assertFalse(kiosk.hasNextPage());
        assertEquals("POST", downloader.requestsTo(OdyseeApi.LIVESTREAM_URL).get(0).httpMethod());
    }

    @Test
    void kiosksAreFeaturedTrendingAndLive() throws Exception {
        assertEquals("Featured", Odysee.getKioskList().getDefaultKioskId());
        assertEquals(java.util.Set.of("Featured", "Trending", "live"),
                Odysee.getKioskList().getAvailableKiosks());
        assertNull(Odysee.getSubscriptionExtractor());
    }

    @Test
    void uprightShortVideoIsAShort() {
        final JsonObject claim = OdyseeFixtures.video();
        final JsonObject video = claim.getObject("value").getObject("video");
        video.put("width", 1080);
        video.put("height", 1920);
        video.put("duration", 113);
        assertTrue(OdyseeClaim.isShort(claim));
        video.put("duration", 181);
        assertFalse(OdyseeClaim.isShort(claim));
        video.put("duration", 113);
        video.put("width", 1920);
        assertFalse(OdyseeClaim.isShort(claim));
    }

    @Test
    void releaseTimeThatIsNoDateGivesWayToTheTimeOfTheClaim() {
        for (final String releaseTime : new String[]{"99999999999999999", "-5", "0", "soon"}) {
            final JsonObject claim = OdyseeFixtures.video();
            claim.getObject("value").put("release_time", releaseTime);
            assertEquals(java.time.Instant.ofEpochSecond(1780000100),
                    OdyseeClaim.uploadDate(claim).getInstant(), releaseTime);
        }
        final JsonObject undated = OdyseeFixtures.video();
        undated.getObject("value").remove("release_time");
        undated.remove("timestamp");
        assertNull(OdyseeClaim.uploadDate(undated));
        assertNull(OdyseeClaim.textualUploadDate(undated));
    }

    private static FilterItem filter(final int id) {
        return Odysee.getSearchQHFactory().getFilterItem(id);
    }

    private static JsonObject claimWithId(final JsonObject claim, final String claimId) {
        claim.put("claim_id", claimId);
        return claim;
    }
}
