package ru.jrestly.benchmark.feed;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Shared StAX feed parser for the real-scenario fetch-and-parse benchmark (ADR-0068):
 * one parser implementation for every benchmark arm.
 *
 * <p>The comparison measures the contribution of the HTTP library (the fetch phase),
 * not of XML bindings &mdash; so the parse phase is deliberately identical for all arms
 * (ADR-0068 &sect;2, fairness #1). The parser is lenient about which elements it sees
 * (unknown elements are skipped) and strict about what it binds: timestamps must be
 * RFC 822 (RSS) or RFC 3339 (Atom), and a structurally broken document fails fast by
 * propagating the {@link XMLStreamException} of the underlying {@link XMLStreamReader}
 * &mdash; the same observable semantics for every arm.
 *
 * <p>Namespace prefixes are ignored (lookups are by local name), so RSS 2.0 and
 * Atom 1.0 documents parse through the same code path: {@code item}/{@code entry}
 * elements are bound to {@link FeedEntry}, everything else is streamed past.
 *
 * <p>Not part of the published library: the whole feed pipeline lives in the
 * non-published benchmark scope (ADR-0049), per the ADR-0068 non-goals.
 */
public final class FeedParser {

    private final XMLInputFactory factory;

    /** Creates a parser with a hardened StAX factory (no external entities, no DTDs). */
    public FeedParser() {
        factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    /**
     * Parses a feed document from a byte array without copying it first.
     *
     * @param document complete RSS 2.0 or Atom 1.0 document
     * @return entries in document order
     * @throws XMLStreamException when the document is not well-formed XML or carries
     *                            an unparsable timestamp
     */
    public List<FeedEntry> parse(byte[] document) throws XMLStreamException {
        XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(document));
        try {
            return entries(reader);
        } finally {
            reader.close();
        }
    }

    /**
     * Parses a feed document straight from the wire stream &mdash; the streaming arm
     * of the benchmark: no full intermediate copy of the body is made.
     *
     * @param stream complete RSS 2.0 or Atom 1.0 document
     * @return entries in document order
     * @throws XMLStreamException when the document is not well-formed XML or carries
     *                            an unparsable timestamp
     */
    public List<FeedEntry> parse(InputStream stream) throws XMLStreamException {
        XMLStreamReader reader = factory.createXMLStreamReader(stream);
        try {
            return entries(reader);
        } finally {
            reader.close();
        }
    }

    private List<FeedEntry> entries(XMLStreamReader reader) throws XMLStreamException {
        List<FeedEntry> entries = new ArrayList<>();
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT
                    && (reader.getLocalName().equals("item") || reader.getLocalName().equals("entry"))) {
                entries.add(entry(reader));
            }
        }
        return entries;
    }

    private FeedEntry entry(XMLStreamReader reader) throws XMLStreamException {
        String title = null;
        String link = null;
        String author = null;
        String uid = null;
        Instant published = null;
        Instant updated = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "title" -> title = text(reader);
                    case "link" -> link = hrefOrText(reader);
                    case "author" -> author = author(reader);
                    case "published", "pubDate" -> {
                        Instant value = instant(reader);
                        if (published == null) {
                            published = value;
                        }
                    }
                    case "updated" -> updated = instant(reader);
                    case "guid", "id" -> {
                        if (uid == null) {
                            uid = text(reader);
                        }
                    }
                    default -> skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                return new FeedEntry(title, link, published != null ? published : updated, author, uid);
            }
        }
        throw new XMLStreamException("Unterminated entry at " + reader.getLocation());
    }

    /**
     * Collects the character data of a leaf element, tolerating nested markup by
     * streaming past it without binding it (CDATA counts as text).
     */
    private static String text(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                sb.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                return sb.toString();
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                skipElement(reader);
            }
        }
        throw new XMLStreamException("Unterminated element at " + reader.getLocation());
    }

    /**
     * Binds a link element: the {@code href} attribute when present (Atom), the element
     * text otherwise (RSS).
     */
    private static String hrefOrText(XMLStreamReader reader) throws XMLStreamException {
        String href = reader.getAttributeValue(null, "href");
        if (href != null) {
            skipElement(reader);
            return href;
        }
        return text(reader);
    }

    /**
     * Binds an author element: for RSS the human name inside the {@code email (Name)}
     * form (the whole element text when there are no parentheses), for Atom the
     * {@code <name>} child text.
     */
    private static String author(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                sb.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                return authorName(sb.toString());
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                if (reader.getLocalName().equals("name")) {
                    sb.append(text(reader));
                } else {
                    skipElement(reader);
                }
            }
        }
        throw new XMLStreamException("Unterminated author at " + reader.getLocation());
    }

    private static String authorName(String raw) {
        int open = raw.indexOf('(');
        int close = raw.lastIndexOf(')');
        return open >= 0 && close > open ? raw.substring(open + 1, close).trim() : raw;
    }

    /**
     * Binds a timestamp: RFC 822 (weekday present, RSS) or RFC 3339 (Atom), fail-fast
     * on anything else.
     */
    private static Instant instant(XMLStreamReader reader) throws XMLStreamException {
        String raw = text(reader).trim();
        try {
            return raw.indexOf(',') >= 0
                    ? OffsetDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                    : Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new XMLStreamException("Malformed feed timestamp: \"" + raw + "\"", reader.getLocation(), e);
        }
    }

    private static void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        if (depth > 0) {
            throw new XMLStreamException("Unterminated element at " + reader.getLocation());
        }
    }
}
