package org.schabi.newpipe.extractor.services.bitchute.extractor;

import org.schabi.newpipe.extractor.StreamingService;
import com.grack.nanojson.JsonObject;
import org.jsoup.Jsoup;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteParserHelper;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;

public class BitchuteSuggestionExtractor extends SuggestionExtractor {

    private static final String AUTOCOMPLETE_URL =
            "https://api.bitchute.com/api/beta/search2/videos/autocomplete";

    public BitchuteSuggestionExtractor(final StreamingService service) {
        super(service);
    }

    @Override
    public List<String> suggestionList(final String query) throws IOException, ExtractionException {
        if (query == null || query.trim().length() < 2) {
            return Collections.emptyList();
        }
        final List<String> suggestions = new ArrayList<>();
        for (final Object entry : BitchuteParserHelper.callJsonArrayApi(JsonObject.builder()
                .value("query", query).value("offset", 0).value("limit", 10), AUTOCOMPLETE_URL)) {
            final String message = ((JsonObject) entry).getString("message", "");
            final String text = Jsoup.parse(message).text();
            if (!text.isEmpty() && !suggestions.contains(text)) {
                suggestions.add(text);
            }
        }
        return suggestions;
    }
}
