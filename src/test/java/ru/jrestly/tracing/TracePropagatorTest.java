package ru.jrestly.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracePropagatorTest {

    @Test
    @DisplayName("TraceIdGenerators produces UUID, hex and prefixed IDs")
    void testTraceIdGenerators() {
        TraceIdGenerator uuidGen = TraceIdGenerators.uuid();
        assertNotNull(uuidGen.generate());
        assertEquals(36, uuidGen.generate().length());

        TraceIdGenerator hexGen = TraceIdGenerators.hex(8);
        assertEquals(16, hexGen.generate().length());
        assertThrows(IllegalArgumentException.class, () -> TraceIdGenerators.hex(0));

        TraceIdGenerator prefixed = TraceIdGenerators.prefixed("req-", () -> "12345");
        assertEquals("req-12345", prefixed.generate());

        ThreadLocal<String> holder = new ThreadLocal<>();
        TraceIdGenerator tlGen = TraceIdGenerators.threadLocal(holder, () -> "fallback-id");
        assertEquals("fallback-id", tlGen.generate());

        holder.set("active-id");
        try {
            assertEquals("active-id", tlGen.generate());
        } finally {
            holder.remove();
        }
    }

    @Test
    @DisplayName("TraceRequestContext correctly detects existing headers")
    void testTraceRequestContext() {
        List<Header> headers = List.of(new Header("X-Request-ID", "custom-123"), new Header("Content-Type", "application/json"));
        TraceRequestContext ctx = new TraceRequestContext(Object.class, null, "https://api.example.com/test", HttpMethod.GET, headers);

        assertTrue(ctx.hasHeader("X-Request-ID"));
        assertTrue(ctx.hasHeader("x-request-id"));
        assertTrue(ctx.hasHeader("CONTENT-TYPE"));
        assertFalse(ctx.hasHeader("traceparent"));
        assertFalse(ctx.hasHeader(null));

        assertEquals("custom-123", ctx.firstHeader("X-Request-ID"));
        assertEquals("application/json", ctx.firstHeader("content-type"));
        assertNull(ctx.firstHeader("traceparent"));
        assertNull(ctx.firstHeader(null));
    }

    @Test
    @DisplayName("TracePropagators.correlationId injects ID if not already present")
    void testCorrelationIdPropagator() {
        TracePropagator p = TracePropagators.correlationId("X-Request-ID", () -> "test-id-1");

        TraceRequestContext ctxEmpty = new TraceRequestContext(Object.class, null, "https://api.example.com", HttpMethod.POST, List.of());
        Map<String, String> injected = p.inject(ctxEmpty);
        assertEquals(1, injected.size());
        assertEquals("test-id-1", injected.get("X-Request-ID"));

        TraceRequestContext ctxPresent = new TraceRequestContext(Object.class, null, "https://api.example.com", HttpMethod.POST,
                List.of(new Header("X-Request-ID", "existing-id")));
        Map<String, String> notInjected = p.inject(ctxPresent);
        assertTrue(notInjected.isEmpty());
    }

    @Test
    @DisplayName("TracePropagators.w3c injects traceparent header")
    void testW3cPropagator() {
        TracePropagator w3c = TracePropagators.w3c();
        TraceRequestContext ctxEmpty = new TraceRequestContext(Object.class, null, "https://api.example.com", HttpMethod.GET, List.of());
        Map<String, String> injected = w3c.inject(ctxEmpty);

        assertTrue(injected.containsKey("traceparent"));
        assertTrue(injected.get("traceparent").startsWith("00-"));

        TraceRequestContext ctxPresent = new TraceRequestContext(Object.class, null, "https://api.example.com", HttpMethod.GET,
                List.of(new Header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")));
        assertTrue(w3c.inject(ctxPresent).isEmpty());
    }

    @Test
    @DisplayName("TracePropagators.w3c with supplier injects context and tracestate")
    void testW3cWithSupplier() {
        TraceContext tc = TraceContext.of("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01", "rojo=1,congo=2");
        TracePropagator w3c = TracePropagators.w3c(() -> tc, false);

        TraceRequestContext ctxEmpty = new TraceRequestContext(Object.class, null, "https://api.example.com", HttpMethod.GET, List.of());
        Map<String, String> injected = w3c.inject(ctxEmpty);

        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", injected.get("traceparent"));
        assertEquals("rojo=1,congo=2", injected.get("tracestate"));

        // When supplier returns null and generateIfMissing is false -> empty map
        TracePropagator w3cNull = TracePropagators.w3c(() -> null, false);
        assertTrue(w3cNull.inject(ctxEmpty).isEmpty());

        // When supplier returns null and generateIfMissing is true -> generated
        TracePropagator w3cFallback = TracePropagators.w3c(() -> null, true);
        assertNotNull(w3cFallback.inject(ctxEmpty).get("traceparent"));
    }

    @Test
    @DisplayName("TracePropagators.threadLocal injects from active thread local")
    void testThreadLocalPropagator() {
        ThreadLocal<String> tl = new ThreadLocal<>();
        TracePropagator p = TracePropagators.threadLocal("X-Correlation-ID", tl, true);

        TraceRequestContext ctx = new TraceRequestContext(Object.class, null, "https://api.example.com", HttpMethod.GET, List.of());

        // Missing => generated
        Map<String, String> map1 = p.inject(ctx);
        assertEquals(1, map1.size());
        assertNotNull(map1.get("X-Correlation-ID"));

        // Present in ThreadLocal => uses value
        tl.set("tl-fixed-uuid");
        try {
            Map<String, String> map2 = p.inject(ctx);
            assertEquals("tl-fixed-uuid", map2.get("X-Correlation-ID"));
        } finally {
            tl.remove();
        }
    }

    @Test
    @DisplayName("TracePropagators.composite combines multiple propagators")
    void testCompositePropagator() {
        TracePropagator p1 = TracePropagators.correlationId("X-Request-ID", () -> "cid-1");
        TracePropagator p2 = TracePropagators.correlationId("X-Tenant-ID", () -> "tenant-alpha");

        TracePropagator composite = TracePropagators.composite(p1, p2);
        TraceRequestContext ctx = new TraceRequestContext(Object.class, null, "https://api.example.com", HttpMethod.GET, List.of());

        Map<String, String> headers = composite.inject(ctx);
        assertEquals(2, headers.size());
        assertEquals("cid-1", headers.get("X-Request-ID"));
        assertEquals("tenant-alpha", headers.get("X-Tenant-ID"));
    }
}
