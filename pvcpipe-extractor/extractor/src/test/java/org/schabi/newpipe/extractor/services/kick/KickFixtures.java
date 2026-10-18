package org.schabi.newpipe.extractor.services.kick;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

final class KickFixtures extends Downloader {
    static final String CURRENT_UUID = "01a146d3-2c58-7c5a-9d40-e3cdbd206bf1";
    static final String LEGACY_UUID = "b74e8a9c-12f4-40bf-8aa9-88fa4298bfd8";
    static final String CHANNEL_URL = "https://kick.com/example/videos";
    static final String CHANNEL_API = "https://kick.com/api/v2/channels/example";
    static final String VIDEOS_API = "https://web.kick.com/api/v1/channels/42/videos";
    static final String VIDEO_URL = CHANNEL_URL + "/" + CURRENT_UUID;
    static final String MASTER_URL = "https://stream.kick.com/path/master.m3u8?signature=kept";
    static final String MASTER = "#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2400000,"
            + "RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\n"
            + "720p60/playlist.m3u8?signature=variant\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=284x160\n"
            + "https://cdn.example/160p/playlist.m3u8?token=kept\n";

    final List<Request> requests = new ArrayList<>();
    private final Map<String, String> bodies = new LinkedHashMap<>();
    private final Map<String, Integer> statuses = new LinkedHashMap<>();
    private final Map<String, Map<String, List<String>>> headers = new LinkedHashMap<>();

    KickFixtures on(final String url, final String body) {
        bodies.put(url, body);
        statuses.put(url, 200);
        return this;
    }

    KickFixtures status(final String url, final int status, final String body) {
        on(url, body);
        statuses.put(url, status);
        return this;
    }

    KickFixtures header(final String url, final String name, final String value) {
        headers.put(url, Map.of(name, List.of(value)));
        return this;
    }

    KickFixtures channel(final boolean live) {
        return on(CHANNEL_API, JsonWriter.string(channelData(live)));
    }

    KickFixtures recording() {
        return channel(false).on(VIDEOS_API + "/" + CURRENT_UUID,
                "{\"data\":" + JsonWriter.string(videoData()) + "}")
                .on(MASTER_URL, MASTER);
    }

    static JsonObject channelData(final boolean live) {
        final JsonObject channel = json("{\"id\":42,\"slug\":\"example\",\"is_banned\":false,"
                + "\"followers_count\":\"73\",\"verified\":true,"
                + "\"playback_url\":\"https://stale.example/old.m3u8\","
                + "\"banner_image\":{\"url\":\"https://images.example/banner.jpg\"},"
                + "\"user\":{\"username\":\"Example\",\"bio\":\"Channel biography\","
                + "\"profile_pic\":\"https://images.example/avatar.jpg\"}}");
        channel.put("livestream", live ? json("{\"id\":9,\"session_title\":\"Live title\","
                + "\"is_live\":true,\"is_mature\":true,\"viewer_count\":51,"
                + "\"start_time\":\"2026-10-18 12:00:00\","
                + "\"thumbnail\":{\"url\":\"https://images.example/live.jpg\"}}")
                : null);
        return channel;
    }

    static JsonObject videoData() {
        return json("{\"id\":\"" + CURRENT_UUID + "\",\"duration\":33751,"
                + "\"recording_url\":\"" + MASTER_URL + "\",\"title\":\"Recorded title\","
                + "\"status\":\"public\",\"start_time\":\"2026-10-16T22:26:47Z\","
                + "\"viewer_count\":101,\"is_mature\":false,"
                + "\"channel\":{\"id\":42,\"slug\":\"example\",\"username\":\"Example\"},"
                + "\"thumbnail\":{\"src\":\"https://images.example/vod.jpg\"}}");
    }

    static JsonObject json(final String source) {
        try {
            return JsonParser.object().from(source);
        } catch (final JsonParserException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Response execute(@Nonnull final Request request) throws IOException {
        requests.add(request);
        if (!bodies.containsKey(request.url())) {
            throw new IOException("Unexpected Kick request: " + request.url());
        }
        return new Response(statuses.get(request.url()), "",
                headers.getOrDefault(request.url(), Map.of()), bodies.get(request.url()),
                request.url());
    }
}
