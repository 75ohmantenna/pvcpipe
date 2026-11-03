package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemsCollector;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.rumble.RumbleCreatorResolver;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.rumble.settings.RumbleSettings;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@Tag("offline")
class RumbleJsonItemMappingTest {
    private static final String WATCH = "https://rumble.com/v7hnjfy-full-watch-slug.html?source=test";
    private static final String SHORT = "https://rumble.com/shorts/v7g09by";
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;
    private boolean previousHidePremium;
    private Map<String, Long> previousMedia;
    private Map<String, String> previousCreators;

    @BeforeEach
    void isolateState() throws Exception {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        previousHidePremium = Rumble.getServiceSettings()
                .isSettingEnabled(RumbleSettings.HIDE_PREMIUM_STREAMS);
        Rumble.getServiceSettings().setting(false, RumbleSettings.HIDE_PREMIUM_STREAMS);
        final Map<String, Long> media = mediaCache();
        synchronized (media) {
            previousMedia = new LinkedHashMap<>(media);
            media.clear();
        }
        final Map<String, String> creators = creatorCache();
        synchronized (creators) {
            previousCreators = new LinkedHashMap<>(creators);
            creators.clear();
        }
    }

    @AfterEach
    void restoreState() throws Exception {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
        Rumble.getServiceSettings().setting(previousHidePremium, RumbleSettings.HIDE_PREMIUM_STREAMS);
        final Map<String, Long> media = mediaCache();
        synchronized (media) {
            media.clear();
            media.putAll(previousMedia);
        }
        final Map<String, String> creators = creatorCache();
        synchronized (creators) {
            creators.clear();
            creators.putAll(previousCreators);
        }
    }

    @Test
    void videosPreserveFullUrlsAndNumericMetadataAndRegisterTheInternalIdentity() throws Exception {
        final JsonObject item = video(WATCH);
        item.put("id", 449259064L);
        item.put("duration", 3661L);
        item.put("views", 5000000000L);
        item.put("upload_date", "2026-10-31T10:15:30+02:00");
        item.put("thumb", "https://example.com/cover.jpg");
        item.put("visibility", "public");
        item.put("availability", null);
        item.put("by", creator("channel", "_c123", "Example"));
        item.getObject("by").put("name", "Example uploader");
        item.getObject("by").put("verified_badge", true);
        item.getObject("by").put("thumb", "https://example.com/avatar.jpg");
        final StreamInfoItemsCollector collector = collect(item);
        final StreamInfoItem result = collector.getItems().get(0);
        assertEquals(WATCH, result.getUrl());
        assertEquals("Fixture video", result.getName());
        assertEquals(StreamType.VIDEO_STREAM, result.getStreamType());
        assertEquals(3661, result.getDuration());
        assertEquals(5000000000L, result.getViewCount());
        assertEquals(Instant.parse("2026-10-31T08:15:30Z"), result.getUploadDate().getInstant());
        assertFalse(result.getUploadDate().isApproximation());
        assertEquals("https://example.com/cover.jpg", result.getThumbnails().get(0).getUrl());
        assertEquals("Example uploader", result.getUploaderName());
        assertEquals("https://rumble.com/c/Example", result.getUploaderUrl());
        assertTrue(result.isUploaderVerified());
        assertEquals(1, result.getUploaderAvatars().size());
        assertEquals(ContentAvailability.AVAILABLE, result.getContentAvailability());
        assertFalse(result.isShortFormContent());
        assertTrue(collector.getErrors().isEmpty());
        assertEquals("v7fh6ig", RumbleMediaResolver.resolveEmbedId(new NoRequestsDownloader(),
                RumbleStreamLinkHandlerFactory.getInstance().fromUrl(WATCH), null));
    }

