package ru.jrestly.engine.netty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineProvider;
import ru.jrestly.engine.HttpEngineResolver;

import java.util.Iterator;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPI registration and resolver wiring for the Netty adapter (ADR-0070):
 * the provider must be discoverable through {@link ServiceLoader}, report priority 60,
 * and win engine resolution when the submodule is on the classpath.
 */
class NettyHttpEngineProviderTest {

    private final NettyHttpEngineProvider provider = new NettyHttpEngineProvider();

    @Test
    @DisplayName("Provider is available (Netty on classpath) with priority 60")
    void providerReportsAvailableAndPriority() {
        assertTrue(provider.isAvailable());
        assertEquals(60, provider.priority());
    }

    @Test
    @DisplayName("ServiceLoader discovers the registered Netty provider")
    void serviceLoaderDiscoversProvider() {
        Iterator<HttpEngineProvider> loaded = ServiceLoader.load(HttpEngineProvider.class).iterator();
        boolean found = false;
        while (loaded.hasNext()) {
            if (loaded.next() instanceof NettyHttpEngineProvider) {
                found = true;
            }
        }
        assertTrue(found, "META-INF/services registration missing or not read");
    }

    @Test
    @DisplayName("The resolver picks the Netty engine when the submodule is on the classpath")
    void resolverPicksNettyEngine() {
        HttpEngine engine = HttpEngineResolver.resolve(
                SimpleModuleInfo.builder().baseUrl("http://localhost:1").build());
        try {
            assertInstanceOf(NettyHttpEngine.class, engine,
                    "ServiceLoader discovery must pick the higher priority Netty provider");
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("createEngine creates a configured NettyHttpEngine")
    void createEngineMapsModuleInfo() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .connectTimeout(1234)
                .socketTimeout(4321)
                .build();

        HttpEngine engine = provider.createEngine(moduleInfo);
        try {
            NettyHttpEngine nettyEngine = assertInstanceOf(NettyHttpEngine.class, engine);
            assertNotNull(nettyEngine.getEventLoopGroup());
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("NettyHttpEngine lifecycle: instantiate and close gracefully")
    void engineLifecycleAndClose() {
        NettyHttpEngine engine = new NettyHttpEngine();
        assertNotNull(engine.getEventLoopGroup());
        engine.close();
        assertTrue(engine.getEventLoopGroup().isShuttingDown() || engine.getEventLoopGroup().isShutdown());
    }
}
