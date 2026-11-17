package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.HttpResponseException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.RequestBlockedException;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("offline")
class YoutubeApiRefusalTest {
    private static final String URL = "https://youtubei.googleapis.com/youtubei/v1/visitor_id";
    private static final String HTML = "<html><title>Request refused</title>"
            + "Unusual traffic from your computer network</html>";
    private static final String JSON =
            "{\"description\":\"a video about captcha and unusual traffic\"}";

    @Test
    void explicitForbiddenHtmlRefusalsHaveNoBodyOrUrlInMessage() {
        for (final String marker : List.of("CAPTCHA", "unusual traffic", "automated queries")) {
            for (final String host : List.of("youtubei.googleapis.com", "www.youtube.com")) {
                for (final String endpoint : List.of(
                        "visitor_id", "player", "next", "reel/reel_item_watch")) {
                    final var failure = assertThrows(RequestBlockedException.class, () -> parse(
                            403, "TEXT/HTML; charset=UTF-8", "<html>" + " ".repeat(50)
                                    + marker + " private-fixture-token</html>",
                            "https://" + host + "/youtubei/v1/" + endpoint + "?private=query"));
                    assertFalse(failure.getMessage().contains("private"));
                    assertFalse(failure.getMessage().contains("https"));
                }
            }
        }
    }

    @Test
    void unrelatedForbiddenHtmlPreservesStatus() {
        for (final String body : List.of("<html>" + "x".repeat(60) + "</html>",
                "<html>" + " ".repeat(60) + "Please sign in</html>",
                "<html>" + " ".repeat(60) + "Consent required</html>", "captcha",
                "x".repeat(16384) + "captcha")) {
            assertHttpFailure(403, "text/html", body, URL);
        }
    }

    @Test
    void otherStatusesAndRedirectDestinationsDoNotInventRefusals() {
        for (final int code : List.of(199, 302, 400, 429, 500)) {
            assertHttpFailure(code, "text/html", HTML, URL);
        }
        for (final String url : List.of("https://youtubei.googleapis.com.evil.example/youtubei/v1/player",
                "https://evil.example/youtubei/v1/player", "http://www.youtube.com/youtubei/v1/player",
                "https://www.youtube.com/watch?v=fixture", "https://www.youtube.com/sorry/index",
                "https://www.youtube.com/youtubei/v1/player/extra")) {
            assertHttpFailure(403, "text/html", HTML, url);
        }
        assertHttpFailure(403, "text/html", HTML, "malformed");
    }

    @Test
    void unsuccessfulJsonAndShortBodiesPreserveStatus() {
        assertHttpFailure(403, "application/json", JSON, URL);
        assertHttpFailure(403, null, JSON, URL);
        assertHttpFailure(503, "application/json", "{}", URL);
        assertHttpFailure(403, "text/html", null, URL);
    }

    @Test
    void notFoundAndSuccessfulErrorPageRedirectsStillIndicateUnavailableContent() {
        assertThrows(ContentNotAvailableException.class, () -> parse(404, null, null, URL));
        assertThrows(ContentNotAvailableException.class,
                () -> parse(200, "text/html", HTML, "https://www.youtube.com/oops"));
    }

    @Test
    void successfulResponsesStillRequireValidJson() throws Exception {
        for (final int code : List.of(200, 206, 299)) {
            assertEquals(JSON, parse(code, "application/json", JSON, URL));
        }
        assertThrows(ParsingException.class, () -> parse(200, "text/html", HTML, URL));
        assertThrows(ParsingException.class, () -> parse(200, "application/json", "{}", URL));
        final String body = parse(200, "application/json", "malformed JSON".repeat(4), URL);
        assertThrows(ParsingException.class, () -> JsonUtils.toJsonObject(body));
    }

    private static void assertHttpFailure(final int code, final String type, final String body,
                                          final String url) {
        final var failure = assertThrows(HttpResponseException.class,
                () -> parse(code, type, body, url + "?private=fixture-secret"));
        assertEquals(code, failure.responseCode());
        assertFalse(failure.getMessage().contains("fixture"));
        assertFalse(failure.getMessage().contains("http"));
    }

    private static String parse(final int code, final String type, final String body,
                                final String url) throws Exception {
        return YoutubeParsingHelper.getValidJsonResponseBody(new Response(code, "fixture",
                type == null ? Map.of() : Map.of("Content-Type", List.of(type)), body, url));
    }
}
