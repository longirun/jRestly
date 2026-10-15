package ru.jrestly.cookie;

import java.net.HttpCookie;
import java.util.Collection;
import java.util.List;

/**
 * Service Provider Interface (SPI) for persisting and loading HTTP cookies across client sessions
 * or JVM restarts (ADR-0039).
 * <p>
 * Implementations can store cookies to files, databases, key-value stores, or secure vaults.
 * All methods should be thread-safe.
 */
public interface CookiePersister {

    /**
     * Loads previously persisted cookies into memory.
     *
     * @return an unmodifiable or modifiable list of loaded cookies, never {@code null}
     */
    List<HttpCookie> loadAll();

    /**
     * Persists the given active cookies to the persistent medium.
     *
     * @param cookies current active cookies to persist, not {@code null}
     */
    void saveAll(Collection<HttpCookie> cookies);

    /**
     * Clears all persisted cookies from the storage medium.
     */
    void clear();
}
