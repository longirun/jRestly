package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.RequestHeader;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestHeadersTest extends BaseWireMockTest {

    interface HeaderFeatureController {
        @Get(path = "/api/headers/multi")
        String getWithMultiHeader(@RequestHeader(name = "X-Tag") List<String> tags);
    }

    interface MapHeaderController {
        @Get(path = "/api/headers/dynamic")
        String getWithMapHeaders(@RequestHeader Map<String, ?> headers);
    }

    private HeaderFeatureController headerController() {
        return client.get(HeaderFeatureController.class);
    }

    private MapHeaderController mapHeaderController() {
        return client.get(MapHeaderController.class);
    }

    @Test
    @DisplayName("@RequestHeader adds header to request")
    void customHeaderIsSent() {
        stubFor(get(urlEqualTo("/api/headers"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        String result = controller.getWithHeaders("my-custom-value");

        assertEquals("ok", result);

        verify(getRequestedFor(urlEqualTo("/api/headers"))
                .withHeader("X-Custom", equalTo("my-custom-value")));
    }

    @Test
    @DisplayName("@RequestHeader with null value is omitted from the request")
    void nullHeaderIsOmitted() {
        stubFor(get(urlEqualTo("/api/headers"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        String result = controller.getWithHeaders(null);

        assertEquals("ok", result);

        verify(getRequestedFor(urlEqualTo("/api/headers"))
                .withoutHeader("X-Custom"));
    }

    @Test
    @DisplayName("@RequestHeader with Collection sends multiple headers with the same name")
    void collectionHeaderSendsMultipleHeaders() {
        stubFor(get(urlEqualTo("/api/headers/multi"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("multi-ok")));

        String result = headerController().getWithMultiHeader(List.of("tag-a", "tag-b"));

        assertEquals("multi-ok", result);

        verify(getRequestedFor(urlEqualTo("/api/headers/multi"))
                .withHeader("X-Tag", equalTo("tag-a"))
                .withHeader("X-Tag", equalTo("tag-b")));
    }

    @Test
    @DisplayName("@RequestHeader with Collection skips null elements")
    void collectionHeaderSkipsNullElements() {
        stubFor(get(urlEqualTo("/api/headers/multi"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("multi-ok")));

        String result = headerController().getWithMultiHeader(Arrays.asList("tag-a", null, "tag-b"));

        assertEquals("multi-ok", result);

        verify(getRequestedFor(urlEqualTo("/api/headers/multi"))
                .withHeader("X-Tag", equalTo("tag-a"))
                .withHeader("X-Tag", equalTo("tag-b")));
    }

    @Test
    @DisplayName("@RequestHeader with Collection containing only nulls is omitted")
    void collectionHeaderWithOnlyNullsIsOmitted() {
        stubFor(get(urlEqualTo("/api/headers/multi"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("multi-ok")));

        String result = headerController().getWithMultiHeader(Collections.singletonList(null));

        assertEquals("multi-ok", result);

        verify(getRequestedFor(urlEqualTo("/api/headers/multi"))
                .withoutHeader("X-Tag"));
    }

    @Test
    @DisplayName("@RequestHeader with empty Collection is omitted")
    void emptyCollectionHeaderIsOmitted() {
        stubFor(get(urlEqualTo("/api/headers/multi"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("multi-ok")));

        String result = headerController().getWithMultiHeader(List.of());

        assertEquals("multi-ok", result);

        verify(getRequestedFor(urlEqualTo("/api/headers/multi"))
                .withoutHeader("X-Tag"));
    }

    @Test
    @DisplayName("Auth header is added when provider is authorized")
    void authHeaderIsSentWhenAuthorized() {
        moduleInfo.getTestAuthProvider().setAuthorized(true);
        moduleInfo.getTestAuthProvider().updateAuthHeader("Authorization", "Bearer test-token");

        stubFor(get(urlEqualTo("/api/auth-check"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"auth\",\"description\":\"ok\"}")));

        controller.getAuthorized();

        verify(getRequestedFor(urlEqualTo("/api/auth-check"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    @DisplayName("@RequestHeader with Map sends each entry as an individual header")
    void mapHeaderSendsIndividualHeaders() {
        stubFor(get(urlEqualTo("/api/headers/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("X-Trace-Id", "trace-123");
        headers.put("X-Client-Version", 42);

        String result = mapHeaderController().getWithMapHeaders(headers);

        assertEquals("ok", result);
        verify(getRequestedFor(urlEqualTo("/api/headers/dynamic"))
                .withHeader("X-Trace-Id", equalTo("trace-123"))
                .withHeader("X-Client-Version", equalTo("42")));
    }

    @Test
    @DisplayName("@RequestHeader with Map containing Collection values sends multiple headers")
    void mapHeaderWithCollectionSendsMultipleHeaders() {
        stubFor(get(urlEqualTo("/api/headers/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("X-Role", List.of("admin", "editor"));

        String result = mapHeaderController().getWithMapHeaders(headers);

        assertEquals("ok", result);
        verify(getRequestedFor(urlEqualTo("/api/headers/dynamic"))
                .withHeader("X-Role", equalTo("admin"))
                .withHeader("X-Role", equalTo("editor")));
    }

    @Test
    @DisplayName("@RequestHeader with Map skips null keys, null values, and null collection elements")
    void mapHeaderSkipsNullKeysAndValues() {
        stubFor(get(urlEqualTo("/api/headers/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(null, "should-be-skipped");
        headers.put("X-Null-Val", null);
        headers.put("X-Tags", Arrays.asList("active", null, "verified"));

        String result = mapHeaderController().getWithMapHeaders(headers);

        assertEquals("ok", result);
        verify(getRequestedFor(urlEqualTo("/api/headers/dynamic"))
                .withoutHeader("null")
                .withoutHeader("X-Null-Val")
                .withHeader("X-Tags", equalTo("active"))
                .withHeader("X-Tags", equalTo("verified")));
    }

    @Test
    @DisplayName("@RequestHeader with null or empty Map is omitted")
    void mapHeaderNullOrEmptyIsOmitted() {
        stubFor(get(urlEqualTo("/api/headers/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        mapHeaderController().getWithMapHeaders(Map.of());
        mapHeaderController().getWithMapHeaders(null);

        verify(2, getRequestedFor(urlEqualTo("/api/headers/dynamic")));
    }
}
