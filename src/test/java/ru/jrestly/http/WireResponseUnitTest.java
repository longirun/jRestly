package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WireResponse} and {@link WireResponse.Builder} factory methods,
 * header access, body representations, equality and string representation.
 */
class WireResponseUnitTest {

    @Test
    @DisplayName("Static factory of(statusCode) initializes empty response with default HTTP/1.1")
    void staticFactoryStatusCodeOnly() {
        WireResponse resp = WireResponse.of(204);
        assertEquals(204, resp.statusCode());
        assertTrue(resp.headers().isEmpty());
        assertNull(resp.body());
        assertNull(resp.stream());
        assertEquals(HttpClient.Version.HTTP_1_1, resp.version());
        assertTrue(resp.trailers().isEmpty());
        assertFalse(resp.hasBody());
    }

    @Test
    @DisplayName("Static factory of(statusCode, String) handles null and non-null strings")
    void staticFactoryStringBody() {
        WireResponse resp = WireResponse.of(200, "hello world");
        assertEquals(200, resp.statusCode());
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), resp.body());
        assertTrue(resp.hasBody());

        WireResponse respNull = WireResponse.of(200, (String) null);
        assertEquals(200, respNull.statusCode());
        assertNull(respNull.body());
        assertFalse(respNull.hasBody());
    }

    @Test
    @DisplayName("Static factory of(statusCode, byte[]) handles null, empty, and non-empty byte arrays")
    void staticFactoryByteArrayBody() {
        byte[] data = new byte[]{1, 2, 3};
        WireResponse resp = WireResponse.of(200, data);
        assertEquals(200, resp.statusCode());
        assertArrayEquals(data, resp.body());
        assertTrue(resp.hasBody());

        WireResponse respEmpty = WireResponse.of(200, new byte[0]);
        assertEquals(0, respEmpty.body().length);
        assertFalse(respEmpty.hasBody());

        WireResponse respNull = WireResponse.of(200, (byte[]) null);
        assertNull(respNull.body());
        assertFalse(respNull.hasBody());
    }

    @Test
    @DisplayName("Static factory of(statusCode, headers, String/byte[]) preserves headers and payloads")
    void staticFactoryWithHeaders() {
        List<Header> headers = List.of(new Header("Content-Type", "text/plain"));
        WireResponse respStr = WireResponse.of(200, headers, "content");
        assertEquals(headers, respStr.headers());
        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), respStr.body());

        WireResponse respStrNull = WireResponse.of(200, headers, (String) null);
        assertEquals(headers, respStrNull.headers());
        assertNull(respStrNull.body());

        byte[] raw = new byte[]{9, 8, 7};
        WireResponse respBytes = WireResponse.of(201, headers, raw);
        assertEquals(headers, respBytes.headers());
        assertArrayEquals(raw, respBytes.body());
    }

    @Test
    @DisplayName("Header lookup finds first matching header case-insensitively and throws on null name")
    void headerLookup() {
        List<Header> headers = List.of(
                new Header("X-Custom", "first"),
                new Header("x-custom", "second")
        );
        WireResponse resp = WireResponse.of(200, headers, "body");

        assertEquals(Optional.of("first"), resp.header("X-Custom"));
        assertEquals(Optional.of("first"), resp.header("x-custom"));
        assertEquals(Optional.of("first"), resp.header("X-CUSTOM"));
        assertTrue(resp.header("Non-Existent").isEmpty());

        assertThrows(NullPointerException.class, () -> resp.header(null));
    }

    @Test
    @DisplayName("WireResponse.Builder fluent methods construct response accurately")
    void builderMethods() {
        Header h1 = new Header("Content-Type", "application/json");
        Header t1 = new Header("X-Checksum", "abc");

        WireResponse resp = WireResponse.builder()
                .statusCode(202)
                .header("X-Trace", "123")
                .header(h1)
                .headers(List.of(new Header("X-Req", "test")))
                .headers(null) // null-safe
                .body("{\"key\":\"value\"}")
                .version(HttpClient.Version.HTTP_2)
                .trailer("X-Trailer-1", "t1")
                .trailer(t1)
                .trailers(List.of(new Header("X-Trailer-2", "t2")))
                .trailers(null) // null-safe
                .build();

        assertEquals(202, resp.statusCode());
        assertEquals(3, resp.headers().size());
        assertEquals(Optional.of("123"), resp.header("X-Trace"));
        assertEquals(Optional.of("application/json"), resp.header("Content-Type"));
        assertEquals(Optional.of("test"), resp.header("X-Req"));
        assertEquals(HttpClient.Version.HTTP_2, resp.version());
        assertEquals(3, resp.trailers().size());
        assertEquals("t1", resp.trailers().get(0).value());
        assertEquals("abc", resp.trailers().get(1).value());
        assertEquals("t2", resp.trailers().get(2).value());
        assertTrue(resp.hasBody());
    }

    @Test
    @DisplayName("WireResponse.Builder body overloads: byte[], Charset, and InputStream")
    void builderBodyOverloads() {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        WireResponse respBytes = WireResponse.builder()
                .body(bytes)
                .build();
        assertArrayEquals(bytes, respBytes.body());
        assertNull(respBytes.stream());

        // body with custom charset
        WireResponse respCharset = WireResponse.builder()
                .body("тест", StandardCharsets.ISO_8859_1)
                .build();
        assertArrayEquals("тест".getBytes(StandardCharsets.ISO_8859_1), respCharset.body());
        assertNull(respCharset.stream());

        // body with null string and custom charset
        WireResponse respCharsetNull = WireResponse.builder()
                .body((String) null, StandardCharsets.UTF_8)
                .build();
        assertNull(respCharsetNull.body());

        assertThrows(NullPointerException.class, () -> WireResponse.builder().body("test", null));

        // stream body clears byte array
        InputStream in = new ByteArrayInputStream(bytes);
        WireResponse respStream = WireResponse.builder()
                .body(bytes)
                .stream(in)
                .build();
        assertNull(respStream.body());
        assertSame(in, respStream.stream());

        // byte body clears stream
        WireResponse respByteReset = WireResponse.builder()
                .stream(in)
                .body(bytes)
                .build();
        assertArrayEquals(bytes, respByteReset.body());
        assertNull(respByteReset.stream());

        // string body clears stream
        WireResponse respStringReset = WireResponse.builder()
                .stream(in)
                .body("hello")
                .build();
        assertNotNull(respStringReset.body());
        assertNull(respStringReset.stream());

        // version(null) defaults to HTTP_1_1
        WireResponse respNullVer = WireResponse.builder()
                .version(null)
                .build();
        assertEquals(HttpClient.Version.HTTP_1_1, respNullVer.version());
    }

    @Test
    @DisplayName("WireResponse.Builder null checks on required header and trailer names")
    void builderNullChecks() {
        assertThrows(NullPointerException.class, () -> WireResponse.builder().header(null, "val"));
        assertThrows(NullPointerException.class, () -> WireResponse.builder().header(null));
        assertThrows(NullPointerException.class, () -> WireResponse.builder().trailer(null, "val"));
        assertThrows(NullPointerException.class, () -> WireResponse.builder().trailer(null));
    }

    @Test
    @DisplayName("equals, hashCode, and toString contracts")
    void equalsHashCodeAndToString() {
        WireResponse r1 = WireResponse.builder()
                .statusCode(200)
                .header("X-A", "1")
                .body("payload")
                .version(HttpClient.Version.HTTP_1_1)
                .trailer("X-T", "val")
                .build();

        WireResponse r2 = WireResponse.builder()
                .statusCode(200)
                .header("X-A", "1")
                .body("payload")
                .version(HttpClient.Version.HTTP_1_1)
                .trailer("X-T", "val")
                .build();

        assertEquals(r1, r1);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertFalse(r1.equals(null));
        assertFalse(r1.equals("different-type"));

        // Differences break equality
        assertNotEquals(r1, WireResponse.builder().statusCode(201).body("payload").build());
        assertNotEquals(r1, WireResponse.builder().statusCode(200).header("X-B", "2").body("payload").build());
        assertNotEquals(r1, WireResponse.builder().statusCode(200).body("different").build());
        assertNotEquals(r1, WireResponse.builder().statusCode(200).body("payload").version(HttpClient.Version.HTTP_2).build());

        // toString formatting with body length and null body
        String strWithBody = r1.toString();
        assertTrue(strWithBody.contains("statusCode=200"));
        assertTrue(strWithBody.contains("bytes]"));
        assertTrue(strWithBody.contains("version=HTTP_1_1"));

        WireResponse rNullBody = WireResponse.of(204);
        String strNullBody = rNullBody.toString();
        assertTrue(strNullBody.contains("body=null"));
    }

    @Test
    @DisplayName("TrailerCompletingInputStream intercepts EOF on byte-by-byte read and triggers trailers consumer")
    void testTrailerCompletingInputStreamSingleByteRead() throws java.io.IOException {
        byte[] data = new byte[]{42};
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        List<Header> trailers = List.of(new Header("X-Trailer", "complete"));
        try (TrailerCompletingInputStream in = new TrailerCompletingInputStream(
                new ByteArrayInputStream(data),
                () -> trailers,
                list -> completed.set(true))) {
            assertEquals(42, in.read());
            assertFalse(completed.get());
            assertEquals(-1, in.read());
            assertTrue(completed.get());
        }

        // Safe null supplier and consumer defaults
        try (TrailerCompletingInputStream in = new TrailerCompletingInputStream(
                new ByteArrayInputStream(new byte[0]),
                null,
                null)) {
            assertEquals(-1, in.read());
        }
    }
}
