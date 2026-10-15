package ru.jrestly.engine;

import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable HTTP transport engine responsible for transmitting wire requests
 * over the network and receiving wire responses.
 *
 * <p>jRestly ships with {@link JdkHttpEngine} by default (100% zero-dependency,
 * built on Java 21's {@link java.net.http.HttpClient}). External high-performance
 * engines (e.g. OkHttp, Apache HttpClient 5) or lightweight mock test transports
 * can be plugged in via {@link ru.jrestly.JRestlyClient.Builder#httpEngine(HttpEngine)}
 * or via the {@link HttpEngineProvider} SPI (ADR-0052).
 */
public interface HttpEngine extends AutoCloseable {

    /**
     * Executes the HTTP request synchronously and returns the wire response.
     *
     * @param request the wire request model containing method, URI, headers, and payload
     * @param options call-specific transport options (timeout, version, streaming flag, progress listeners)
     * @return the wire response model
     * @throws IOException on network or I/O failure
     * @throws InterruptedException if the executing thread was interrupted
     */
    WireResponse send(WireRequest request, HttpEngineOptions options) throws IOException, InterruptedException;

    /**
     * Executes the HTTP request asynchronously and returns a {@link CompletableFuture}
     * completing with the wire response.
     *
     * @param request the wire request model containing method, URI, headers, and payload
     * @param options call-specific transport options
     * @return a future completing with the wire response
     */
    CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options);

    /**
     * Closes underlying network resources or connection pools. Default is a no-op.
     */
    @Override
    default void close() {
    }
}
