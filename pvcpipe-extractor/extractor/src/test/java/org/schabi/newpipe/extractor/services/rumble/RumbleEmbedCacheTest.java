package org.schabi.newpipe.extractor.services.rumble;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("offline")
class RumbleEmbedCacheTest {

    @Test
    void reusesCachedEmbedId() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final String url = "https://rumble.com/cache-reuse-test.html";

        assertEquals("cacheid", RumbleParsingHelper.getEmbedVideoId(url,
                () -> embedPage("vcacheid", calls)));
        assertEquals("cacheid", RumbleParsingHelper.getEmbedVideoId(url,
                () -> embedPage("vdifferent", calls)));
        assertEquals(1, calls.get());
    }

    @Test
    void evictsLeastRecentlyUsedEmbedId() throws Exception {
        final AtomicInteger firstCalls = new AtomicInteger();
        final String prefix = "https://rumble.com/cache-bound-test-";
        RumbleParsingHelper.getEmbedVideoId(prefix + 0,
                () -> embedPage("vfirstid", firstCalls));

        for (int i = 1; i <= 64; i++) {
            final int index = i;
            RumbleParsingHelper.getEmbedVideoId(prefix + index,
                    () -> embedPage("vid" + index, new AtomicInteger()));
        }

        assertEquals("replacement", RumbleParsingHelper.getEmbedVideoId(prefix + 0,
                () -> embedPage("vreplacement", firstCalls)));
        assertEquals(2, firstCalls.get());
    }

    @Test
    void extractsInlinePlayerIdsWhenThereIsNoIframe() throws Exception {
        assertEquals("inline123", RumbleParsingHelper.getEmbedVideoId(
                "https://rumble.com/inline-player-test.html",
                () -> "<script>Rumble(\"play\", {video: 'vinline123', div: 'player'});</script>"));
    }

    private static String embedPage(final String id, final AtomicInteger calls) {
        calls.incrementAndGet();
        return "<iframe src=\"https://rumble.com/embed/" + id + "\"></iframe>";
    }
}
