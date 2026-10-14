package org.schabi.newpipe.extractor.services.odysee;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Memory-only counters with access-order eviction and expiry measured from insertion.
 * Entries are keyed by endpoint and claim id. A clear invalidates writes from earlier requests.
 */
final class OdyseeCountCache {
    private final int capacity;
    private final long lifetime;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long generation;

    OdyseeCountCache(final int capacity, final long lifetime, final LongSupplier clock) {
        this.capacity = capacity;
        this.lifetime = lifetime;
        this.clock = clock;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized void clear() {
        entries.clear();
        generation++;
    }

    synchronized Map<String, Long> get(final String endpoint, final Collection<String> ids) {
        final Map<String, Long> result = new HashMap<>();
        final long now = clock.getAsLong();
        for (final String id : ids) {
            final String key = endpoint + ":" + id;
            final Entry entry = entries.get(key);
            if (entry != null) {
                if (now - entry.created >= 0 && now - entry.created < lifetime) {
                    result.put(id, entry.count);
                } else {
                    entries.remove(key);
                }
            }
        }
        return result;
    }

    synchronized void put(final String endpoint, final Map<String, Long> counts,
                          final long startedIn) {
        if (startedIn != generation) {
            return;
        }
        final long now = clock.getAsLong();
        counts.forEach((id, count) -> {
            if (count >= 0) {
                entries.put(endpoint + ":" + id, new Entry(count, now));
            }
        });
        while (entries.size() > capacity) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    private static final class Entry {
        private final long count;
        private final long created;

        private Entry(final long count, final long created) {
            this.count = count;
            this.created = created;
        }
    }
}
