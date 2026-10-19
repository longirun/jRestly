package ru.jrestly.benchmark;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.benchmark.server.BenchmarkHttpServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and regression coverage of the zero-dependency raw-TCP benchmark server: HTTP/1.1
 * request parsing, keep-alive, response framing and the {@code TCP_NODELAY} latency gate.
 */
@DisplayName("Raw-TCP Benchmark HTTP Server Protocol and Latency Tests")
class BenchmarkHttpServerTest {

    private static BenchmarkHttpServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = BenchmarkHttpServer.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Serves all benchmark endpoints over a single keep-alive connection")
    void servesEndpointsOverKeepAliveConnection() throws IOException {
        try (RawClient client = new RawClient(server.port())) {
            Response user = client.send("GET /api/users/42 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, user.status());
            assertTrue(user.body().contains("\"id\":42"));
            assertEquals(0, user.body().indexOf('{'));
            assertEquals(String.valueOf(user.body().getBytes(StandardCharsets.UTF_8).length),
                    user.headers().get("Content-Length"));

            Response batch = client.send("GET /api/users/batch HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, batch.status());
            assertTrue(batch.body().startsWith("["));
            assertTrue(batch.body().contains("\"id\":49"));

            Response ping = client.send("GET /api/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, ping.status());
            assertEquals("pong", ping.body());

            byte[] payload = "{\"id\":42,\"name\":\"Alice\"}".getBytes(StandardCharsets.UTF_8);
            Response echo = client.send("POST /api/users HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + payload.length + "\r\n\r\n", payload);
            assertEquals(200, echo.status());
            assertEquals("{\"id\":42,\"name\":\"Alice\"}", echo.body());
        }
    }

    @Test
    @DisplayName("Decodes chunked request bodies")
    void decodesChunkedRequestBody() throws IOException {
        try (RawClient client = new RawClient(server.port())) {
            Response echo = client.send("POST /api/users HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n", null);
            assertEquals(200, echo.status());
            assertEquals("hello", echo.body());
        }
    }

    @Test
    @DisplayName("Honors Connection: close and closes the socket after the response")
    void honorsConnectionClose() throws IOException {
        try (RawClient client = new RawClient(server.port())) {
            Response response = client.send("GET /api/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n", null);
            assertEquals(200, response.status());
            assertEquals("close", response.headers().get("Connection"));
            assertEquals("pong", response.body());
            assertEquals(-1, client.in.read(), "server must close the socket after Connection: close");
        }
    }

    @Test
    @DisplayName("Returns 404 for unknown paths and 405 for unsupported methods")
    void returnsNotFoundAndMethodNotAllowed() throws IOException {
        try (RawClient client = new RawClient(server.port())) {
            Response notFound = client.send("GET /api/unknown HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(404, notFound.status());

            Response methodNotAllowed = client.send("POST /api/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Content-Length: 0\r\n\r\n", null);
            assertEquals(405, methodNotAllowed.status());

            Response queryIgnored = client.send("GET /api/users/7?verbose=true HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, queryIgnored.status());
            assertTrue(queryIgnored.body().contains("\"id\":42"));
        }
    }

    @Test
    @DisplayName("Keep-alive round trips stay far below the Nagle + delayed-ACK artifact (~42 ms)")
    void keepAliveLatencyIsNotDelayedByNagle() throws IOException {
        try (RawClient client = new RawClient(server.port())) {
            long bestNanos = Long.MAX_VALUE;
            for (int i = 0; i < 10; i++) {
                long start = System.nanoTime();
                Response response = client.send("GET /api/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
                long elapsed = System.nanoTime() - start;
                assertEquals(200, response.status());
                assertEquals("pong", response.body());
                bestNanos = Math.min(bestNanos, elapsed);
            }
            double bestMillis = bestNanos / 1_000_000.0;
            assertTrue(bestMillis < 25.0, () -> "keep-alive round trip took " + bestMillis
                    + " ms; expected well under 25 ms, otherwise the stand regressed to the "
                    + "Nagle + delayed-ACK artifact (~42 ms) that masks real differences between clients");
        }
    }

    @Test
    @DisplayName("Serves the deterministic feed endpoints with correct framing and content types")
    void servesFeedEndpointsOverKeepAliveConnection() throws IOException {
        try (RawClient client = new RawClient(server.port())) {
            Response rssSmall = client.send("GET /feed/rss-20 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, rssSmall.status());
            assertEquals("application/rss+xml; charset=utf-8", rssSmall.headers().get("Content-Type"));
            assertEquals(String.valueOf(rssSmall.body().getBytes(StandardCharsets.UTF_8).length),
                    rssSmall.headers().get("Content-Length"));
            assertTrue(rssSmall.body().startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
            assertTrue(rssSmall.body().contains("<rss version=\"2.0\""));

            Response rssLarge = client.send("GET /feed/rss-1000 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, rssLarge.status());
            assertEquals("application/rss+xml; charset=utf-8", rssLarge.headers().get("Content-Type"));
            assertTrue(rssLarge.bodyBytes() > 0, "large feed must carry a non-empty body");

            Response atomSmall = client.send("GET /feed/atom-20 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, atomSmall.status());
            assertEquals("application/atom+xml; charset=utf-8", atomSmall.headers().get("Content-Type"));
            assertTrue(atomSmall.body().contains("<feed xmlns=\"http://www.w3.org/2005/Atom\""));

            Response atomMedium = client.send("GET /feed/atom-200 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, atomMedium.status());
            assertTrue(atomMedium.body().contains("urn:example:article:199"));

            Response methodNotAllowed = client.send("POST /feed/rss-20 HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Content-Length: 0\r\n\r\n", null);
            assertEquals(405, methodNotAllowed.status());

            Response notFound = client.send("GET /feed/rss-30 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(404, notFound.status());
        }
    }

    @Test
    @DisplayName("Feed route bodies match the fixture entry counts end-to-end")
    void feedRouteBodiesMatchFixtureEntryCounts() throws Exception {
        try (RawClient client = new RawClient(server.port())) {
            Response rss20 = client.send("GET /feed/rss-20 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(20, countElements(rss20.body(), "item"));

            Response rss200 = client.send("GET /feed/rss-200 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, countElements(rss200.body(), "item"));

            Response atom20 = client.send("GET /feed/atom-20 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(20, countElements(atom20.body(), "entry"));

            Response atom200 = client.send("GET /feed/atom-200 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
            assertEquals(200, countElements(atom200.body(), "entry"));
        }
    }

    @Test
    @DisplayName("Keep-alive feed round trips stay far below the Nagle + delayed-ACK artifact (~42 ms)")
    void feedLatencyIsNotDelayedByNagle() throws IOException {
        try (RawClient client = new RawClient(server.port())) {
            long bestNanos = Long.MAX_VALUE;
            for (int i = 0; i < 10; i++) {
                long start = System.nanoTime();
                Response response = client.send("GET /feed/rss-20 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null);
                long elapsed = System.nanoTime() - start;
                assertEquals(200, response.status());
                assertTrue(response.body().endsWith("</rss>\n"));
                bestNanos = Math.min(bestNanos, elapsed);
            }
            double bestMillis = bestNanos / 1_000_000.0;
            assertTrue(bestMillis < 25.0, () -> "feed round trip took " + bestMillis
                    + " ms; expected well under 25 ms, otherwise the stand regressed to the "
                    + "Nagle + delayed-ACK artifact (~42 ms) that masks real differences between clients");
        }
    }

    @Test
    @DisplayName("Stops cleanly, closes active keep-alive connections and refuses new ones")
    void stopsCleanly() throws IOException {
        BenchmarkHttpServer ephemeral = BenchmarkHttpServer.start();
        int port = ephemeral.port();
        try (RawClient client = new RawClient(port)) {
            assertEquals("pong", client.send("GET /api/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null).body());
            assertEquals("pong", client.send("GET /api/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", null).body());

            ephemeral.stop();

            assertEquals(-1, client.in.read(), "stop() must close active keep-alive connections");
        }
        assertFalse(canConnect(port), "stopped server must not accept new connections");
    }

    private static boolean canConnect(int port) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int countElements(String xml, String localName) throws Exception {
        XMLStreamReader reader = XMLInputFactory.newFactory().createXMLStreamReader(new StringReader(xml));
        try {
            int count = 0;
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && localName.equals(reader.getLocalName())) {
                    count++;
                }
            }
            return count;
        } finally {
            reader.close();
        }
    }

    private record Response(int status, Map<String, String> headers, String body) {

        int bodyBytes() {
            return body.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private static final class RawClient implements AutoCloseable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        RawClient(int port) throws IOException {
            this.socket = new Socket("127.0.0.1", port);
            this.socket.setTcpNoDelay(true);
            this.in = new BufferedInputStream(socket.getInputStream(), 8192);
            this.out = new BufferedOutputStream(socket.getOutputStream(), 8192);
        }

        Response send(String head, byte[] body) throws IOException {
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            return readResponse();
        }

        private Response readResponse() throws IOException {
            String statusLine = readLine(in);
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
            int length = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
            byte[] body = in.readNBytes(length);
            return new Response(status, headers, new String(body, StandardCharsets.UTF_8));
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(64);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    byte[] bytes = line.toByteArray();
                    int length = bytes.length;
                    if (length > 0 && bytes[length - 1] == '\r') {
                        length--;
                    }
                    return new String(bytes, 0, length, StandardCharsets.US_ASCII);
                }
                line.write(b);
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