    @Test
    void liveCountsAndShortFlagsDoNotUseDurationOrStringClockConversions() {
        final JsonObject item = video(SHORT);
        item.put("live", true);
        item.put("is_short", true);
        item.put("duration", 125);
        item.put("views", 1100);
        item.put("watching_now", 7);
        StreamInfoItem result = collect(item).getItems().get(0);
        assertEquals(StreamType.LIVE_STREAM, result.getStreamType());
        assertEquals(-1, result.getDuration());
        assertEquals(7, result.getViewCount());
        assertTrue(result.isShortFormContent());
        item.remove("watching_now");
        result = collect(item).getItems().get(0);
        assertEquals(1100, result.getViewCount());
        item.remove("views");
        assertEquals(-1, collect(item).getItems().get(0).getViewCount());
    }

    @Test
    void absentOptionalMetadataAndNullUploaderRemainUnknownWithoutErrors() {
        final JsonObject item = video(SHORT);
        item.put("by", null);
        item.put("thumb", null);
        final StreamInfoItemsCollector collector = collect(item);
        final StreamInfoItem result = collector.getItems().get(0);
        assertEquals(-1, result.getDuration());
        assertEquals(-1, result.getViewCount());
        assertNull(result.getUploaderName());
        assertNull(result.getUploaderUrl());
        assertFalse(result.isUploaderVerified());
        assertTrue(result.getThumbnails().isEmpty());
        assertTrue(result.getUploaderAvatars().isEmpty());
        assertNull(result.getUploadDate());
        assertEquals(ContentAvailability.UNKNOWN, result.getContentAvailability());
        assertTrue(collector.getErrors().isEmpty());
        item.put("thumb", "");
        assertTrue(collect(item).getItems().get(0).getThumbnails().isEmpty());
    }

    @Test
    void optionalFailuresRetainTheVideoAndDoNotAbortLaterEmbeddedChannelRows() throws Exception {
        final JsonObject broken = video(WATCH);
        broken.put("upload_date", "not a date");
        broken.put("duration", "not a number");
        broken.put("thumb", new JsonObject());
        broken.put("by", "not an uploader object");
        final JsonObject later = video(SHORT);
        later.put("title", "Later video");
        final StreamInfoItemsCollector collector = embedded(broken, later);
        assertEquals(2, collector.getItems().size());
        assertEquals("Later video", collector.getItems().get(1).getName());
        assertFalse(collector.getErrors().isEmpty());
        assertEquals(-1, collector.getItems().get(0).getDuration());
        assertNull(collector.getItems().get(0).getUploadDate());
    }

    @Test
    void requiredFieldFailuresAreCollectorErrorsNotConstructorOrPageFailures() throws Exception {
        final List<JsonObject> bad = new ArrayList<>();
        for (final Object title : new Object[]{null, "", "  ", 42}) {
            final JsonObject item = video(WATCH);
            item.put("title", title);
            bad.add(item);
        }
        for (final Object url : new Object[]{null, "", "not a URL", "https://example.com/v7hnjfy",
                "https://rumble.com/embed/v7fh6ig/"}) {
            final JsonObject item = video(WATCH);
            item.put("url", url);
            bad.add(item);
        }
        bad.add(video(SHORT));
        final StreamInfoItemsCollector collector = embedded(bad.toArray(new JsonObject[0]));
        assertEquals(1, collector.getItems().size());
        assertEquals(SHORT, collector.getItems().get(0).getUrl());
        assertEquals(bad.size() - 1, collector.getErrors().size());
    }

    @Test
    void suppliedMediaIdsMustBePositiveIntegralNumbersButMayBeAbsent() {
        for (final Object id : new Object[]{null, 0, -1, 1.5, "449259064", 1e30}) {
            final JsonObject item = video(WATCH);
            item.put("id", id);
            assertThrows(ParsingException.class,
                    () -> new RumbleJsonStreamInfoItemExtractor(item).getUrl(), String.valueOf(id));
        }
        assertEquals(WATCH, collect(video(WATCH)).getItems().get(0).getUrl());
    }

