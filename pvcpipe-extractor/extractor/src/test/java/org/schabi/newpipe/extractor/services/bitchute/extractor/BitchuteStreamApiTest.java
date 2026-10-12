package org.schabi.newpipe.extractor.services.bitchute.extractor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteParserHelper;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Bitchute;

@Tag("offline")
class BitchuteStreamApiTest {
    private static final String VIDEO_ID = "UGlrF9o9b-Q";

    @Test
    void extractsStreamFromApiResponses() throws Exception {
        final ApiDownloader downloader = new ApiDownloader();
        NewPipe.init(downloader);

        final StreamExtractor extractor = Bitchute.getStreamExtractor(
                "https://www.bitchute.com/video/" + VIDEO_ID + "/");
        extractor.fetchPage();

        assertEquals(VIDEO_ID, extractor.getId());
        assertEquals("Example video", extractor.getName());
        assertEquals(Instant.parse("2026-09-18T12:34:56Z"),
                extractor.getUploadDate().getInstant());
        assertEquals("<p>Example description</p>", extractor.getDescription().getContent());
        assertEquals(StreamExtractor.NO_AGE_LIMIT, extractor.getAgeLimit());
        assertEquals(3723, extractor.getLength());
        assertEquals(456, extractor.getViewCount());
        assertEquals(17, extractor.getLikeCount());
        assertEquals(3, extractor.getDislikeCount());
        assertEquals("Example Channel", extractor.getUploaderName());
        assertEquals("https://www.bitchute.com/channel/example/", extractor.getUploaderUrl());
        assertEquals("news", extractor.getCategory());
        assertEquals(Set.of("one", "two"), new HashSet<>(extractor.getTags()));

        final Image thumbnail = extractor.getThumbnails().get(0);
        assertEquals("https://cdn.example/live/cover_images/example/video_640x360.jpg",
                thumbnail.getUrl());
        assertEquals(640, thumbnail.getWidth());
        assertEquals(360, thumbnail.getHeight());
        final Image avatar = extractor.getUploaderAvatars().get(0);
        assertEquals("https://cdn.example/live/channel_images/example/avatar_large.jpg",
                avatar.getUrl());
        assertEquals(Image.ResolutionLevel.MEDIUM, avatar.getEstimatedResolutionLevel());

        final VideoStream stream = extractor.getVideoStreams().get(0);
        assertEquals(DeliveryMethod.HLS, stream.getDeliveryMethod());
        assertEquals("https://cdn.example/master.m3u8?token=abc", stream.getManifestUrl());
        assertEquals(1, extractor.getRelatedItems().getItems().size());
        assertEquals("Related video", extractor.getRelatedItems().getItems().get(0).getName());
        assertEquals(ApiDownloader.ENDPOINTS, downloader.seenEndpoints);
        assertEquals(4, downloader.requestCount);
        assertTrue(downloader.allRequestsWerePosts);
        assertTrue(downloader.allRequestsContainedExpectedPayload);
    }

    @Test
    void mapsLocationErrors() {
        NewPipe.init(new FixedDownloader(403,
                "{\"errors\":[{\"context\":\"reason\","
                        + "\"message\":\"Unavailable in your location\"}]}"));

        assertThrows(GeographicRestrictionException.class,
                () -> BitchuteParserHelper.callJsonApi(
                        com.grack.nanojson.JsonObject.builder(), "https://api.example/video"));
    }

    @Test
    void mapsMissingContentErrors() {
        NewPipe.init(new FixedDownloader(404,
                "{\"errors\":[{\"context\":\"reason\","
                        + "\"message\":\"Not Found\"}]}"));

        assertThrows(ContentNotAvailableException.class,
                () -> BitchuteParserHelper.callJsonApi(
                        com.grack.nanojson.JsonObject.builder(), "https://api.example/video"));
    }

    private static class FixedDownloader extends Downloader {
        private final int status;
        private final String body;

        FixedDownloader(final int status, final String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public Response execute(@Nonnull final Request request)
                throws IOException, ReCaptchaException {
            return new Response(status, "test", Map.of(), body, request.url());
        }
    }

