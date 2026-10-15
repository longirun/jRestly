package ru.jrestly.engine;

import ru.jrestly.ModuleInfo;
import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.MultipartBodyPublisher;
import ru.jrestly.http.ProgressBodyHandler;
import ru.jrestly.http.ProgressBodyPublisher;
import ru.jrestly.http.UploadProgressListener;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Standard zero-dependency {@link HttpEngine} implementation backed by Java 21's {@link HttpClient}.
 */
public class JdkHttpEngine implements HttpEngine {

    private final HttpClient httpClient;
    private final boolean ownsClient;

    /**
     * Constructs a {@link JdkHttpEngine} wrapping an existing {@link HttpClient} without ownership.
     * The client will not be closed on {@link #close()}.
     *
     * @param httpClient the JDK HTTP client, must not be {@code null}
     */
    public JdkHttpEngine(HttpClient httpClient) {
        this(httpClient, false);
    }

    /**
     * Constructs a {@link JdkHttpEngine} wrapping an existing {@link HttpClient} with optional lifecycle ownership.
     *
     * @param httpClient the JDK HTTP client, must not be {@code null}
     * @param ownsClient {@code true} if {@link #close()} should close the underlying {@link HttpClient}
     */
    public JdkHttpEngine(HttpClient httpClient, boolean ownsClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.ownsClient = ownsClient;
    }

    /**
     * Creates a {@link JdkHttpEngine} wrapping the specified {@link HttpClient} without lifecycle ownership.
     *
     * @param httpClient the JDK HTTP client, must not be {@code null}
     * @return the HTTP engine instance
     */
    public static JdkHttpEngine from(HttpClient httpClient) {
        return new JdkHttpEngine(httpClient, false);
    }

    /**
     * Creates a new {@link JdkHttpEngine} with an internally managed {@link HttpClient} configured from {@link ModuleInfo}.
     *
     * @param moduleInfo the client module configuration, must not be {@code null}
     * @return a new managed HTTP engine instance
     */
    public static JdkHttpEngine create(ModuleInfo moduleInfo) {
        Objects.requireNonNull(moduleInfo, "moduleInfo");
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(moduleInfo.getConnectTimeout()))
                .version(moduleInfo.getHttpVersion())
                .followRedirects(HttpClient.Redirect.NEVER);

