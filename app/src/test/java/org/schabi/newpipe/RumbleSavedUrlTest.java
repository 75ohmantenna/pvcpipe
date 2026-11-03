package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exercises the persisted URLs through the real extractor, without the app's info cache. */
public class RumbleSavedUrlTest {
    public static final String WATCH_URL =
            "https://rumble.com/v7hnjfy-durable-video.html";
    public static final String SHORT_URL = "https://rumble.com/shorts/v7hnjfy";
    public static final String EMBED_URL = "https://rumble.com/embed/v7fh6ig/";
    public static final String MEDIA_URL = "https://cdn.example/rumble-durable.mp4";

    @Test
    public void serializedQueueAndLibraryUrlsReplayWithoutResolverState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final StreamInfo initial = fixture.extractCold(WATCH_URL);
            assertPlayable(initial);
            assertEquals(WATCH_URL, initial.getUrl());

            final PlayQueueItem queued = new PlayQueueItem(initial);
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(queued);
            }
            final PlayQueueItem restored;
            try (ObjectInputStream input = new ObjectInputStream(
                    new ByteArrayInputStream(bytes.toByteArray()))) {
                restored = (PlayQueueItem) input.readObject();
            }
            final StreamEntity entity = new StreamEntity(initial);
            final StreamInfoItem libraryItem = entity.toStreamInfoItem();

            assertEquals(WATCH_URL, restored.getUrl());
            assertEquals(ServiceList.Rumble.getServiceId(), restored.getServiceId());
            assertEquals(WATCH_URL, libraryItem.getUrl());
            assertEquals(ServiceList.Rumble.getServiceId(), libraryItem.getServiceId());
            assertPlayable(fixture.extractCold(restored.getUrl()));
            assertPlayable(fixture.extractCold(libraryItem.getUrl()));
        }
    }

    public static void assertPlayable(final StreamInfo info) {
        assertEquals("Durable Rumble video", info.getName());
        assertEquals("v7hnjfy", info.getId());
        assertEquals(MEDIA_URL, info.getVideoStreams().get(0).getContent());
    }

    /** Shared routed fixture for saved-URL and timestamp consumer tests. */
    public static final class Fixture extends Downloader implements AutoCloseable {
        private static final String DETAILS_OPTIONS =
                "video.full,extended,with_cc,categories,comments,video.related_video,video.dthumbs";
        private final Downloader previousDownloader = NewPipe.getDownloader();
        private final Localization previousLocalization = NewPipe.getPreferredLocalization();
        private final ContentCountry previousCountry = NewPipe.getPreferredContentCountry();
        private final Map<String, Long> cache;
        private final Map<String, Long> previousCache;

        @SuppressWarnings("unchecked")
        public Fixture() throws ReflectiveOperationException {
            final Field field = RumbleMediaResolver.class.getDeclaredField("VIDEO_IDS_CACHE");
            field.setAccessible(true);
            cache = (Map<String, Long>) field.get(null);
            synchronized (cache) {
                previousCache = new LinkedHashMap<>(cache);
                cache.clear();
            }
            NewPipe.init(this, Localization.DEFAULT, ContentCountry.DEFAULT);
        }

        public StreamInfo extractCold(final String url) throws Exception {
            synchronized (cache) {
                cache.clear();
            }
            return StreamInfo.getInfo(ServiceList.Rumble, url);
        }

        @Override
        public void close() {
            NewPipe.init(previousDownloader, previousLocalization, previousCountry);
            synchronized (cache) {
                cache.clear();
                cache.putAll(previousCache);
            }
        }

        @NonNull
        @Override
        public Response execute(@NonNull final Request request) {
            final URI uri = URI.create(request.url());
            assertEquals("https", uri.getScheme());
            assertEquals("rumble.com", uri.getHost());
            final Map<String, String> query = query(uri);
            final String body;
            if ("/service.php".equals(uri.getPath())
                    && "media.details".equals(query.get("name"))) {
                assertEquals("8", query.get("api"));
                assertEquals(DETAILS_OPTIONS, query.get("options"));
                final String publicUrl = query.get("url");
                if (!WATCH_URL.equals(publicUrl) && !SHORT_URL.equals(publicUrl)) {
                    throw new AssertionError(
                            "Details require the full watch or canonical Short URL: "
                                    + request.url());
                }
                body = "{\"data\":{\"id\":449259064,\"permalink_id\":\"v7hnjfy\","
                        + "\"url\":\"" + publicUrl + "\",\"title\":\"Durable Rumble video\","
                        + "\"description\":\"Durable fixture\",\"duration\":60,\"views\":123,"
                        + "\"visibility\":\"public\",\"availability\":\"free\","
                        + "\"is_short\":" + SHORT_URL.equals(publicUrl) + ","
                        + "\"thumb\":\"https://cdn.example/cover.jpg\","
                        + "\"by\":{\"name\":\"Fixture creator\","
                        + "\"url\":\"https://rumble.com/c/Fixture\"},"
                        + "\"videos\":[{\"url\":\"" + MEDIA_URL + "\","
                        + "\"res\":720,\"bitrate_kbps\":1200}],\"cc\":[]}}";
            } else if ("/embedJS/u3/".equals(uri.getPath())) {
                assertEquals("video", query.get("request"));
                assertEquals("2", query.get("ver"));
                assertEquals("v7fh6ig", query.get("v"));
                body = "{\"vid\":449259064,\"title\":\"Durable Rumble video\",\"live\":0,"
                        + "\"duration\":60,\"i\":\"https://cdn.example/cover.jpg\",\"t\":[],"
                        + "\"author\":{\"name\":\"Fixture creator\","
                        + "\"url\":\"https://rumble.com/c/Fixture\"},"
                        + "\"ua\":{\"mp4\":[{\"url\":\"" + MEDIA_URL + "\","
                        + "\"meta\":{\"h\":720,\"bitrate\":1200}}]},\"cc\":{}}";
            } else if ("/service.php".equals(uri.getPath())
                    && "shorts.feed".equals(query.get("name"))) {
                body = "{\"data\":{\"items\":[],\"has_more\":false}}";
            } else if ("/service.php".equals(uri.getPath())
                    && "video.autoplay".equals(query.get("name"))) {
                assertEquals("449259064", query.get("video_id"));
                body = "{\"data\":{\"videos\":[]}}";
            } else {
                throw new AssertionError("Unexpected extraction request: " + request.url());
            }
            return new Response(200, "Fixture", Map.of(), body, request.url());
        }

        private static Map<String, String> query(final URI uri) {
            final Map<String, String> result = new LinkedHashMap<>();
            if (uri.getRawQuery() != null) {
                for (final String parameter : uri.getRawQuery().split("&")) {
                    final String[] pair = parameter.split("=", 2);
                    result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            pair.length == 1 ? ""
                                    : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
            return result;
        }
    }
}
