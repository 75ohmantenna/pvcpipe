package org.schabi.newpipe.extractor.utils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link Serializable serializable} cache class used by the extractor to cache manifests
 * generated with extractor's manifests generators.
 *
 * <p>
 * Cache operations are synchronized so insertion, eviction, and configuration changes are atomic.
 * </p>
 *
 * @param <K> the type of cache keys, which must be {@link Serializable serializable}
 * @param <V> the type of the second element of {@link Pair pairs} used as values of the cache,
 *            which must be {@link Serializable serializable}
 */
public final class ManifestCreatorCache<K extends Serializable, V extends Serializable>
        implements Serializable {

    // Preserve compatibility with caches serialized before synchronized methods were added.
    private static final long serialVersionUID = 7144118292723300363L;

    /**
     * The default maximum size of a manifest cache.
     */
    public static final int DEFAULT_MAXIMUM_SIZE = Integer.MAX_VALUE;

    /**
     * The default clear factor of a manifest cache.
     */
    public static final double DEFAULT_CLEAR_FACTOR = 0.75;

    /**
     * The {@link ConcurrentHashMap} used internally as the cache of manifests.
     */
    private final ConcurrentHashMap<K, Pair<Integer, V>> concurrentHashMap;

    /**
     * The maximum size of the cache.
     *
     * <p>
     * The default value is {@link #DEFAULT_MAXIMUM_SIZE}.
     * </p>
     */
    private int maximumSize = DEFAULT_MAXIMUM_SIZE;

    /**
     * The clear factor of the cache, which is a double between {@code 0} and {@code 1} excluded.
     *
     * <p>
     * The default value is {@link #DEFAULT_CLEAR_FACTOR}.
     * </p>
     */
    private double clearFactor = DEFAULT_CLEAR_FACTOR;

    /**
     * Creates a new {@link ManifestCreatorCache}.
     */
    public ManifestCreatorCache() {
        concurrentHashMap = new ConcurrentHashMap<>();
    }

    /**
     * Tests if the specified key is in the cache.
     *
     * @param key the key to test its presence in the cache
     * @return {@code true} if the key is in the cache, {@code false} otherwise.
     */
    public synchronized boolean containsKey(final K key) {
        return concurrentHashMap.containsKey(key);
    }

    /**
     * Returns the value to which the specified key is mapped, or {@code null} if the cache
     * contains no mapping for the key.
     *
     * @param key the key to which getting its value
     * @return the value to which the specified key is mapped, or {@code null}
     */
    @Nullable
    public synchronized Pair<Integer, V> get(final K key) {
        return concurrentHashMap.get(key);
    }

    /**
     * Adds a new element to the cache.
     *
     * <p>
     * If the cache limit is reached, oldest elements will be cleared first using the clear factor
     * and the maximum size.
     * </p>
     *
     * @param key   the key to put
     * @param value the value to associate to the key
     *
     * @return the previous value associated with the key, or {@code null} if there was no mapping
     * for the key (note that a null return can also indicate that the cache previously associated
     * {@code null} with the key).
     */
    @Nullable
    public synchronized V put(final K key, final V value) {
        final Pair<Integer, V> previous = concurrentHashMap.get(key);
        if (previous != null) {
            // Replacing an entry makes it newest without leaving gaps or duplicate ranks.
            final int previousRank = previous.getFirst();
            concurrentHashMap.values().forEach(entry -> {
                if (entry.getFirst() > previousRank) {
                    entry.setFirst(entry.getFirst() - 1);
                }
            });
            concurrentHashMap.put(key, new Pair<>(concurrentHashMap.size() - 1, value));
            return previous.getSecond();
        }

        if (concurrentHashMap.size() >= maximumSize) {
            // Reserve a slot even when rounding would retain every existing entry.
            final int retainedSize = Math.min(maximumSize - 1,
                    Math.max(1, (int) Math.round(maximumSize * clearFactor)));
            keepNewestEntries(retainedSize);
        }
        concurrentHashMap.put(key, new Pair<>(concurrentHashMap.size(), value));
        return null;
    }

    /**
     * Clears the cached manifests.
     *
     * <p>
     * The cache will be empty after this method is called.
     * </p>
     */
    public synchronized void clear() {
        concurrentHashMap.clear();
    }

    /**
     * Resets the cache.
     *
     * <p>
     * The cache will be empty and the clear factor and the maximum size will be reset to their
     * default values.
     * </p>
     *
     * @see #clear()
     * @see #resetClearFactor()
     * @see #resetMaximumSize()
     */
    public synchronized void reset() {
        clear();
        resetClearFactor();
        resetMaximumSize();
    }

    /**
     * @return the number of cached manifests in the cache
     */
    public synchronized int size() {
        return concurrentHashMap.size();
    }

    /**
     * @return the maximum size of the cache
     */
    public synchronized long getMaximumSize() {
        return maximumSize;
    }

    /**
     * Sets the maximum size of the cache.
     *
     * Reducing the maximum keeps at most the rounded clear-factor portion of the new maximum,
     * retaining at least one entry.
     *
     * @param maximumSize the new maximum size of the cache
     * @throws IllegalArgumentException if {@code maximumSize} is less than or equal to 0
     */
    public synchronized void setMaximumSize(final int maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("Invalid maximum size");
        }

        if (maximumSize < this.maximumSize && !concurrentHashMap.isEmpty()) {
            final int newCacheSize = (int) Math.round(maximumSize * clearFactor);
            keepNewestEntries(newCacheSize != 0 ? newCacheSize : 1);
        }

        this.maximumSize = maximumSize;
    }

    /**
     * Resets the maximum size of the cache to its {@link #DEFAULT_MAXIMUM_SIZE default value}.
     */
    public synchronized void resetMaximumSize() {
        this.maximumSize = DEFAULT_MAXIMUM_SIZE;
    }

    /**
     * @return the current clear factor of the cache, used when the cache limit size is reached
     */
    public synchronized double getClearFactor() {
        return clearFactor;
    }

    /**
     * Sets the clear factor of the cache, used when the cache limit size is reached.
     *
     * <p>
     * The clear factor must be a double between {@code 0} excluded and {@code 1} excluded.
     * </p>
     *
     * <p>
     * Note that it will be only used the next time the cache size limit is reached.
     * </p>
     *
     * @param clearFactor the new clear factor of the cache
     * @throws IllegalArgumentException if the clear factor passed a parameter is invalid
     */
    public synchronized void setClearFactor(final double clearFactor) {
        if (Double.isNaN(clearFactor) || clearFactor <= 0 || clearFactor >= 1) {
            throw new IllegalArgumentException("Invalid clear factor");
        }

        this.clearFactor = clearFactor;
    }

    /**
     * Resets the clear factor to its {@link #DEFAULT_CLEAR_FACTOR default value}.
     */
    public synchronized void resetClearFactor() {
        this.clearFactor = DEFAULT_CLEAR_FACTOR;
    }

    @Nonnull
    @Override
    public synchronized String toString() {
        return "ManifestCreatorCache[clearFactor=" + clearFactor + ", maximumSize=" + maximumSize
                + ", concurrentHashMap=" + concurrentHashMap + "]";
    }

    private synchronized void writeObject(final ObjectOutputStream output) throws IOException {
        output.defaultWriteObject();
    }

    private void readObject(final ObjectInputStream input)
            throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        if (concurrentHashMap == null || maximumSize <= 0 || Double.isNaN(clearFactor)
                || clearFactor <= 0 || clearFactor >= 1) {
            throw new InvalidObjectException("Invalid manifest cache settings");
        }
        for (final Pair<Integer, V> value : concurrentHashMap.values()) {
            if (value.getFirst() == null) {
                throw new InvalidObjectException("Invalid manifest cache rank");
            }
        }
        // Normalize legacy ranks, including gaps or duplicates from repeated replacements.
        keepNewestEntries(maximumSize);
    }

    /**
     * Keeps the newest entries and renumbers their insertion ranks contiguously.
     *
     * @param newLimit the number of entries to retain
     */
    private void keepNewestEntries(final int newLimit) {
        final ArrayList<Map.Entry<K, Pair<Integer, V>>> entries =
                new ArrayList<>(concurrentHashMap.entrySet());
        entries.sort(Comparator.comparingInt(entry -> entry.getValue().getFirst()));
        final int removeCount = Math.max(0, entries.size() - newLimit);
        for (int i = 0; i < entries.size(); i++) {
            final Map.Entry<K, Pair<Integer, V>> entry = entries.get(i);
            if (i < removeCount) {
                concurrentHashMap.remove(entry.getKey());
            } else {
                entry.getValue().setFirst(i - removeCount);
            }
        }
    }
}
