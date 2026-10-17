package ru.jrestly.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the W3C {@link TraceContext} (ADR-0042): validated
 * construction, random generation, child contexts and {@code traceparent}
 * parsing (valid and invalid). Pure unit tests.
 */
class TraceContextTest {

    private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String VALID_SPAN_ID = "00f067aa0ba902b7";

    @Test
    @DisplayName("TraceContext constructs successfully and normalizes lowercase hex")
    void shouldConstructValidContext() {
        TraceContext ctx = TraceContext.of(VALID_TRACE_ID.toUpperCase(), VALID_SPAN_ID.toUpperCase());
        assertEquals(VALID_TRACE_ID, ctx.traceId());
        assertEquals(VALID_SPAN_ID, ctx.spanId());
        assertEquals("01", ctx.traceFlags());
        assertTrue(ctx.isSampled());
        assertNull(ctx.traceState());
        assertEquals("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01", ctx.toTraceparent());
    }

    @Test
    @DisplayName("TraceContext throws on invalid hex or illegal lengths")
    void shouldThrowOnInvalidContext() {
        assertThrows(NullPointerException.class, () -> new TraceContext(null, VALID_SPAN_ID, "01", null));
        assertThrows(NullPointerException.class, () -> new TraceContext(VALID_TRACE_ID, null, "01", null));
        assertThrows(NullPointerException.class, () -> new TraceContext(VALID_TRACE_ID, VALID_SPAN_ID, null, null));

        // Trace ID length != 32
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of("123", VALID_SPAN_ID));
        // Trace ID non-hex
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of("4bf92f3577b34da6a3ce929d0e0e473z", VALID_SPAN_ID));
        // All-zero trace ID
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of("00000000000000000000000000000000", VALID_SPAN_ID));

        // Span ID length != 16
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of(VALID_TRACE_ID, "123"));
        // Span ID non-hex
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of(VALID_TRACE_ID, "00f067aa0ba902bz"));
        // All-zero span ID
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of(VALID_TRACE_ID, "0000000000000000"));

        // Invalid flags
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of(VALID_TRACE_ID, VALID_SPAN_ID, "001"));
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of(VALID_TRACE_ID, VALID_SPAN_ID, "zz"));
    }

    @Test
    @DisplayName("TraceContext.random() generates valid non-zero hex identifiers")
    void shouldGenerateRandomContext() {
        TraceContext ctx = TraceContext.random();
        assertNotNull(ctx);
        assertEquals(32, ctx.traceId().length());
        assertEquals(16, ctx.spanId().length());
        assertEquals("01", ctx.traceFlags());
        assertTrue(ctx.isSampled());
        assertTrue(ctx.toTraceparent().startsWith("00-"));
    }

    @Test
    @DisplayName("TraceContext.child() preserves traceId and creates new spanId")
    void shouldCreateChildContext() {
        TraceContext parent = TraceContext.of(VALID_TRACE_ID, VALID_SPAN_ID, "01", "rojo=1");
        TraceContext child = parent.child();

        assertEquals(parent.traceId(), child.traceId());
        assertEquals(parent.traceFlags(), child.traceFlags());
        assertEquals(parent.traceState(), child.traceState());
        assertNotSame(parent.spanId(), child.spanId());
        assertEquals(16, child.spanId().length());
    }

    @Test
    @DisplayName("TraceContext.parse() successfully parses valid W3C traceparent headers")
    void shouldParseValidTraceparent() {
        String header = "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01";
        Optional<TraceContext> parsed = TraceContext.parse(header, "congo=t61rcWkgMzE");

        assertTrue(parsed.isPresent());
        TraceContext ctx = parsed.get();
        assertEquals(VALID_TRACE_ID, ctx.traceId());
        assertEquals(VALID_SPAN_ID, ctx.spanId());
        assertEquals("01", ctx.traceFlags());
        assertTrue(ctx.isSampled());
        assertEquals("congo=t61rcWkgMzE", ctx.traceState());
        assertEquals(header, ctx.toTraceparent());
    }

    @Test
    @DisplayName("TraceContext.parse() returns empty for invalid or null traceparent headers")
    void shouldReturnEmptyOnInvalidTraceparent() {
        assertTrue(TraceContext.parse(null).isEmpty());
        assertTrue(TraceContext.parse("").isEmpty());
        assertTrue(TraceContext.parse("not-a-traceparent").isEmpty());
        // Version "ff" forbidden
        assertTrue(TraceContext.parse("ff-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01").isEmpty());
        // All zeroes
        assertTrue(TraceContext.parse("00-00000000000000000000000000000000-" + VALID_SPAN_ID + "-01").isEmpty());
        assertTrue(TraceContext.parse("00-" + VALID_TRACE_ID + "-0000000000000000-01").isEmpty());
        // Wrong parts count in version 00
        assertTrue(TraceContext.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01-extra").isEmpty());
        assertTrue(TraceContext.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID).isEmpty());
    }
}
