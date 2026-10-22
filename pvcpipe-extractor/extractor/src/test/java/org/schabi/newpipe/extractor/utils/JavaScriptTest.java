package org.schabi.newpipe.extractor.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("offline")
class JavaScriptTest {
    @Test
    void executesStringTransformationWithDirectRhinoApi() {
        final String function = "function decipher(value) {"
                + " return value.split('').reverse().join(''); }";
        JavaScript.compileOrThrow(function);
        assertEquals("321cba", JavaScript.run(function, "decipher", "abc123"));
    }
}