    private static final class ApiDownloader extends FixedDownloader {
        private static final Set<String> ENDPOINTS = Set.of(
                "https://api.bitchute.com/api/beta9/video",
                "https://api.bitchute.com/api/beta/video/media",
                "https://api.bitchute.com/api/beta/video/counts",
                "https://api.bitchute.com/api/beta9/videos");

        private final Set<String> seenEndpoints = new HashSet<>();
        private int requestCount;
        private boolean allRequestsWerePosts = true;
        private boolean allRequestsContainedExpectedPayload = true;

        ApiDownloader() {
            super(200, "");
        }

        @Override
        public Response execute(@Nonnull final Request request) {
            requestCount++;
            seenEndpoints.add(request.url());
            allRequestsWerePosts &= "POST".equals(request.httpMethod());
            final String payload = new String(request.dataToSend(), StandardCharsets.UTF_8);
            allRequestsContainedExpectedPayload &= request.url().endsWith("/videos")
                    ? payload.contains("\"selection\":\"suggested\"")
                    : payload.contains("\"video_id\":\"" + VIDEO_ID + "\"");
            return new Response(200, "OK", Map.of(), bodyFor(request.url()), request.url());
        }

        private static String bodyFor(final String endpoint) {
            if (endpoint.endsWith("/video/media")) {
                return "{\"media_type\":\"application/x-mpegURL\","
                        + "\"media_url\":\"https://cdn.example/master.m3u8?token=abc\","
                        + "\"video_id\":\"" + VIDEO_ID + "\"}";
            }
            if (endpoint.endsWith("/video/counts")) {
                return "{\"dislike_count\":3,\"like_count\":17,\"view_count\":456}";
            }
            if (endpoint.endsWith("/videos")) {
                return "{\"videos\":[{\"channel\":{"
                        + "\"channel_id\":\"related-channel\","
                        + "\"channel_name\":\"Related Channel\","
                        + "\"channel_url\":\"/channel/related/\","
                        + "\"thumbnail_url\":\"https://cdn.example/channel-related.jpg\"},"
                        + "\"date_published\":\"2026-09-17T00:00:00Z\","
                        + "\"duration\":\"00:01:30\",\"sensitivity_id\":\"safe\","
                        + "\"state_id\":\"published\","
                        + "\"thumbnail_url\":\"https://cdn.example/related.jpg\","
                        + "\"video_id\":\"Related0001\",\"video_name\":\"Related video\","
                        + "\"video_url\":\"/video/Related0001/\",\"view_count\":99}]}";
            }
            return "{\"category_id\":\"news\",\"channel\":{"
                    + "\"channel_id\":\"channel-id\","
                    + "\"channel_name\":\"Example Channel\","
                    + "\"channel_url\":\"/channel/example/\","
                    + "\"date_created\":\"2020-01-01T00:00:00Z\","
                    + "\"last_video_published\":\"2026-09-18T12:34:56Z\","
                    + "\"subscriber_count\":42,"
                    + "\"thumbnail_url\":\"https://cdn.example/live/channel_images/example/"
                    + "avatar_large.jpg\"},"
                    + "\"date_published\":\"2026-09-18T12:34:56Z\","
                    + "\"description\":\"<p>Example description</p>\","
                    + "\"duration\":\"01:02:03\",\"hashtags\":[\"one\",\"two\"],"
                    + "\"is_discussable\":true,\"is_disliked\":false,\"is_liked\":true,"
                    + "\"profile_id\":\"profile-id\",\"rumble_id\":\"\","
                    + "\"sensitivity_id\":\"safe\",\"show_adverts\":false,"
                    + "\"show_comments\":true,\"show_rantrave\":false,"
                    + "\"state_id\":\"published\","
                    + "\"thumbnail_url\":\"https://cdn.example/live/cover_images/example/"
                    + "video_640x360.jpg\","
                    + "\"video_id\":\"" + VIDEO_ID + "\","
                    + "\"video_name\":\"Example video\",\"view_count\":321}";
        }
    }
}
