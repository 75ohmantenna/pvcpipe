package org.schabi.newpipe.extractor.services.odysee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Odysee;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.downloader.DownloaderTestImpl;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.odysee.search.filter.OdyseeFilters;
import org.schabi.newpipe.extractor.services.odysee.settings.OdyseeSettings;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

/**
 * Checks against the live website. These are not part of the offline suite: they depend on the
 * network and on content that its publishers can change or remove.
 */
class OdyseeLiveTest {

    private static final String CHANNEL = "https://odysee.com/@Odysee:8";
    private static final String TRANSCODED_VIDEO =
            "https://odysee.com/@Odysee:8/channel-roundup-may-2026:e";
    private static final String UNTRANSCODED_VIDEO =
            "https://odysee.com/@gardeningincanada:b/plants-i-will-never-grow-again.-the:e";
    private static final String AUDIO = "https://odysee.com/@LBRYFoundation:0/Episode-1:e";
    private static final String PLAYLIST =
            "https://odysee.com/$/playlist/ffef782f27486f0ac138bde8777f72ebdd0548c2";

    @BeforeAll
    static void setUp() {
        NewPipe.init(DownloaderTestImpl.getInstance());
        OdyseeApi.clearCaches();
        // Registers one anonymous visitor with Odysee for this run.
        OdyseeAuth.setTokenStore(null);
        OdyseeSettings.getInstance().setting(true, OdyseeSettings.SHOW_COUNTS);
    }

    @AfterAll
    static void tearDown() {
        OdyseeSettings.getInstance().setting(false, OdyseeSettings.SHOW_COUNTS);
        OdyseeAuth.setTokenStore(null);
    }

