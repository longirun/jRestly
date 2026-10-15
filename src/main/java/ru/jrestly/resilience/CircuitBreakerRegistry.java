package ru.jrestly.resilience;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry for sharing and managing {@link CircuitBreaker} instances by name.
 */
public class CircuitBreakerRegistry {

    private final ConcurrentMap<String, CircuitBreaker> registry = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig defaultConfig;

    /**
     * Constructs a registry with default circuit breaker configuration.
     */
    public CircuitBreakerRegistry() {
        this(CircuitBreakerConfig.ofDefaults());
    }

    /**
     * Constructs a registry with custom default configuration.
     *
     * @param defaultConfig default configuration for newly created circuit breakers
     */
    public CircuitBreakerRegistry(CircuitBreakerConfig defaultConfig) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
    }

    /**
     * Returns a new registry with default settings.
     *
     * @return a new registry
     */
    public static CircuitBreakerRegistry ofDefaults() {
        return new CircuitBreakerRegistry();
    }

    /**
     * Obtains or creates a {@link CircuitBreaker} using the registry default configuration.
     *
     * @param name name of the circuit breaker
     * @return the circuit breaker
     */
    public CircuitBreaker getOrCreate(String name) {
        return getOrCreate(name, defaultConfig);
    }

    /**
     * Obtains or creates a {@link CircuitBreaker} using the specified configuration.
     *
     * @param name name of the circuit breaker
     * @param config configuration for creation if absent
     * @return the circuit breaker
     */
    public CircuitBreaker getOrCreate(String name, CircuitBreakerConfig config) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(config, "config must not be null");
        return registry.computeIfAbsent(name, n -> CircuitBreaker.of(n, config));
    }

    /**
     * Returns the circuit breaker associated with the given name, or {@code null} if absent.
     *
     * @param name name of the circuit breaker
     * @return the circuit breaker or {@code null}
     */
    public CircuitBreaker get(String name) {
        return registry.get(name);
    }
}
