package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("offline")
class YoutubeSignatureTimestampTest {
    @BeforeEach
    void setUp() {
        YoutubeJavaScriptPlayerManager.clearAllCaches();
    }

    @AfterEach
    void tearDown() {
        YoutubeJavaScriptPlayerManager.clearAllCaches();
    }

    @Test
    void overflowingTimestampThrowsOnEveryCall() throws Exception {
        final Field playerCode = YoutubeJavaScriptPlayerManager.class
                .getDeclaredField("cachedJavaScriptPlayerCode");
        playerCode.setAccessible(true);
        playerCode.set(null, "var player={signatureTimestamp:2147483648};");

        final ParsingException first = assertThrows(ParsingException.class,
                () -> YoutubeJavaScriptPlayerManager.getSignatureTimestamp("unused"));
        assertEquals("Could not convert signature timestamp to a number", first.getMessage());
        assertInstanceOf(NumberFormatException.class, first.getCause());
        final ParsingException second = assertThrows(ParsingException.class,
                () -> YoutubeJavaScriptPlayerManager.getSignatureTimestamp("unused"));
        assertSame(first, second);
    }
}
