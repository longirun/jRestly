package ru.jrestly.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage of engine resolution order: an explicit engine, SPI providers by priority
 * and the JDK fallback, with a null {@code ModuleInfo} rejected.
 */
@DisplayName("HttpEngineResolver Tests")
class HttpEngineResolverTest {

    @Test
    @DisplayName("HttpEngineResolver requires non-null moduleInfo")
    void requiresNonNullModuleInfo() {
        assertThrows(NullPointerException.class, () -> HttpEngineResolver.resolve(null));
    }

    @Test
    @DisplayName("Explicit HttpEngine configured in ModuleInfo always wins")
    void explicitEngineWins() {
        HttpEngine mockEngine = new DummyHttpEngine();
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .httpEngine(mockEngine)
                .build();

        HttpEngine resolved = HttpEngineResolver.resolve(moduleInfo);
        assertSame(mockEngine, resolved);
    }

    @Test
    @DisplayName("SPI provider resolves engine when available")
    void spiProviderResolvesWhenAvailable() {
        HttpEngine customEngine = new DummyHttpEngine();
        HttpEngineProvider availableProvider = new HttpEngineProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public HttpEngine createEngine(ModuleInfo moduleInfo) {
                return customEngine;
            }
        };

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, List.of(availableProvider));
        assertSame(customEngine, resolved);
    }

    @Test
    @DisplayName("Unavailable SPI provider is skipped, falling back to JdkHttpEngine")
    void unavailableProviderSkippedFallbackToJdk() {
        HttpEngineProvider unavailableProvider = new HttpEngineProvider() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public HttpEngine createEngine(ModuleInfo moduleInfo) {
                return new DummyHttpEngine();
            }
        };

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, List.of(unavailableProvider));
        assertNotNull(resolved);
        assertTrue(resolved instanceof JdkHttpEngine);
    }

    @Test
    @DisplayName("Provider returning null engine falls back to JdkHttpEngine")
    void nullEngineFallbackToJdk() {
        HttpEngineProvider nullProvider = new HttpEngineProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public HttpEngine createEngine(ModuleInfo moduleInfo) {
                return null;
            }
        };

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, List.of(nullProvider));
        assertNotNull(resolved);
        assertTrue(resolved instanceof JdkHttpEngine);
    }

    @Test
    @DisplayName("Empty SPI providers falls back to JdkHttpEngine")
    void emptyProvidersFallbackToJdk() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, List.of());
        assertNotNull(resolved);
        assertTrue(resolved instanceof JdkHttpEngine);
    }

    @Test
    @DisplayName("Higher-priority provider wins regardless of iteration order (ADR-0058)")
    void higherPriorityProviderWinsOverIterationOrder() {
        HttpEngine lowPriorityEngine = new DummyHttpEngine();
        HttpEngine highPriorityEngine = new DummyHttpEngine();

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, List.of(
                new TestProvider(true, 10, lowPriorityEngine),
                new TestProvider(true, 50, highPriorityEngine)));
        assertSame(highPriorityEngine, resolved);
    }

    @Test
    @DisplayName("Equal priority keeps the ServiceLoader iteration order")
    void equalPriorityKeepsIterationOrder() {
        HttpEngine firstEngine = new DummyHttpEngine();
        HttpEngine secondEngine = new DummyHttpEngine();

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, List.of(
                new TestProvider(true, 50, firstEngine),
                new TestProvider(true, 50, secondEngine)));
        assertSame(firstEngine, resolved);
    }

    @Test
    @DisplayName("Unavailable high-priority provider is skipped, lower-priority available wins")
    void unavailableHighPriorityProviderSkipped() {
        HttpEngine lowPriorityEngine = new DummyHttpEngine();
        HttpEngine unavailableEngine = new DummyHttpEngine();

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, List.of(
                new TestProvider(false, 50, unavailableEngine),
                new TestProvider(true, 10, lowPriorityEngine)));
        assertSame(lowPriorityEngine, resolved);
    }

    @Test
    @DisplayName("High-priority provider returning a null engine falls through to lower priority")
    void highPriorityNullEngineFallsThrough() {
        HttpEngine lowPriorityEngine = new DummyHttpEngine();

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, List.of(
                new TestProvider(true, 50, null),
                new TestProvider(true, 10, lowPriorityEngine)));
        assertSame(lowPriorityEngine, resolved);
    }

    @Test
    @DisplayName("Legacy provider without explicit priority() (default 0) loses to explicit priority")
    void legacyDefaultPriorityLosesToExplicitPriority() {
        HttpEngine explicitEngine = new DummyHttpEngine();
        HttpEngineProvider legacyProvider = new HttpEngineProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public HttpEngine createEngine(ModuleInfo moduleInfo) {
                return new DummyHttpEngine();
            }
        };

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, List.of(
                legacyProvider,
                new TestProvider(true, 1, explicitEngine)));
        assertSame(explicitEngine, resolved);
    }

    @Test
    @DisplayName("resolve() without an explicit engine walks SPI then falls back to JDK")
    void spiResolutionWithoutExplicitEngineFallsBackToJdk() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolve(moduleInfo);
        assertNotNull(resolved);
        assertTrue(resolved instanceof JdkHttpEngine);
    }

    @Test
    @DisplayName("Null provider iterable falls back to JdkHttpEngine (seam contract)")
    void nullProvidersIterableFallsBackToJdk() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, null);
        assertNotNull(resolved);
        assertTrue(resolved instanceof JdkHttpEngine);
    }

    @Test
    @DisplayName("SPI iterable with null elements skips them and resolves the next available provider")
    void nullProviderElementsAreSkipped() {
        HttpEngine customEngine = new DummyHttpEngine();
        HttpEngineProvider availableProvider = new TestProvider(true, 10, customEngine);

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        // ArrayList accepts null elements (List.of does not)
        java.util.List<HttpEngineProvider> iterable = new java.util.ArrayList<>();
        iterable.add(null);
        iterable.add(availableProvider);

        HttpEngine resolved = HttpEngineResolver.resolveFromSpi(moduleInfo, iterable);
        assertSame(customEngine, resolved);
    }

    /**
     * Fixed SPI provider for priority scenarios. {@code priority()} is declared
     * without {@code @Override} so the test compiles both before and after the
     * default method is added to the SPI (red-first, TDD).
     */
    private static final class TestProvider implements HttpEngineProvider {
        private final boolean available;
        private final int priority;
        private final HttpEngine engine;

        TestProvider(boolean available, int priority, HttpEngine engine) {
            this.available = available;
            this.priority = priority;
            this.engine = engine;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        public int priority() {
            return priority;
        }

        @Override
        public HttpEngine createEngine(ModuleInfo moduleInfo) {
            return engine;
        }
    }

    private static final class DummyHttpEngine implements HttpEngine {
        @Override
        public WireResponse send(WireRequest request, HttpEngineOptions options) {
            return WireResponse.of(200);
        }

        @Override
        public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
            return CompletableFuture.completedFuture(WireResponse.of(200));
        }
    }
}
