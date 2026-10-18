package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code HttpTemplate.hasHeader} — the defensive scan behind
 * the ADR-0063 conditional cache requests ({@code If-None-Match} /
 * {@code If-Modified-Since}): a null request or a request without headers
 * is never a match, a null header entry or a null header name is skipped
 * (never dereferenced), and names compare case-insensitively.
 *
 * <p>No network — the seam is package-private on purpose, so the branch
 * matrix is pinned directly against the wire model.
 */
class HttpTemplateConditionalHeaderTest {

    private static final URI TARGET = URI.create("http://example.com/items/1");

    private static WireRequest request(List<Header> headers) {
        return new WireRequest(HttpMethod.GET, TARGET, headers, null, null);
    }

    @Test
    @DisplayName("null request: never a match")
    void nullRequestIsNeverAMatch() {
        assertFalse(HttpTemplate.hasHeader(null, "If-None-Match"));
    }

    @Test
    @DisplayName("null header list: never a match")
    void nullHeadersIsNeverAMatch() {
        assertFalse(HttpTemplate.hasHeader(request(null), "If-None-Match"));
    }

    @Test
    @DisplayName("empty header list: never a match")
    void emptyHeadersIsNeverAMatch() {
        assertFalse(HttpTemplate.hasHeader(request(List.of()), "If-None-Match"));
    }

    @Test
    @DisplayName("matching name: found, case-insensitively")
    void matchingNameIsFoundCaseInsensitively() {
        WireRequest req = request(List.of(new Header("if-none-match", "\"abc\"")));

        assertTrue(HttpTemplate.hasHeader(req, "If-None-Match"));
        assertTrue(HttpTemplate.hasHeader(req, "IF-NONE-MATCH"));
    }

    @Test
    @DisplayName("non-matching name: not found")
    void nonMatchingNameIsNotFound() {
        WireRequest req = request(List.of(new Header("Accept", "application/json")));

        assertFalse(HttpTemplate.hasHeader(req, "If-None-Match"));
    }

    @Test
    @DisplayName("null entry and null name are skipped, a later match still wins")
    void nullEntriesAndNamesAreSkippedThenMatch() {
        List<Header> headers = new ArrayList<>(Arrays.asList(null, new Header(null, "v")));
        headers.add(new Header("If-None-Match", "\"abc\""));

        assertTrue(HttpTemplate.hasHeader(request(headers), "If-None-Match"));
    }

    @Test
    @DisplayName("only null entries and null names: not a match")
    void onlyNullEntriesAndNamesAreNotAMatch() {
        List<Header> headers = new ArrayList<>(Arrays.asList(null, new Header(null, "v"), null));

        assertFalse(HttpTemplate.hasHeader(request(headers), "If-None-Match"));
    }
}