    @Test
    void onlyUploaderUrlRequiresCreatorIdentityAndItsFailureRemainsOptional() throws Exception {
        final JsonObject item = video(WATCH);
        final JsonObject by = creator("user", "_c123", "Example");
        by.put("name", "Still visible");
        by.put("verified_badge", true);
        item.put("by", by);
        final RumbleJsonStreamInfoItemExtractor mapper =
                new RumbleJsonStreamInfoItemExtractor(item);
        assertEquals("Fixture video", mapper.getName());
        assertEquals(WATCH, mapper.getUrl());
        assertEquals("Still visible", mapper.getUploaderName());
        assertTrue(mapper.isUploaderVerified());
        assertTrue(mapper.getUploaderAvatars().isEmpty());
        assertThrows(ParsingException.class, mapper::getUploaderUrl);
        final StreamInfoItemsCollector collector = collect(item);
        assertEquals(1, collector.getItems().size());
        assertEquals("Still visible", collector.getItems().get(0).getUploaderName());
        assertEquals(1, collector.getErrors().size());
    }

    @Test
    void onlyExplicitPremiumIsMembershipOrHidden() {
        final JsonObject premium = video(WATCH);
        premium.put("visibility", "public");
        premium.put("availability", "premium");
        assertEquals(ContentAvailability.MEMBERSHIP,
                collect(premium).getItems().get(0).getContentAvailability());
        Rumble.getServiceSettings().setting(true, RumbleSettings.HIDE_PREMIUM_STREAMS);
        assertTrue(collect(premium).getItems().isEmpty());
        for (final String visibility : List.of("private", "unlisted", "future")) {
            final JsonObject item = video(WATCH);
            item.put("visibility", visibility);
            final StreamInfoItemsCollector collector = collect(item);
            assertEquals(1, collector.getItems().size());
            assertEquals(ContentAvailability.UNKNOWN,
                    collector.getItems().get(0).getContentAvailability());
        }
        final JsonObject unknownRestriction = video(WATCH);
        unknownRestriction.put("visibility", "public");
        unknownRestriction.put("availability", "future");
        assertEquals(ContentAvailability.UNKNOWN,
                collect(unknownRestriction).getItems().get(0).getContentAvailability());
    }

    @Test
    void creatorMappingPreservesTypedAlphanumericIdsAndSeparatesSameSlugTypes() throws Exception {
        final JsonObject channel = creator("channel", "_c123", "Same");
        final JsonObject user = creator("user", "_uA1b2", "Same");
        channel.put("url", "https://rumble.com/c/Same");
        channel.put("description", "Creator description");
        channel.put("followers", 5000000000L);
        channel.put("videos", 12);
        channel.put("verified_badge", true);
        channel.put("thumb", "https://example.com/creator.jpg");
        final ChannelInfoItemsCollector collector =
                new ChannelInfoItemsCollector(Rumble.getServiceId());
        collector.commit(new RumbleJsonChannelInfoItemExtractor(channel));
        collector.commit(new RumbleJsonChannelInfoItemExtractor(user));
        assertEquals(2, collector.getItems().size());
        final ChannelInfoItem result = collector.getItems().get(0);
        assertEquals("https://rumble.com/c/Same", result.getUrl());
        assertEquals("Creator title", result.getName());
        assertEquals("Creator description", result.getDescription());
        assertEquals(5000000000L, result.getSubscriberCount());
        assertEquals(12, result.getStreamCount());
        assertTrue(result.isVerified());
        assertEquals(1, result.getThumbnails().size());
        assertEquals("https://rumble.com/user/Same", collector.getItems().get(1).getUrl());
        assertTrue(collector.getErrors().isEmpty());
        for (final JsonObject creator : List.of(channel, user)) {
            final String url = "https://rumble.com/"
                    + ("channel".equals(creator.getString("type")) ? "c/" : "user/") + "Same";
            final JsonObject metadata = new JsonObject();
            metadata.putAll(creator);
            metadata.put("url", url);
            final CreatorMetadataDownloader downloader = new CreatorMetadataDownloader(metadata);
            assertEquals(creator.getString("id"), RumbleCreatorResolver.resolveMetadata(
                    downloader, url, null).getString("id"));
            assertTrue(downloader.requestUrl.contains("name=video_collection.meta&api=8&id="
                    + creator.getString("id")));
        }
    }

