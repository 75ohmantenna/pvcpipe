package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RumbleSuggestionExtractor extends SuggestionExtractor {

    private static final String AUTOCOMPLETE_PATH =
            "/service.php?name=search.autocomplete&api=8&query=";

    public RumbleSuggestionExtractor(final StreamingService service) {
        super(service);
    }

    @Override
    public List<String> suggestionList(final String query)
            throws IOException, ExtractionException {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        final String response = RumbleParsingHelper.fetchResponse(NewPipe.getDownloader(),
                        getService().getBaseUrl() + AUTOCOMPLETE_PATH + Utils.encodeUrlUtf8(query),
                        null, getExtractorLocalization())
                .responseBody();
        return parseSuggestions(response);
    }

    static List<String> parseSuggestions(final String response) throws ParsingException {
        try {
            final JsonObject data = JsonParser.object().from(response).getObject("data");
            if (data == null) {
                return List.of();
            }

            final Set<String> suggestions = new LinkedHashSet<>();
            addTitles(data.getArray("channels"), suggestions);
            addTitles(data.getArray("categories"), suggestions);
            return new ArrayList<>(suggestions);
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse Rumble autocomplete response", e);
        }
    }

    private static void addTitles(final JsonArray items, final Set<String> suggestions) {
        if (items == null) {
            return;
        }

        for (final Object item : items) {
            if (item instanceof JsonObject) {
                final String title = ((JsonObject) item).getString("title");
                if (title != null && !title.trim().isEmpty()) {
                    suggestions.add(title);
                }
            }
        }
    }
}
