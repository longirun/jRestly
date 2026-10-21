package ru.jrestly.tracing;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Immutable value representation of a W3C Trace Context (RFC 8414 / W3C TraceContext Level 1).
 *
 * <p>Encapsulates the standard tracing identifiers:
 * <ul>
 *   <li>{@code traceId}: 16-byte identifier represented as 32 lowercase hex characters</li>
 *   <li>{@code spanId}: 8-byte parent/span identifier represented as 16 lowercase hex characters</li>
 *   <li>{@code traceFlags}: 1-byte 8-bit flags represented as 2 lowercase hex characters (e.g. {@code "01"} for sampled)</li>
 *   <li>{@code traceState}: optional opaque comma-separated key-value pairs</li>
 * </ul>
 *
 * @param traceId the 32-character hex trace ID, not null
 * @param spanId the 16-character hex span ID, not null
 * @param traceFlags the 2-character hex trace flags, not null
 * @param traceState the optional trace state string, may be null or empty
 */
public record TraceContext(
        String traceId,
        String spanId,
        String traceFlags,
        String traceState
) {

    private static final String VERSION_00 = "00";
    private static final String DEFAULT_FLAGS = "01";
    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ALL_ZERO_SPAN_ID = "0000000000000000";

    private static final RandomGenerator RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Canonical constructor validating invariants.
     */
    public TraceContext {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(spanId, "spanId must not be null");
        Objects.requireNonNull(traceFlags, "traceFlags must not be null");

        traceId = traceId.trim().toLowerCase(Locale.ROOT);
        spanId = spanId.trim().toLowerCase(Locale.ROOT);
        traceFlags = traceFlags.trim().toLowerCase(Locale.ROOT);
        traceState = traceState != null ? traceState.trim() : null;

        if (traceId.length() != 32 || !isHex(traceId) || ALL_ZERO_TRACE_ID.equals(traceId)) {
            throw new IllegalArgumentException("Invalid traceId: must be 32 non-zero hex chars: " + traceId);
        }
        if (spanId.length() != 16 || !isHex(spanId) || ALL_ZERO_SPAN_ID.equals(spanId)) {
            throw new IllegalArgumentException("Invalid spanId: must be 16 non-zero hex chars: " + spanId);
        }
        if (traceFlags.length() != 2 || !isHex(traceFlags)) {
            throw new IllegalArgumentException("Invalid traceFlags: must be 2 hex chars: " + traceFlags);
        }
    }

    /**
     * Creates a {@link TraceContext} with the given IDs and default sampled flags ({@code "01"}).
     *
     * @param traceId the 32-hex trace ID
     * @param spanId the 16-hex span ID
     * @return a new trace context
     */
    public static TraceContext of(String traceId, String spanId) {
        return new TraceContext(traceId, spanId, DEFAULT_FLAGS, null);
    }

    /**
     * Creates a {@link TraceContext} with the given IDs and trace flags.
     *
     * @param traceId the 32-hex trace ID
     * @param spanId the 16-hex span ID
     * @param traceFlags the 2-hex trace flags
     * @return a new trace context
     */
    public static TraceContext of(String traceId, String spanId, String traceFlags) {
        return new TraceContext(traceId, spanId, traceFlags, null);
    }

    /**
     * Creates a {@link TraceContext} with the given IDs, flags, and trace state.
     *
     * @param traceId the 32-hex trace ID
     * @param spanId the 16-hex span ID
     * @param traceFlags the 2-hex trace flags
     * @param traceState the trace state string
     * @return a new trace context
     */
    public static TraceContext of(String traceId, String spanId, String traceFlags, String traceState) {
        return new TraceContext(traceId, spanId, traceFlags, traceState);
    }

    /**
     * Generates a new random {@link TraceContext} with sampled flag ({@code "01"})
     * using the default secure random generator.
     *
     * @return a newly generated random trace context
     */
    public static TraceContext random() {
        return random(RANDOM);
    }

    /**
     * Generates a new random {@link TraceContext} with sampled flag ({@code "01"})
     * using the specified {@link RandomGenerator}.
     *
     * @param random the random generator to use, not null
     * @return a newly generated random trace context
     */
    public static TraceContext random(RandomGenerator random) {
        Objects.requireNonNull(random, "random must not be null");
        byte[] traceBytes = new byte[16];
        byte[] spanBytes = new byte[8];
        random.nextBytes(traceBytes);
        random.nextBytes(spanBytes);

        String traceId = HEX.formatHex(traceBytes);
        String spanId = HEX.formatHex(spanBytes);
        // Extremely improbable collision with all zeroes guard
        if (ALL_ZERO_TRACE_ID.equals(traceId)) {
            traceId = "00000000000000000000000000000001";
        }
        if (ALL_ZERO_SPAN_ID.equals(spanId)) {
            spanId = "0000000000000001";
        }
        return new TraceContext(traceId, spanId, DEFAULT_FLAGS, null);
    }

    /**
     * Generates a child {@link TraceContext} sharing the same trace ID and trace flags,
     * but with a newly generated random span ID using the default secure random generator.
     *
     * @return a child trace context
     */
    public TraceContext child() {
        return child(RANDOM);
    }

    /**
     * Generates a child {@link TraceContext} sharing the same trace ID and trace flags,
     * but with a newly generated random span ID using the specified {@link RandomGenerator}.
     *
     * @param random the random generator to use, not null
     * @return a child trace context
     */
    public TraceContext child(RandomGenerator random) {
        Objects.requireNonNull(random, "random must not be null");
        byte[] spanBytes = new byte[8];
        random.nextBytes(spanBytes);
        String newSpanId = HEX.formatHex(spanBytes);
        if (ALL_ZERO_SPAN_ID.equals(newSpanId)) {
            newSpanId = "0000000000000001";
        }
        return new TraceContext(this.traceId, newSpanId, this.traceFlags, this.traceState);
    }

    /**
     * Formats this context into the standard W3C {@code traceparent} header format:
     * {@code 00-${traceId}-${spanId}-${traceFlags}}.
     *
     * @return the formatted traceparent header string
     */
    public String toTraceparent() {
        return VERSION_00 + "-" + traceId + "-" + spanId + "-" + traceFlags;
    }

    /**
     * Returns whether the sampled bit in {@link #traceFlags} is set.
     *
     * @return {@code true} if sampled, {@code false} otherwise
     */
    public boolean isSampled() {
        try {
            int flags = Integer.parseInt(traceFlags, 16);
            return (flags & 1) != 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * Parses a W3C {@code traceparent} header string into a {@link TraceContext}.
     * Returns {@link Optional#empty()} if the string is null, empty, or does not conform
     * to the W3C TraceContext specification.
     *
     * @param traceparent the traceparent header value
     * @return an {@link Optional} containing the parsed context, or empty if malformed
     */
    public static Optional<TraceContext> parse(String traceparent) {
        return parse(traceparent, null);
    }

    /**
     * Parses a W3C {@code traceparent} header and optional {@code tracestate} string into a {@link TraceContext}.
     * Returns {@link Optional#empty()} if the traceparent is malformed.
     *
     * @param traceparent the traceparent header value
     * @param tracestate the tracestate header value, or null
     * @return an {@link Optional} containing the parsed context, or empty if malformed
     */
    public static Optional<TraceContext> parse(String traceparent, String tracestate) {
        if (traceparent == null) {
            return Optional.empty();
        }
        String trimmed = traceparent.trim();
        // Standard W3C format: version(2)-traceId(32)-spanId(16)-traceFlags(2) => length 55
        // Forward-compatible parsing allows future versions with extra fields
        String[] parts = trimmed.split("-", -1);
        if (parts.length < 4) {
            return Optional.empty();
        }

        String version = parts[0];
        if (version.length() != 2 || !isHex(version) || "ff".equalsIgnoreCase(version)) {
            return Optional.empty();
        }

        if (VERSION_00.equals(version) && parts.length != 4) {
            // Version 00 forbids extra fields
            return Optional.empty();
        }

        String traceId = parts[1];
        String spanId = parts[2];
        String flags = parts[3];

        if (traceId.length() != 32 || !isHex(traceId) || ALL_ZERO_TRACE_ID.equals(traceId)) {
            return Optional.empty();
        }
        if (spanId.length() != 16 || !isHex(spanId) || ALL_ZERO_SPAN_ID.equals(spanId)) {
            return Optional.empty();
        }
        if (flags.length() != 2 || !isHex(flags)) {
            return Optional.empty();
        }

        return Optional.of(new TraceContext(traceId, spanId, flags, tracestate));
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                continue;
            }
            char lower = (char) (c | 0x20);
            if (lower >= 'a' && lower <= 'f') {
                continue;
            }
            return false;
        }
        return true;
    }
}