    @Test
    void missingCreatorUrlUsesSingleEncodedSlugNeverTitleAndDefaultsOptionalFields() throws Exception {
        final JsonObject item = creator("user", "_uaBc123", "Space & 世界");
        final RumbleJsonChannelInfoItemExtractor mapper =
                new RumbleJsonChannelInfoItemExtractor(item);
        assertEquals("https://rumble.com/user/Space%20%26%20%E4%B8%96%E7%95%8C", mapper.getUrl());
        assertEquals("", mapper.getDescription());
        assertEquals(-1, mapper.getSubscriberCount());
        assertEquals(-1, mapper.getStreamCount());
        assertFalse(mapper.isVerified());
        assertTrue(mapper.getThumbnails().isEmpty());
        item.put("thumb", "");
        assertTrue(mapper.getThumbnails().isEmpty());
    }

    @Test
    void wrongCreatorTypesMissingIdentityAndMultiSegmentSlugsFailWithoutHidingLaterRows() {
        final List<JsonObject> bad = List.of(
                creator("channel", "_uA1b", "Example"),
                creator("user", "_c123", "Example"),
                creator("other", "_uA1b", "Example"),
                creator("channel", "_cA1b", "Example"),
                creator("user", "_uA1b", "one/two"),
                creator("user", "_uA1b", ""));
        final ChannelInfoItemsCollector collector =
                new ChannelInfoItemsCollector(Rumble.getServiceId());
        for (final JsonObject item : bad) {
            collector.commit(new RumbleJsonChannelInfoItemExtractor(item));
        }
        final JsonObject missingSlug = creator("user", "_uA1b", "Example");
        missingSlug.remove("slug");
        collector.commit(new RumbleJsonChannelInfoItemExtractor(missingSlug));
        final JsonObject missingId = creator("user", "_uA1b", "Example");
        missingId.remove("id");
        collector.commit(new RumbleJsonChannelInfoItemExtractor(missingId));
        final JsonObject wrongUrl = creator("user", "_uA1b", "Example");
        wrongUrl.put("url", "https://rumble.com/c/Example");
        collector.commit(new RumbleJsonChannelInfoItemExtractor(wrongUrl));
        collector.commit(new RumbleJsonChannelInfoItemExtractor(
                creator("channel", "_c123", "Good")));
        assertEquals(1, collector.getItems().size());
        assertEquals("https://rumble.com/c/Good", collector.getItems().get(0).getUrl());
        assertEquals(bad.size() + 3, collector.getErrors().size());
    }

    @Test
    void creatorOptionalErrorsDoNotDiscardCreatorsOrFollowingRows() {
        final JsonObject item = creator("user", "_uA1b", "Example");
        item.put("followers", "many");
        item.put("description", new JsonObject());
        item.put("thumb", false);
        final ChannelInfoItemsCollector collector =
                new ChannelInfoItemsCollector(Rumble.getServiceId());
        collector.commit(new RumbleJsonChannelInfoItemExtractor(item));
        collector.commit(new RumbleJsonChannelInfoItemExtractor(
                creator("channel", "_c1", "Later")));
        assertEquals(2, collector.getItems().size());
        assertEquals(3, collector.getErrors().size());
    }

    @Test
    void videosAndLivestreamTabsKeepHtmlUrlsAndFreshExtractorContinuations() throws Exception {
        final HtmlTabDownloader downloader = new HtmlTabDownloader();
        NewPipe.init(downloader);
        for (final var tab : List.of(ChannelTabs.VIDEOS, ChannelTabs.LIVESTREAMS)) {
            final String suffix = tab == ChannelTabs.VIDEOS ? "videos" : "livestreams";
            final String id = "c/Example";
            final String url = "https://rumble.com/" + id + "/" + suffix;
            final var extractor = Rumble.getChannelTabExtractorFromId(id, tab);
            extractor.fetchPage();
            final var first = extractor.getInitialPage();
            assertEquals(url, downloader.urls.get(downloader.urls.size() - 1));
            assertEquals(1, first.getItems().size());
            assertEquals(WATCH, first.getItems().get(0).getUrl());
            assertNotNull(first.getNextPage());
            assertEquals(url + "?page=2", first.getNextPage().getUrl());
            final var fresh = Rumble.getChannelTabExtractorFromId(id, tab);
            final var next = fresh.getPage(first.getNextPage());
            assertEquals(url + "?page=2", downloader.urls.get(downloader.urls.size() - 1));
            assertEquals(SHORT, next.getItems().get(0).getUrl());
            assertNull(next.getNextPage());
        }
        assertTrue(downloader.urls.stream().noneMatch(url -> url.contains("service.php")));
    }

