package ru.jrestly.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.http.Header;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheControlTest {

    @Test
    @DisplayName("parse empty and null headers returns empty CacheControl")
    void parseEmptyHeaders() {
        CacheControl empty1 = CacheControl.empty();
        assertFalse(empty1.noCache());
        assertFalse(empty1.noStore());
        assertFalse(empty1.mustRevalidate());
        assertNull(empty1.maxAgeSeconds());
        assertNull(empty1.staleWhileRevalidateSeconds());
        assertFalse(empty1.isPrivate());
        assertFalse(empty1.isPublic());

        CacheControl parsedNull = CacheControl.parse((List<Header>) null);
        assertFalse(parsedNull.noCache());
        assertNull(parsedNull.maxAgeSeconds());

        CacheControl parsedEmptyString = CacheControl.parse("");
        assertFalse(parsedEmptyString.noCache());

        CacheControl parsedNullString = CacheControl.parse((String) null);
        assertFalse(parsedNullString.noCache());
    }

    @Test
    @DisplayName("parse standard max-age and must-revalidate directives")
    void parseStandardDirectives() {
        CacheControl cc = CacheControl.parse("max-age=3600, must-revalidate, public");
        assertFalse(cc.noCache());
        assertFalse(cc.noStore());
        assertTrue(cc.mustRevalidate());
        assertEquals(3600L, cc.maxAgeSeconds());
        assertTrue(cc.isPublic());
        assertFalse(cc.isPrivate());
    }

    @Test
    @DisplayName("parse no-cache and no-store directives with quotes and whitespace")
    void parseNoCacheNoStore() {
        CacheControl cc = CacheControl.parse(" no-cache , no-store , private, max-age=\"60\", stale-while-revalidate=120 ");
        assertTrue(cc.noCache());
        assertTrue(cc.noStore());
        assertFalse(cc.mustRevalidate());
        assertTrue(cc.isPrivate());
        assertFalse(cc.isPublic());
        assertEquals(60L, cc.maxAgeSeconds());
        assertEquals(120L, cc.staleWhileRevalidateSeconds());
    }

    @Test
    @DisplayName("parse multiple Cache-Control headers aggregated")
    void parseMultipleHeaders() {
        List<Header> headers = List.of(
                new Header("Content-Type", "application/json"),
                new Header("Cache-Control", "public, max-age=1800"),
                new Header("Cache-Control", "must-revalidate")
        );
        CacheControl cc = CacheControl.parse(headers);
        assertTrue(cc.isPublic());
        assertTrue(cc.mustRevalidate());
        assertEquals(1800L, cc.maxAgeSeconds());
    }

    @Test
    @DisplayName("parse ignores invalid numeric formats gracefully")
    void parseInvalidNumbers() {
        CacheControl cc = CacheControl.parse("max-age=invalid, stale-while-revalidate=-5");
        assertNull(cc.maxAgeSeconds());
        assertNull(cc.staleWhileRevalidateSeconds());
    }

    @Test
    @DisplayName("toString produces formatted directives and handles empty instance")
    void testToStringAndDirectives() {
        assertEquals("CacheControl[]", CacheControl.empty().toString());

        CacheControl cc = CacheControl.parse("no-cache, no-store, must-revalidate, max-age=60, stale-while-revalidate=120, private, public, unknown-directive");
        String s = cc.toString();
        assertTrue(s.startsWith("CacheControl["));
        assertTrue(s.contains("no-cache"));
        assertTrue(s.contains("no-store"));
        assertTrue(s.contains("must-revalidate"));
        assertTrue(s.contains("max-age=60"));
        assertTrue(s.contains("stale-while-revalidate=120"));
        assertTrue(s.contains("private"));
        assertTrue(s.contains("public"));
    }

    @Test
    @DisplayName("parse handles null elements, null names, null values, empty tokens, and stripped quotes")
    void parseHeaderEdgeCases() {
        List<Header> edgeHeaders = new java.util.ArrayList<>();
        edgeHeaders.add(null);
        edgeHeaders.add(new Header(null, "value"));
        edgeHeaders.add(new Header("Cache-Control", null));
        edgeHeaders.add(new Header("Cache-Control", " , , max-age=\"120\", stale-while-revalidate=\"300\", max-age=-5, max-age, stale-while-revalidate, "));

        CacheControl cc = CacheControl.parse(edgeHeaders);
        assertEquals(120L, cc.maxAgeSeconds());
        assertEquals(300L, cc.staleWhileRevalidateSeconds());

        // Headers list without any Cache-Control
        CacheControl noCc = CacheControl.parse(List.of(new Header("Content-Type", "text/plain")));
        assertEquals(CacheControl.empty(), noCc);
    }

    @Test
    @DisplayName("quote stripping tolerates unbalanced, lone, and empty quotes")
    void parseUnbalancedQuotes() {
        // Lone opening quote: startsWith and endsWith both true, but length == 1
        assertNull(CacheControl.parse("max-age=\"").maxAgeSeconds());
        // Unbalanced opening quote: startsWith true, endsWith false
        assertNull(CacheControl.parse("max-age=\"120").maxAgeSeconds());
        // Empty quoted value: stripQuotes yields "" which is not a number
        assertNull(CacheControl.parse("stale-while-revalidate=\"\"").staleWhileRevalidateSeconds());
        // Well-formed quoted values still parse on both directives
        CacheControl cc = CacheControl.parse("max-age=\"90\", stale-while-revalidate=\"45\"");
        assertEquals(90L, cc.maxAgeSeconds());
        assertEquals(45L, cc.staleWhileRevalidateSeconds());
    }
}