    @Test
    void transcodedVideoHasHlsRenditionsAndTheUploadedFile() throws Exception {
        final StreamInfo info = StreamInfo.getInfo(Odysee, TRANSCODED_VIDEO);
        System.out.println(describe(info));
        assertEquals(StreamType.VIDEO_STREAM, info.getStreamType());
        assertFalse(info.getName().isEmpty());
        assertEquals("https://odysee.com/@Odysee:8", info.getUploaderUrl());
        assertTrue(info.getDuration() > 0);
        assertNotNull(info.getUploadDate());
        assertFalse(info.getThumbnails().isEmpty());
        assertTrue(info.getLikeCount() >= 0);
        assertTrue(info.getViewCount() > 1000, "views " + info.getViewCount());
        assertTrue(info.getUploaderSubscriberCount() > 1000,
                "followers " + info.getUploaderSubscriberCount());
        assertTrue(info.getVideoStreams().stream()
                .anyMatch(stream -> stream.getDeliveryMethod() == DeliveryMethod.HLS));
        assertEquals(1, info.getVideoStreams().stream()
                .filter(stream -> stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP)
                .count());
        assertFalse(info.getRelatedItems().isEmpty());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void untranscodedVideoHasOnlyTheUploadedFile() throws Exception {
        final StreamInfo info = StreamInfo.getInfo(Odysee, UNTRANSCODED_VIDEO);
        System.out.println(describe(info));
        assertEquals(1, info.getVideoStreams().size());
        assertEquals(DeliveryMethod.PROGRESSIVE_HTTP,
                info.getVideoStreams().get(0).getDeliveryMethod());
        assertEquals(554, info.getDuration());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void audioIsAnAudioStream() throws Exception {
        final StreamInfo info = StreamInfo.getInfo(Odysee, AUDIO);
        System.out.println(describe(info));
        assertEquals(StreamType.AUDIO_STREAM, info.getStreamType());
        assertEquals(1, info.getAudioStreams().size());
        assertTrue(info.getVideoStreams().isEmpty());
    }

    @Test
    void missingVideoIsReportedAsNotAvailable() {
        assertThrows(ContentNotAvailableException.class, () -> StreamInfo.getInfo(Odysee,
                "https://odysee.com/@Odysee:8/this-video-does-not-exist-pvcpipe:0"));
    }

    @Test
    void channelHasVideosLivestreamsAndPlaylists() throws Exception {
        final ChannelInfo channel = ChannelInfo.getInfo(Odysee, CHANNEL);
        System.out.println(channel.getName() + " | avatars " + channel.getAvatars()
                + " | banners " + channel.getBanners() + " | tabs " + channel.getTabs().size());
        assertEquals("Odysee", channel.getName());
        assertTrue(channel.getSubscriberCount() > 1000,
                "followers " + channel.getSubscriberCount());
        assertFalse(channel.getAvatars().isEmpty());
        assertEquals(3, channel.getTabs().size());

        for (final ListLinkHandler tab : channel.getTabs()) {
            final ChannelTabInfo tabInfo = ChannelTabInfo.getInfo(Odysee, tab);
            System.out.println("tab " + tab.getContentFilters().get(0).getNameId() + ": "
                    + tabInfo.getRelatedItems().size() + " items, next page "
                    + tabInfo.hasNextPage() + ", first " + (tabInfo.getRelatedItems().isEmpty()
                    ? "-" : describe(tabInfo.getRelatedItems().get(0))));
            assertTrue(tabInfo.getErrors().isEmpty(), tabInfo.getErrors().toString());
            if (tab.getContentFilters().get(0).equals(ChannelTabs.VIDEOS)) {
                assertFalse(tabInfo.getRelatedItems().isEmpty());
                assertTrue(((StreamInfoItem) tabInfo.getRelatedItems().get(0)).getViewCount() > 0);
                assertTrue(tabInfo.hasNextPage());
                final ListExtractor.InfoItemsPage<InfoItem> second =
                        ChannelTabInfo.getMoreItems(Odysee, tab, tabInfo.getNextPage());
                assertFalse(second.getItems().isEmpty());
                assertFalse(second.getItems().get(0).getUrl()
                        .equals(tabInfo.getRelatedItems().get(0).getUrl()));
            }
        }
    }

    @Test
    void playlistListsItsVideos() throws Exception {
        final PlaylistInfo playlist = PlaylistInfo.getInfo(Odysee, PLAYLIST);
        System.out.println(playlist.getName() + " by " + playlist.getUploaderName() + ": "
                + playlist.getStreamCount() + " streams, " + playlist.getRelatedItems().size()
                + " listed");
        assertFalse(playlist.getRelatedItems().isEmpty());
        assertTrue(playlist.getErrors().isEmpty(), playlist.getErrors().toString());
    }

    @Test
    void searchFindsVideosAndChannels() throws Exception {
        final SearchInfo all = SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("linux"));
        System.out.println("search all: " + all.getRelatedItems().size() + " items, first "
                + describe(all.getRelatedItems().get(0)));
        assertFalse(all.getRelatedItems().isEmpty());
        assertTrue(all.hasNextPage());

        final SearchInfo channels = SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("linux",
                        List.of(Odysee.getSearchQHFactory()
                                .getFilterItem(OdyseeFilters.ID_CF_MAIN_CHANNELS)), List.of()));
        assertFalse(channels.getRelatedItems().isEmpty());
        assertTrue(channels.getRelatedItems().stream()
                .allMatch(item -> item.getInfoType() == InfoItem.InfoType.CHANNEL));

        final SearchInfo newest = SearchInfo.getInfo(Odysee,
                Odysee.getSearchQHFactory().fromQuery("linux",
                        List.of(Odysee.getSearchQHFactory()
                                .getFilterItem(OdyseeFilters.ID_CF_MAIN_VIDEOS)),
                        List.of(Odysee.getSearchQHFactory()
                                .getFilterItem(OdyseeFilters.ID_SF_SORT_BY_NEWEST),
                                Odysee.getSearchQHFactory()
                                        .getFilterItem(OdyseeFilters.ID_SF_DURATION_SHORT))));
        System.out.println("search newest short: " + describe(newest.getRelatedItems().get(0)));
        assertTrue(newest.getRelatedItems().stream().allMatch(item ->
                item instanceof StreamInfoItem && ((StreamInfoItem) item).getDuration() <= 240));
    }

    @Test
    void suggestionsAreTitles() throws Exception {
        final List<String> suggestions = Odysee.getSuggestionExtractor().suggestionList("linu");
        System.out.println("suggestions: " + suggestions);
        assertFalse(suggestions.isEmpty());
    }

