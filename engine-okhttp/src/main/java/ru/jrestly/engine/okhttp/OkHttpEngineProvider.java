package ru.jrestly.engine.okhttp;

import ru.jrestly.ModuleInfo;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineProvider;

/**
 * Service provider interface (SPI) for discovering and providing pluggable {@link HttpEngine}
 * implementations via {@link java.util.ServiceLoader}.
 *
 * <p>Registers the official OkHttp adapter (ADR-0058): when {@code jrestly-engine-okhttp} is
 * on the classpath, {@link HttpEngineProvider} discovery picks the OkHttp transport
 * automatically; users who want to keep the zero-dependency default can pass an explicit
 * engine to the client builder.
 */
public final class OkHttpEngineProvider implements HttpEngineProvider {

    /**
     * OkHttp's key class: its presence on the classpath is the availability probe.
     */
    private static final String OKHTTP_CLASS = "okhttp3.OkHttpClient";

    /**
     * The shared priority of the official engine adapters (ADR-0058): two official adapters on
     * the classpath resolve the tie explicitly via {@link ModuleInfo#getHttpEngine()}.
     */
    public static final int PRIORITY = 50;

    /**
     * Creates the provider instance; {@link java.util.ServiceLoader} instantiates it
     * through the {@code META-INF/services} registration (ADR-0058).
     */
    public OkHttpEngineProvider() {
    }

    /**
     * Returns whether this engine provider is available in the current runtime environment
     * (i.e. the OkHttp client classes are present on the classpath).
     *
     * @return {@code true} if this provider can create engines
     */
    @Override
    public boolean isAvailable() {
        try {
            Class.forName(OKHTTP_CLASS, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Creates a new {@link HttpEngine} configured according to the provided module settings.
     *
     * @param moduleInfo the client module configuration
     * @return the configured HTTP engine
     */
    @Override
    public HttpEngine createEngine(ModuleInfo moduleInfo) {
        return OkHttpEngine.create(moduleInfo);
    }

    /**
     * Returns the priority of this provider: the resolver considers the
     * highest-priority available provider first (ADR-0058).
     *
     * @return the priority value, higher values are preferred
     */
    @Override
    public int priority() {
        return PRIORITY;
    }
}