    private static StreamInfoItemsCollector collect(final JsonObject item) {
        final StreamInfoItemsCollector collector =
                new StreamInfoItemsCollector(Rumble.getServiceId());
        collector.commit(new RumbleJsonStreamInfoItemExtractor(item));
        return collector;
    }

    private static StreamInfoItemsCollector embedded(final JsonObject... items) throws Exception {
        final JsonArray rows = new JsonArray();
        rows.addAll(List.of(items));
        final JsonObject root = new JsonObject();
        root.put("items", rows);
        final String html = "<rum-videos-grid><script type='application/json'>"
                + JsonWriter.string(root) + "</script></rum-videos-grid>";
        final StreamInfoItemsCollector collector =
                new StreamInfoItemsCollector(Rumble.getServiceId());
        for (final var mapper : new RumbleChannelTabItemsExtractorImpl()
                .extractStreamItems(Jsoup.parse(html))) {
            collector.commit(mapper);
        }
        return collector;
    }

    private static JsonObject video(final String url) {
        final JsonObject item = new JsonObject();
        item.put("object_type", "video");
        item.put("url", url);
        item.put("title", "Fixture video");
        return item;
    }

    private static JsonObject creator(final String type, final String id, final String slug) {
        final JsonObject item = new JsonObject();
        item.put("type", type);
        item.put("id", id);
        item.put("slug", slug);
        item.put("title", "Creator title");
        return item;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> mediaCache() throws Exception {
        final Field field = RumbleMediaResolver.class.getDeclaredField("VIDEO_IDS_CACHE");
        field.setAccessible(true);
        return (Map<String, Long>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> creatorCache() throws Exception {
        final Field field = RumbleCreatorResolver.class.getDeclaredField("CREATOR_IDS_CACHE");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    private static final class NoRequestsDownloader extends Downloader {
        @Override
        public Response execute(final Request request) {
            throw new AssertionError("Identity should already be registered: " + request.url());
        }
    }

    private static final class CreatorMetadataDownloader extends Downloader {
        private final JsonObject metadata;
        private String requestUrl;

        private CreatorMetadataDownloader(final JsonObject metadata) {
            this.metadata = metadata;
        }

        @Override
        public Response execute(final Request request) {
            requestUrl = request.url();
            if (!requestUrl.contains("name=video_collection.meta")) {
                throw new AssertionError("Creator association was not registered: " + requestUrl);
            }
            final JsonObject root = new JsonObject();
            root.put("data", metadata);
            return new Response(200, "OK", Map.of(), JsonWriter.string(root), requestUrl);
        }
    }

    private static final class HtmlTabDownloader extends Downloader {
        private final List<String> urls = new ArrayList<>();

        @Override
        public Response execute(final Request request) {
            final String url = request.url();
            urls.add(url);
            if (url.contains("service.php")) {
                throw new AssertionError("Channel tabs must retain their HTML path");
            }
            final boolean next = url.endsWith("?page=2");
            final JsonArray items = new JsonArray();
            items.add(video(next ? SHORT : WATCH));
            final JsonObject root = new JsonObject();
            root.put("items", items);
            final String body = (next ? "" : "<link rel='next' href='?page=2'>")
                    + "<rum-videos-grid><script type='application/json'>"
                    + JsonWriter.string(root) + "</script></rum-videos-grid>";
            return new Response(200, "OK", Map.of(), body, url);
        }
    }
}