    @Test
    void commentsHaveLikesAuthorsAndReplies() throws Exception {
        final CommentsInfo comments = CommentsInfo.getInfo(Odysee, TRANSCODED_VIDEO);
        final CommentsInfoItem first = comments.getRelatedItems().get(0);
        System.out.println("comments: " + comments.getCommentsCount() + " total, first by "
                + first.getUploaderName() + " " + first.getUploaderUrl() + " likes "
                + first.getLikeCount() + " replies " + first.getReplyCount() + " pinned "
                + first.isPinned() + " avatars " + first.getUploaderAvatars().size());
        assertTrue(comments.getCommentsCount() > 0);
        assertTrue(comments.hasNextPage());
        assertTrue(first.getLikeCount() >= 0);
        assertFalse(first.getUploaderAvatars().isEmpty());
        assertNotNull(first.getReplies());
        final ListExtractor.InfoItemsPage<CommentsInfoItem> replies =
                CommentsInfo.getMoreItems(Odysee, comments, first.getReplies());
        assertFalse(replies.getItems().isEmpty());
        final ListExtractor.InfoItemsPage<CommentsInfoItem> second =
                CommentsInfo.getMoreItems(Odysee, comments, comments.getNextPage());
        assertFalse(second.getItems().isEmpty());
    }

    @Test
    void kiosksListStreams() throws Exception {
        for (final String kioskId : Odysee.getKioskList().getAvailableKiosks()) {
            final KioskInfo kiosk = KioskInfo.getInfo(Odysee,
                    Odysee.getKioskList().getListLinkHandlerFactoryByType(kioskId)
                            .fromId(kioskId).getUrl());
            System.out.println("kiosk " + kioskId + ": " + kiosk.getRelatedItems().size()
                    + " items, next page " + kiosk.hasNextPage() + ", first "
                    + (kiosk.getRelatedItems().isEmpty() ? "-"
                    : describe(kiosk.getRelatedItems().get(0))));
            assertFalse(kiosk.getRelatedItems().isEmpty(), kioskId);
            assertTrue(kiosk.getErrors().isEmpty(), kiosk.getErrors().toString());
        }
    }

    @Test
    void liveKioskItemsPlayAsLivestreams() throws Exception {
        final KioskInfo live = KioskInfo.getInfo(Odysee, "https://odysee.com/$/livenow");
        final StreamInfoItem first = live.getRelatedItems().get(0);
        assertEquals(StreamType.LIVE_STREAM, first.getStreamType());
        final StreamInfo info = StreamInfo.getInfo(Odysee, first.getUrl());
        System.out.println(describe(info) + " hls " + info.getHlsUrl());
        assertEquals(StreamType.LIVE_STREAM, info.getStreamType());
        assertTrue(info.getViewCount() >= 0, "viewers " + info.getViewCount());
        assertTrue(first.getViewCount() >= 0, "viewers in the list " + first.getViewCount());
        assertTrue(info.getHlsUrl().contains(".m3u8"));
    }

    @Test
    void paidVideoIsReportedAsPaid() throws Exception {
        final com.grack.nanojson.JsonObject params = new com.grack.nanojson.JsonObject();
        params.put("claim_type", OdyseeApi.array(List.of("stream")));
        params.put("any_tags", OdyseeApi.array(List.of(OdyseeClaim.TAG_MEMBERS_ONLY)));
        params.put("has_source", true);
        params.put("page", 1);
        params.put("page_size", 1);
        params.put("order_by", OdyseeApi.array(List.of("release_time")));
        final String url = OdyseeClaim.webUrl(
                OdyseeClaim.list(OdyseeApi.claimSearch(params)).get(0));
        System.out.println("members only: " + url);
        assertThrows(PaidContentException.class, () -> StreamInfo.getInfo(Odysee, url));
    }

    private static String describe(final StreamInfo info) {
        final StringBuilder out = new StringBuilder(info.getName()).append(" | ")
                .append(info.getUrl()).append(" | ").append(info.getUploaderName()).append(" | ")
                .append(info.getDuration()).append("s | likes ").append(info.getLikeCount())
                .append(" | views ").append(info.getViewCount())
                .append(" | followers ").append(info.getUploaderSubscriberCount())
                .append(" | ").append(info.getStreamType()).append(" | related ")
                .append(info.getRelatedItems().size());
        for (final VideoStream stream : info.getVideoStreams()) {
            out.append("\n    ").append(stream.getDeliveryMethod()).append(' ')
                    .append(stream.getResolution()).append(' ').append(stream.getFormat())
                    .append(' ').append(stream.getContent());
        }
        info.getAudioStreams().forEach(stream -> out.append("\n    audio ")
                .append(stream.getFormat()).append(' ').append(stream.getAverageBitrate())
                .append("k ").append(stream.getContent()));
        return out.toString();
    }

    private static String describe(final InfoItem item) {
        return item.getInfoType() + " " + item.getName() + " | " + item.getUrl();
    }
}
