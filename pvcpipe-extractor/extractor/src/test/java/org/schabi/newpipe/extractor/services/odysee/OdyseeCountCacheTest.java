package org.schabi.newpipe.extractor.services.odysee;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@Tag("offline")
class OdyseeCountCacheTest {
    private final AtomicLong clock = new AtomicLong();
    private final OdyseeCountCache cache = new OdyseeCountCache(2, 10, clock::get);

    @Test
    void expiresWithoutExtendingLifetimeOnRead() {
        cache.put("views", Map.of("a", 0L), cache.generation());
        clock.set(9);
        assertEquals(Map.of("a", 0L), cache.get("views", List.of("a")));
        clock.set(10);
        assertTrue(cache.get("views", List.of("a")).isEmpty());
    }

    @Test
    void namespacesAndCapacityKeepCountsSeparateAndBounded() {
        cache.put("views", Map.of("a", 10L), cache.generation());
        cache.put("followers", Map.of("a", 20L), cache.generation());
        assertEquals(Map.of("a", 10L), cache.get("views", List.of("a")));
        cache.put("views", Map.of("b", 30L), cache.generation());
        assertTrue(cache.get("followers", List.of("a")).isEmpty());
        assertEquals(Map.of("a", 10L, "b", 30L), cache.get("views", List.of("a", "b")));
    }

    @Test
    void clearedCacheCannotBeRepopulatedByAnOldRequest() {
        final long generation = cache.generation();
        cache.clear();
        cache.put("views", Map.of("a", 10L), generation);
        assertTrue(cache.get("views", List.of("a")).isEmpty());
    }

    @Test
    void negativeCountsAreNotCachedAndReturnedMapsAreIndependent() {
        cache.put("views", Map.of("a", -1L, "b", 2L), cache.generation());
        final Map<String, Long> result = cache.get("views", List.of("a", "b"));
        assertEquals(Map.of("b", 2L), result);
        result.clear();
        assertEquals(Map.of("b", 2L), cache.get("views", List.of("b")));
    }
}
