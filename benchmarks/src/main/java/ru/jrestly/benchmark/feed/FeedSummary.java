package ru.jrestly.benchmark.feed;

import java.time.Instant;
import java.util.List;

/**
 * Fold of a parsed feed for the real-scenario benchmark (ADR-0068): entry count,
 * the most recent publication instant and a deterministic 64-bit content digest.
 *
 * <p>The digest is what JMH consumes through a {@code Blackhole}: it depends on every
 * entry field, so a run cannot be optimized into nothing, yet it stays stable across
 * identical documents &mdash; the benchmark result must be reproducible (ADR-0068 §2).
 *
 * <p>Not part of the published library: the whole feed pipeline lives in the
 * non-published benchmark scope (ADR-0049), per the ADR-0068 non-goals.
 *
 * @param count  number of parsed entries
 * @param latest the most recent {@code published} instant across entries,
 *               {@code null} when no entry carries one
 * @param digest FNV-1a 64-bit hash over every entry field, order-sensitive
 */
public record FeedSummary(int count, Instant latest, long digest) {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * Folds the parsed entries into a summary.
     *
     * @param entries parsed entries, must not be null
     * @return summary over the entries in list order
     */
    public static FeedSummary of(List<FeedEntry> entries) {
        Instant latest = null;
        long digest = FNV_OFFSET_BASIS;
        for (FeedEntry entry : entries) {
            digest = mix(digest, entry.title());
            digest = mix(digest, entry.link());
            digest = mix(digest, entry.published() == null ? null : Long.toString(entry.published().toEpochMilli()));
            digest = mix(digest, entry.author());
            digest = mix(digest, entry.uid());
            if (entry.published() != null && (latest == null || entry.published().isAfter(latest))) {
                latest = entry.published();
            }
        }
        return new FeedSummary(entries.size(), latest, digest);
    }

    private static long mix(long hash, String value) {
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                hash ^= value.charAt(i);
                hash *= FNV_PRIME;
            }
        }
        hash ^= 0x1f;
        hash *= FNV_PRIME;
        return hash;
    }
}
