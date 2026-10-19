package ru.jrestly.benchmark.feed;

import java.time.Instant;

/**
 * One parsed entry of the real-scenario feed benchmark (ADR-0068): an RSS 2.0
 * {@code <item>} or an Atom 1.0 {@code <entry>} folded into a common shape.
 *
 * <p>The fields mirror what real feeds carry; a field that the document omits is
 * {@code null}. Both fixture formats bind every field, so parity tests can compare
 * RSS and Atom documents byte-for-byte at the entry level.
 *
 * <p>Not part of the published library: the whole feed pipeline lives in the
 * non-published benchmark scope (ADR-0049), per the ADR-0068 non-goals.
 *
 * @param title     entry headline, decoded (entities resolved, CDATA unwrapped)
 * @param link      primary link; the {@code href} attribute when the document carries
 *                  one (Atom), the element text otherwise (RSS)
 * @param published publication instant from {@code pubDate} (RSS, RFC 822) or
 *                  {@code published} (Atom, RFC 3339); {@code updated} (Atom) is the
 *                  fallback when {@code published} is absent
 * @param author    bound author name: for RSS the human name inside the
 *                  {@code email (Name)} element text, for Atom the {@code <name>}
 *                  child text
 * @param uid       stable identifier: {@code guid} (RSS) or {@code id} (Atom)
 */
public record FeedEntry(String title, String link, Instant published, String author, String uid) {
}
