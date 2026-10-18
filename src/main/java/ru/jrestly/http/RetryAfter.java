package ru.jrestly.http;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the {@code Retry-After} response header (RFC 9110 §10.2.3) into a
 * millisecond hint for the retry backoff (ADR-0009 candidate 3, ADR-0010).
 *
 * <p>Both RFC 9110 forms are honored: {@code delta-seconds} (multiplied by
 * 1000) and the HTTP-date, whose three grammars a recipient "MUST accept"
 * — the live IMF-fixdate plus the two obsolete RFC 850 and asctime(3) forms.
 * asctime carries no zone and is prescribed as UTC by the RFC. A parsed date
 * is floored at 0 ("you may have retried already"); an absent, null-valued or
 * unparseable header is {@code null} (pure jitter).
 *
 * <p>Extracted from {@code HttpTemplate} (ADR-0066) — a self-contained,
 * side-effect-free concern; the unit tests exercise the parser directly.
 */
final class RetryAfter {

    // ADR-0010: all three RFC 9110 HTTP-date grammars.
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;
    // RFC 850 without the weekday prefix — the full weekday name is validated
    // separately (WEEKDAYS) but not parsed as a field: as a parsed EEEE field,
    // java.time cross-checks it against the date and trips on the two-digit-year
    // mapping before the pivot below can fix it.
    private static final DateTimeFormatter RFC850_DATE =
            DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH);
    private static final Set<String> RFC850_WEEKDAYS =
            Set.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");
    // asctime carries no zone — the RFC prescribes UTC, not an assumption of ours.
    private static final DateTimeFormatter ASCTIME_DATE =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private RetryAfter() {
    }

    /**
     * The retry hint carried by {@code response}, in milliseconds, or
     * {@code null} when the header is absent, null-valued or unparseable.
     */
    static Long delayMillis(WireResponse response) {
        if (response == null) {
            return null;
        }
        for (Header header : response.headers()) {
            if (header.name() != null && header.name().equalsIgnoreCase("Retry-After")) {
                return parse(header.value());
            }
        }
        return null;
    }

    private static Long parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value) * 1_000L;
        } catch (NumberFormatException e) {
            // not delta-seconds — try the HTTP-date forms below
        }
        // The formats are mutually exclusive by grammar (comma placement, zone,
        // field order), so the try order is just the RFC's presentation order.
        try {
            return delayFromParsedDate(ZonedDateTime.parse(value, HTTP_DATE));
        } catch (DateTimeParseException e) {
            // not IMF-fixdate — try the obsolete RFC 850 form
        }
        String rfc850Body = value;
        int comma = value.indexOf(',');
        if (comma > 0 && RFC850_WEEKDAYS.contains(value.substring(0, comma))) {
            rfc850Body = value.substring(comma + 1).trim();
        }
        try {
            return delayFromParsedDate(
                    pivotRfc850Year(ZonedDateTime.parse(rfc850Body, RFC850_DATE)));
        } catch (DateTimeParseException e) {
            // not RFC 850 — try the obsolete asctime(3) form
        }
        try {
            // asctime may carry a double space before a one-digit day (the RFC
            // 9110 example does) — collapse runs of spaces for this attempt only;
            // a one- or two-digit day parses either way.
            return delayFromParsedDate(
                    ZonedDateTime.parse(value.replaceAll("\\s{2,}", " "), ASCTIME_DATE));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // A past date means "you may have retried already" and is floored at 0 —
    // mirrors the integer form, where a small value never shrinks the
    // max(bound, ...) below the bound.
    private static long delayFromParsedDate(ZonedDateTime date) {
        return Math.max(0L, date.toInstant().toEpochMilli() - System.currentTimeMillis());
    }

    // java.time maps a two-digit year to the current century (94 -> 2094); the
    // classic pivot that legacy parsers used (80-99 -> previous century) is
    // applied after parsing, so 94 reads as 1994.
    private static ZonedDateTime pivotRfc850Year(ZonedDateTime date) {
        return date.getYear() % 100 > 80 ? date.minusYears(100) : date;
    }
}
