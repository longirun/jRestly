package ru.jrestly.benchmark.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * High-performance, zero-dependency embedded HTTP/1.1 server for comparative benchmarks.
 *
 * <p>Implemented directly on {@link ServerSocket} + virtual threads instead of
 * {@code com.sun.net.httpserver.HttpServer}. The JDK's built-in server disables no
 * TCP options and writes the response in two chunks (headers, then body); on a
 * keep-alive loopback connection the second write waits for a delayed ACK (~40 ms),
 * adding a flat ~42 ms tax to <em>every</em> request and masking the real differences
 * between HTTP clients. This server fixes the stand by:
 *
 * <ul>
 *   <li>setting {@link Socket#setTcpNoDelay(boolean) TCP_NODELAY} on every accepted socket;</li>
 *   <li>assembling status line, headers and body into a single buffer written in one call;</li>
 *   <li>precomputing the full response byte arrays for the static endpoints (zero allocation per request);</li>
 *   <li>supporting persistent connections (HTTP/1.1 keep-alive) with {@code Content-Length} framing.</li>
 * </ul>
 */
public final class BenchmarkHttpServer implements AutoCloseable {

    public static final String SAMPLE_USER_JSON = """
            {"id":42,"name":"Alice","email":"alice@example.com","active":true,"tags":["admin","engineer"],"createdAt":"2026-10-15T12:00:00Z"}""";

    public static final String SAMPLE_BATCH_USERS_JSON;

    static {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"id\":").append(i)
                    .append(",\"name\":\"User_").append(i)
                    .append("\",\"email\":\"user").append(i).append("@example.com\"")
                    .append(",\"active\":true")
                    .append(",\"tags\":[\"dev\",\"qa\"]")
                    .append(",\"createdAt\":\"2026-10-15T12:00:00Z\"}");
        }
        sb.append("]");
        SAMPLE_BATCH_USERS_JSON = sb.toString();
    }

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String TEXT_CONTENT_TYPE = "text/plain; charset=utf-8";

    private static final byte[] PING_RESPONSE = response(200, "OK", TEXT_CONTENT_TYPE, "pong".getBytes(StandardCharsets.UTF_8), false);
    private static final byte[] PING_RESPONSE_CLOSE = response(200, "OK", TEXT_CONTENT_TYPE, "pong".getBytes(StandardCharsets.UTF_8), true);
    private static final byte[] USER_RESPONSE = response(200, "OK", JSON_CONTENT_TYPE, SAMPLE_USER_JSON.getBytes(StandardCharsets.UTF_8), false);
    private static final byte[] USER_RESPONSE_CLOSE = response(200, "OK", JSON_CONTENT_TYPE, SAMPLE_USER_JSON.getBytes(StandardCharsets.UTF_8), true);
    private static final byte[] BATCH_RESPONSE = response(200, "OK", JSON_CONTENT_TYPE, SAMPLE_BATCH_USERS_JSON.getBytes(StandardCharsets.UTF_8), false);
    private static final byte[] BATCH_RESPONSE_CLOSE = response(200, "OK", JSON_CONTENT_TYPE, SAMPLE_BATCH_USERS_JSON.getBytes(StandardCharsets.UTF_8), true);
    private static final byte[] NOT_FOUND_RESPONSE = response(404, "Not Found", TEXT_CONTENT_TYPE, new byte[0], false);
    private static final byte[] NOT_FOUND_RESPONSE_CLOSE = response(404, "Not Found", TEXT_CONTENT_TYPE, new byte[0], true);
    private static final byte[] METHOD_NOT_ALLOWED_RESPONSE = response(405, "Method Not Allowed", TEXT_CONTENT_TYPE, new byte[0], false);
    private static final byte[] METHOD_NOT_ALLOWED_RESPONSE_CLOSE = response(405, "Method Not Allowed", TEXT_CONTENT_TYPE, new byte[0], true);
    private static final byte[] CONTINUE_RESPONSE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int MAX_LINE_LENGTH = 8192;

    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final CountDownLatch acceptLoopExited = new CountDownLatch(1);
    private final int port;
    private volatile boolean running = true;

    private BenchmarkHttpServer(ServerSocket serverSocket, int port) {
        this.serverSocket = serverSocket;
        this.port = port;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Creates and starts a benchmark HTTP server bound to an ephemeral loopback port.
     *
     * @return started server instance
     * @throws IOException if the server fails to bind
     */
    public static BenchmarkHttpServer start() throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0), 128);
        BenchmarkHttpServer server = new BenchmarkHttpServer(serverSocket, serverSocket.getLocalPort());
        server.executor.submit(server::acceptLoop);
        return server;
    }

    private void acceptLoop() {
        try {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    activeSockets.add(socket);
                    executor.submit(() -> handleConnection(socket));
                } catch (IOException e) {
                    if (!running || serverSocket.isClosed()) {
                        return;
                    }
                }
            }
        } finally {
            acceptLoopExited.countDown();
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
             InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
             OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 8192)) {
            while (running) {
                String requestLine = readLine(in);
                if (requestLine == null) {
                    return;
                }
                if (requestLine.isEmpty()) {
                    continue;
                }
                int firstSpace = requestLine.indexOf(' ');
                int secondSpace = firstSpace < 0 ? -1 : requestLine.indexOf(' ', firstSpace + 1);
                if (firstSpace < 0 || secondSpace < 0) {
                    writeResponse(out, response(400, "Bad Request", TEXT_CONTENT_TYPE, new byte[0], true));
                    return;
                }
                String method = requestLine.substring(0, firstSpace);
                String target = requestLine.substring(firstSpace + 1, secondSpace);

                long contentLength = -1;
                boolean chunked = false;
                boolean close = false;
                boolean expectContinue = false;
                String header;
                while ((header = readLine(in)) != null && !header.isEmpty()) {
                    int colon = header.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String name = header.substring(0, colon).trim();
                    String value = header.substring(colon + 1).trim();
                    if (name.equalsIgnoreCase("Content-Length")) {
                        contentLength = Long.parseLong(value);
                    } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
                        chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
                    } else if (name.equalsIgnoreCase("Connection")) {
                        close = value.equalsIgnoreCase("close");
                    } else if (name.equalsIgnoreCase("Expect") && value.equalsIgnoreCase("100-continue")) {
                        expectContinue = true;
                    }
                }
                if (expectContinue) {
                    out.write(CONTINUE_RESPONSE);
                    out.flush();
                }

                byte[] body = new byte[0];
                if (chunked) {
                    body = readChunkedBody(in);
                } else if (contentLength > 0) {
                    body = in.readNBytes((int) contentLength);
                }

                byte[] response = route(method, target, body, close);
                writeResponse(out, response);
                if (close) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // Client aborted the connection or the server is shutting down.
        } finally {
            activeSockets.remove(socket);
        }
    }

    private static void writeResponse(OutputStream out, byte[] response) throws IOException {
        out.write(response);
        out.flush();
    }

    private static byte[] route(String method, String target, byte[] body, boolean close) {
        int query = target.indexOf('?');
        String path = query < 0 ? target : target.substring(0, query);
        if ("/api/ping".equals(path)) {
            if ("GET".equals(method)) {
                return close ? PING_RESPONSE_CLOSE : PING_RESPONSE;
            }
            return close ? METHOD_NOT_ALLOWED_RESPONSE_CLOSE : METHOD_NOT_ALLOWED_RESPONSE;
        }
        if ("/api/users".equals(path) || path.startsWith("/api/users/")) {
            if ("GET".equals(method)) {
                if (path.endsWith("/batch")) {
                    return close ? BATCH_RESPONSE_CLOSE : BATCH_RESPONSE;
                }
                return close ? USER_RESPONSE_CLOSE : USER_RESPONSE;
            }
            if ("POST".equals(method)) {
                if (body.length == 0) {
                    return close ? USER_RESPONSE_CLOSE : USER_RESPONSE;
                }
                return response(200, "OK", JSON_CONTENT_TYPE, body, close);
            }
            return close ? METHOD_NOT_ALLOWED_RESPONSE_CLOSE : METHOD_NOT_ALLOWED_RESPONSE;
        }
        return close ? NOT_FOUND_RESPONSE_CLOSE : NOT_FOUND_RESPONSE;
    }

    private static byte[] response(int status, String reason, String contentType, byte[] body, boolean close) {
        StringBuilder head = new StringBuilder(160);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n");
        if (close) {
            head.append("Connection: close\r\n");
        }
        head.append("\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] response = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, response, 0, headBytes.length);
        System.arraycopy(body, 0, response, headBytes.length, body.length);
        return response;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            if (line.length() >= MAX_LINE_LENGTH) {
                throw new IOException("HTTP line exceeds " + MAX_LINE_LENGTH + " characters");
            }
            line.append((char) b);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semicolon = sizeLine.indexOf(';');
            int size = Integer.parseInt((semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim(), 16);
            if (size == 0) {
                readLine(in);
                break;
            }
            byte[] chunk = in.readNBytes(size);
            body.write(chunk);
            readLine(in);
        }
        return body.toByteArray();
    }

    /**
     * Stops the HTTP server and closes all active connections.
     *
     * <p>The accept loop is interrupted and awaited before the listening socket is closed:
     * closing a {@link ServerSocket} while another thread is blocked in {@code accept()}
     * can complete the TCP handshake of a connect that races with the shutdown. Once the
     * accept loop has exited, no thread is blocked in {@code accept()} and the socket is
     * closed deterministically.
     */
    public void stop() {
        running = false;
        executor.shutdownNow();
        try {
            acceptLoopExited.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        for (Socket socket : activeSockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        activeSockets.clear();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Returns the port this server is listening on.
     *
     * @return loopback port
     */
    public int port() {
        return port;
    }

    /**
     * Returns the base URL for HTTP requests.
     *
     * @return base URL string (e.g. {@code "http://127.0.0.1:54321"})
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
