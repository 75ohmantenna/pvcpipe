package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class OdyseeSuggestionExtractor extends SuggestionExtractor {

    private static final int SUGGESTION_COUNT = 10;
    private static final int MIN_QUERY_LENGTH = 2;

    public OdyseeSuggestionExtractor(final StreamingService service) {
        super(service);
    }

    /**
     * Odysee has no service that completes a query. What the website suggests while typing are
     * the titles of the first search results, and this does the same.
     */
    @Override
    public List<String> suggestionList(final String query)
            throws IOException, ExtractionException {
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        final List<String> suggestions = new ArrayList<>();
        for (final Object hit : OdyseeApi.search("s=" + Utils.encodeUrlUtf8(query.trim())
                + "&size=" + SUGGESTION_COUNT + "&from=0&nsfw=false&resolve=true")) {
            if (hit instanceof JsonObject) {
                final String title = ((JsonObject) hit).getString("title", "").trim();
                if (!title.isEmpty() && !suggestions.contains(title)) {
                    suggestions.add(title);
                }
            }
        }
        return suggestions;
    }
}
