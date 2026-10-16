package ru.jrestly.engine.apache5;

import ru.jrestly.ModuleInfo;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineProvider;

/**
 * Service provider for the official Apache HttpClient 5 adapter (ADR-0058): registers
 * {@link Apache5HttpEngine} with {@link java.util.ServiceLoader}, so a client build without an
 * explicit engine picks an available transport automatically. Users who want to keep the
 * zero-dependency default can pass an explicit engine to the client builder.
 */
public final class Apache5HttpEngineProvider implements HttpEngineProvider {

    /**
     * Apache HttpClient 5's key class: its presence on the classpath is the availability probe.
     */
    private static final String APACHE5_CLASS = "org.apache.hc.client5.http.impl.classic.CloseableHttpClient";

    /**
     * The shared priority of the official engine adapters (ADR-0058): two official adapters on
     * the classpath resolve the tie explicitly via {@link ModuleInfo#getHttpEngine()}.
     */
    public static final int PRIORITY = 50;

    /**
     * Creates the provider instance; {@link java.util.ServiceLoader} instantiates it
     * through the {@code META-INF/services} registration (ADR-0058).
     */
    public Apache5HttpEngineProvider() {
    }

    /**
     * Returns whether this engine provider is available in the current runtime environment
     * (i.e. the HttpClient 5 classes are present on the classpath).
     *
     * @return {@code true} if this provider can create engines
     */
    @Override
    public boolean isAvailable() {
        try {
            Class.forName(APACHE5_CLASS, false, getClass().getClassLoader());
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
        return Apache5HttpEngine.create(moduleInfo);
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
