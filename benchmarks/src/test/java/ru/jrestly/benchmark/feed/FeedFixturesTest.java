package ru.jrestly.benchmark.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanitary gate for the deterministic RSS 2.0 / Atom 1.0 fixtures of the real-scenario
 * benchmark (ADR-0068 phase 1): every fixture must be well-formed XML, carry the
 * expected number of entries and the declared feed complexity (CDATA, entities,
 * namespaces, RFC 822/3339 dates), and be byte-identical across invocations.
 */
@DisplayName("Deterministic Feed Fixtures Sanitary Tests")
class FeedFixturesTest {

    private static final List<Integer> SIZES = List.of(FeedFixtures.SMALL, FeedFixtures.MEDIUM, FeedFixtures.LARGE);

    @Test
    @DisplayName("Every RSS fixture is well-formed XML with the expected number of items")
    void rssFixturesAreWellFormedWithExpectedEntryCount() throws Exception {
        for (int size : SIZES) {
            String document = FeedFixtures.rss(size);
            assertEquals(size, countElements(document, "item"),
                    () -> "rss(" + size + ") must carry exactly " + size + " <item> elements");
            assertEquals(1, countElements(document, "channel"),
                    () -> "rss(" + size + ") must have a single <channel>");
        }
    }

    @Test
    @DisplayName("Every Atom fixture is well-formed XML with the expected number of entries")
    void atomFixturesAreWellFormedWithExpectedEntryCount() throws Exception {
        for (int size : SIZES) {
            String document = FeedFixtures.atom(size);
            assertEquals(size, countElements(document, "entry"),
                    () -> "atom(" + size + ") must carry exactly " + size + " <entry> elements");
            assertEquals(1, countElements(document, "feed"),
                    () -> "atom(" + size + ") must have a single <feed>");
        }
    }

    @Test
    @DisplayName("Generation is deterministic: identical entry counts produce identical documents")
    void fixturesAreDeterministic() {
        for (int size : SIZES) {
            assertEquals(FeedFixtures.rss(size), FeedFixtures.rss(size),
                    () -> "rss(" + size + ") must be byte-identical across invocations");
            assertEquals(FeedFixtures.atom(size), FeedFixtures.atom(size),
                    () -> "atom(" + size + ") must be byte-identical across invocations");
        }
        assertTrue(FeedFixtures.rss(FeedFixtures.SMALL).length() < FeedFixtures.rss(FeedFixtures.MEDIUM).length());
        assertTrue(FeedFixtures.rss(FeedFixtures.MEDIUM).length() < FeedFixtures.rss(FeedFixtures.LARGE).length());
    }

    @Test
    @DisplayName("Fixtures carry the declared real-feed complexity markers")
    void fixturesCarryRealFeedComplexity() {
        String rss = FeedFixtures.rss(FeedFixtures.SMALL);
        assertTrue(rss.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(rss.contains("xmlns:atom=\"http://www.w3.org/2005/Atom\""), "namespaced atom:link");
        assertTrue(rss.contains("xmlns:content=\"http://purl.org/rss/1.0/modules/content/\""), "content:encoded module");
        assertTrue(rss.contains("<content:encoded><![CDATA["), "CDATA sections");
        assertTrue(rss.contains("<description><![CDATA["), "CDATA descriptions");
        assertTrue(rss.contains("&amp;"), "escaped entities");
        assertTrue(rss.contains("&#39;"), "numeric entity for apostrophe");
        assertTrue(rss.contains("<guid isPermaLink=\"false\">"), "guid attributes");
        assertTrue(rss.contains(" GMT</pubDate>"), "RFC 822 dates");

        String atom = FeedFixtures.atom(FeedFixtures.SMALL);
        assertTrue(atom.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(atom.contains("xmlns=\"http://www.w3.org/2005/Atom\""), "Atom namespace");
        assertTrue(atom.contains("<content type=\"html\">&lt;p&gt;"), "escaped HTML content");
        assertTrue(atom.contains("&amp;"), "escaped entities");
        assertTrue(atom.contains("<category term=\""), "category terms");
        assertTrue(atom.matches("(?s).*<published>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z</published>.*"),
                "RFC 3339 dates");
    }

    @Test
    @DisplayName("Non-positive entry counts fail fast")
    void rejectsNonPositiveEntryCounts() {
        assertThrows(IllegalArgumentException.class, () -> FeedFixtures.rss(0));
        assertThrows(IllegalArgumentException.class, () -> FeedFixtures.rss(-1));
        assertThrows(IllegalArgumentException.class, () -> FeedFixtures.atom(0));
        assertThrows(IllegalArgumentException.class, () -> FeedFixtures.atom(-7));
    }

    private static int countElements(String xml, String localName) throws Exception {
        XMLStreamReader reader = XMLInputFactory.newFactory().createXMLStreamReader(new StringReader(xml));
        try {
            int count = 0;
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && localName.equals(reader.getLocalName())) {
                    count++;
                }
            }
            return count;
        } finally {
            reader.close();
        }
    }
}
