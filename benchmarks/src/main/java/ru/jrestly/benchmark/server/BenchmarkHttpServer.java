package ru.jrestly.benchmark.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * High-performance, zero-dependency embedded HTTP server built on JDK {@link HttpServer}.
 * Serves static and dynamic JSON / text payloads for comparative benchmarks over loopback TCP sockets.
 */
public final class BenchmarkHttpServer {

    public static final String SAMPLE_USER_JSON = """
            {"id":42,"name":"Alice","email":"alice@example.com","active":true,"tags":["admin","engineer"],"createdAt":"2026-10-15T12:00:00Z"}""";

    private final HttpServer server;
    private final int port;

    private BenchmarkHttpServer(HttpServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * Creates and starts a benchmark HTTP server bound to an ephemeral loopback port.
     *
     * @return started server instance
     * @throws IOException if the server fails to bind
     */
    public static BenchmarkHttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        byte[] userBytes = SAMPLE_USER_JSON.getBytes(StandardCharsets.UTF_8);
        byte[] pingBytes = "pong".getBytes(StandardCharsets.UTF_8);

        server.createContext("/api/users", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String method = exchange.getRequestMethod();
                if ("GET".equalsIgnoreCase(method)) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, userBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(userBytes);
                    }
                } else if ("POST".equalsIgnoreCase(method)) {
                    byte[] requestBody;
                    try (InputStream is = exchange.getRequestBody()) {
                        requestBody = is.readAllBytes();
                    }
                    byte[] response = requestBody.length > 0 ? requestBody : userBytes;
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            }
        });

        server.createContext("/api/ping", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, pingBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(pingBytes);
                    }
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            }
        });

        server.start();
        return new BenchmarkHttpServer(server, server.getAddress().getPort());
    }

    /**
     * Stops the HTTP server.
     */
    public void stop() {
        server.stop(0);
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
