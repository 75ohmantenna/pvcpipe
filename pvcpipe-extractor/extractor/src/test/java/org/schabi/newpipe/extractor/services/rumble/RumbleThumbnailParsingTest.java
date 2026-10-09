package org.schabi.newpipe.extractor.services.rumble;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.exceptions.ParsingException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class RumbleThumbnailParsingTest {
    @Test
    void missingIdentifierDoesNotUseAnotherThumbnail() {
        final ParsingException error = assertThrows(ParsingException.class,
                () -> RumbleParsingHelper.extractThumbnail(
                        Jsoup.parse("<style>.other { background-image: url(other.jpg); }</style>"),
                        "user-image--avatar", () -> "missing-thumbnail-marker"));

        assertTrue(error.getMessage().contains("missing-thumbnail-marker"));
    }

    @Test
    void identifierExtractionPreservesCause() {
        final IllegalStateException cause = new IllegalStateException("invalid image metadata");
        final ParsingException error = assertThrows(ParsingException.class,
                () -> RumbleParsingHelper.extractThumbnail(Jsoup.parse(""),
                        "user-image--avatar", () -> { throw cause; }));

        assertEquals("Could not extract thumbnail identifier", error.getMessage());
        assertSame(cause, error.getCause());
    }
}
