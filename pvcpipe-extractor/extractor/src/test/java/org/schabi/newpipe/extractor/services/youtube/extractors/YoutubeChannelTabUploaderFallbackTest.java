package org.schabi.newpipe.extractor.services.youtube.extractors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("offline")
class YoutubeChannelTabUploaderFallbackTest {
    private static final String CHANNEL_ID = "UC1234567890123456789012";
    private static final String BASE_URL = "https://www.youtube.com/channel/" + CHANNEL_ID;
    private static final String BASE_NAME = "Fixture uploader";
    private static final String OVERRIDE_URL = "https://www.youtube.com/@override";
    private static final String BROWSE_URL =
            "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final byte[] BODY = "{\"continuation\":\"fixture\"}"
            .getBytes(StandardCharsets.UTF_8);
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;
    private Object previousVersion;
    private Object previousExtracted;

    @BeforeEach
    void setUp() throws Exception {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        previousVersion = field("clientVersion").get(null);
        previousExtracted = field("clientVersionExtracted").get(null);
        field("clientVersion").set(null, "2.20261001.00.00");
        field("clientVersionExtracted").set(null, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        field("clientVersion").set(null, previousVersion);
        field("clientVersionExtracted").set(null, previousExtracted);
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"video", "videoLockup", "playlist", "playlistLockup"})
    void twoIdPageUsesRendererUrlNotRendererName(final String kind) throws Exception {
        final InfoItemsPage<InfoItem> result = page(kind, List.of("Ignored name", OVERRIDE_URL), true);
        assertUploader(result, BASE_NAME, BASE_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"video", "videoLockup", "playlist", "playlistLockup"})
    void emptyOverrideUrlFallsBackWithoutLosingNameOverride(final String kind) throws Exception {
        final InfoItemsPage<InfoItem> result = page(kind, List.of("Override name", "", "UNKNOWN"), true);
        assertUploader(result, "Override name", BASE_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"video", "videoLockup", "playlist", "playlistLockup", "reel", "shorts"})
    void threeIdPageKeepsExplicitChannelOverrides(final String kind) throws Exception {
        final InfoItemsPage<InfoItem> result = page(kind,
                List.of("Override name", OVERRIDE_URL, "VERIFIED"), true);
        assertUploader(result, "Override name", OVERRIDE_URL);
        final InfoItem item = result.getItems().get(0);
        assertTrue(item instanceof StreamInfoItem
                ? ((StreamInfoItem) item).isUploaderVerified()
                : ((PlaylistInfoItem) item).isUploaderVerified());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reel", "shorts"})
    void shortsWithoutChannelContextKeepNullUploader(final String kind) throws Exception {
        assertUploader(page(kind, List.of("Ignored name", OVERRIDE_URL), true), null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"video", "videoLockup"})
    void absentEndpointRetainsItemAndReportsErrorInsteadOfFabricatingUrl(final String kind)
            throws Exception {
        final InfoItemsPage<InfoItem> result = page(kind, List.of("Ignored name", OVERRIDE_URL), false);
        assertUploader(result, BASE_NAME, null);
        assertTrue(result.getErrors().stream().anyMatch(error ->
                error.getMessage() != null && error.getMessage().contains("uploader url")));
    }

    private static void assertUploader(final InfoItemsPage<InfoItem> result,
                                       final String name, final String url) {
        assertEquals(1, result.getItems().size(), () -> "Errors: " + result.getErrors());
        final InfoItem item = result.getItems().get(0);
        if (item instanceof StreamInfoItem) {
            assertEquals(name, ((StreamInfoItem) item).getUploaderName());
            assertEquals(url, ((StreamInfoItem) item).getUploaderUrl());
        } else {
            assertEquals(name, ((PlaylistInfoItem) item).getUploaderName());
            assertEquals(url, ((PlaylistInfoItem) item).getUploaderUrl());
        }
    }

    private static InfoItemsPage<InfoItem> page(final String kind, final List<String> ids,
                                               final boolean endpoint) throws Exception {
        final String json = "{\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":"
                + "{\"continuationItems\":[" + renderer(kind, endpoint) + "]}}]}";
        final int[] requests = {0};
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                assertEquals(BROWSE_URL, request.url());
                assertEquals("POST", request.httpMethod());
                assertArrayEquals(BODY, request.dataToSend());
                requests[0]++;
                return new Response(200, "OK", Map.of("Content-Type", List.of("application/json")),
                        json, BROWSE_URL);
            }
        });
        final var extractor = ServiceList.YouTube.getChannelTabExtractorFromId(
                "@fixture", ChannelTabs.VIDEOS);
        final InfoItemsPage<InfoItem> result = extractor.getPage(
                new Page(BROWSE_URL, null, ids, null, BODY));
        assertEquals(1, requests[0]);
        assertNull(result.getNextPage());
        return result;
    }

    private static String renderer(final String kind, final boolean endpoint) {
        final String navigation = endpoint
                ? "{\"browseEndpoint\":{\"browseId\":\"" + CHANNEL_ID + "\"}}" : "{}";
        final String byline = "{\"runs\":[{\"text\":\"" + BASE_NAME
                + "\",\"navigationEndpoint\":" + navigation + "}]}";
        if (kind.equals("video")) {
            return "{\"gridVideoRenderer\":{\"videoId\":\"fixtureVid1\","
                    + "\"title\":{\"simpleText\":\"Fixture video\"},\"longBylineText\":" + byline
                    + ",\"lengthText\":{\"simpleText\":\"1:00\"},"
                    + "\"viewCountText\":{\"simpleText\":\"12 views\"},"
                    + "\"publishedTimeText\":{\"simpleText\":\"2 days ago\"}}}";
        }
        if (kind.equals("playlist")) {
            return "{\"gridPlaylistRenderer\":{\"playlistId\":\"PL1234567890123456\","
                    + "\"title\":{\"simpleText\":\"Fixture playlist\"},\"longBylineText\":" + byline
                    + ",\"videoCount\":\"2\"}}";
        }
        if (kind.equals("reel")) {
            return "{\"richItemRenderer\":{\"content\":{\"reelItemRenderer\":{"
                    + "\"videoId\":\"fixtureVid1\",\"headline\":{\"simpleText\":\"Fixture reel\"},"
                    + "\"viewCountText\":{\"simpleText\":\"12 views\"}}}}}";
        }
        if (kind.equals("shorts")) {
            return "{\"richItemRenderer\":{\"content\":{\"shortsLockupViewModel\":{"
                    + "\"onTap\":{\"innertubeCommand\":{\"reelWatchEndpoint\":{\"videoId\":\"fixtureVid1\"}}},"
                    + "\"overlayMetadata\":{\"primaryText\":{\"content\":\"Fixture short\"},"
                    + "\"secondaryText\":{\"content\":\"12 views\"}}}}}}";
        }
        final boolean playlist = kind.equals("playlistLockup");
        return "{\"lockupViewModel\":{\"contentId\":\""
                + (playlist ? "PL1234567890123456" : "fixtureVid1") + "\",\"contentType\":\""
                + (playlist ? "LOCKUP_CONTENT_TYPE_PLAYLIST" : "LOCKUP_CONTENT_TYPE_VIDEO")
                + "\",\"contentImage\":{\"thumbnailViewModel\":{\"overlays\":[]}},"
                + "\"metadata\":{\"lockupMetadataViewModel\":{\"title\":{\"content\":\"Fixture lockup\"},"
                + "\"image\":{\"decoratedAvatarViewModel\":{\"rendererContext\":{\"commandContext\":"
                + "{\"onTap\":{\"innertubeCommand\":" + navigation + "}}}}},"
                + "\"metadata\":{\"contentMetadataViewModel\":{\"metadataRows\":[{\"metadataParts\":["
                + "{\"text\":{\"content\":\"" + BASE_NAME + "\",\"commandRuns\":[{\"onTap\":"
                + "{\"innertubeCommand\":" + navigation + "}}]}}]},"
                + "{\"metadataParts\":[{\"text\":{\"content\":\"12 views\"}},"
                + "{\"text\":{\"content\":\"2 days ago\"}}]}]}}}}}}";
    }

    private static Field field(final String name) throws Exception {
        final Field field = YoutubeParsingHelper.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