        SSLContext sslContext = moduleInfo.getSslContext();
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        SSLParameters sslParameters = moduleInfo.getSslParameters();
        if (sslParameters != null) {
            builder.sslParameters(sslParameters);
        }
        CookieHandler cookieHandler = moduleInfo.getCookieHandler();
        if (cookieHandler != null) {
            builder.cookieHandler(cookieHandler);
        }
        ProxySelector proxySelector = moduleInfo.getProxySelector();
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        Authenticator authenticator = moduleInfo.getAuthenticator();
        if (authenticator != null) {
            builder.authenticator(authenticator);
        }
        Executor executor = moduleInfo.getExecutor();
        if (executor != null) {
            builder.executor(executor);
        }
        return new JdkHttpEngine(builder.build(), true);
    }

    /**
     * Returns the underlying JDK {@link HttpClient}.
     *
     * @return the JDK HTTP client
     */
    public HttpClient getHttpClient() {
        return httpClient;
    }

    @Override
    public WireResponse send(WireRequest request, HttpEngineOptions options) throws IOException, InterruptedException {
        HttpRequest httpRequest = toHttpRequest(request, options);
        if (options != null && options.streamResponse()) {
            HttpResponse.BodyHandler<InputStream> bodyHandler = HttpResponse.BodyHandlers.ofInputStream();
            if (options.downloadProgressListener() != null) {
                bodyHandler = new ProgressBodyHandler<>(bodyHandler, options.downloadProgressListener());
            }
            HttpResponse<InputStream> response = httpClient.send(httpRequest, bodyHandler);
            return toWireResponse(response);
        } else {
            HttpResponse.BodyHandler<byte[]> bodyHandler = HttpResponse.BodyHandlers.ofByteArray();
            if (options != null && options.downloadProgressListener() != null) {
                bodyHandler = new ProgressBodyHandler<>(bodyHandler, options.downloadProgressListener());
            }
            HttpResponse<byte[]> response = httpClient.send(httpRequest, bodyHandler);
            return toWireResponse(response);
        }
    }

    @Override
    public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
        HttpRequest httpRequest = toHttpRequest(request, options);
        if (options != null && options.streamResponse()) {
            HttpResponse.BodyHandler<InputStream> bodyHandler = HttpResponse.BodyHandlers.ofInputStream();
            if (options.downloadProgressListener() != null) {
                bodyHandler = new ProgressBodyHandler<>(bodyHandler, options.downloadProgressListener());
            }
            return httpClient.sendAsync(httpRequest, bodyHandler)
                    .thenApply(JdkHttpEngine::toWireResponse);
        } else {
            HttpResponse.BodyHandler<byte[]> bodyHandler = HttpResponse.BodyHandlers.ofByteArray();
            if (options != null && options.downloadProgressListener() != null) {
                bodyHandler = new ProgressBodyHandler<>(bodyHandler, options.downloadProgressListener());
            }
            return httpClient.sendAsync(httpRequest, bodyHandler)
                    .thenApply(JdkHttpEngine::toWireResponse);
        }
    }

    @Override
    public void close() {
        if (ownsClient) {
            httpClient.close();
        }
    }

    private HttpRequest toHttpRequest(WireRequest request, HttpEngineOptions options) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri());

        if (options != null && options.timeout() != null) {
            builder.timeout(options.timeout());
        }

        if (options != null && options.version() != null) {
            builder.version(options.version());
        }

        if (request.headers() != null) {
            for (Header header : request.headers()) {
                builder.header(header.name(), header.value());
            }
        }
        if (request.contentType() != null) {
            builder.setHeader("Content-Type", request.contentType());
        }

        UploadProgressListener uploadListener = options != null ? options.uploadProgressListener() : null;
        if (request.method() == HttpMethod.GET && request.body() == null && request.bodyPublisherSupplier() == null && request.parts() == null) {
            builder.GET();
        } else {
            builder.method(request.method().name(), createBodyPublisher(request, uploadListener));
        }

        return builder.build();
    }

    private HttpRequest.BodyPublisher createBodyPublisher(WireRequest request, UploadProgressListener uploadListener) {
        HttpRequest.BodyPublisher publisher;
        if (request.parts() != null) {
            publisher = MultipartBodyPublisher.of(request.boundary(), request.parts());
        } else if (request.bodyPublisherSupplier() != null) {
            publisher = request.bodyPublisherSupplier().get();
            if (publisher == null) {
                publisher = HttpRequest.BodyPublishers.noBody();
            }
        } else if (request.body() == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else {
            publisher = HttpRequest.BodyPublishers.ofByteArray(request.body());
        }

        if (uploadListener != null) {
            publisher = new ProgressBodyPublisher(publisher, uploadListener);
        }
        return publisher;
    }

    @SuppressWarnings("unchecked")
    private static WireResponse toWireResponse(HttpResponse<?> response) {
        Object body = response.body();
        List<Header> headers = toHeaders(response.headers());
        HttpClient.Version version = response.version();
        List<Header> trailers = List.of();
        if (body instanceof InputStream stream) {
            return new WireResponse(response.statusCode(), headers, null, stream, version, trailers);
        }
        return new WireResponse(response.statusCode(), headers, (byte[]) body, null, version, trailers);
    }

    private static List<Header> toHeaders(HttpHeaders headers) {
        if (headers == null) {
            return List.of();
        }
        var map = headers.map();
        if (map.isEmpty()) {
            return List.of();
        }
        List<Header> list = new ArrayList<>(map.size() * 2);
        for (var entry : map.entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            for (int i = 0, size = values.size(); i < size; i++) {
                list.add(new Header(name, values.get(i)));
            }
        }
        return List.copyOf(list);
    }
}
