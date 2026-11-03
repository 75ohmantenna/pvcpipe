package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@Tag("offline")
class RumbleSuggestionExtractorTest {
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;

    @BeforeEach
    void saveExtractorState() {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
    }

    @AfterEach
    void restoreExtractorState() {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @Test
    void serviceProvidesSuggestionExtractor() {
        assertInstanceOf(RumbleSuggestionExtractor.class, Rumble.getSuggestionExtractor());
    }

    @Test
    void parsesChannelsAndCategoriesInResponseOrder() throws Exception {
        NewPipe.init(new FixtureDownloader("{\"data\":{"
                + "\"channels\":[{\"title\":\"Tech News\"},{\"title\":\"Gadgets\"}],"
                + "\"categories\":[{\"title\":\"Technology\"}]}}"));

        assertEquals(List.of("Tech News", "Gadgets", "Technology"),
                Rumble.getSuggestionExtractor().suggestionList("tech"));
    }

    @Test
    void skipsBlankTitlesAndDuplicates() throws Exception {
        NewPipe.init(new FixtureDownloader("{\"data\":{"
                + "\"channels\":[{\"title\":\"News\"},{\"title\":\" \"},{}],"
                + "\"categories\":[{\"title\":\"News\"},{\"title\":\"Politics\"}]}}"));

        assertEquals(List.of("News", "Politics"),
                Rumble.getSuggestionExtractor().suggestionList("news"));
    }

    @Test
    void rejectsMissingNullOrMalformedDataInsteadOfReturningEmptySuggestions() {
        for (final String body : List.of("{}", "{\"data\":null}", "{\"data\":[]}",
                "{\"data\":\"wrong\"}", "not json", "[]", "{} {}")) {
            NewPipe.init(new FixtureDownloader(body));
            assertThrows(ParsingException.class,
                    () -> Rumble.getSuggestionExtractor().suggestionList("news"), body);
        }
    }

    @Test
    void missingOptionalArraysAndValidEmptyArraysAreSuccessfulEmptyResults() throws Exception {
        for (final String body : List.of("{\"data\":{}}",
                "{\"errors\":[],\"data\":{\"channels\":[],\"categories\":[]}}",
                "{\"data\":{\"channels\":null}}")) {
            NewPipe.init(new FixtureDownloader(body));
            assertTrue(Rumble.getSuggestionExtractor().suggestionList("news").isEmpty());
        }
        NewPipe.init(new FixtureDownloader("{\"data\":{\"channels\":{}}}"));
        assertThrows(ParsingException.class,
                () -> Rumble.getSuggestionExtractor().suggestionList("news"));
    }

    @Test
    void apiErrorsDoNotBecomeEmptySuggestions() {
        NewPipe.init(new FixtureDownloader("{\"errors\":[{\"code\":0,"
                + "\"message\":\"Video not found\"}],\"data\":{}}"));
        final var error = assertThrows(ContentNotAvailableException.class,
                () -> Rumble.getSuggestionExtractor().suggestionList("news"));
        assertTrue(error.getMessage().contains("Video not found"));
        assertTrue(error.getMessage().contains("0"));
    }

    @Test
    void blankQueriesDoNotIssueRequests() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader("not json");
        NewPipe.init(downloader);
        for (final String query : new String[]{null, "", " \t "}) {
            assertTrue(Rumble.getSuggestionExtractor().suggestionList(query).isEmpty());
        }
        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    void encodesQueryAndSendsJsonAcceptWithoutBrowseCookiesOrIdentityOverride() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader("{\"data\":{}}");
        NewPipe.init(downloader);
        Rumble.getSuggestionExtractor().suggestionList("space & science/世界");
        final Request request = downloader.requests.get(0);
        assertEquals("https://rumble.com/service.php?name=search.autocomplete&api=8"
                + "&query=space+%26+science%2F%E4%B8%96%E7%95%8C", request.url());
        assertEquals(List.of("application/json"), request.headers().get("Accept"));
        assertFalse(request.headers().keySet().stream().anyMatch(key ->
                key.equalsIgnoreCase("Cookie") || key.equalsIgnoreCase("User-Agent")));
    }

    private static final class FixtureDownloader extends Downloader {
        private final String body;
        private final List<Request> requests = new ArrayList<>();

        private FixtureDownloader(final String body) {
            this.body = body;
        }

        @Override
        public Response execute(@Nonnull final Request request) {
            requests.add(request);
            return new Response(200, "Fixture", Map.of(), body, request.url());
        }
    }
}
