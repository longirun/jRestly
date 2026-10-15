package ru.jrestly.benchmark.engine;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.io.CloseMode;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineOptions;
import ru.jrestly.http.Header;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable {@link HttpEngine} backed by Apache HttpClient 5.
 */
public final class Apache5HttpEngine implements HttpEngine {

    private final CloseableHttpClient client;
    private final boolean ownsClient;

    public Apache5HttpEngine() {
        PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(200)
                .setMaxConnPerRoute(200)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connManager)
                .disableAutomaticRetries()
                .build();
        this.ownsClient = true;
    }

    public Apache5HttpEngine(CloseableHttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
        this.ownsClient = false;
    }

    @Override
    public WireResponse send(WireRequest request, HttpEngineOptions options) throws IOException {
        HttpUriRequestBase hcRequest = new HttpUriRequestBase(request.method().name(), request.uri());

        if (request.headers() != null) {
            for (Header h : request.headers()) {
                hcRequest.addHeader(h.name(), h.value());
            }
        }

        if (request.body() != null) {
            ContentType ct = request.contentType() != null ? ContentType.parse(request.contentType()) : null;
            hcRequest.setEntity(new ByteArrayEntity(request.body(), ct));
        }

        return client.execute(hcRequest, response -> {
            int code = response.getCode();
            List<Header> headers = new ArrayList<>();
            for (org.apache.hc.core5.http.Header h : response.getHeaders()) {
                headers.add(new Header(h.getName(), h.getValue()));
            }
            byte[] bodyBytes = null;
            if (response.getEntity() != null) {
                bodyBytes = EntityUtils.toByteArray(response.getEntity());
            }
            return new WireResponse(code, headers, bodyBytes, null, HttpClient.Version.HTTP_1_1, List.of());
        });
    }

    @Override
    public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(request, options);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close(CloseMode.GRACEFUL);
        }
    }
}
