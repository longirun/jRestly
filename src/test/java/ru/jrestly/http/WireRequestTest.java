package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireRequestTest {

    private static final URI URI_A = URI.create("https://api.example.com/v1/items");
    private static final URI URI_B = URI.create("https://api.example.com/v2/items");

    @Test
    @DisplayName("withHeader(name, value) appends header to existing headers")
    void withHeaderNameValueAppendsHeader() {
        WireRequest initial = new WireRequest(HttpMethod.GET, URI_A, List.of(new Header("A", "1")), null, null, null, null);
        WireRequest updated = initial.withHeader("B", "2");

        assertEquals(2, updated.headers().size());
        assertEquals("A", updated.headers().get(0).name());
        assertEquals("1", updated.headers().get(0).value());
        assertEquals("B", updated.headers().get(1).name());
        assertEquals("2", updated.headers().get(1).value());
        assertEquals(1, initial.headers().size(), "Original request must remain unchanged");
    }

    @Test
    @DisplayName("withHeader on request with null headers initializes list with single header")
    void withHeaderOnNullHeaders() {
        WireRequest initial = new WireRequest(HttpMethod.GET, URI_A, null, null, null, null, null);
        WireRequest updated = initial.withHeader("X-Trace", "abc");

        assertNotNull(updated.headers());
        assertEquals(1, updated.headers().size());
        assertEquals(new Header("X-Trace", "abc"), updated.headers().get(0));
    }

    @Test
    @DisplayName("withHeader(Header) and withHeaders(List) append correctly")
    void withHeaderObjectAndWithHeaders() {
        WireRequest initial = new WireRequest(HttpMethod.GET, URI_A, List.of(new Header("A", "1")), null, null, null, null);
        WireRequest step1 = initial.withHeader(new Header("B", "2"));
        WireRequest step2 = step1.withHeaders(List.of(new Header("C", "3"), new Header("D", "4")));

        assertEquals(4, step2.headers().size());
        assertEquals(List.of(
                new Header("A", "1"),
                new Header("B", "2"),
                new Header("C", "3"),
                new Header("D", "4")
        ), step2.headers());
    }

    @Test
    @DisplayName("withoutHeader removes all matching headers case-insensitively")
    void withoutHeaderCaseInsensitive() {
        List<Header> headers = List.of(
                new Header("Authorization", "Bearer old"),
                new Header("AUTHORIZATION", "Bearer duplicate"),
                new Header("X-Keep", "ok"),
                new Header("authorization", "Bearer lowercase")
        );
        WireRequest initial = new WireRequest(HttpMethod.POST, URI_A, headers, null, null, null, null);
        WireRequest updated = initial.withoutHeader("authorization");

        assertEquals(1, updated.headers().size());
        assertEquals(new Header("X-Keep", "ok"), updated.headers().get(0));
    }

    @Test
    @DisplayName("withoutHeader returns same instance when no matching headers exist or headers are null/empty")
    void withoutHeaderNoOpReturnsSame() {
        WireRequest withNull = new WireRequest(HttpMethod.GET, URI_A, null, null, null, null, null);
        assertSame(withNull, withNull.withoutHeader("X-Any"));

        WireRequest withHeaders = new WireRequest(HttpMethod.GET, URI_A, List.of(new Header("A", "1")), null, null, null, null);
        WireRequest unchanged = withHeaders.withoutHeader("B");
        assertEquals(withHeaders.headers(), unchanged.headers());
    }

    @Test
    @DisplayName("withUri, withMethod, withBody, withContentType and withParts return updated copies")
    void withBasicProperties() {
        WireRequest initial = new WireRequest(HttpMethod.GET, URI_A, List.of(), null, null, null, null);

        WireRequest changedUri = initial.withUri(URI_B);
        assertEquals(URI_B, changedUri.uri());
        assertEquals(HttpMethod.GET, changedUri.method());

        WireRequest changedMethod = initial.withMethod(HttpMethod.POST);
        assertEquals(HttpMethod.POST, changedMethod.method());

        byte[] payload = "test-payload".getBytes();
        WireRequest withBody = initial.withBody(payload, "application/json");
        assertArrayEquals(payload, withBody.body());
        assertEquals("application/json", withBody.contentType());

        WireRequest withOnlyBody = initial.withBody(payload);
        assertArrayEquals(payload, withOnlyBody.body());
        assertNull(withOnlyBody.contentType());

        WireRequest withOnlyContentType = initial.withContentType("text/plain");
        assertEquals("text/plain", withOnlyContentType.contentType());
        assertNull(withOnlyContentType.body());

        MultipartPart part = MultipartPart.file("file", "test.txt", "data".getBytes());
        WireRequest withParts = initial.withParts(List.of(part), "boundary-123");
        assertEquals(1, withParts.parts().size());
        assertEquals("boundary-123", withParts.boundary());
    }

    @Test
    @DisplayName("builder and toBuilder recreate and modify WireRequest cleanly")
    void builderAndToBuilder() {
        byte[] body = "payload".getBytes();
        WireRequest original = WireRequest.builder()
                .method(HttpMethod.PUT)
                .uri(URI_A)
                .header("X-Custom", "val")
                .header(new Header("X-Custom-2", "val2"))
                .contentType("application/json")
                .body(body)
                .build();

        assertEquals(HttpMethod.PUT, original.method());
        assertEquals(URI_A, original.uri());
        assertEquals(2, original.headers().size());
        assertEquals("application/json", original.contentType());
        assertArrayEquals(body, original.body());
        assertNull(original.parts());
        assertNull(original.boundary());

        WireRequest modified = original.toBuilder()
                .uri(URI_B)
                .method(HttpMethod.PATCH)
                .headers(List.of(new Header("Authorization", "Bearer 123")))
                .build();

        assertEquals(HttpMethod.PATCH, modified.method());
        assertEquals(URI_B, modified.uri());
        assertEquals(1, modified.headers().size());
        assertEquals("Authorization", modified.headers().get(0).name());
        assertArrayEquals(body, modified.body());
    }

    @Test
    @DisplayName("Null validations on withers throw NullPointerException")
    void nullValidationChecks() {
        WireRequest req = new WireRequest(HttpMethod.GET, URI_A, null, null, null, null, null);

        assertThrows(NullPointerException.class, () -> req.withHeader(null, "val"));
        assertThrows(NullPointerException.class, () -> req.withHeader("name", null));
        assertThrows(NullPointerException.class, () -> req.withHeader((Header) null));
        assertThrows(NullPointerException.class, () -> req.withHeaders(null));
        assertThrows(NullPointerException.class, () -> req.withoutHeader(null));
        assertThrows(NullPointerException.class, () -> req.withUri(null));
        assertThrows(NullPointerException.class, () -> req.withMethod(null));
    }
}
