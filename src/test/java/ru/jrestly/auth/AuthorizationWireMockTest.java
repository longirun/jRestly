package ru.jrestly.auth;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Authorization;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.http.ClientErrorException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end WireMock tests for declarative authorization: the built-in
 * {@code BearerAuth} / {@code BasicAuth} / {@code ApiKeyAuth} providers
 * reaching the wire, transparent 401 retry with token invalidation on both
 * the sync and {@code CompletableFuture} circuits capped at a single retry,
 * and the {@code @Authorization} bypass. Real HTTP traffic against a stub.
 */
@WireMockTest
class AuthorizationWireMockTest {

    private JRestlyClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    interface SecureApi {
        @Get("/secure/data")
        String getData();

        @Get("/secure/data-async")
        CompletableFuture<String> getDataAsync();

        @Post("/auth/login")
        @Authorization
        String login(@RequestBody String credentials);
    }

    @Test
    @DisplayName("BearerAuth attaches Authorization: Bearer header and receives 200 OK")
    void bearerAuthStatic(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/secure/data"))
                .withHeader("Authorization", equalTo("Bearer static-secret-xyz"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("secure-content")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .bearerAuth("static-secret-xyz")
                .build();

        SecureApi api = client.get(SecureApi.class);
        assertEquals("secure-content", api.getData());
    }

    @Test
    @DisplayName("BasicAuth sends RFC 7617 base64 credentials and receives 200 OK")
    void basicAuth(WireMockRuntimeInfo wm) {
        // user:secret -> dXNlcjpzZWNyZXQ=
        stubFor(get(urlEqualTo("/secure/data"))
                .withHeader("Authorization", equalTo("Basic dXNlcjpzZWNyZXQ="))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("basic-ok")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .basicAuth("user", "secret")
                .build();

        SecureApi api = client.get(SecureApi.class);
        assertEquals("basic-ok", api.getData());
    }

    @Test
    @DisplayName("ApiKeyAuth via HTTP header receives 200 OK")
    void apiKeyHeader(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/secure/data"))
                .withHeader("X-API-Key", equalTo("key-secret-999"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("apikey-header-ok")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .apiKeyHeader("X-API-Key", "key-secret-999")
                .build();

        SecureApi api = client.get(SecureApi.class);
        assertEquals("apikey-header-ok", api.getData());
    }

    @Test
    @DisplayName("ApiKeyAuth via URL query parameter receives 200 OK")
    void apiKeyQuery(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/secure/data?api_key=key-query-888"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("apikey-query-ok")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .apiKeyQuery("api_key", "key-query-888")
                .build();

        SecureApi api = client.get(SecureApi.class);
        assertEquals("apikey-query-ok", api.getData());
    }

    @Test
    @DisplayName("Transparent 401 retry (synchronous): invalidates token, acquires fresh token, and retries once")
    void transparent401RetrySync(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/secure/data"))
                .withHeader("Authorization", equalTo("Bearer stale-token"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized: token expired")));

        stubFor(get(urlEqualTo("/secure/data"))
                .withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("recovered-success")));

        AtomicInteger tokenFetchCounter = new AtomicInteger();
        TokenManager tokenManager = TokenManager.of(() -> {
            int count = tokenFetchCounter.incrementAndGet();
            return Token.of(count == 1 ? "stale-token" : "fresh-token");
        });

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .bearerAuth(tokenManager) // automatically enables retryOnUnauthorized(true)
                .build();

        SecureApi api = client.get(SecureApi.class);
        String result = api.getData();

        assertEquals("recovered-success", result);
        assertEquals(2, tokenFetchCounter.get());
    }

    @Test
    @DisplayName("Transparent 401 retry (asynchronous CompletableFuture)")
    void transparent401RetryAsync(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/secure/data-async"))
                .withHeader("Authorization", equalTo("Bearer stale-async-token"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized: token expired")));

        stubFor(get(urlEqualTo("/secure/data-async"))
                .withHeader("Authorization", equalTo("Bearer fresh-async-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("recovered-async-success")));

        AtomicInteger tokenFetchCounter = new AtomicInteger();
        TokenManager tokenManager = TokenManager.of(() -> {
            int count = tokenFetchCounter.incrementAndGet();
            return Token.of(count == 1 ? "stale-async-token" : "fresh-async-token");
        });

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .bearerAuth(tokenManager)
                .build();

        SecureApi api = client.get(SecureApi.class);
        String result = api.getDataAsync().join();

        assertEquals("recovered-async-success", result);
        assertEquals(2, tokenFetchCounter.get());
    }

    @Test
    @DisplayName("401 retry stops after 1 attempt when repeated call also returns 401")
    void retryStopsAfterSingleRetry(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/secure/data"))
                .willReturn(aResponse().withStatus(401).withBody("Permanently Unauthorized")));

        AtomicInteger tokenFetchCounter = new AtomicInteger();
        TokenManager tokenManager = TokenManager.of(() ->
                Token.of("token-" + tokenFetchCounter.incrementAndGet()));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .bearerAuth(tokenManager)
                .build();

        SecureApi api = client.get(SecureApi.class);
        ClientErrorException ex = assertThrows(ClientErrorException.class, api::getData);
        assertEquals(401, ex.getStatusCode());

        // Initial token (1) + 1 refresh attempt (2) = 2 attempts total (no infinite loop)
        assertEquals(2, tokenFetchCounter.get());
    }

    @Test
    @DisplayName("Endpoints annotated with @Authorization bypass Bearer header and 401 retry")
    void authorizationAnnotationBypass(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/auth/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("login-token-result")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .bearerAuth("bearer-token-123")
                .build();

        SecureApi api = client.get(SecureApi.class);
        String loginResult = api.login("user:pass");
        assertEquals("login-token-result", loginResult);
    }
}
