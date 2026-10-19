package ru.jrestly.engine.netty;

import ru.jrestly.ModuleInfo;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineProvider;

/**
 * Service provider interface (SPI) for discovering and providing pluggable {@link HttpEngine}
 * implementations via {@link java.util.ServiceLoader}.
 *
 * <p>Registers the Netty adapter (ADR-0070): when {@code jrestly-engine-netty} is
 * on the classpath, {@link HttpEngineProvider} discovery picks the Netty transport
 * automatically; users who want to keep the zero-dependency default can pass an explicit
 * engine to the client builder.
 */
public final class NettyHttpEngineProvider implements HttpEngineProvider {

    /**
     * Netty's key class: its presence on the classpath is the availability probe.
     */
    private static final String NETTY_CLASS = "io.netty.channel.Channel";

    /**
     * The priority of the Netty engine adapter (ADR-0070): declared at 60 (preferred over
     * standard classic transports when explicitly included on the classpath).
     */
    public static final int PRIORITY = 60;

    /**
     * Creates the provider instance; {@link java.util.ServiceLoader} instantiates it
     * through the {@code META-INF/services} registration (ADR-0070).
     */
    public NettyHttpEngineProvider() {
    }

    /**
     * Returns whether this engine provider is available in the current runtime environment
     * (i.e. Netty classes are present on the classpath).
     *
     * @return {@code true} if this provider can create engines
     */
    @Override
    public boolean isAvailable() {
        try {
            Class.forName(NETTY_CLASS, false, getClass().getClassLoader());
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
        return NettyHttpEngine.create(moduleInfo);
    }

    /**
     * Returns the priority of this provider: the resolver considers the
     * highest-priority available provider first (ADR-0052/0070).
     *
     * @return the priority value, higher values are preferred
     */
    @Override
    public int priority() {
        return PRIORITY;
    }
}
