package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code Retry-After} header parsing in {@link HttpTemplate}.
 */
class HttpTemplateRetryAfterTest {

    private final HttpTemplate template = new HttpTemplate();

    private static WireResponse withRetryAfter(String value) {
        return new WireResponse(429, List.of(new Header("Retry-After", value)), new byte[0]);
    }

    private static String rfc850(String weekday, ZonedDateTime date) {
        return weekday + ", " + date.format(DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH));
    }

    private static String asctime(ZonedDateTime date) {
        return date.format(DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC));
    }

    private static ZonedDateTime inOneMinute() {
        return ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60).withNano(0);
    }

    private static ZonedDateTime anHourAgo() {
        return ZonedDateTime.now(ZoneOffset.UTC).minusHours(1).withNano(0);
    }

    @Test
    @DisplayName("a null response, a missing header, and a blank value parse to null")
    void absentHeaderParsesToNull() {
        assertNull(template.retryAfterDelayMillis(null));
        assertNull(template.retryAfterDelayMillis(new WireResponse(429, List.of(), new byte[0])));
        assertNull(template.retryAfterDelayMillis(withRetryAfter("   ")));
    }

    @Test
    @DisplayName("an unparseable value is null: pure jitter, no crash")
    void garbageParsesToNull() {
        assertNull(template.retryAfterDelayMillis(withRetryAfter("soon")));
        assertNull(template.retryAfterDelayMillis(withRetryAfter("1m")));
        assertNull(template.retryAfterDelayMillis(withRetryAfter("Mon, 32-Foo-94 99:99:99 GMT")));
    }

    @Test
    @DisplayName("delta-seconds: multiplied to milliseconds, 0 stays 0")
    void deltaSecondsParse() {
        assertEquals(120_000L, template.retryAfterDelayMillis(withRetryAfter("120")));
        assertEquals(0L, template.retryAfterDelayMillis(withRetryAfter("0")));
    }

    @Test
    @DisplayName("IMF-fixdate: a past date floors at 0, a future date yields the positive delta")
    void imfFixdateParses() {
        assertEquals(0L, template.retryAfterDelayMillis(withRetryAfter(
                anHourAgo().format(DateTimeFormatter.RFC_1123_DATE_TIME))));

        Long delay = template.retryAfterDelayMillis(withRetryAfter(
                inOneMinute().format(DateTimeFormatter.RFC_1123_DATE_TIME)));
        assertNotNull(delay);
        assertTrue(delay >= 58_000L && delay <= 60_000L, "got " + delay + " ms");
    }

    @Test
    @DisplayName("RFC 850: a two-digit year pivots back (94 -> 1994), so a past date floors at 0")
    void rfc850PastDateFloorsAtZero() {
        ZonedDateTime past1994 = anHourAgo().withYear(1994);
        String weekday = past1994.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
        assertEquals(0L, template.retryAfterDelayMillis(withRetryAfter(rfc850(weekday, past1994))));
    }

    @Test
    @DisplayName("RFC 850: a future date yields the positive delta")
    void rfc850FutureDateParses() {
        ZonedDateTime future = inOneMinute();
        Long delay = template.retryAfterDelayMillis(withRetryAfter(rfc850(future.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, Locale.ENGLISH), future)));
        assertNotNull(delay);
        assertTrue(delay >= 58_000L && delay <= 60_000L, "got " + delay + " ms");
    }

    @Test
    @DisplayName("RFC 850: the weekday prefix must be a real weekday name — otherwise null")
    void rfc850WeekdayIsValidated() {
        ZonedDateTime future = inOneMinute();
        assertNull(template.retryAfterDelayMillis(withRetryAfter("Funday, " + rfc850(
                future.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH), future))));
    }

    @Test
    @DisplayName("asctime: the RFC's UTC assumption applies, a past date floors at 0")
    void asctimePastDateFloorsAtZero() {
        assertEquals(0L, template.retryAfterDelayMillis(withRetryAfter(asctime(anHourAgo()))));
    }

    @Test
    @DisplayName("asctime: a future date yields the positive delta")
    void asctimeFutureDateParses() {
        Long delay = template.retryAfterDelayMillis(withRetryAfter(asctime(inOneMinute())));
        assertNotNull(delay);
        assertTrue(delay >= 58_000L && delay <= 60_000L, "got " + delay + " ms");
    }

    @Test
    @DisplayName("asctime: runs of spaces collapse — the RFC example's double-space day field parses")
    void asctimeDoubleSpaceCollapses() {
        String padded = asctime(inOneMinute()).replace(" ", "  ");
        assertNotNull(template.retryAfterDelayMillis(withRetryAfter(padded)));
    }
}
