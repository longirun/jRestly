package ru.jrestly.engine.apache5;

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
 * SPI registration and resolver wiring for the Apache HttpClient 5 adapter (ADR-0058):
 * the provider must be discoverable through {@link ServiceLoader}, report the
 * official adapter priority, and win engine resolution when the submodule is
 * on the classpath.
 */
class Apache5HttpEngineProviderTest {

    private final Apache5HttpEngineProvider provider = new Apache5HttpEngineProvider();

    @Test
    @DisplayName("Provider is available (HttpClient 5 on classpath) with the official adapter priority")
    void providerReportsAvailableAndPriority() {
        assertTrue(provider.isAvailable());
        assertEquals(50, provider.priority());
    }

    @Test
    @DisplayName("ServiceLoader discovers the registered provider")
    void serviceLoaderDiscoversProvider() {
        Iterator<HttpEngineProvider> loaded = ServiceLoader.load(HttpEngineProvider.class).iterator();
        boolean found = false;
        while (loaded.hasNext()) {
            if (loaded.next() instanceof Apache5HttpEngineProvider) {
                found = true;
            }
        }
        assertTrue(found, "META-INF/services registration missing or not read");
    }

    @Test
    @DisplayName("The resolver picks the Apache5 engine when the submodule is on the classpath")
    void resolverPicksApache5Engine() {
        HttpEngine engine = HttpEngineResolver.resolve(
                SimpleModuleInfo.builder().baseUrl("http://localhost:1").build());
        try {
            assertInstanceOf(Apache5HttpEngine.class, engine,
                    "ServiceLoader discovery must override the zero-dependency fallback");
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("createEngine maps ModuleInfo settings onto the HttpClient 5 client")
    void createEngineMapsModuleInfo() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .connectTimeout(1234)
                .socketTimeout(4321)
                .cookieManager()
                .useVirtualThreads()
                .build();

        HttpEngine engine = provider.createEngine(moduleInfo);
        try {
            Apache5HttpEngine apache5Engine = assertInstanceOf(Apache5HttpEngine.class, engine);
            assertNotNull(apache5Engine.getHttpClient());
        } finally {
            engine.close();
        }
    }
}
