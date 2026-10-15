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

    /**
     * Returns the priority of this provider: the resolver considers the
     * highest-priority available provider first (ADR-0058).
     *
     * <p>Providers with equal priority are considered in the order reported by
     * {@link java.util.ServiceLoader}. The default priority is {@code 0}.
     *
     * @return the priority value, higher values are preferred
     */
    default int priority() {
        return 0;
    }
}
