package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class KickSuggestionExtractor extends SuggestionExtractor {
    public KickSuggestionExtractor(final StreamingService service) {
        super(service);
    }

    @Override
    public List<String> suggestionList(final String query) throws IOException, ExtractionException {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        final List<String> suggestions = new ArrayList<>();
        for (final Object value : KickApi.search(query.trim(), 10)) {
            if (value instanceof JsonObject) {
                final JsonObject channel = (JsonObject) value;
                final String name = KickApi.name(channel);
                if (!channel.getBoolean("is_banned") && !name.isEmpty()
                        && !suggestions.contains(name)) {
                    suggestions.add(name);
                    if (suggestions.size() == 10) {
                        break;
                    }
                }
            }
        }
        return suggestions;
    }
}
