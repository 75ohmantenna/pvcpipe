package org.schabi.newpipe.downloader;

import com.google.gson.Gson;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.services.soundcloud.SoundcloudParsingHelper;
import org.schabi.newpipe.extractor.services.soundcloud.extractors.SoundcloudStreamExtractor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises transcoding request matching independently of stale client-discovery recordings. */
@Tag("offline")
class RecordedSoundcloudPrivacyTest {
    @Test
    void replaysEverySanitizedTrackAuthorizationRequest() throws Exception {
        final var oldDownloader = NewPipe.getDownloader();
        final var oldLocalization = NewPipe.getPreferredLocalization();
        final var oldCountry = NewPipe.getPreferredContentCountry();
        final var clientId = SoundcloudParsingHelper.class.getDeclaredField("clientId");
        clientId.setAccessible(true);
        final var oldClientId = clientId.get(null);
        int checked = 0;
        try (var paths = Files.walk(Path.of("src/test/resources/mocks/v1/org/schabi/"
                + "newpipe/extractor/services/soundcloud"))) {
            for (final var path : paths.filter(p -> p.getFileName().toString()
                    .startsWith("generated_mock_")).toList()) {
                final TestRequestResponse recording;
                try (var reader = Files.newBufferedReader(path)) {
                    recording = new Gson().fromJson(reader, TestRequestResponse.class);
                }
                final var expected = recording.getRequest();
                final var url = expected.url();
                final var marker = "&track_authorization=SYNTHETIC_MEDIA_TOKEN";
                if (!url.endsWith(marker)) {
                    continue;
                }
                final int query = url.indexOf("?client_id=");
                assertTrue(query > 0, "Recorded transcoding request shape");
                clientId.set(null, url.substring(query + "?client_id=".length(),
                        url.length() - marker.length()));
                final var replay = new MockDownloader(path.getParent().toString());
                final var calls = new AtomicInteger();
                NewPipe.init(new Downloader() {
                    @Override
                    public Response execute(@Nonnull final Request request) {
                        assertTrue(expected.equals(request),
                                "Production request must match the sanitized recording exactly");
                        calls.incrementAndGet();
                        return replay.execute(request);
                    }
                }, expected.localization(), oldCountry);

                // Isolate the real transcoding request/response method, including denied
                // recorded responses. This does not establish client discovery, stream
                // construction or full fetchPage playback.
                final var track = new JsonObject();
                track.put("track_authorization", "SYNTHETIC_MEDIA_TOKEN");
                final var extractor = new SoundcloudStreamExtractor(ServiceList.SoundCloud,
                        new LinkHandler("https://soundcloud.com/example/fixture",
                                "https://soundcloud.com/example/fixture", "fixture"));
                final var trackField = SoundcloudStreamExtractor.class.getDeclaredField("track");
                trackField.setAccessible(true);
                trackField.set(extractor, track);
                final var resolve = SoundcloudStreamExtractor.class
                        .getDeclaredMethod("getTranscodingUrl", String.class);
                resolve.setAccessible(true);
                final var actualContent = resolve.invoke(extractor, url.substring(0, query));
                assertEquals(1, calls.get());
                // Legacy groups can contain repeated request keys with differing recorded
                // responses. Compare the replay map's selected response, not file ordering.
                final var expectedContent = JsonParser.object()
                        .from(replay.execute(expected).responseBody()).getString("url");
                assertTrue(Objects.equals(expectedContent, actualContent),
                        "Production transcoding result must match the recorded response");
                checked++;
            }
        } finally {
            clientId.set(null, oldClientId);
            NewPipe.init(oldDownloader, oldLocalization, oldCountry);
        }
        assertEquals(13, checked, "Exercise every rewritten transcoding recording");
    }
}
