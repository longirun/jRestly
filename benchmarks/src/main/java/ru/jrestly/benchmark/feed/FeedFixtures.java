package ru.jrestly.benchmark.feed;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Deterministic RSS 2.0 / Atom 1.0 feed fixtures for the real-scenario fetch-and-parse
 * benchmark (ADR-0068).
 *
 * <p>Documents are assembled from a fixed pool of realistic templates: CDATA sections,
 * escaped entities, namespaced elements ({@code atom:link}, {@code content:encoded}),
 * RFC 822 (RSS) and RFC 3339 (Atom) timestamps, {@code guid}/{@code id} identifiers and
 * channel metadata. Generation is fully deterministic &mdash; the same entry count always
 * produces byte-identical documents, which keeps benchmark runs reproducible (the stand
 * serves precomputed bodies, no live internet inside JMH).
 *
 * <p>Nothing here is part of the published library: the whole feed pipeline lives in the
 * non-published benchmark scope (ADR-0049), per the ADR-0068 non-goals.
 */
public final class FeedFixtures {

    /** Small feed: roughly the size of a personal blog atom feed. */
    public static final int SMALL = 20;
    /** Medium feed: a typical news section of a mid-size site. */
    public static final int MEDIUM = 200;
    /** Large feed: a high-volume aggregator feed (over a megabyte on the wire). */
    public static final int LARGE = 1000;

    private static final Instant BASE_INSTANT = Instant.parse("2026-10-19T08:00:00Z");
    private static final long ENTRY_SPACING_SECONDS = 90;
    private static final DateTimeFormatter RFC_822 = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter RFC_3339 = DateTimeFormatter.ISO_INSTANT;

    private static final String[] TOPICS = {
            "Release engineering at scale: shipping daily without fear",
            "Inside the scheduler: how we cut tail latency by 40 percent",
            "The case for boring storage engines",
            "Benchmarking HTTP clients without fooling yourself",
            "Virtual threads in production: one year later",
            "A field guide to backpressure in streaming pipelines",
            "Why our cache hit rate lied to us",
            "Zero-copy serialization: myths and measurements"
    };

    private static final String[] SLUGS = {
            "release-engineering-at-scale",
            "inside-the-scheduler",
            "the-case-for-boring-storage",
            "benchmarking-without-fooling-yourself",
            "virtual-threads-in-production",
            "field-guide-to-backpressure",
            "why-our-cache-hit-rate-lied",
            "zero-copy-serialization-myths"
    };

    private static final String[] AUTHORS = {
            "Marta Keller", "Ivan Petrov", "Sofia Lindqvist", "Daniel Okafor", "Yuki Tanaka"
    };

    private static final String[] CATEGORIES = {
            "engineering", "performance", "distributed-systems", "developer-experience", "observability"
    };

    private static final String[] SUMMARIES = {
            "A walkthrough of the rollout pipeline, the guardrails that make it safe, "
                    + "and the two incidents that taught us to automate rollback first.",
            "We instrumented every queue on the hot path and found the real culprit was "
                    + "not the scheduler at all. Notes from a month of flame graphs.",
            "Boring technology wins again: a log-structured store, a compaction thread "
                    + "and a checksum pass replaced a stack of clever services.",
            "Methodology matters more than tooling: pinned CPU clocks, warm caches and "
                    + "allocation profiling before any microsecond gets quoted.",
            "Pinning virtual threads to carriers turned out to be the easy part; the "
                    + "hard part was teaching every library to stop pinning them back.",
            "Backpressure is a contract, not a knob. What broke when we treated it as "
                    + "a knob, and the protocol that fixed it.",
            "The dashboard said 98 percent, the users said otherwise. A story about "
                    + "key skew, TTL cliffs and honest instrumentation.",
            "We measured every copy between the socket and the DTO. Half of them were "
                    + "load-bearing; the other half paid our latency bill."
    };

    private static final String[] BODY_PARAGRAPHS = {
            "The first surprise was that the obvious fix made things worse under load, "
                    + "so we kept the old path as a fallback and shipped the new one behind a flag.",
            "Latency budgets are only useful when someone owns them. We assigned each "
                    + "hop a number, wired alerts to the p99 and stopped debating anecdotes.",
            "Every benchmark in this post is reproducible from the repository: fixed "
                    + "seeds, pinned CPU clocks & warm caches, precomputed payloads and a "
                    + "latency gate that fails loudly.",
            "The migration took three sprints, two of which were mostly deleting code. "
                    + "The diff stats lie in the usual direction: less code, more behavior.",
            "Observability came last but paid first: one histogram per hop, one dashboard "
                    + "per contract, and a runbook that a new hire can follow at 3 a.m."
    };

    private FeedFixtures() {
    }

