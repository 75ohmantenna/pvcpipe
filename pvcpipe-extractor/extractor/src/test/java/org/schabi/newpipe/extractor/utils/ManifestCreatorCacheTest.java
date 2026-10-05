package org.schabi.newpipe.extractor.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class ManifestCreatorCacheTest {
    @Test
    void basicMaximumSizeAndResetTest() {
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();

        // 30 elements set -> cache resized to 23 -> 5 new elements set to the cache -> 28
        cache.setMaximumSize(30);
        setCacheContent(cache);
        assertEquals(28, cache.size(),
                "Wrong cache size with default clear factor and 30 as the maximum size");
        cache.reset();

        assertEquals(0, cache.size(),
                "The cache has been not cleared after a reset call (wrong cache size)");
        assertEquals(ManifestCreatorCache.DEFAULT_MAXIMUM_SIZE, cache.getMaximumSize(),
                "Wrong maximum size after cache reset");
        assertEquals(ManifestCreatorCache.DEFAULT_CLEAR_FACTOR, cache.getClearFactor(),
                "Wrong clear factor after cache reset");
    }

    @Test
    void maximumSizeAndClearFactorSettersAndResettersTest() {
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();
        cache.setMaximumSize(20);
        cache.setClearFactor(0.5);

        setCacheContent(cache);
        // 30 elements set -> cache resized to 10 -> 5 new elements set to the cache -> 15
        assertEquals(15, cache.size(),
                "Wrong cache size with 0.5 as the clear factor and 20 as the maximum size");

        // Clear factor and maximum size getters tests
        assertEquals(0.5, cache.getClearFactor(),
                "Wrong clear factor gotten from clear factor getter");
        assertEquals(20, cache.getMaximumSize(),
                "Wrong maximum cache size gotten from maximum size getter");

        // Resetters tests
        cache.resetMaximumSize();
        assertEquals(ManifestCreatorCache.DEFAULT_MAXIMUM_SIZE, cache.getMaximumSize(),
                "Wrong maximum cache size gotten from maximum size getter after maximum size "
                        + "resetter call");

        cache.resetClearFactor();
        assertEquals(ManifestCreatorCache.DEFAULT_CLEAR_FACTOR, cache.getClearFactor(),
                "Wrong clear factor gotten from clear factor getter after clear factor resetter "
                        + "call");
    }

    @Test
    void capacityOneNeverGrowsBeyondItsLimit() {
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();
        cache.setMaximumSize(1);
        for (int i = 0; i < 20; i++) {
            final String key = "key" + i;
            assertNull(cache.put(key, "value" + i));
            assertEquals(1, cache.size());
            assertEquals("value" + i, cache.get(key).getSecond());
            if (i > 0) {
                assertFalse(cache.containsKey("key" + (i - 1)));
            }
        }
    }

    @Test
    void roundedEvictionAlwaysReservesAnInsertionSlot() {
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();
        cache.setMaximumSize(2);
        cache.setClearFactor(0.99);
        cache.put("first", "one");
        cache.put("second", "two");
        cache.put("third", "three");
        assertEquals(2, cache.size());
        assertFalse(cache.containsKey("first"));
        assertEquals("two", cache.get("second").getSecond());
        assertEquals("three", cache.get("third").getSecond());
    }

    @Test
    void replacementRefreshesAgeWithoutDuplicateRanks() {
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();
        cache.setMaximumSize(3);
        cache.setClearFactor(0.5);
        cache.put("first", "one");
        cache.put("second", "two");
        cache.put("third", "three");
        assertEquals("one", cache.put("first", "updated"));
        assertEquals(3, cache.size());
        cache.put("fourth", "four");
        cache.put("fifth", "five");
        assertEquals(3, cache.size());
        assertEquals("updated", cache.get("first").getSecond());
        assertNull(cache.get("second"));
        assertNull(cache.get("third"));
        assertEquals(0, cache.get("first").getFirst());
        assertEquals(1, cache.get("fourth").getFirst());
        assertEquals(2, cache.get("fifth").getFirst());
    }

    @Test
    void shrinkingCapacityKeepsNewestEntriesWithinTheLimit() {
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();
        cache.setClearFactor(0.99);
        for (int i = 0; i < 5; i++) {
            cache.put("key" + i, "value" + i);
        }
        cache.setMaximumSize(2);
        assertEquals(2, cache.size());
        assertEquals("value3", cache.get("key3").getSecond());
        assertEquals("value4", cache.get("key4").getSecond());
        cache.setMaximumSize(1);
        assertEquals(1, cache.size());
        assertEquals("value4", cache.get("key4").getSecond());
    }

    @Test
    void configurationRejectsInvalidValuesWithoutChangingSettings() {
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();
        for (final double factor : new double[]{Double.NaN, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, 0, 1, -1}) {
            assertThrows(IllegalArgumentException.class, () -> cache.setClearFactor(factor));
            assertEquals(ManifestCreatorCache.DEFAULT_CLEAR_FACTOR, cache.getClearFactor());
        }
        for (final int size : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> cache.setMaximumSize(size));
            assertEquals(ManifestCreatorCache.DEFAULT_MAXIMUM_SIZE, cache.getMaximumSize());
        }
    }

    @Test
    void nullValuesAndReplacementReturnValuesRemainSupported() {
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();
        cache.setMaximumSize(1);
        assertNull(cache.put("key", null));
        assertTrue(cache.containsKey("key"));
        assertNull(cache.put("key", "value"));
        assertEquals("value", cache.put("key", null));
        assertNull(cache.get("key").getSecond());
        assertEquals(1, cache.size());
        assertThrows(NullPointerException.class, () -> cache.put(null, "other"));
        assertEquals(1, cache.size());
    }

    @Test
    void concurrentWritesClearsAndConfigurationChangesKeepTheBound() throws Exception {
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();
        cache.setMaximumSize(7);
        final ExecutorService executor = Executors.newFixedThreadPool(6);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<?>> work = new ArrayList<>();
        try {
            for (int worker = 0; worker < 6; worker++) {
                final int id = worker;
                work.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 300; i++) {
                        if (id == 0) {
                            cache.setMaximumSize(i % 2 == 0 ? 1 : 7);
                            cache.setClearFactor(i % 2 == 0 ? 0.99 : 0.5);
                        } else if (id == 1 && i % 5 == 0) {
                            cache.clear();
                        }
                        cache.put(id + ":" + i % 15, "value" + i);
                        synchronized (cache) {
                            assertTrue(cache.size() <= cache.getMaximumSize());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (final Future<?> task : work) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void serializationKeepsTheLegacyIdentifierAndCacheBehavior() throws Exception {
        assertEquals(7144118292723300363L,
                ObjectStreamClass.lookup(ManifestCreatorCache.class).getSerialVersionUID());
        final ManifestCreatorCache<String, String> cache = new ManifestCreatorCache<>();
        cache.setMaximumSize(2);
        cache.setClearFactor(0.99);
        cache.put("first", "one");
        cache.put("second", "two");
        cache.put("first", "updated");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(cache);
        }
        final ManifestCreatorCache<String, String> restored;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            @SuppressWarnings("unchecked")
            final ManifestCreatorCache<String, String> decoded =
                    (ManifestCreatorCache<String, String>) input.readObject();
            restored = decoded;
        }
        assertEquals(cache.get("first"), restored.get("first"));
        assertEquals(cache.get("second"), restored.get("second"));
        assertEquals(cache.size(), restored.size());
        assertEquals(cache.getMaximumSize(), restored.getMaximumSize());
        assertEquals(cache.getClearFactor(), restored.getClearFactor());
        restored.put("third", "three");
        assertEquals(2, restored.size());
        assertNull(restored.get("second"));
        assertEquals("updated", restored.get("first").getSecond());
    }

    @Test
    void readsCacheSerializedByTheOriginalClass() throws Exception {
        // Fixture created with the pre-fix class on master at 5143eaf49:
        // maximumSize=3, clearFactor=0.5, put(first,one), put(second,two), put(third,three).
        final ManifestCreatorCache<String, String> cache;
        try (ObjectInputStream input = new ObjectInputStream(
                Objects.requireNonNull(getClass().getResourceAsStream("/manifest-cache-legacy.ser")))) {
            @SuppressWarnings("unchecked")
            final ManifestCreatorCache<String, String> restored =
                    (ManifestCreatorCache<String, String>) input.readObject();
            cache = restored;
        }
        assertEquals(3, cache.size());
        assertEquals(3, cache.getMaximumSize());
        assertEquals(0.5, cache.getClearFactor());
        assertEquals("one", cache.get("first").getSecond());
        cache.put("fourth", "four");
        assertEquals(3, cache.size());
        assertNull(cache.get("first"));
        assertEquals("two", cache.get("second").getSecond());
        assertEquals("three", cache.get("third").getSecond());
        assertEquals("four", cache.get("fourth").getSecond());
    }

    @Test
    void restoresTheBoundOfAnOverfullLegacyCache() throws Exception {
        // Same pre-fix fixture generator, but maximumSize=1 incorrectly admitted three entries.
        final ManifestCreatorCache<String, String> cache;
        try (ObjectInputStream input = new ObjectInputStream(Objects.requireNonNull(
                getClass().getResourceAsStream("/manifest-cache-legacy-overflow.ser")))) {
            @SuppressWarnings("unchecked")
            final ManifestCreatorCache<String, String> restored =
                    (ManifestCreatorCache<String, String>) input.readObject();
            cache = restored;
        }
        assertEquals(1, cache.size());
        assertEquals("three", cache.get("third").getSecond());
        assertEquals(0, cache.get("third").getFirst());
        assertNull(cache.get("first"));
        assertNull(cache.get("second"));
        cache.put("fourth", "four");
        assertEquals(1, cache.size());
        assertEquals("four", cache.get("fourth").getSecond());
    }

    /**
     * Adds sample strings to the provided manifest creator cache, in order to test clear factor and
     * maximum size.
     * @param cache the cache to fill with some data
     */
    private static void setCacheContent(final ManifestCreatorCache<String, String> cache) {
        int i = 0;
        while (i < 26) {
            cache.put(String.valueOf((char) ('a' + i)), "V");
            ++i;
        }

        i = 0;
        while (i < 9) {
            cache.put("a" + (char) ('a' + i), "V");
            ++i;
        }
    }
}
