package org.schabi.newpipe.player.cast;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Receiver input: exactly one remotely fetchable URL or self-contained manifest.
 *
 * @param mimeType media or manifest MIME type
 * @param url receiver-fetchable URL, or null for inline content
 * @param content inline content, or null for a URL
 * @param headers request headers, snapshotted at construction
 */
public record CastMedia(String mimeType, @Nullable String url, @Nullable String content,
                        Map<String, String> headers) {
    public CastMedia {
        Objects.requireNonNull(mimeType);
        if (mimeType.isBlank() || (url == null) == (content == null)
                || (url != null && url.isBlank()) || (content != null && content.isBlank())) {
            throw new IllegalArgumentException("Expected a MIME type and exactly one URL/content");
        }
        headers = Map.copyOf(headers);
    }
}
