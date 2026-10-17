package ru.jrestly.cache;

/**
 * Factory and utility methods for {@link HttpCache} instances.
 */
public final class HttpCaches {

    private HttpCaches() {
    }

    /**
     * Creates a new in-memory LRU HTTP cache with the default capacity of 100 entries.
     *
     * @return in-memory HTTP cache
     */
    public static HttpCache inMemory() {
        return new InMemoryHttpCache();
    }

    /**
     * Creates a new in-memory LRU HTTP cache with the specified capacity.
     *
     * @param maxEntries maximum cached entries before LRU eviction
     * @return in-memory HTTP cache
     * @throws IllegalArgumentException if {@code maxEntries <= 0}
     */
    public static HttpCache inMemory(int maxEntries) {
        return new InMemoryHttpCache(maxEntries);
    }
}
