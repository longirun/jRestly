package ru.jrestly.engine.okhttp;

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
 * SPI registration and resolver wiring for the OkHttp adapter (ADR-0058):
 * the provider must be discoverable through {@link ServiceLoader}, report the
 * official adapter priority, and win engine resolution when the submodule is
 * on the classpath.
 */
class OkHttpEngineProviderTest {

    private final OkHttpEngineProvider provider = new OkHttpEngineProvider();

    @Test
    @DisplayName("Provider is available (OkHttp on classpath) with the official adapter priority")
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
            if (loaded.next() instanceof OkHttpEngineProvider) {
                found = true;
            }
        }
        assertTrue(found, "META-INF/services registration missing or not read");
    }

    @Test
    @DisplayName("The resolver picks the OkHttp engine when the submodule is on the classpath")
    void resolverPicksOkHttpEngine() {
        HttpEngine engine = HttpEngineResolver.resolve(
                SimpleModuleInfo.builder().baseUrl("http://localhost:1").build());
        try {
            assertInstanceOf(OkHttpEngine.class, engine,
                    "ServiceLoader discovery must override the zero-dependency fallback");
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("createEngine maps ModuleInfo settings onto the OkHttp client")
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
            OkHttpEngine okHttpEngine = assertInstanceOf(OkHttpEngine.class, engine);
            assertNotNull(okHttpEngine.getOkHttpClient());
            assertEquals(1234, okHttpEngine.getOkHttpClient().connectTimeoutMillis());
            assertEquals(4321, okHttpEngine.getOkHttpClient().readTimeoutMillis());
            assertEquals(4321, okHttpEngine.getOkHttpClient().writeTimeoutMillis());
        } finally {
            engine.close();
        }
    }
}
