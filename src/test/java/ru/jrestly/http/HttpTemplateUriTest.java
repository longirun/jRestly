package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@code UrlComposer.appendQuery}: correct {@code ?}/{@code &}
 * joining and insertion before a {@code #fragment}, with a null/empty query
 * leaving the URI untouched. No network.
 */
class HttpTemplateUriTest {

    @Test
    @DisplayName("appendQuery appends query with '?' when URL has no query or fragment")
    void appendQuerySimple() {
        URI uri = UrlComposer.appendQuery("http://example.com/items", "page=1&size=10");
        assertEquals("http://example.com/items?page=1&size=10", uri.toString());
    }

    @Test
    @DisplayName("appendQuery appends query with '&' when URL already has query params")
    void appendQueryWithExistingQuery() {
        URI uri = UrlComposer.appendQuery("http://example.com/items?active=true", "page=1");
        assertEquals("http://example.com/items?active=true&page=1", uri.toString());
    }

    @Test
    @DisplayName("appendQuery inserts query before fragment when URL has '#anchor'")
    void appendQueryWithFragment() {
        URI uri = UrlComposer.appendQuery("http://example.com/items#top", "page=1");
        assertEquals("http://example.com/items?page=1#top", uri.toString());
    }

    @Test
    @DisplayName("appendQuery inserts query before fragment when URL already has query and fragment")
    void appendQueryWithExistingQueryAndFragment() {
        URI uri = UrlComposer.appendQuery("http://example.com/items?active=true#section2", "sort=desc");
        assertEquals("http://example.com/items?active=true&sort=desc#section2", uri.toString());
    }

    @Test
    @DisplayName("appendQuery returns original URI when query is null or empty")
    void appendQueryEmpty() {
        assertEquals("http://example.com/items#top", UrlComposer.appendQuery("http://example.com/items#top", null).toString());
        assertEquals("http://example.com/items#top", UrlComposer.appendQuery("http://example.com/items#top", "").toString());
    }
}
