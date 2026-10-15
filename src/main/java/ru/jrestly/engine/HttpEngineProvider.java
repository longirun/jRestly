package ru.jrestly.engine;

import ru.jrestly.ModuleInfo;

/**
 * Service provider interface (SPI) for discovering and providing pluggable {@link HttpEngine}
 * implementations via {@link java.util.ServiceLoader}.
 */
public interface HttpEngineProvider {

    /**
     * Returns whether this engine provider is available in the current runtime environment
     * (e.g. required third-party client classes are present on the classpath).
     *
     * @return {@code true} if this provider can create engines
     */
    boolean isAvailable();

    /**
     * Creates a new {@link HttpEngine} configured according to the provided module settings.
     *
     * @param moduleInfo the client module configuration
     * @return the configured HTTP engine
     */
    HttpEngine createEngine(ModuleInfo moduleInfo);
}
