package ru.jrestly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end for proxy routing and transport authentication (ADR-0037): requests routed
 * through an HTTP proxy, proxy credentials and the transport authenticator, sync and async.
 */
@DisplayName("HTTP Proxy and Transport Authenticator WireMock Integration Tests (ADR-0037)")
class ProxyIntegrationWireMockTest extends BaseWireMockTest {

    private static final String ITEM_JSON = "{\"id\":42,\"name\":\"Proxy Item\",\"description\":\"Routed through proxy\"}";

    private TestHttpProxy proxyServer;

    @AfterEach
    void tearDownProxy() {
        if (proxyServer != null) {
            proxyServer.close();
            proxyServer = null;
        }
    }

    @Test
    @DisplayName("Requests route through configured HTTP proxy and receive valid response")
    void requestRoutesThroughConfiguredProxy() throws Exception {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        proxyServer = new TestHttpProxy(wireMockServer.port(), null, null);
        proxyServer.start();

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .proxy("127.0.0.1", proxyServer.getPort())
                .build();

        try {
            TestController controller = client.get(TestController.class);
            TestDto dto = controller.getItem(42L);

            assertNotNull(dto);
            assertEquals(42L, dto.id());
            assertEquals("Proxy Item", dto.name());
            assertTrue(proxyServer.getRequestCount() >= 1, "Proxy server must receive at least 1 request");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Proxy with 407 challenge authenticates automatically using proxyCredentials")
    void proxyWithAuthenticationHandles407Challenge() throws Exception {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        proxyServer = new TestHttpProxy(wireMockServer.port(), "corpUser", "corpSecret");
        proxyServer.start();

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .proxy("127.0.0.1", proxyServer.getPort())
                .proxyCredentials("corpUser", "corpSecret")
                .build();

        try {
            TestController controller = client.get(TestController.class);
            TestDto dto = controller.getItem(42L);

            assertNotNull(dto);
            assertEquals(42L, dto.id());
            // Proxy must see 407 challenge followed by authenticated request
            assertTrue(proxyServer.getRequestCount() >= 2, "Expected initial challenge and authenticated retry");
            assertTrue(proxyServer.hasSeenHeader("Proxy-Authorization"), "Proxy-Authorization header must be sent by client");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Proxy with 407 challenge fails when credentials are invalid or missing")
    void proxyAuthenticationFailsWithWrongCredentials() throws Exception {
        proxyServer = new TestHttpProxy(wireMockServer.port(), "corpUser", "corpSecret");
        proxyServer.start();

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .proxy("127.0.0.1", proxyServer.getPort())
                .proxyCredentials("corpUser", "wrongPassword")
                .build();

        try {
            TestController controller = client.get(TestController.class);
            assertThrows(RuntimeException.class, () -> controller.getItem(42L));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Server 401 challenge authenticates using credentials() helper")
    void serverAuthentication401WithCredentials() {
        // WireMock requires Basic auth
        stubFor(get(urlEqualTo("/api/items/42"))
                .withHeader("Authorization", matching("Basic " + Base64.getEncoder().encodeToString("apiAdmin:secretPass".getBytes(StandardCharsets.UTF_8))))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        stubFor(get(urlEqualTo("/api/items/42"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.absent())
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", "Basic realm=\"Restricted\"")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .credentials("apiAdmin", "secretPass")
                .build();

        try {
            TestController controller = client.get(TestController.class);
            TestDto dto = controller.getItem(42L);

            assertNotNull(dto);
            assertEquals(42L, dto.id());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("noProxy() setting connects directly without hitting proxy")
    void noProxyBypassesProxy() throws Exception {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        proxyServer = new TestHttpProxy(wireMockServer.port(), null, null);
        proxyServer.start();

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .noProxy()
                .build();

        try {
            TestController controller = client.get(TestController.class);
            TestDto dto = controller.getItem(42L);

            assertNotNull(dto);
            assertEquals(42L, dto.id());
            assertEquals(0, proxyServer.getRequestCount(), "Proxy server must receive 0 requests when noProxy is active");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Async GET over HTTP proxy completes normally")
    void asyncTwinOverProxy() throws Exception {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ITEM_JSON)));

        proxyServer = new TestHttpProxy(wireMockServer.port(), null, null);
        proxyServer.start();

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .proxy("127.0.0.1", proxyServer.getPort())
                .build();

        try {
            TestController controller = client.get(TestController.class);
            CompletableFuture<TestDto> future = controller.getItemAsync(42L);

            TestDto dto = future.join();
            assertNotNull(dto);
            assertEquals(42L, dto.id());
            assertTrue(proxyServer.getRequestCount() >= 1);
        } finally {
            client.close();
        }
    }

    /**
     * Minimal zero-dependency HTTP forward proxy server for integration tests.
     */
    private static class TestHttpProxy implements AutoCloseable {
        private final int targetPort;
        private final String requiredUser;
        private final String requiredPass;
        private final ServerSocket serverSocket;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final List<String> receivedHeaders = new CopyOnWriteArrayList<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Thread serverThread;

        TestHttpProxy(int targetPort, String requiredUser, String requiredPass) throws Exception {
            this.targetPort = targetPort;
            this.requiredUser = requiredUser;
            this.requiredPass = requiredPass;
            this.serverSocket = new ServerSocket(0);
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        int getRequestCount() {
            return requestCount.get();
        }

        boolean hasSeenHeader(String headerPrefix) {
            return receivedHeaders.stream().anyMatch(h -> h.toLowerCase().startsWith(headerPrefix.toLowerCase()));
        }

        void start() {
            serverThread = new Thread(this::runLoop, "test-http-proxy");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        private void runLoop() {
            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    Thread handler = new Thread(() -> handleConnection(clientSocket), "proxy-conn-handler");
                    handler.setDaemon(true);
                    handler.start();
                } catch (Exception e) {
                    if (!running.get()) {
                        break;
                    }
                }
            }
        }

        private void handleConnection(Socket clientSocket) {
            try (clientSocket;
                 InputStream in = clientSocket.getInputStream();
                 OutputStream out = clientSocket.getOutputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isBlank()) {
                    return;
                }
                requestCount.incrementAndGet();

                List<String> headers = new ArrayList<>();
                String line;
                String proxyAuthHeader = null;
                while ((line = reader.readLine()) != null && !line.isBlank()) {
                    headers.add(line);
                    receivedHeaders.add(line);
                    if (line.toLowerCase().startsWith("proxy-authorization:")) {
                        proxyAuthHeader = line.substring(line.indexOf(':') + 1).trim();
                    }
                }

                // Check proxy authentication if required
                if (requiredUser != null && requiredPass != null) {
                    String expectedAuth = "Basic " + Base64.getEncoder().encodeToString((requiredUser + ":" + requiredPass).getBytes(StandardCharsets.UTF_8));
                    if (proxyAuthHeader == null || !proxyAuthHeader.equals(expectedAuth)) {
                        String challenge = "HTTP/1.1 407 Proxy Authentication Required\r\n"
                                + "Proxy-Authenticate: Basic realm=\"MockProxy\"\r\n"
                                + "Content-Length: 0\r\n"
                                + "Connection: close\r\n\r\n";
                        out.write(challenge.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        return;
                    }
                }

                // Forward request to target WireMock server
                // Extract path from request line: "GET http://localhost:PORT/path HTTP/1.1" -> "GET /path HTTP/1.1"
                String forwardedRequestLine = requestLine;
                String[] parts = requestLine.split(" ");
                if (parts.length >= 3 && parts[1].startsWith("http://")) {
                    int pathStart = parts[1].indexOf('/', "http://".length());
                    String path = pathStart >= 0 ? parts[1].substring(pathStart) : "/";
                    forwardedRequestLine = parts[0] + " " + path + " " + parts[2];
                }

                try (Socket targetSocket = new Socket("127.0.0.1", targetPort);
                     OutputStream targetOut = targetSocket.getOutputStream();
                     InputStream targetIn = targetSocket.getInputStream()) {

                    StringBuilder forwardHeaderBuf = new StringBuilder();
                    forwardHeaderBuf.append(forwardedRequestLine).append("\r\n");
                    for (String h : headers) {
                        if (!h.toLowerCase().startsWith("proxy-")) {
                            forwardHeaderBuf.append(h).append("\r\n");
                        }
                    }
                    forwardHeaderBuf.append("\r\n");
                    targetOut.write(forwardHeaderBuf.toString().getBytes(StandardCharsets.UTF_8));
                    targetOut.flush();

                    // Relay response back to client
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = targetIn.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() {
            running.set(false);
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            if (serverThread != null) {
                serverThread.interrupt();
            }
        }
    }
}
