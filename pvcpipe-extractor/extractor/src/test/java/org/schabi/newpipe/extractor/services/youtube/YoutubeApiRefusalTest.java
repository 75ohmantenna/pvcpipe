package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.RequestBlockedException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("offline")
class YoutubeApiRefusalTest {
    private static final String URL = "https://youtubei.googleapis.com/youtubei/v1/visitor_id";
    private static final String HTML = "<html><title>Request refused</title>"
            + "Unusual traffic from your computer network</html>";

    @Test
    void explicitForbiddenHtmlRefusalsHaveNoBodyOrUrlInMessage() {
        for (final String marker : List.of("CAPTCHA", "unusual traffic", "automated queries")) {
            for (final String host : List.of("youtubei.googleapis.com", "www.youtube.com")) {
                for (final String endpoint : List.of("visitor_id", "player", "reel/reel_item_watch")) {
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
    void unrelatedForbiddenHtmlRemainsParsingFailure() {
        for (final String body : List.of("<html>" + "x".repeat(60) + "</html>",
                "<html>" + " ".repeat(60) + "Please sign in</html>",
                "<html>" + " ".repeat(60) + "Consent required</html>", "captcha",
                "x".repeat(16384) + "captcha")) {
            assertEquals(ParsingException.class, assertThrows(ParsingException.class,
                    () -> parse(403, "text/html", body, URL)).getClass());
        }
    }

    @Test
    void otherStatusesAndRedirectDestinationsDoNotInventRefusals() {
        for (final int code : List.of(200, 302, 400, 500)) {
            assertEquals(ParsingException.class, assertThrows(ParsingException.class,
                    () -> parse(code, "text/html", HTML, URL)).getClass());
        }
        for (final String url : List.of("https://youtubei.googleapis.com.evil.example/youtubei/v1/player",
                "https://evil.example/youtubei/v1/player", "http://www.youtube.com/youtubei/v1/player",
                "https://www.youtube.com/watch?v=fixture", "https://www.youtube.com/sorry/index",
                "https://www.youtube.com/youtubei/v1/player/extra")) {
            assertEquals(ParsingException.class, assertThrows(ParsingException.class,
                    () -> parse(403, "text/html", HTML, url)).getClass());
        }
    }

    @Test
    void jsonWithChallengeWordsAndSuccessfulResponsesAreUnchanged() throws Exception {
        final String json = "{\"description\":\"a video about captcha and unusual traffic\"}";
        assertEquals(json, parse(403, "application/json", json, URL));
        assertEquals(json, parse(200, "application/json", json, URL));
        assertEquals(json, parse(403, null, json, URL));
    }

    private static String parse(final int code, final String type, final String body,
                                final String url) throws Exception {
        return YoutubeParsingHelper.getValidJsonResponseBody(new Response(code, "fixture",
                type == null ? Map.of() : Map.of("Content-Type", List.of(type)), body, url));
    }
}
