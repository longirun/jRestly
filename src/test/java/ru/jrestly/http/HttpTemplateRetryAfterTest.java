package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code HttpTemplate.retryAfterDelayMillis} — the
 * {@code Retry-After} parser and its unit (ADR-0009 candidate 3): both RFC 9110
 * forms honored, the lower bound in milliseconds. No WireMock — the seam is
 * package-private on purpose, exactly as the backoff-math seam in
 * {@link RetryPolicyTest}.
 */
class HttpTemplateRetryAfterTest {

    private static WireResponse withRetryAfter(String name, String value) {
        return new WireResponse(429, List.of(new Header(name, value)), new byte[0]);
    }

    @Test
    @DisplayName("HTTP-date form: a date 60 s in the future -> a positive delta near 60 s")
    void httpDateInTheFutureIsAPositiveDelta() {
        String value = ZonedDateTime.ofInstant(Instant.now().plusSeconds(60), ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);

        Long delay = HttpTemplate.retryAfterDelayMillis(withRetryAfter("Retry-After", value));

        assertTrue(delay >= 59_000 && delay <= 60_000,
                "expected a delta in [59000, 60000] ms, got " + delay);
    }

    @Test
    @DisplayName("HTTP-date form: a past date is floored at 0 ('you may have retried already')")
    void httpDateInThePastIsFlooredAtZero() {
        assertEquals(0L, HttpTemplate.retryAfterDelayMillis(
                withRetryAfter("Retry-After", "Sun, 06 Nov 1994 08:49:37 GMT")));
    }

    @Test
    @DisplayName("delta-seconds form: semantics unchanged, unit in milliseconds")
    void deltaSecondsAreMultipliedByOneThousand() {
        assertEquals(1000L, HttpTemplate.retryAfterDelayMillis(
                withRetryAfter("Retry-After", "1")));
        assertEquals(42_000L, HttpTemplate.retryAfterDelayMillis(
                withRetryAfter("Retry-After", "42")));
    }

    @Test
    @DisplayName("garbage values are unparseable -> null (pure jitter)")
    void garbageValuesAreNull() {
        assertNull(HttpTemplate.retryAfterDelayMillis(withRetryAfter("Retry-After", "soon")));
        assertNull(HttpTemplate.retryAfterDelayMillis(withRetryAfter("Retry-After", "1.5")));
    }

    @Test
    @DisplayName("asctime (obsolete per RFC 9110) is deliberately not parsed -> null (micro-gap)")
    void asctimeIsNotParsed() {
        assertNull(HttpTemplate.retryAfterDelayMillis(
                withRetryAfter("Retry-After", "Wed Oct 21 07:45:00 2026")));
    }

    @Test
    @DisplayName("absent or empty header -> null")
    void absentOrEmptyHeaderIsNull() {
        assertNull(HttpTemplate.retryAfterDelayMillis(new WireResponse(
                429, List.of(new Header("Content-Type", "application/json")), new byte[0])));
        assertNull(HttpTemplate.retryAfterDelayMillis(withRetryAfter("Retry-After", "")));
        assertNull(HttpTemplate.retryAfterDelayMillis(withRetryAfter("Retry-After", "   ")));
    }

    @Test
    @DisplayName("null response (a transport failure has no headers) -> null")
    void nullResponseIsNull() {
        assertNull(HttpTemplate.retryAfterDelayMillis(null));
    }

    @Test
    @DisplayName("header name is case-insensitive and the value is trimmed (as before)")
    void headerNameIsCaseInsensitiveAndValueTrimmed() {
        assertEquals(42_000L, HttpTemplate.retryAfterDelayMillis(
                withRetryAfter("retry-after", "  42  ")));
        assertEquals(42_000L, HttpTemplate.retryAfterDelayMillis(
                withRetryAfter("RETRY-AFTER", "42")));
    }
}
