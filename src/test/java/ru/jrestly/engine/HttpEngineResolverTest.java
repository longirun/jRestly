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
