package ru.jrestly.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end guard for ServiceLoader engine auto-detection (ADR-0058,
 * Finding #158): a provider registered on the classpath must be picked up
 * by a client built without an explicit engine, and the zero-dependency
 * JDK fallback must remain in place when no provider is available.
 */
@DisplayName("SPI engine auto-detection end-to-end (ADR-0058 Finding #158)")
class SpiEngineAutoDetectionTest {

    @AfterEach
    void makeProviderUnavailableAgain() {
        TestSpiEngineProvider.AVAILABLE.set(false);
    }

    @Test
    @DisplayName("Default client picks up the SPI-registered engine when the provider is available")
    void defaultClientPicksUpSpiEngine() {
        TestSpiEngineProvider.AVAILABLE.set(true);
        int before = TestSpiEngineProvider.CREATE_COUNT.get();

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://mock.example.com")
                .build();
        try {
            assertInstanceOf(TestSpiEngineProvider.StubEngine.class, client.getHttpEngine(),
                    "A default client must auto-detect a ServiceLoader-registered engine (Finding #158)");
            assertTrue(TestSpiEngineProvider.CREATE_COUNT.get() > before,
                    "The SPI provider must have been asked to create the engine");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Default client falls back to JdkHttpEngine when no provider is available")
    void defaultClientFallsBackToJdkEngine() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://mock.example.com")
                .build();
        try {
            assertInstanceOf(JdkHttpEngine.class, client.getHttpEngine(),
                    "Without an available provider the zero-dependency JDK engine must remain the fallback");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("An explicit engine still wins over SPI providers")
    void explicitEngineStillWins() {
        TestSpiEngineProvider.AVAILABLE.set(true);
        HttpEngine explicit = new HttpEngine() {
            @Override
            public WireResponse send(WireRequest request, HttpEngineOptions options) {
                return WireResponse.of(200);
            }

            @Override
            public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
                return CompletableFuture.completedFuture(WireResponse.of(200));
            }
        };

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://mock.example.com")
                .httpEngine(explicit)
                .build();
        try {
            assertSame(explicit, client.getHttpEngine(),
                    "An explicitly configured engine must never be replaced by SPI auto-detection");
        } finally {
            client.close();
        }
    }
}
