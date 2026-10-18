package ru.jrestly.http;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end guard for the zero-garbage dispatch pipeline (ADR-0055): a
 * parameterless call reuses the pre-computed static params/headers/cookies and
 * the conditional 304 fast-path, exercised over a real WireMock server.
 */
@WireMockTest
class ZeroGarbagePipelineTest {

    interface FastPathApi {
        @Get("/ping")
        String ping();

        @Get("/ping")
        String pingWithHeaders(ResponseHeaders headers);

        @Get("/search")
        String search(@RequestParam(name = "q") String query, @RequestParam int page);

        @Post(value = "/form",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String submitForm(@RequestParam String user, @RequestParam String role);

        @Get("/static-cookies")
        @Cookie(name = "token", value = "secret-token")
        @Cookie(name = "env", value = "prod")
        String staticCookies();

        @Get("/etag-cached")
        @RequestHeader(name = "If-None-Match", value = "\"etag-999\"")
        Optional<String> getWithEtag();
    }

    private JRestlyClient client;
    private FastPathApi api;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .build();
        api = client.get(FastPathApi.class);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("Zero-parameter ping dispatches with zero-garbage requestParams and static headers")
    void testZeroParamPing() {
        stubFor(get(urlEqualTo("/ping"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("PONG")));

        String result = api.ping();
        assertEquals("PONG", result);

        verify(getRequestedFor(urlEqualTo("/ping"))
                .withHeader("Accept-Encoding", equalTo("gzip, deflate")));
    }

    @Test
    @DisplayName("Ping with ResponseHeaders holder populates callDuration and metadata")
    void testPingWithResponseHeaders() {
        stubFor(get(urlEqualTo("/ping"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("PONG")));

        ResponseHeaders headers = new ResponseHeaders();
        String result = api.pingWithHeaders(headers);
        assertEquals("PONG", result);
        assertEquals(200, headers.statusCode());
        assertNotNull(headers.duration());
        assertTrue(headers.duration().toNanos() > 0);
    }

    @Test
    @DisplayName("Query parameters are formatted correctly using direct StringBuilder")
    void testQueryParamsDirectFormatting() {
        stubFor(get(urlEqualTo("/search?q=java%2021&page=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("SEARCH_OK")));

        String result = api.search("java 21", 1);
        assertEquals("SEARCH_OK", result);

        verify(getRequestedFor(urlEqualTo("/search?q=java%2021&page=1")));
    }

    @Test
    @DisplayName("Form-urlencoded body is formatted correctly using direct StringBuilder")
    void testFormUrlEncodedDirectFormatting() {
        stubFor(post(urlEqualTo("/form"))
                .withRequestBody(equalTo("user=alice&role=admin"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("FORM_OK")));

        String result = api.submitForm("alice", "admin");
        assertEquals("FORM_OK", result);

        verify(postRequestedFor(urlEqualTo("/form"))
                .withRequestBody(equalTo("user=alice&role=admin")));
    }

    @Test
    @DisplayName("Static cookie header is sent directly from pre-computed string")
    void testStaticCookieHeaderSending() {
        stubFor(get(urlEqualTo("/static-cookies"))
                .withHeader("Cookie", equalTo("token=secret-token; env=prod"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("COOKIES_OK")));

        String result = api.staticCookies();
        assertEquals("COOKIES_OK", result);

        verify(getRequestedFor(urlEqualTo("/static-cookies"))
                .withHeader("Cookie", equalTo("token=secret-token; env=prod")));
    }

    @Test
    @DisplayName("Conditional fast-path returns Optional.empty() on 304 Not Modified")
    void testConditionalFastPath() {
        stubFor(get(urlEqualTo("/etag-cached"))
                .withHeader("If-None-Match", equalTo("\"etag-999\""))
                .willReturn(aResponse()
                        .withStatus(304)));

        Optional<String> result = api.getWithEtag();
        assertTrue(result.isEmpty());

        verify(getRequestedFor(urlEqualTo("/etag-cached"))
                .withHeader("If-None-Match", equalTo("\"etag-999\"")));
    }
}
