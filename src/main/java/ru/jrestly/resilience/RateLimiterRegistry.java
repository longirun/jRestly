package ru.jrestly.resilience;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry for sharing and managing {@link RateLimiter} instances by name.
 */
public class RateLimiterRegistry {

    private final ConcurrentMap<String, RateLimiter> registry = new ConcurrentHashMap<>();
    private final RateLimiterConfig defaultConfig;

    /**
     * Constructs a registry with default rate limiter configuration.
     */
    public RateLimiterRegistry() {
        this(RateLimiterConfig.ofDefaults());
    }

    /**
     * Constructs a registry with the given default configuration.
     *
     * @param defaultConfig default configuration
     */
    public RateLimiterRegistry(RateLimiterConfig defaultConfig) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
    }

    /**
     * Creates a new registry with default settings.
     *
     * @return a new registry
     */
    public static RateLimiterRegistry ofDefaults() {
        return new RateLimiterRegistry();
    }

    /**
     * Obtains or creates a {@link RateLimiter} using default configuration.
     *
     * @param name name of the rate limiter
     * @return the rate limiter
     */
    public RateLimiter getOrCreate(String name) {
        return getOrCreate(name, defaultConfig);
    }

    /**
     * Obtains or creates a {@link RateLimiter} using the specified configuration.
     *
     * @param name name of the rate limiter
     * @param config configuration for creation if absent
     * @return the rate limiter
     */
    public RateLimiter getOrCreate(String name, RateLimiterConfig config) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(config, "config must not be null");
        return registry.computeIfAbsent(name, n -> RateLimiter.of(n, config));
    }

    /**
     * Returns the rate limiter associated with the given name, or {@code null} if absent.
     *
     * @param name name of the rate limiter
     * @return the rate limiter or {@code null}
     */
    public RateLimiter get(String name) {
        return registry.get(name);
    }
}
