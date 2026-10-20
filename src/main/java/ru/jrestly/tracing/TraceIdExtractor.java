package ru.jrestly.tracing;

import ru.jrestly.http.Header;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Enterprise trace and correlation ID extractor (ADR-0096).
 *
 * <p>Extracts active distributed tracing identifiers from request/response headers
 * prioritizing standard W3C {@code traceparent} (RFC 8414) and standard correlation
 * headers ({@code X-Request-ID}, {@code X-Correlation-ID}, {@code X-Trace-ID}).
 */
public final class TraceIdExtractor {

    private static final String TRACEPARENT_HEADER = "traceparent";

    private static final List<String> CORRELATION_HEADERS = List.of(
            "x-request-id",
            "x-correlation-id",
            "x-trace-id",
            "trace-id",
            "request-id",
            "correlation-id"
    );

    private TraceIdExtractor() {
        // Utility class
    }

    /**
     * Extracts a trace ID or correlation ID from a list of HTTP headers.
     *
     * @param headers the HTTP headers to inspect, may be null or empty
     * @return the extracted trace/correlation ID, or {@code null} if none found
     */
    public static String extract(List<Header> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }

        // 1. Check for W3C traceparent header
        for (Header h : headers) {
            if (h != null && h.name() != null && TRACEPARENT_HEADER.equalsIgnoreCase(h.name())) {
                String val = h.value();
                if (val != null && !val.isBlank()) {
                    Optional<TraceContext> parsed = TraceContext.parse(val.trim());
                    if (parsed.isPresent()) {
                        return parsed.get().traceId();
                    }
                }
            }
        }

        // 2. Check for standard correlation ID headers
        for (String expectedName : CORRELATION_HEADERS) {
            for (Header h : headers) {
                if (h != null && h.name() != null && expectedName.equalsIgnoreCase(h.name())) {
                    String val = h.value();
                    if (val != null && !val.isBlank()) {
                        return val.trim();
                    }
                }
            }
        }

        return null;
    }

    /**
     * Extracts a trace ID or correlation ID from a wire request.
     *
     * @param request the wire request, may be null
     * @return the extracted trace/correlation ID, or {@code null} if none found
     */
    public static String extract(WireRequest request) {
        return request != null ? extract(request.headers()) : null;
    }

    /**
     * Extracts a trace ID or correlation ID from a wire response.
     *
     * @param response the wire response, may be null
     * @return the extracted trace/correlation ID, or {@code null} if none found
     */
    public static String extract(WireResponse response) {
        return response != null ? extract(response.headers()) : null;
    }
}
