package ru.jrestly.engine;

import ru.jrestly.ModuleInfo;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SPI provider registered for the test classpath
 * ({@code META-INF/services/ru.jrestly.engine.HttpEngineProvider}) to guard
 * ServiceLoader auto-detection end-to-end (ADR-0058, Finding #158).
 *
 * <p>Unavailable by default so that it never interferes with other tests;
 * a test flips {@link #AVAILABLE} for the duration of its scenario.
 */
public final class TestSpiEngineProvider implements HttpEngineProvider {

    /** Whether the provider reports itself available; {@code false} outside explicit scenarios. */
    public static final AtomicBoolean AVAILABLE = new AtomicBoolean(false);

    /** Number of engines created, for auto-detection evidence in assertions. */
    public static final AtomicInteger CREATE_COUNT = new AtomicInteger();

    /** ServiceLoader requires a public no-argument constructor. */
    public TestSpiEngineProvider() {
    }

    @Override
    public boolean isAvailable() {
        return AVAILABLE.get();
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public HttpEngine createEngine(ModuleInfo moduleInfo) {
        CREATE_COUNT.incrementAndGet();
        return new StubEngine();
    }

    /** Marker engine proving the provider's engine reached the client. */
    public static final class StubEngine implements HttpEngine {
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
