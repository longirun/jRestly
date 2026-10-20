package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification of {@link UriEncoder} fast-paths, unreserved character
 * classification, parity with {@link URLEncoder}, and single-pass URI composition (ADR-0072).
 */
class UriEncoderTest {

    @Test
    @DisplayName("private constructor can be accessed via reflection")
    void privateConstructorContract() throws Exception {
        Constructor<UriEncoder> constructor = UriEncoder.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        UriEncoder instance = constructor.newInstance();
        assertNotNull(instance);
    }

    @Test
    @DisplayName("isUnreserved returns true for null and empty strings")
    void isUnreservedNullAndEmpty() {
        assertTrue(UriEncoder.isUnreserved(null));
        assertTrue(UriEncoder.isUnreserved(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcXYZ",
            "1234567890",
            "a-b_c.d*e",
            "page",
            "size",
            "ACTIVE",
            "2026-10-19",
            "c3ab5e78-0000-4000-8000-000000000001",
            "9999999999"
    })
    @DisplayName("isUnreserved returns true for purely alphanumeric and safe delimiter strings")
    void isUnreservedMatchesSafeStrings(String input) {
        assertTrue(UriEncoder.isUnreserved(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "hello world",
            "a+b",
            "user/123",
            "key=val",
            "a&b",
            "id?",
            "item#top",
            "100%",
            "привет",
            "café",
            "value with\nnewline"
    })
    @DisplayName("isUnreserved returns false when string contains reserved or non-ASCII characters")
    void isUnreservedRejectsReservedStrings(String input) {
        assertFalse(UriEncoder.isUnreserved(input));
    }

    @Test
    @DisplayName("encode returns null for null input and empty for empty input")
    void encodeNullAndEmpty() {
        assertNull(UriEncoder.encode(null));
        assertEquals("", UriEncoder.encode(""));
    }

    @Test
    @DisplayName("encode returns identical string instance for unreserved inputs (zero allocation)")
    void encodeReturnsSameInstanceForUnreserved() {
        String input = "simple_identifier-123.test*";
        String encoded = UriEncoder.encode(input);
        assertSame(input, encoded);
    }

    @Test
    @DisplayName("encode matches URLEncoder parity with %20 for spaces and reserved characters")
    void encodeMatchesStandardUrlEncoderParity() {
        String input = "hello world & foo/bar = 100% (привет)";
        String expected = URLEncoder.encode(input, StandardCharsets.UTF_8).replace("+", "%20");
        String actual = UriEncoder.encode(input);

        assertEquals(expected, actual);
        assertTrue(actual.contains("%20"));
        assertFalse(actual.contains("+"));
    }

    @Test
    @DisplayName("encodeTo appends nothing for null or empty input")
    void encodeToNullAndEmpty() {
        StringBuilder sb = new StringBuilder("prefix:");
        UriEncoder.encodeTo(null, sb);
        UriEncoder.encodeTo("", sb);
        assertEquals("prefix:", sb.toString());
    }

    @Test
    @DisplayName("encodeTo appends directly for unreserved inputs and percent-encodes reserved inputs")
    void encodeToAppendsCorrectly() {
        StringBuilder sb = new StringBuilder();
        UriEncoder.encodeTo("page", sb);
        sb.append('=');
        UriEncoder.encodeTo("10", sb);
        sb.append('&');
        UriEncoder.encodeTo("query", sb);
        sb.append('=');
        UriEncoder.encodeTo("hello world", sb);

        assertEquals("page=10&query=hello%20world", sb.toString());
    }

    @Test
    @DisplayName("UrlComposer.appendQuery preserves query and fragment invariants without substrings")
    void appendQueryComprehensiveInvariants() {
        // Plain URL without query or fragment
        assertEquals("http://host/items?page=1", UrlComposer.appendQuery("http://host/items", "page=1").toString());

        // URL with existing query
        assertEquals("http://host/items?status=open&page=1", UrlComposer.appendQuery("http://host/items?status=open", "page=1").toString());

        // URL with fragment but no query
        assertEquals("http://host/items?page=1#top", UrlComposer.appendQuery("http://host/items#top", "page=1").toString());

        // URL with existing query and fragment
        assertEquals("http://host/items?status=open&page=1#top", UrlComposer.appendQuery("http://host/items?status=open#top", "page=1").toString());

        // URL with fragment containing '?' character: query must still be placed before '#' with '?'
        assertEquals("http://host/items?page=1#frag?withQuestion", UrlComposer.appendQuery("http://host/items#frag?withQuestion", "page=1").toString());

        // Null and empty queries return identical URI
        assertEquals("http://host/items#top", UrlComposer.appendQuery("http://host/items#top", null).toString());
        assertEquals("http://host/items#top", UrlComposer.appendQuery("http://host/items#top", "").toString());
    }

    @Test
    @DisplayName("HttpTemplate.createUri single-pass assembly handles params, existing query, fragments, and valueless params")
    void createUriSinglePassComprehensive() {
        HttpTemplate template = new HttpTemplate();
        template.setUrl("http://host/api/users?active=true#section");
        template.setRequestParams(List.of(
                new Param("role", "admin"),
                new Param("flag", null),
                new Param("name", "John Doe"),
                new Param("slug", "привет")
        ));

        URI uri = UrlComposer.resolve(template.getUrl(), template.getRequestParams());
        String expected = "http://host/api/users?active=true&role=admin&flag&name=John%20Doe&slug=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82#section";
        assertEquals(expected, uri.toString());
    }

    @Test
    @DisplayName("HttpTemplate.createUri handles clean base URL without existing query or fragment")
    void createUriCleanBaseUrl() {
        HttpTemplate template = new HttpTemplate();
        template.setUrl("http://host/api/search");
        template.setRequestParams(List.of(
                new Param("q", "java"),
                new Param("limit", "25")
        ));

        URI uri = UrlComposer.resolve(template.getUrl(), template.getRequestParams());
        assertEquals("http://host/api/search?q=java&limit=25", uri.toString());
    }

    @Test
    @DisplayName("HttpTemplate.createUri returns untouched URI when params are empty or null")
    void createUriNoParams() {
        HttpTemplate template = new HttpTemplate();
        template.setUrl("http://host/api/ping");

        assertEquals(URI.create("http://host/api/ping"), UrlComposer.resolve(template.getUrl(), template.getRequestParams()));

        template.setRequestParams(List.of());
        assertEquals(URI.create("http://host/api/ping"), UrlComposer.resolve(template.getUrl(), template.getRequestParams()));
    }
}
