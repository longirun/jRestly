package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.RequestHeader;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestHeadersTest extends BaseWireMockTest {

    interface HeaderFeatureController {
        @Get(path = "/api/headers/multi")
        String getWithMultiHeader(@RequestHeader(name = "X-Tag") List<String> tags);
    }

    private HeaderFeatureController headerController() {
        return client.get(HeaderFeatureController.class);
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
}
