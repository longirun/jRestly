package ru.jrestly.benchmark.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanitary gate for the shared StAX feed parser of the real-scenario benchmark
 * (ADR-0068 phase 2): fixtures must parse to the expected number of fully bound
 * entries, RSS and Atom documents must fold to the identical summary (parity of the
 * two formats through the one common parser), byte and stream entry points must agree,
 * and broken documents must fail fast.
 */
@DisplayName("Shared Feed Parser Sanitary Tests")
class FeedParserTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-10-19T08:00:00Z");
    private static final List<Integer> SIZES = List.of(FeedFixtures.SMALL, FeedFixtures.MEDIUM, FeedFixtures.LARGE);

    private final FeedParser parser = new FeedParser();

    @Test
    @DisplayName("Every RSS fixture parses to the expected number of entries")
    void rssFixturesParseToExpectedEntryCount() throws Exception {
        for (int size : SIZES) {
            List<FeedEntry> entries = parser.parse(FeedFixtures.rss(size).getBytes(StandardCharsets.UTF_8));
            assertEquals(size, entries.size(),
                    () -> "rss(" + size + ") must parse to exactly " + size + " entries");
        }
    }

    @Test
    @DisplayName("Every Atom fixture parses to the expected number of entries")
    void atomFixturesParseToExpectedEntryCount() throws Exception {
        for (int size : SIZES) {
            List<FeedEntry> entries = parser.parse(FeedFixtures.atom(size).getBytes(StandardCharsets.UTF_8));
            assertEquals(size, entries.size(),
                    () -> "atom(" + size + ") must parse to exactly " + size + " entries");
        }
    }

    @Test
    @DisplayName("RSS and Atom fixtures of the same size fold to the identical summary")
    void rssAndAtomParity() throws Exception {
        for (int size : SIZES) {
            FeedSummary rss = FeedSummary.of(parser.parse(FeedFixtures.rss(size).getBytes(StandardCharsets.UTF_8)));
            FeedSummary atom = FeedSummary.of(parser.parse(FeedFixtures.atom(size).getBytes(StandardCharsets.UTF_8)));
            assertEquals(size, rss.count(), () -> "rss(" + size + ") summary count");
            assertEquals(rss.count(), atom.count(), () -> "atom(" + size + ") summary count");
            assertEquals(rss.latest(), atom.latest(), () -> "latest publication must agree across formats");
            assertEquals(rss.digest(), atom.digest(),
                    () -> "content digest must agree across formats at " + size + " entries");
        }
    }

    @Test
    @DisplayName("Entry fields are bound completely and entities are decoded")
    void entryFieldsAreBoundCompletely() throws Exception {
        List<FeedEntry> entries = parser.parse(FeedFixtures.rss(FeedFixtures.SMALL).getBytes(StandardCharsets.UTF_8));
        FeedEntry first = entries.getFirst();
        assertEquals("Release engineering at scale: shipping daily without fear #0", first.title());
        assertEquals("https://news.example.com/articles/release-engineering-at-scale-0", first.link());
        assertEquals(BASE_INSTANT.minusSeconds(90), first.published());
        assertEquals("Marta Keller", first.author());
        assertEquals("urn:example:article:0", first.uid());

        FeedEntry second = entries.get(1);
        assertTrue(second.title().endsWith("\u2014 reader's Q&A"),
                () -> "numeric entities must be decoded, was: " + second.title());
        for (FeedEntry entry : entries) {
            assertTrue(!entry.title().contains("&amp;") && !entry.title().contains("&#"),
                    () -> "no entity remnants may survive in titles: " + entry.title());
        }
    }

    @Test
    @DisplayName("Atom author binds from the <name> child and prefers published over updated")
    void atomEntryFieldsAreBoundCompletely() throws Exception {
        List<FeedEntry> entries = parser.parse(FeedFixtures.atom(FeedFixtures.SMALL).getBytes(StandardCharsets.UTF_8));
        FeedEntry first = entries.getFirst();
        assertEquals("Release engineering at scale: shipping daily without fear #0", first.title());
        assertEquals("https://news.example.com/articles/release-engineering-at-scale-0", first.link());
        assertEquals(BASE_INSTANT.minusSeconds(90), first.published());
        assertEquals("Marta Keller", first.author());
        assertEquals("urn:example:article:0", first.uid());
    }

    @Test
    @DisplayName("Byte and stream entry points produce identical entries")
    void byteAndStreamEntryPointsAgree() throws Exception {
        for (int size : SIZES) {
            byte[] document = FeedFixtures.rss(size).getBytes(StandardCharsets.UTF_8);
            assertEquals(parser.parse(document), parser.parse(new ByteArrayInputStream(document)),
                    () -> "rss(" + size + ") must parse identically from bytes and streams");
            document = FeedFixtures.atom(size).getBytes(StandardCharsets.UTF_8);
            assertEquals(parser.parse(document), parser.parse(new ByteArrayInputStream(document)),
                    () -> "atom(" + size + ") must parse identically from bytes and streams");
        }
    }

    @Test
    @DisplayName("A structurally broken document fails fast")
    void malformedDocumentsFailFast() {
        assertThrows(XMLStreamException.class, () -> parser.parse("not xml at all".getBytes(StandardCharsets.UTF_8)));
        assertThrows(XMLStreamException.class,
                () -> parser.parse("<rss><channel><item><title>x</title>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("A broken timestamp fails fast with a diagnosable message")
    void malformedTimestampsFailFast() {
        String document = FeedFixtures.rss(FeedFixtures.SMALL);
        int start = document.indexOf("<pubDate>") + "<pubDate>".length();
        int end = document.indexOf("</pubDate>");
        String broken = document.substring(0, start) + "not-a-timestamp" + document.substring(end);
        XMLStreamException thrown = assertThrows(XMLStreamException.class,
                () -> parser.parse(broken.getBytes(StandardCharsets.UTF_8)));
        assertTrue(thrown.getMessage().contains("Malformed feed timestamp"),
                () -> "the failure must name the broken timestamp, was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("Summary folds are deterministic across repeated parses")
    void summaryFoldsAreDeterministic() throws Exception {
        byte[] document = FeedFixtures.rss(FeedFixtures.MEDIUM).getBytes(StandardCharsets.UTF_8);
        FeedSummary first = FeedSummary.of(parser.parse(document));
        FeedSummary second = FeedSummary.of(parser.parse(document));
        assertEquals(first, second);
        assertEquals(FeedFixtures.MEDIUM, first.count());
        assertEquals(BASE_INSTANT.minusSeconds(90), first.latest());
    }
}
