package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.rumble.RumbleApi;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RumbleSuggestionExtractor extends SuggestionExtractor {

    public RumbleSuggestionExtractor(final StreamingService service) {
        super(service);
    }

    @Override
    public List<String> suggestionList(final String query)
            throws IOException, ExtractionException {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("api", "8");
        parameters.put("query", query);
        final JsonObject data = RumbleApi.fetchData(NewPipe.getDownloader(),
                RumbleApi.serviceUrl("search.autocomplete", parameters),
                getExtractorLocalization());
        final Set<String> suggestions = new LinkedHashSet<>();
        addTitles(optionalArray(data, "channels"), suggestions);
        addTitles(optionalArray(data, "categories"), suggestions);
        return new ArrayList<>(suggestions);
    }

    private static JsonArray optionalArray(final JsonObject data, final String key)
            throws ParsingException {
        return data.get(key) == null ? null : JsonUtils.getArray(data, key);
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
