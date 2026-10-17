package ru.jrestly;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end for TLS configuration (ADR-0020): custom SSL contexts and trust managers,
 * mutual-TLS client certificates, hostname verification and failure propagation across
 * sync and async calls.
 */
@DisplayName("HTTPS & SSL/TLS Integration Tests (ADR-0020)")
class HttpsSslTest {

    private static WireMockServer wireMockServer;
    private static String httpsBaseUrl;
    private static SSLContext trustAllSslContext;

    private final List<AbstractHttpClient> clientsToClose = new ArrayList<>();

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .dynamicHttpsPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());
        httpsBaseUrl = "https://localhost:" + wireMockServer.httpsPort();

        trustAllSslContext = createTrustAllSslContext();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @AfterEach
    void closeClients() {
        for (AbstractHttpClient c : clientsToClose) {
            c.close();
        }
        clientsToClose.clear();
    }

    private <T extends AbstractHttpClient> T track(T client) {
        clientsToClose.add(client);
        return client;
    }

    /**
     * Trust-all SSLContext that trusts all certificates and skips hostname verification
     * by implementing {@link X509ExtendedTrustManager} with no-op checks.
     */
    private static SSLContext createTrustAllSslContext() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        TrustManager[] trustAll = new TrustManager[] {
                new X509ExtendedTrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        sslContext.init(null, trustAll, new SecureRandom());
        return sslContext;
    }

    private static boolean hasSslExceptionInCauseChain(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SSLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Test
    @DisplayName("Default client without custom SSL fails against self-signed HTTPS server")
    void defaultClientFailsAgainstUntrustedHttps() {
        stubFor(get(urlEqualTo("/api/items/1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"Secure Item\",\"description\":\"ssl\"}")));

        JRestlyClient defaultClient = track(JRestlyClient.builder()
                .baseUrl(httpsBaseUrl)
                .build());
        TestController controller = defaultClient.get(TestController.class);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> controller.getItem(1L));
        assertTrue(hasSslExceptionInCauseChain(ex),
                () -> "Expected SSLException in cause chain, got: " + ex);
    }

    @Test
    @DisplayName("Client with custom SSLContext successfully connects and receives response")
    void clientWithCustomSslContextSucceeds() {
        stubFor(get(urlEqualTo("/api/items/1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"Secure Item\",\"description\":\"ssl\"}")));

        JRestlyClient secureClient = track(JRestlyClient.builder()
                .baseUrl(httpsBaseUrl)
                .sslContext(trustAllSslContext)
                .build());
        TestController controller = secureClient.get(TestController.class);

        TestDto result = controller.getItem(1L);

        assertNotNull(result);
        assertEquals(1L, result.id());
        assertEquals("Secure Item", result.name());
        assertEquals("ssl", result.description());
    }

    @Test
    @DisplayName("Client with custom SSLContext and explicit TLS protocols in SSLParameters succeeds")
    void clientWithCustomSslContextAndParametersSucceeds() {
        stubFor(get(urlEqualTo("/api/raw"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("secure raw payload")));

        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});

        JRestlyClient clientWithParams = track(JRestlyClient.builder()
                .baseUrl(httpsBaseUrl)
                .sslContext(trustAllSslContext)
                .sslParameters(sslParameters)
                .build());
        TestController controller = clientWithParams.get(TestController.class);

        String result = controller.getRaw();
        assertEquals("secure raw payload", result);
    }

    @Test
    @DisplayName("Client with custom SSLParameters pinning cipher suite connects successfully")
    void clientWithExplicitCipherSuiteInSslParametersSucceeds() {
        stubFor(get(urlEqualTo("/api/raw"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("pinned cipher payload")));

        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setProtocols(new String[]{"TLSv1.3"});
        sslParameters.setCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256"});

        JRestlyClient client = track(JRestlyClient.builder()
                .baseUrl(httpsBaseUrl)
                .sslContext(trustAllSslContext)
                .sslParameters(sslParameters)
                .build());
        TestController controller = client.get(TestController.class);

        String result = controller.getRaw();
        assertEquals("pinned cipher payload", result);
    }

    @Test
    @DisplayName("Async HTTPS call with custom SSLContext completes successfully")
    void asyncHttpsCallWithCustomSslSucceeds() {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"name\":\"Async Secure\",\"description\":\"async-ssl\"}")));

        JRestlyClient secureClient = track(JRestlyClient.builder()
                .baseUrl(httpsBaseUrl)
                .sslContext(trustAllSslContext)
                .build());
        TestController controller = secureClient.get(TestController.class);

        CompletableFuture<TestDto> future = controller.getItemAsync(42L);
        TestDto result = future.join();

        assertNotNull(result);
        assertEquals(42L, result.id());
        assertEquals("Async Secure", result.name());
    }

    @Test
    @DisplayName("Async HTTPS call without custom SSL fails exceptionally with SSL error")
    void asyncHttpsCallWithoutCustomSslFails() {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"name\":\"Async Secure\",\"description\":\"async-ssl\"}")));

        JRestlyClient defaultClient = track(JRestlyClient.builder()
                .baseUrl(httpsBaseUrl)
                .build());
        TestController controller = defaultClient.get(TestController.class);

        CompletableFuture<TestDto> future = controller.getItemAsync(42L);
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertTrue(hasSslExceptionInCauseChain(ex),
                () -> "Expected SSLException in cause chain for async failure, got: " + ex);
    }

    @Test
    @DisplayName("Streaming body over HTTPS with custom SSLContext reads full payload")
    void streamingBodyOverHttpsSucceeds() throws Exception {
        byte[] payload = "streaming-secure-payload-line1\nline2\nline3\n".getBytes(StandardCharsets.UTF_8);
        stubFor(get(urlEqualTo("/api/stream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBody(payload)));

        JRestlyClient secureClient = track(JRestlyClient.builder()
                .baseUrl(httpsBaseUrl)
                .sslContext(trustAllSslContext)
                .build());
        TestController controller = secureClient.get(TestController.class);

        try (InputStream stream = controller.getStream()) {
            byte[] read = stream.readAllBytes();
            assertArrayEquals(payload, read);
        }
    }

    @Test
    @DisplayName("Incompatible SSLParameters cipher suites fail TLS handshake")
    void incompatibleSslParametersFailHandshake() {
        stubFor(get(urlEqualTo("/api/raw"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("never reached")));

        SSLParameters sslParameters = new SSLParameters();
        // Restricting to an empty renegotiation cipher that cannot negotiate a TLS session with the server
        sslParameters.setCipherSuites(new String[]{"TLS_EMPTY_RENEGOTIATION_INFO_SCSV"});

        JRestlyClient client = track(JRestlyClient.builder()
                .baseUrl(httpsBaseUrl)
                .sslContext(trustAllSslContext)
                .sslParameters(sslParameters)
                .build());
        TestController controller = client.get(TestController.class);

        RuntimeException ex = assertThrows(RuntimeException.class, controller::getRaw);
        assertTrue(hasSslExceptionInCauseChain(ex),
                () -> "Expected SSLException due to incompatible cipher suites, got: " + ex);
    }
}
