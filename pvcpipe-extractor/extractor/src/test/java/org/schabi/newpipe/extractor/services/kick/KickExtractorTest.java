package org.schabi.newpipe.extractor.services.kick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Kick;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.CHANNEL_API;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.CHANNEL_URL;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.CURRENT_UUID;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.LEGACY_UUID;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.MASTER_URL;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.VIDEOS_API;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.VIDEO_URL;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Tag("offline")
class KickExtractorTest {
    private KickFixtures downloader;

    @BeforeEach
    void setUp() {
        downloader = new KickFixtures();
        NewPipe.init(downloader);
    }

    @Test
    void serviceLookupPreservesPersistentIdAndRoutesChannelLinks() throws Exception {
        assertSame(Kick, NewPipe.getService(8));
        assertSame(Kick, NewPipe.getServiceByUrl(VIDEO_URL));
        assertEquals("Kick", Kick.getServiceInfo().getName());
        assertEquals(StreamingService.LinkType.STREAM, Kick.getLinkTypeByUrl("https://kick.com/example"));
        assertEquals(StreamingService.LinkType.CHANNEL, Kick.getLinkTypeByUrl(CHANNEL_URL));
        assertEquals(StreamingService.LinkType.CHANNEL,
                Kick.getLinkTypeByUrl("https://kick.com/example/about"));
        assertEquals("example", Kick.getChannelLHFactory().getId("https://www.kick.com/EXAMPLE/"));
        assertTrue(Kick.getStreamLHFactory().acceptUrl("https://kick.com/video/" + LEGACY_UUID));
        assertTrue(Kick.getStreamLHFactory().acceptUrl(VIDEO_URL + "?t=25&ref=share"));
        assertEquals(List.of(ChannelTabs.VIDEOS),
                Kick.getChannelTabLHFactory().fromUrl(CHANNEL_URL).getContentFilters());
        assertEquals(List.of(ChannelTabs.LIVESTREAMS),
                Kick.getChannelTabLHFactory().fromUrl("https://kick.com/EXAMPLE/").getContentFilters());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://kick.com.evil/example", "https://evil/kick.com/example",
            "https://kick.com@evil/example", "https://evil@kick.com/example",
            "https://kick.com:443/example", "ftp://kick.com/example", "https://kick.com/api",
            "https://kick.com/categories", "https://kick.com/search?query=example",
            "https://kick.com/example/videos/not-a-uuid", "https://kick.com/example/clips/123",
            "https://kick.com/example/extra", "https://kick.com/%65xample",
            "https://kick.com/example%2Fvideos%2F" + CURRENT_UUID,
            "https://kick.com/", "https://kick.com",
            "https://kick.com/example?live=0", "https://kick.com/example?live=-1",
            "https://kick.com/example?live=9999999999999999999",
            "https://kick.com/example?live=123&live=456",
            "https://kick.com/example?live=tomorrow",
            "https://kick.com/example/../other", "https://kick.com//example"})
    void unrelatedOrAmbiguousLinksAreRejected(final String url) throws Exception {
        assertFalse(Kick.getStreamLHFactory().acceptUrl(url), url);
        assertFalse(Kick.getChannelLHFactory().acceptUrl(url), url);
    }

    @Test
    void livePlaybackUsesFreshSignedUrlAndHonorsHiddenViewers() throws Exception {
        final String fresh = "https://live.example/master.m3u8?token=fresh";
        downloader.channel(true).on(CHANNEL_API + "/playback-url", "{\"data\":\"" + fresh + "\"}")
                .on(fresh, KickFixtures.MASTER);
        final StreamInfo info = StreamInfo.getInfo(Kick, "https://kick.com/example?t=25");
        assertEquals(StreamType.LIVE_STREAM, info.getStreamType());
        assertEquals(fresh, info.getHlsUrl());
        assertEquals("Live title", info.getName());
        assertEquals(CHANNEL_URL, info.getUploaderUrl());
        assertEquals(0, info.getDuration());
        assertEquals(0, info.getStartPosition());
        assertEquals(-1, info.getViewCount());
        assertEquals(18, info.getAgeLimit());
        assertEquals(DeliveryMethod.HLS, info.getVideoStreams().get(0).getDeliveryMethod());
        assertFalse(info.getVideoStreams().get(0).isVideoOnly());
        assertEquals("https://live.example/720p60/playlist.m3u8?signature=variant",
                info.getVideoStreams().get(0).getContent());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void offlineChannelsAreUnavailableEvenWithAPlaybackUrl() {
        downloader.channel(false);
        assertThrows(ContentNotAvailableException.class,
                () -> StreamInfo.getInfo(Kick, "https://kick.com/example"));
        assertEquals(1, downloader.requests.size());
    }

    @Test
    void currentRecordingIsPlayableAndDownloadableWithoutLegacyLookup() throws Exception {
        downloader.recording();
        final StreamInfo info = StreamInfo.getInfo(Kick, VIDEO_URL);
        assertEquals(StreamType.VIDEO_STREAM, info.getStreamType());
        assertEquals("Recorded title", info.getName());
        assertEquals(VIDEO_URL, info.getUrl());
        assertEquals(33751, info.getDuration());
        assertEquals(101, info.getViewCount());
        assertEquals(MASTER_URL, info.getHlsUrl());
        assertNotNull(info.getUploadDate());
        assertEquals(2, info.getVideoStreams().size());
        assertEquals(DeliveryMethod.HLS, info.getVideoStreams().get(0).getDeliveryMethod());
        assertEquals("https://stream.kick.com/path/720p60/playlist.m3u8?signature=variant",
                info.getVideoStreams().get(0).getContent());
        assertEquals(info.getVideoStreams().get(0).getContent(),
                info.getVideoStreams().get(0).getManifestUrl());
        assertEquals("https://cdn.example/160p/playlist.m3u8?token=kept",
                info.getVideoStreams().get(1).getContent());
        assertEquals(2400000, info.getVideoStreams().get(0).getBitrate());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
        assertTrue(downloader.requests.stream().noneMatch(r -> r.url().contains("/api/v1/video/")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void liveAndRecordingExposeCachedAudioAndCaptionGroups(final boolean live) throws Exception {
        if (live) {
            downloader.channel(true).on(CHANNEL_API + "/playback-url",
                    "{\"data\":\"" + MASTER_URL + "\"}");
        } else {
            downloader.recording();
        }
        downloader.on(MASTER_URL, "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Original\","
                + "LANGUAGE=\"fr\",URI=\"audio.m3u8\"\n"
                + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"French\","
                + "LANGUAGE=\"fr\",URI=\"text.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,"
                + "CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"a\",SUBTITLES=\"s\"\n"
                + "video.m3u8?signature=keep\n");
        final StreamExtractor extractor = Kick.getStreamExtractor(
                live ? "https://kick.com/example" : VIDEO_URL);
        extractor.fetchPage();
        assertEquals(MASTER_URL, extractor.getHlsUrl());
        assertTrue(extractor.getVideoStreams().isEmpty());
        assertEquals(1, extractor.getVideoOnlyStreams().size());
        assertTrue(extractor.getVideoOnlyStreams().get(0).isVideoOnly());
        assertEquals(extractor.getVideoOnlyStreams().get(0).getContent(),
                extractor.getVideoOnlyStreams().get(0).getManifestUrl());
        assertEquals(1, extractor.getAudioStreams().size());
        assertEquals(MASTER_URL, extractor.getAudioStreams().get(0).getManifestUrl());
        assertEquals("fr", extractor.getSubtitlesDefault().get(0).getLanguageTag());
        assertEquals(extractor.getSubtitlesDefault(), extractor.getSubtitles(MediaFormat.VTT));
        assertTrue(extractor.getSubtitles(MediaFormat.SRT).isEmpty());
        assertEquals(1, downloader.requests.stream()
                .filter(request -> MASTER_URL.equals(request.url())).count());
    }

    @Test
    void timestampedRecordingSeeksWithoutChangingCanonicalIdentity() throws Exception {
        downloader.recording();
        final String originalUrl = VIDEO_URL + "?t=25&ref=share";
        assertEquals(VIDEO_URL, Kick.getStreamLHFactory().fromUrl(originalUrl).getUrl());
        final StreamInfo info = StreamInfo.getInfo(Kick, originalUrl);
        assertEquals(25, info.getStartPosition());
        assertEquals(VIDEO_URL, info.getUrl());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void encodedQueryValueCannotCreateRecordingSeekOffset() throws Exception {
        downloader.recording();
        final StreamInfo info = StreamInfo.getInfo(Kick, VIDEO_URL + "?ref=x%26t%3D25");
        assertEquals(0, info.getStartPosition());
        assertEquals(VIDEO_URL, info.getUrl());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?t", "?t=", "?t=-25", "?t=1.5", "?t=25seconds",
            "?t=9223372036854775807", "?t=9223372036854776", "?t=9223372036855",
            "?t=9223372036854775808", "?t=999999999999999999999999999999",
            "?t=%26t%3D25", "?t=invalid&t=25"})
    void invalidOptionalRecordingOffsetsDoNotPreventPlayback(final String query) throws Exception {
        downloader.recording();
        final StreamInfo info = StreamInfo.getInfo(Kick, VIDEO_URL + query);
        assertEquals(0, info.getStartPosition());
        assertEquals(VIDEO_URL, info.getUrl());
        assertEquals(MASTER_URL, info.getHlsUrl());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void encodedRecordingOffsetUsesTheFirstQueryParameterValue() throws Exception {
        downloader.recording();
        final StreamInfo info = StreamInfo.getInfo(Kick, VIDEO_URL + "?%74=%32%35&t=50");
        assertEquals(25, info.getStartPosition());
        assertEquals(VIDEO_URL, info.getUrl());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void legacyUuidResolvesAndCanonicalizesToCurrentRecording() throws Exception {
        final JsonObject legacy = KickFixtures.json("{\"uuid\":\"" + LEGACY_UUID + "\","
                + "\"source\":\"" + MASTER_URL + "\",\"views\":23,\"status\":\"public\"}");
        final JsonObject livestream = KickFixtures.json("{\"vod_id\":\"" + CURRENT_UUID + "\","
                + "\"session_title\":\"Legacy title\",\"duration\":33751000,"
                + "\"start_time\":\"2026-10-16 22:26:47\"}");
        livestream.put("channel", KickFixtures.channelData(false));
        legacy.put("livestream", livestream);
        downloader.on("https://kick.com/api/v1/video/" + LEGACY_UUID, JsonWriter.string(legacy))
                .on(MASTER_URL, KickFixtures.MASTER);
        final StreamInfo info = StreamInfo.getInfo(Kick, "https://kick.com/video/" + LEGACY_UUID);
        assertEquals(VIDEO_URL, info.getUrl());
        assertEquals(33751, info.getDuration());
        assertEquals("Legacy title", info.getName());
        assertEquals(23, info.getViewCount());
        assertNotNull(info.getUploadDate());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void channelsExposeStableSubscriptionTabsAndCurrentVodLinks() throws Exception {
        downloader.channel(true).on(VIDEOS_API,
                "{\"data\":[" + JsonWriter.string(KickFixtures.videoData()) + "]}");
        final ChannelInfo channel = ChannelInfo.getInfo(Kick, "https://kick.com/example/about");
        assertEquals(CHANNEL_URL, channel.getUrl());
        assertEquals("example", channel.getId());
        assertEquals("Example", channel.getName());
        assertEquals(73, channel.getSubscriberCount());
        assertEquals("Channel biography", channel.getDescription());
        assertTrue(channel.isVerified());
        assertTrue(channel.getErrors().isEmpty(), channel.getErrors().toString());
        assertEquals(ChannelTabs.VIDEOS, channel.getTabs().get(0).getContentFilters().get(0));
        final ChannelTabInfo videos = ChannelTabInfo.getInfo(Kick, channel.getTabs().get(0));
        assertEquals(1, videos.getRelatedItems().size());
        final StreamInfoItem first = (StreamInfoItem) videos.getRelatedItems().get(0);
        assertEquals(VIDEO_URL, first.getUrl());
        assertEquals(33751, first.getDuration());
        assertFalse(videos.hasNextPage());
        assertTrue(videos.getErrors().isEmpty(), videos.getErrors().toString());
        final ChannelTabInfo live = ChannelTabInfo.getInfo(Kick, channel.getTabs().get(1));
        assertEquals(1, live.getRelatedItems().size());
        assertEquals(StreamType.LIVE_STREAM, ((StreamInfoItem) live.getRelatedItems().get(0)).getStreamType());
        assertEquals(Instant.parse("2026-10-18T12:00:00Z").getEpochSecond(),
                ((StreamInfoItem) live.getRelatedItems().get(0))
                        .getUploadDate().offsetDateTime().toEpochSecond());
    }

    @Test
    void retainedLiveTabHandlerRefreshesOfflineAndEndedBroadcasts() throws Exception {
        downloader.channel(false);
        final ChannelInfo channel = ChannelInfo.getInfo(Kick, CHANNEL_URL);
        final ListLinkHandler liveHandler = channel.getTabs().get(1);
        assertTrue(ChannelTabInfo.getInfo(Kick, liveHandler).getRelatedItems().isEmpty());

        downloader.channel(true);
        final ChannelTabInfo live = ChannelTabInfo.getInfo(Kick, liveHandler);
        assertEquals(1, live.getRelatedItems().size());
        final String firstUrl = live.getRelatedItems().get(0).getUrl();
        assertEquals("https://kick.com/example?live="
                + Instant.parse("2026-10-18T12:00:00Z").getEpochSecond(), firstUrl);
        assertTrue(live.getErrors().isEmpty(), live.getErrors().toString());

        downloader.channel(false);
        final ChannelTabInfo ended = ChannelTabInfo.getInfo(Kick, liveHandler);
        assertTrue(ended.getRelatedItems().isEmpty());
        assertTrue(ended.getErrors().isEmpty(), ended.getErrors().toString());

        final JsonObject nextChannel = KickFixtures.channelData(true);
        nextChannel.getObject("livestream").put("start_time", "2026-10-18 13:00:00");
        nextChannel.getObject("livestream").put("session_title", "Next broadcast");
        downloader.on(CHANNEL_API, JsonWriter.string(nextChannel));
        final ChannelTabInfo restarted = ChannelTabInfo.getInfo(Kick, liveHandler);
        assertEquals(1, restarted.getRelatedItems().size());
        assertEquals("Next broadcast", restarted.getRelatedItems().get(0).getName());
        assertEquals("https://kick.com/example?live="
                + Instant.parse("2026-10-18T13:00:00Z").getEpochSecond(),
                restarted.getRelatedItems().get(0).getUrl());
        assertTrue(restarted.getErrors().isEmpty(), restarted.getErrors().toString());
    }

    @Test
    void searchAndSuggestionsUseRealChannelsAndEncodeQueries() throws Exception {
        final JsonObject channel = KickFixtures.json("{\"id\":42,\"slug\":\"example\","
                + "\"username\":\"Example\",\"follower_count\":73,\"is_live\":false,"
                + "\"is_verified\":true,\"profile_picture\":\"https://images.example/search.jpg\"}");
        final String result = "{\"data\":[" + JsonWriter.string(channel) + "]}";
        downloader.on("https://search.kick.com/api/v1/channels/search?query=a%26b&limit=50", result)
                .on("https://search.kick.com/api/v1/channels/search?query=a%26b&limit=10", result);
        final SearchInfo search = SearchInfo.getInfo(Kick, Kick.getSearchQHFactory().fromQuery(
                "a&b", List.of(new FilterItem(KickSearchFilters.CHANNELS,
                        LibraryStringIds.SEARCH_FILTERS_CHANNELS)), List.of()));
        assertEquals(1, search.getRelatedItems().size());
        final ChannelInfoItem first = (ChannelInfoItem) search.getRelatedItems().get(0);
        assertEquals(CHANNEL_URL, first.getUrl());
        assertEquals("Example", first.getName());
        assertTrue(first.isVerified());
        assertEquals(73, first.getSubscriberCount());
        assertEquals("https://images.example/search.jpg", first.getThumbnails().get(0).getUrl());
        assertFalse(search.hasNextPage());
        assertTrue(search.getErrors().isEmpty(), search.getErrors().toString());
        assertEquals(List.of("Example"), Kick.getSuggestionExtractor().suggestionList("a&b"));
        assertTrue(Kick.getSuggestionExtractor().suggestionList(" ").isEmpty());
    }

    @Test
    void featuredLiveDiscoveryLeadsToPlayback() throws Exception {
        downloader.on(KickApi.livestreamsUrl("en", 24, null, null),
                "{\"data\":{\"livestreams\":[{\"title\":\"Featured live\","
                + "\"show_view_count\":false,\"viewer_count\":999,"
                + "\"start_time\":\"2026-10-18T12:00:00Z\","
                + "\"thumbnail\":{\"src\":\"https://images.example/featured.webp\"},"
                + "\"channel\":{\"slug\":\"example\",\"username\":\"Example\","
                + "\"profile_pic\":\"https://images.example/avatar.jpg\"}}]}}")
                .channel(true).on(CHANNEL_API + "/playback-url",
                        "{\"data\":\"https://live.example/current.m3u8?token=kept\"}")
                .on("https://live.example/current.m3u8?token=kept", KickFixtures.MASTER);
        final KioskInfo kiosk = KioskInfo.getInfo(Kick, "https://kick.com/");
        final StreamInfoItem item = (StreamInfoItem) kiosk.getRelatedItems().get(0);
        assertEquals("Featured live", item.getName());
        assertEquals(-1, item.getViewCount());
        assertEquals("https://kick.com/example?live="
                + Instant.parse("2026-10-18T12:00:00Z").getEpochSecond(), item.getUrl());
        assertFalse(kiosk.hasNextPage());
        assertTrue(kiosk.getErrors().isEmpty(), kiosk.getErrors().toString());
        assertEquals(StreamType.LIVE_STREAM, StreamInfo.getInfo(Kick, item.getUrl()).getStreamType());
    }

    @Test
    void unavailablePrivateBlockedAndMalformedResponsesStayDistinct() throws Exception {
        downloader.status(CHANNEL_API, 404, "{}");
        assertThrows(ContentNotAvailableException.class, () -> ChannelInfo.getInfo(Kick, CHANNEL_URL));
        downloader.status(CHANNEL_API, 403, "{\"message\":\"Forbidden\"}");
        assertThrows(IOException.class, () -> ChannelInfo.getInfo(Kick, CHANNEL_URL));
        downloader.on(CHANNEL_API, "<html><title>Just a moment...</title></html>");
        assertThrows(ReCaptchaException.class, () -> ChannelInfo.getInfo(Kick, CHANNEL_URL));
        downloader.on(CHANNEL_API, "invalid JSON");
        assertThrows(ParsingException.class, () -> ChannelInfo.getInfo(Kick, CHANNEL_URL));
        downloader.status(CHANNEL_API, 429, "{}");
        assertThrows(IOException.class, () -> ChannelInfo.getInfo(Kick, CHANNEL_URL));
        downloader.channel(false).status(VIDEOS_API + "/" + CURRENT_UUID, 401, "{}");
        assertThrows(PrivateContentException.class, () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        assertTrue(downloader.requests.stream().noneMatch(r -> r.url().contains("/api/v1/video/")));
        downloader.status(CHANNEL_API, 403, "{}").header(CHANNEL_API, "CF-Mitigated", "challenge");
        assertThrows(ReCaptchaException.class, () -> ChannelInfo.getInfo(Kick, CHANNEL_URL));
    }

    @Test
    void missingCurrentAndLegacyRecordingIsTypedUnavailable() {
        downloader.channel(false).status(VIDEOS_API + "/" + CURRENT_UUID, 404, "{}")
                .status("https://kick.com/api/v1/video/" + CURRENT_UUID, 404, "{}");
        assertThrows(ContentNotAvailableException.class, () -> StreamInfo.getInfo(Kick, VIDEO_URL));
    }

    @Test
    void expiredAndEmptyCurrentResponsesCannotFallBackToTheLegacyApi() {
        downloader.channel(false).status(VIDEOS_API + "/" + CURRENT_UUID, 410, "{}");
        assertThrows(ContentNotAvailableException.class, () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        downloader.on(VIDEOS_API + "/" + CURRENT_UUID, "null");
        assertThrows(ContentNotAvailableException.class, () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        assertTrue(downloader.requests.stream().noneMatch(r -> r.url().contains("/api/v1/video/")));
    }

    @Test
    void challengeWordsInsideChannelMetadataDoNotMisclassifyValidJson() throws Exception {
        final JsonObject channel = KickFixtures.channelData(false);
        final String bio = "<title>Just a moment cf-chl- /cdn-cgi/challenge-platform";
        channel.getObject("user").put("bio", bio);
        downloader.on(CHANNEL_API, JsonWriter.string(channel));
        final ChannelInfo info = ChannelInfo.getInfo(Kick, CHANNEL_URL);
        assertEquals(bio, info.getDescription());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void subscriberOnlyRecordingsNeverFetchSuppliedMedia() {
        final JsonObject video = KickFixtures.videoData();
        video.put("status", "sub_only");
        downloader.channel(false).on(VIDEOS_API + "/" + CURRENT_UUID,
                "{\"data\":" + JsonWriter.string(video) + "}");
        final PaidContentException error = assertThrows(PaidContentException.class,
                () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        assertEquals("This Kick recording requires a channel subscription", error.getMessage());
        assertTrue(downloader.requests.stream().noneMatch(r -> r.url().equals(MASTER_URL)));
        final JsonObject legacy = KickFixtures.json("{\"uuid\":\"" + LEGACY_UUID
                + "\",\"status\":\"sub_only\",\"source\":\"" + MASTER_URL + "\"}");
        video.put("channel", KickFixtures.channelData(false));
        legacy.put("livestream", video);
        downloader.on("https://kick.com/api/v1/video/" + LEGACY_UUID, JsonWriter.string(legacy));
        assertThrows(PaidContentException.class,
                () -> StreamInfo.getInfo(Kick, "https://kick.com/video/" + LEGACY_UUID));
        assertTrue(downloader.requests.stream().noneMatch(r -> r.url().equals(MASTER_URL)));
    }

    @Test
    void privateAndPrunedRecordingMetadataDoesNotReachTheMediaHost() {
        downloader.channel(false);
        final JsonObject video = KickFixtures.videoData();
        video.put("status", "private");
        downloader.on(VIDEOS_API + "/" + CURRENT_UUID,
                "{\"data\":" + JsonWriter.string(video) + "}");
        assertThrows(PrivateContentException.class, () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        video.put("status", "public");
        video.put("is_pruned", true);
        downloader.on(VIDEOS_API + "/" + CURRENT_UUID,
                "{\"data\":" + JsonWriter.string(video) + "}");
        assertThrows(ContentNotAvailableException.class, () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        assertTrue(downloader.requests.stream().noneMatch(r -> r.url().equals(MASTER_URL)));
    }

    @Test
    void emptyRecordingAndMissingPlaybackUrlAreTypedUnavailable() {
        downloader.channel(false).on(VIDEOS_API + "/" + CURRENT_UUID, "{\"data\":null}");
        assertThrows(ContentNotAvailableException.class, () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        downloader.channel(true).on(CHANNEL_API + "/playback-url", "{\"data\":null}");
        assertThrows(ContentNotAvailableException.class,
                () -> StreamInfo.getInfo(Kick, "https://kick.com/example"));
    }

    @Test
    void oldUuidInAChannelScopedLinkUsesLegacyApiOnlyAfterCurrentResourceIsMissing() throws Exception {
        final JsonObject legacy = KickFixtures.json("{\"uuid\":\"" + LEGACY_UUID + "\","
                + "\"source\":\"" + MASTER_URL + "\",\"views\":23,\"status\":\"public\"}");
        final JsonObject livestream = KickFixtures.json("{\"vod_id\":\"" + CURRENT_UUID + "\","
                + "\"session_title\":\"Legacy title\",\"duration\":33751000,"
                + "\"start_time\":\"2026-10-16 22:26:47\"}");
        livestream.put("channel", KickFixtures.channelData(false));
        legacy.put("livestream", livestream);
        downloader.channel(false).status(VIDEOS_API + "/" + LEGACY_UUID, 404, "{}")
                .on("https://kick.com/api/v1/video/" + LEGACY_UUID, JsonWriter.string(legacy))
                .on(MASTER_URL, KickFixtures.MASTER);
        final StreamInfo info = StreamInfo.getInfo(Kick, CHANNEL_URL + "/" + LEGACY_UUID);
        assertEquals(VIDEO_URL, info.getUrl());
        assertEquals(33751, info.getDuration());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }

    @Test
    void continuationsCannotRequestArbitraryHosts() throws Exception {
        final Page hostile = new Page("https://evil.example/api", "999");
        assertThrows(ParsingException.class,
                () -> Kick.getSearchExtractor(Kick.getSearchQHFactory().fromQuery("x")).getPage(hostile));
        assertThrows(ParsingException.class,
                () -> Kick.getKioskList().getDefaultKioskExtractor().getPage(hostile));
        assertThrows(ParsingException.class, () -> Kick.getChannelTabExtractor(
                Kick.getChannelTabLHFactory().fromQuery("example", List.of(ChannelTabs.VIDEOS), List.of()))
                .getPage(hostile));
        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    void malformedOrUnsafeHlsCannotBeAdvertisedAsDownloadable() {
        downloader.recording().on(MASTER_URL, "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1x720\nhttp://evil/playlist.m3u8\n");
        assertThrows(ParsingException.class, () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        downloader.on(MASTER_URL, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\naudio.m3u8\n");
        assertThrows(ParsingException.class, () -> StreamInfo.getInfo(Kick, VIDEO_URL));
    }

    @Test
    void ongoingRecordingsAreNotOfferedAsCompletedDownloads() throws Exception {
        final JsonObject ongoing = KickFixtures.videoData();
        ongoing.put("id", "11111111-1111-4111-8111-111111111111");
        ongoing.put("is_live", true);
        ongoing.put("recording_url", "");
        downloader.channel(true).on(VIDEOS_API, "{\"data\":[" + JsonWriter.string(ongoing)
                + "," + JsonWriter.string(KickFixtures.videoData()) + "]}");
        final ChannelInfo channel = ChannelInfo.getInfo(Kick, CHANNEL_URL);
        final ChannelTabInfo tab = ChannelTabInfo.getInfo(Kick, channel.getTabs().get(0));
        assertEquals(List.of(VIDEO_URL), tab.getRelatedItems().stream()
                .map(item -> item.getUrl()).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void ongoingRecordingLinkRoutesToLivePlaybackRatherThanPartialDownload() throws Exception {
        final JsonObject ongoing = KickFixtures.videoData();
        ongoing.put("is_live", true);
        ongoing.put("start_time", "2026-10-18T12:00:00Z");
        ongoing.put("recording_url", "");
        downloader.channel(true).on(VIDEOS_API + "/" + CURRENT_UUID,
                "{\"data\":" + JsonWriter.string(ongoing) + "}")
                .on(CHANNEL_API + "/playback-url", "{\"data\":\"" + MASTER_URL + "\"}")
                .on(MASTER_URL, KickFixtures.MASTER);
        final StreamInfo info = StreamInfo.getInfo(Kick, VIDEO_URL + "?t=25");
        assertEquals(StreamType.LIVE_STREAM, info.getStreamType());
        assertEquals("https://kick.com/example?live="
                + Instant.parse("2026-10-18T12:00:00Z").getEpochSecond(), info.getUrl());
        assertEquals("Live title", info.getName());
        assertEquals(0, info.getDuration());
        assertEquals(0, info.getStartPosition());
        assertEquals(MASTER_URL, info.getHlsUrl());
    }

    @Test
    void ongoingLegacyRecordingLoadsFullChannelBeforeLivePlayback() throws Exception {
        final JsonObject nestedChannel = KickFixtures.json("{\"slug\":\"example\"}");
        final JsonObject ongoing = KickFixtures.json("{\"is_live\":true,\"duration\":0,"
                + "\"start_time\":\"2026-10-18T12:00:00Z\"}");
        ongoing.put("channel", nestedChannel);
        final JsonObject legacy = KickFixtures.json("{\"uuid\":\"" + LEGACY_UUID + "\"}");
        legacy.put("livestream", ongoing);
        downloader.channel(true)
                .on("https://kick.com/api/v1/video/" + LEGACY_UUID, JsonWriter.string(legacy))
                .on(CHANNEL_API + "/playback-url", "{\"data\":\"" + MASTER_URL + "\"}")
                .on(MASTER_URL, KickFixtures.MASTER);
        final StreamInfo info = StreamInfo.getInfo(Kick, "https://kick.com/video/" + LEGACY_UUID);
        assertEquals(StreamType.LIVE_STREAM, info.getStreamType());
        assertEquals("Live title", info.getName());
        assertEquals(MASTER_URL, info.getHlsUrl());
    }

    @Test
    void ongoingRecordingCannotPlayADifferentOrMissingBroadcast() {
        final JsonObject ongoing = KickFixtures.videoData();
        ongoing.put("is_live", true);
        downloader.channel(true).on(VIDEOS_API + "/" + CURRENT_UUID,
                "{\"data\":" + JsonWriter.string(ongoing) + "}");
        final ContentNotAvailableException ended = assertThrows(ContentNotAvailableException.class,
                () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        assertEquals("This Kick broadcast has ended", ended.getMessage());
        ongoing.remove("start_time");
        downloader.on(VIDEOS_API + "/" + CURRENT_UUID,
                "{\"data\":" + JsonWriter.string(ongoing) + "}");
        final ParsingException missing = assertThrows(ParsingException.class,
                () -> StreamInfo.getInfo(Kick, VIDEO_URL));
        assertEquals("Kick recording has no broadcast identity", missing.getMessage());
        assertTrue(downloader.requests.stream().noneMatch(
                r -> r.url().contains("playback-url") || r.url().equals(MASTER_URL)));
    }

    @Test
    void successiveBroadcastsHaveDistinctCanonicalIdentitiesAndOldLinksCannotPlayNewOnes()
            throws Exception {
        final JsonObject channel = KickFixtures.channelData(true);
        downloader.on(CHANNEL_API, JsonWriter.string(channel))
                .on(CHANNEL_API + "/playback-url", "{\"data\":\"" + MASTER_URL + "\"}")
                .on(MASTER_URL, KickFixtures.MASTER);
        final ChannelInfo firstChannel = ChannelInfo.getInfo(Kick, CHANNEL_URL);
        final StreamInfoItem first = (StreamInfoItem) ChannelTabInfo.getInfo(Kick,
                firstChannel.getTabs().get(1)).getRelatedItems().get(0);
        final long firstStart = Instant.parse("2026-10-18T12:00:00Z").getEpochSecond();
        assertEquals("https://kick.com/example?live=" + firstStart, first.getUrl());
        assertEquals(first.getUrl(), StreamInfo.getInfo(Kick, first.getUrl()).getUrl());
        channel.getObject("livestream").put("id", 10);
        channel.getObject("livestream").put("start_time", "2026-10-18 13:00:00");
        downloader.on(CHANNEL_API, JsonWriter.string(channel));
        final StreamInfoItem second = (StreamInfoItem) ChannelTabInfo.getInfo(Kick,
                firstChannel.getTabs().get(1)).getRelatedItems().get(0);
        assertEquals("https://kick.com/example?live=" + (firstStart + 3600), second.getUrl());
        assertEquals(second.getUrl(), StreamInfo.getInfo(Kick, second.getUrl()).getUrl());
        assertThrows(ContentNotAvailableException.class,
                () -> StreamInfo.getInfo(Kick, first.getUrl()));
    }

    @Test
    void encodedQueryValuesCannotCreateLiveOrClipParameters() throws Exception {
        assertEquals("https://kick.com/example", Kick.getStreamLHFactory().fromUrl(
                "https://kick.com/example?ref=x%26live%3D1").getUrl());
        assertEquals(VIDEO_URL, Kick.getStreamLHFactory().fromUrl(
                VIDEO_URL + "?ref=x%26live%3D1").getUrl());
        assertEquals("https://kick.com/example", Kick.getStreamLHFactory().fromUrl(
                "https://kick.com/example?ref=x%26clip%3D123").getUrl());
        assertEquals("https://kick.com/example?live=123", Kick.getStreamLHFactory().fromUrl(
                "https://kick.com/example?%6cive=%31%32%33").getUrl());
        assertFalse(Kick.getStreamLHFactory().acceptUrl(
                "https://kick.com/example?live=123&%6cive=456"));
    }

    @Test
    void legacyAtSignChannelsRemainBrowsableFromCurrentSearch() throws Exception {
        downloader.on("https://search.kick.com/api/v1/search/enriched?query=legacy",
                "{\"data\":{\"livestreams\":[],\"channels\":[{\"id\":42,"
                        + "\"slug\":\"@legacy\",\"username\":\"@Legacy\"}]}}");
        final JsonObject channel = KickFixtures.channelData(false);
        channel.put("slug", "@legacy");
        downloader.on("https://kick.com/api/v2/channels/@legacy", JsonWriter.string(channel));
        final SearchInfo search = SearchInfo.getInfo(Kick,
                Kick.getSearchQHFactory().fromQuery("legacy"));
        assertTrue(search.getErrors().isEmpty(), search.getErrors().toString());
        final String url = search.getRelatedItems().get(0).getUrl();
        assertEquals("https://kick.com/@legacy/videos", url);
        final ChannelInfo info = ChannelInfo.getInfo(Kick, url);
        assertEquals("@legacy", info.getId());
        assertEquals(url, info.getUrl());
        assertTrue(info.getErrors().isEmpty(), info.getErrors().toString());
    }
}
