package ru.jrestly.benchmark.engine;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
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
 * Pluggable {@link HttpEngine} backed by Square OkHttp 3/4.
 */
public final class OkHttpEngine implements HttpEngine {

    private final OkHttpClient client;
    private final boolean ownsClient;

    public OkHttpEngine() {
        this(new OkHttpClient.Builder().build(), true);
    }

    public OkHttpEngine(OkHttpClient client) {
        this(client, false);
    }

    public OkHttpEngine(OkHttpClient client, boolean ownsClient) {
        this.client = Objects.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
    }

    @Override
    public WireResponse send(WireRequest request, HttpEngineOptions options) throws IOException {
        Request okRequest = toOkHttpRequest(request);
        try (Response response = client.newCall(okRequest).execute()) {
            return toWireResponse(response);
        }
    }

    @Override
    public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
        Request okRequest = toOkHttpRequest(request);
        CompletableFuture<WireResponse> future = new CompletableFuture<>();
        client.newCall(okRequest).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    future.complete(toWireResponse(response));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }
        });
        return future;
    }

    private Request toOkHttpRequest(WireRequest request) {
        Request.Builder builder = new Request.Builder().url(request.uri().toString());

        if (request.headers() != null) {
            for (Header header : request.headers()) {
                builder.addHeader(header.name(), header.value());
            }
        }

        String contentType = request.contentType();
        MediaType mediaType = contentType != null ? MediaType.parse(contentType) : null;

        RequestBody body = null;
        if (request.body() != null) {
            body = RequestBody.create(mediaType, request.body());
        } else if (requiresRequestBody(request.method().name())) {
            body = RequestBody.create(mediaType, new byte[0]);
        }

        builder.method(request.method().name(), body);
        return builder.build();
    }

    private static boolean requiresRequestBody(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
    }

    private static WireResponse toWireResponse(Response response) throws IOException {
        int code = response.code();
        Headers okHeaders = response.headers();
        List<Header> headers = new ArrayList<>(okHeaders.size());
        for (int i = 0; i < okHeaders.size(); i++) {
            headers.add(new Header(okHeaders.name(i), okHeaders.value(i)));
        }

        byte[] bodyBytes = null;
        ResponseBody responseBody = response.body();
        if (responseBody != null) {
            bodyBytes = responseBody.bytes();
        }

        HttpClient.Version version = switch (response.protocol()) {
            case HTTP_2 -> HttpClient.Version.HTTP_2;
            default -> HttpClient.Version.HTTP_1_1;
        };

        return new WireResponse(code, headers, bodyBytes, null, version, List.of());
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