    /**
     * Generates a deterministic RSS 2.0 document with the requested number of entries.
     *
     * @param entries number of {@code <item>} elements, must be positive
     * @return complete RSS 2.0 document with channel metadata
     */
    public static String rss(int entries) {
        requirePositive(entries);
        StringBuilder sb = new StringBuilder(256 + entries * 1400);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<rss version=\"2.0\" ")
                .append("xmlns:atom=\"http://www.w3.org/2005/Atom\" ")
                .append("xmlns:content=\"http://purl.org/rss/1.0/modules/content/\">\n")
                .append("  <channel>\n")
                .append("    <title>Engineering News</title>\n")
                .append("    <link>https://news.example.com/</link>\n")
                .append("    <description>Practical notes on building and operating software systems.</description>\n")
                .append("    <language>en-us</language>\n")
                .append("    <lastBuildDate>").append(rfc822(BASE_INSTANT)).append("</lastBuildDate>\n")
                .append("    <atom:link href=\"https://news.example.com/feed/rss\" rel=\"self\" type=\"application/rss+xml\"/>\n");
        for (int i = 0; i < entries; i++) {
            appendRssItem(sb, i);
        }
        sb.append("  </channel>\n").append("</rss>\n");
        return sb.toString();
    }

    /**
     * Generates a deterministic Atom 1.0 document with the requested number of entries.
     *
     * @param entries number of {@code <entry>} elements, must be positive
     * @return complete Atom 1.0 document with feed metadata
     */
    public static String atom(int entries) {
        requirePositive(entries);
        StringBuilder sb = new StringBuilder(256 + entries * 1400);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<feed xmlns=\"http://www.w3.org/2005/Atom\" xml:lang=\"en\">\n")
                .append("  <title>Engineering News</title>\n")
                .append("  <id>urn:example:feed:engineering-news</id>\n")
                .append("  <updated>").append(rfc3339(BASE_INSTANT)).append("</updated>\n")
                .append("  <link href=\"https://news.example.com/feed/atom\" rel=\"self\" type=\"application/atom+xml\"/>\n")
                .append("  <link href=\"https://news.example.com/\" rel=\"alternate\" type=\"text/html\"/>\n")
                .append("  <author><name>Engineering News</name></author>\n");
        for (int i = 0; i < entries; i++) {
            appendAtomEntry(sb, i);
        }
        sb.append("</feed>\n");
        return sb.toString();
    }

    private static void appendRssItem(StringBuilder sb, int i) {
        Instant published = BASE_INSTANT.minusSeconds((long) (i + 1) * ENTRY_SPACING_SECONDS);
        String topic = TOPICS[i % TOPICS.length];
        String title = entryTitle(i, topic);
        String slug = SLUGS[i % SLUGS.length] + "-" + i;
        String link = "https://news.example.com/articles/" + slug;
        sb.append("    <item>\n")
                .append("      <title>").append(title).append("</title>\n")
                .append("      <link>").append(link).append("</link>\n");
        if (i % 2 == 0) {
            sb.append("      <description><![CDATA[").append(SUMMARIES[i % SUMMARIES.length]).append("]]></description>\n");
        } else {
            sb.append("      <description>").append(escapedSummary(SUMMARIES[i % SUMMARIES.length])).append("</description>\n");
        }
        sb.append("      <content:encoded><![CDATA[<p>").append(BODY_PARAGRAPHS[i % BODY_PARAGRAPHS.length])
                .append("</p><p>&copy; 2026 Engineering News. Read more on the <a href=\"")
                .append(link).append("\">site</a>.</p>]]></content:encoded>\n")
                .append("      <pubDate>").append(rfc822(published)).append("</pubDate>\n")
                .append("      <guid isPermaLink=\"false\">urn:example:article:").append(i).append("</guid>\n")
                .append("      <author>newsroom@example.com (").append(AUTHORS[i % AUTHORS.length]).append(")</author>\n")
                .append("      <category>").append(CATEGORIES[i % CATEGORIES.length]).append("</category>\n")
                .append("      <atom:link href=\"").append(link).append("\" rel=\"alternate\" type=\"text/html\"/>\n")
                .append("    </item>\n");
    }

    private static void appendAtomEntry(StringBuilder sb, int i) {
        Instant published = BASE_INSTANT.minusSeconds((long) (i + 1) * ENTRY_SPACING_SECONDS);
        Instant updated = published.plusSeconds(300);
        String topic = TOPICS[i % TOPICS.length];
        String title = entryTitle(i, topic);
        String slug = SLUGS[i % SLUGS.length] + "-" + i;
        String link = "https://news.example.com/articles/" + slug;
        sb.append("  <entry>\n")
                .append("    <title>").append(title).append("</title>\n")
                .append("    <id>urn:example:article:").append(i).append("</id>\n")
                .append("    <link href=\"").append(link).append("\" rel=\"alternate\" type=\"text/html\"/>\n")
                .append("    <published>").append(rfc3339(published)).append("</published>\n")
                .append("    <updated>").append(rfc3339(updated)).append("</updated>\n")
                .append("    <summary>").append(escapedSummary(SUMMARIES[i % SUMMARIES.length])).append("</summary>\n")
                .append("    <content type=\"html\">&lt;p&gt;").append(escapedBody(BODY_PARAGRAPHS[i % BODY_PARAGRAPHS.length]))
                .append("&lt;/p&gt;</content>\n")
                .append("    <author><name>").append(AUTHORS[i % AUTHORS.length]).append("</name></author>\n")
                .append("    <category term=\"").append(CATEGORIES[i % CATEGORIES.length]).append("\"/>\n")
                .append("  </entry>\n");
    }

    /**
     * Every third headline carries escaped entities (&amp;, numeric apostrophe) &mdash;
     * real feeds mix plain and escaped titles, and parsers must survive both.
     */
    private static String entryTitle(int i, String topic) {
        String base = topic + " #" + i;
        if (i % 3 == 1) {
            return base.replace(" and ", " &amp; ").replace("'", "&#39;")
                    + " &#8212; reader&#39;s Q&amp;A";
        }
        return base;
    }

    private static String escapedSummary(String summary) {
        return summary.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String escapedBody(String body) {
        return body.replace("&", "&amp;").replace("\"", "&quot;");
    }

    private static String rfc822(Instant instant) {
        return RFC_822.format(instant);
    }

    private static String rfc3339(Instant instant) {
        return RFC_3339.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    private static void requirePositive(int entries) {
        if (entries <= 0) {
            throw new IllegalArgumentException("entries must be positive, was " + entries);
        }
    }
}
