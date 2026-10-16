package ru.jrestly.engine.apache5;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import ru.jrestly.ModuleInfo;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineOptions;
import ru.jrestly.engine.JdkHttpEngine;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.Header;
import ru.jrestly.http.MultipartBodyPublisher;
import ru.jrestly.http.UploadProgressListener;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@link HttpEngine} backed by Apache HttpClient 5 (ADR-0058, phase 2).
 *
 * <p>Parity contract with the zero-dependency {@link JdkHttpEngine} (ADR-0058 §Decision.4):
 * <ul>
 *   <li>{@code options.timeout()} — per-call deadline via the request's response timeout
 *       (bounded by the socket so-timeout of the connection manager);</li>
 *   <li>{@code options.version()} — the engine negotiates and reports the protocol actually
 *       used (the HttpClient 5 classic API has no per-request version pinning);</li>
 *   <li>{@code options.streamResponse()} — the body is delivered as a live
 *       {@link InputStream} (the entity stream; the response stays open until the stream is
 *       closed); trailers are reported in byte-buffering mode only, where the protocol has
 *       finished delivering the body;</li>
 *   <li>upload progress — a counting wrapper over the request entity stream; download
 *       progress — a counting wrapper over the entity stream; the final {@code (total, total)}
 *       event and the zero-length {@code (0, 0)} semantics of ADR-0030/0033 are preserved;</li>
 *   <li>compression — the transport stays passive: {@code disableContentCompression()}
 *       suppresses both the injected {@code Accept-Encoding} and HttpClient 5's transparent
 *       decompression, so the declarative layer's own negotiation and
 *       {@code ContentCompressor} handling (ADR-0021) behaves exactly as on the
 *       zero-dependency engine;</li>
 *   <li>redirects and retries are left to the declarative layer
 *       ({@code disableRedirectHandling()}, {@code disableAutomaticRetries()});</li>
 *   <li>{@code close()} releases the connection pool only when the engine owns the client;
 *       the async executor is released when the engine created it.</li>
 * </ul>
 *
 * <p>Streaming/multipart request bodies arrive as JDK {@link HttpRequest.BodyPublisher}s
 * ({@link WireRequest#bodyPublisherSupplier()}, {@link WireRequest#parts()}) and are drained
 * into an {@link InputStream} with {@link PublisherInputStream}. Asynchronous exchanges run on
 * a dedicated per-engine executor (virtual threads by default, or a user-supplied
 * {@link Executor}) — never on the common {@code ForkJoinPool}.
 */
public final class Apache5HttpEngine implements HttpEngine {

    /**
     * The default connection pool sizing (total and per route), mirroring the prototype that
     * won the benchmark matrix.
     */
    private static final int DEFAULT_MAX_CONNECTIONS = 200;

    private final CloseableHttpClient client;
    private final boolean ownsClient;
    private final ProxySelector proxySelector;
    private final ExecutorService asyncExecutor;
    private final boolean ownsAsyncExecutor;

    /**
     * Constructs an {@link Apache5HttpEngine} with an internally managed
     * {@link CloseableHttpClient} (pooled connections, passive content coding, no redirects,
     * no automatic retries, no cookie management). The client and the async executor are
     * released on {@link #close()}.
     */
    public Apache5HttpEngine() {
        this(defaultClient(), true, null, newAsyncExecutor(), true);
    }

    /**
     * Constructs an {@link Apache5HttpEngine} wrapping an existing {@link CloseableHttpClient}
     * without ownership. The client is not closed on {@link #close()}; the engine's own async
     * executor is released.
     *
     * @param client the Apache client, must not be {@code null}
     */
    public Apache5HttpEngine(CloseableHttpClient client) {
        this(Objects.requireNonNull(client, "client"), false, null, newAsyncExecutor(), true);
    }

    private Apache5HttpEngine(CloseableHttpClient client, boolean ownsClient, ProxySelector proxySelector,
                              ExecutorService asyncExecutor, boolean ownsAsyncExecutor) {
        this.client = client;
        this.ownsClient = ownsClient;
        this.proxySelector = proxySelector;
        this.asyncExecutor = asyncExecutor;
        this.ownsAsyncExecutor = ownsAsyncExecutor;
    }

    /**
     * Creates an {@link Apache5HttpEngine} wrapping the specified {@link CloseableHttpClient}
     * without lifecycle ownership.
     *
     * @param client the Apache client, must not be {@code null}
     * @return the HTTP engine instance
     */
    public static Apache5HttpEngine from(CloseableHttpClient client) {
        return new Apache5HttpEngine(client);
    }

    /**
     * Creates a new {@link Apache5HttpEngine} with an internally managed
     * {@link CloseableHttpClient} configured from {@link ModuleInfo} (the Apache5 twin of
     * {@link JdkHttpEngine#create(ModuleInfo)}): connect/response timeouts, TLS (ADR-0020),
     * cookies (ADR-0032/0039), per-request proxy (ADR-0037), the 401-challenge authenticator
     * (ADR-0037, best-effort Basic), and the async executor (ADR-0026).
     *
     * @param moduleInfo the client module configuration, must not be {@code null}
     * @return a new managed HTTP engine instance
     */
    public static Apache5HttpEngine create(ModuleInfo moduleInfo) {
        Objects.requireNonNull(moduleInfo, "moduleInfo");
        PoolingHttpClientConnectionManagerBuilder connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(DEFAULT_MAX_CONNECTIONS)
                .setMaxConnPerRoute(DEFAULT_MAX_CONNECTIONS);
        int socketTimeout = moduleInfo.getSocketTimeout();
        if (socketTimeout > 0) {
            connectionManager.setDefaultSocketConfig(SocketConfig.custom()
                    .setSoTimeout(Timeout.ofMilliseconds(socketTimeout))
                    .build());
        }
        SSLContext sslContext = moduleInfo.getSslContext();
        if (sslContext != null) {
            SSLParameters sslParameters = moduleInfo.getSslParameters();
            HostnameVerifier hostnameVerifier = sslParameters != null
                    && "HTTPS".equalsIgnoreCase(sslParameters.getEndpointIdentificationAlgorithm())
                    ? new DefaultHostnameVerifier()
                    : null;
            connectionManager.setTlsSocketStrategy(hostnameVerifier != null
                    ? new DefaultClientTlsStrategy(sslContext, hostnameVerifier)
                    : new DefaultClientTlsStrategy(sslContext));
        }

        if (moduleInfo.getConnectTimeout() > 0) {
            // HttpClient 5.4 carries the connect timeout on the connection config
            connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(moduleInfo.getConnectTimeout(), TimeUnit.MILLISECONDS)
                    .build());
        }

        HttpClientBuilder builder = HttpClients.custom()
                .setConnectionManager(connectionManager.build())
                // the jRestly redirect/retry policies run in the declarative layer
                .disableRedirectHandling()
                .disableAutomaticRetries()
                // the transport stays passive on content coding (ADR-0021)
                .disableContentCompression();
        RequestConfig.Builder defaults = RequestConfig.custom();
        if (socketTimeout > 0) {
            defaults.setResponseTimeout(Timeout.ofMilliseconds(socketTimeout));
        }
        builder.setDefaultRequestConfig(defaults.build());

        CookieHandler cookieHandler = moduleInfo.getCookieHandler();
        if (cookieHandler instanceof CookieManager cookieManager && cookieManager.getCookieStore() != null) {
            builder.setDefaultCookieStore(new JdkCookieStoreAdapter(cookieManager.getCookieStore()));
        } else {
            // the JDK engine manages cookies only when a handler is configured; mirror that
            builder.disableCookieManagement();
        }
        Authenticator authenticator = moduleInfo.getAuthenticator();
        if (authenticator != null) {
            builder.setDefaultCredentialsProvider(new JdkAuthenticatorCredentialsProvider(authenticator));
        }

        Executor executor = moduleInfo.getExecutor();
        ExecutorService asyncExecutor;
        boolean ownsAsyncExecutor;
        if (executor instanceof ExecutorService service) {
            asyncExecutor = service;
            ownsAsyncExecutor = false;
        } else if (executor != null) {
            asyncExecutor = new ExecutorServiceAdapter(executor);
            ownsAsyncExecutor = false;
        } else {
            asyncExecutor = newAsyncExecutor();
            ownsAsyncExecutor = true;
        }
        return new Apache5HttpEngine(builder.build(), true, moduleInfo.getProxySelector(),
                asyncExecutor, ownsAsyncExecutor);
    }

    /**
     * Returns the engine's effective {@link CloseableHttpClient} (the supplied or internally
     * built client).
     *
     * @return the Apache client
     */
    public CloseableHttpClient getHttpClient() {
        return client;
    }

    @Override
    public WireResponse send(WireRequest request, HttpEngineOptions options) throws IOException, InterruptedException {
        HttpUriRequestBase hcRequest = toApacheRequest(request, options);
        HttpClientContext context = HttpClientContext.create();
        DownloadProgressListener download = options != null ? options.downloadProgressListener() : null;
        if (options != null && options.streamResponse()) {
            ClassicHttpResponse response = client.executeOpen(null, hcRequest, context);
            try {
                return toStreamingWireResponse(response, context, download);
            } catch (IOException | RuntimeException | Error e) {
                // the stream now owns the response; this covers any failure before it was returned
                response.close();
                throw e;
            }
        }
        return client.execute(hcRequest, context, response -> toBufferedWireResponse(response, context, download));
    }

    @Override
    public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
        CompletableFuture<WireResponse> future = new CompletableFuture<>();
        // a dedicated executor: the prototype's supplyAsync on the common ForkJoinPool could
        // exhaust it under load (ADR-0058)
        asyncExecutor.execute(() -> {
            try {
                future.complete(send(request, options));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close(CloseMode.GRACEFUL);
        }
        if (ownsAsyncExecutor) {
            asyncExecutor.shutdown();
        }
    }

    private static CloseableHttpClient defaultClient() {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(DEFAULT_MAX_CONNECTIONS)
                        .setMaxConnPerRoute(DEFAULT_MAX_CONNECTIONS)
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .build();
    }

    private static ExecutorService newAsyncExecutor() {
        // virtual threads: cheap, unbounded, and never shared with other libraries
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jrestly-apache5-", 0).factory());
    }

    private HttpUriRequestBase toApacheRequest(WireRequest request, HttpEngineOptions options) {
        HttpUriRequestBase hcRequest = new HttpUriRequestBase(request.method().name(), request.uri());
        if (request.headers() != null) {
            for (Header header : request.headers()) {
                hcRequest.addHeader(header.name(), header.value());
            }
        }
        if (request.contentType() != null) {
            // set-style: replaces a duplicated Content-Type carried in request.headers(),
            // mirroring JdkHttpEngine
            hcRequest.setHeader("Content-Type", request.contentType());
        }
        UploadProgressListener upload = options != null ? options.uploadProgressListener() : null;
        HttpEntity entity = entityFor(request, upload);
        if (entity != null) {
            hcRequest.setEntity(entity);
        }
        RequestConfig config = requestConfigFor(request, options);
        if (config != null) {
            hcRequest.setConfig(config);
        }
        return hcRequest;
    }

    private HttpEntity entityFor(WireRequest request, UploadProgressListener upload) {
        HttpRequest.BodyPublisher publisher = null;
        if (request.parts() != null) {
            publisher = MultipartBodyPublisher.of(request.boundary(), request.parts());
        } else if (request.bodyPublisherSupplier() != null) {
            publisher = request.bodyPublisherSupplier().get();
        }
        if (publisher != null) {
            // the JDK BodyPublisher reports -1 for unknown length (chunked transfer)
            long contentLength = publisher.contentLength();
            return new InputStreamEntity(
                    progressWrapped(new PublisherInputStream(publisher), contentLength, upload), contentLength, null);
        }
        if (request.body() != null) {
            return new InputStreamEntity(
                    progressWrapped(new ByteArrayInputStream(request.body()), request.body().length, upload),
                    request.body().length, null);
        }
        // no payload: methods that carry a body get an explicit empty one (Content-Length: 0),
        // all others go bodyless — mirrors JdkHttpEngine's noBody()
        if (requiresEmptyBody(request.method().name())) {
            return new InputStreamEntity(progressWrapped(InputStream.nullInputStream(), 0L, upload), 0L, null);
        }
        return null;
    }

    private static boolean requiresEmptyBody(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
    }

    private static InputStream progressWrapped(InputStream in, long declaredLength, UploadProgressListener upload) {
        if (upload == null) {
            return in;
        }
        if (declaredLength == 0) {
            // zero-length semantics of ADR-0030: exactly one (0, 0) event
            upload.onProgress(0, 0);
        }
        return new CountingInputStream(in, declaredLength, upload::onProgress);
    }

    // RequestConfig.Builder.setProxy(HttpHost) is deprecated in HttpClient 5.4 (the route
    // planner is being reworked), yet it remains the only per-request proxy hook of the
    // classic client; the suppression is confined to this documented best-effort mapping.
    @SuppressWarnings("deprecation")
    private RequestConfig requestConfigFor(WireRequest request, HttpEngineOptions options) {
        RequestConfig.Builder builder = null;
        if (options != null && options.timeout() != null) {
            Timeout timeout = Timeout.of(options.timeout());
            builder = RequestConfig.custom()
                    .setResponseTimeout(timeout)
                    .setConnectionRequestTimeout(timeout);
        }
        if (proxySelector != null) {
            HttpHost proxy = resolveProxy(request.uri());
            if (proxy != null) {
                if (builder == null) {
                    builder = RequestConfig.custom();
                }
                builder.setProxy(proxy);
            }
        }
        return builder != null ? builder.build() : null;
    }

    private HttpHost resolveProxy(URI uri) {
        try {
            List<Proxy> proxies = proxySelector.select(uri);
            if (proxies != null && !proxies.isEmpty()
                    && proxies.get(0).type() != Proxy.Type.DIRECT
                    && proxies.get(0).address() instanceof InetSocketAddress address) {
                return new HttpHost("http", address.getHostString(), address.getPort());
            }
        } catch (RuntimeException ignored) {
            // best-effort: fall back to a direct connection
        }
        return null;
    }

    private static WireResponse toBufferedWireResponse(ClassicHttpResponse response, HttpContext context,
                                                       DownloadProgressListener download) throws IOException {
        List<Header> headers = toHeaders(response.getHeaders());
        HttpEntity entity = response.getEntity();
        long total = entity != null ? entity.getContentLength() : -1L;
        byte[] bodyBytes = null;
        List<Header> trailers = List.of();
        if (entity != null) {
            try (InputStream in = entity.getContent()) {
                bodyBytes = download != null
                        ? drainWithProgress(in, total, download)
                        : in.readAllBytes();
            }
            trailers = toTrailers(entity);
        }
        return new WireResponse(response.getCode(), headers, bodyBytes, null,
                toVersion(context.getProtocolVersion()), trailers);
    }

    private static WireResponse toStreamingWireResponse(ClassicHttpResponse response, HttpContext context,
                                                        DownloadProgressListener download) throws IOException {
        List<Header> headers = toHeaders(response.getHeaders());
        HttpClient.Version version = toVersion(context.getProtocolVersion());
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            response.close();
            return new WireResponse(response.getCode(), headers, null, null, version, List.of());
        }
        long total = entity.getContentLength();
        InputStream in = entity.getContent();
        if (in == null) {
            response.close();
            return new WireResponse(response.getCode(), headers, null, null, version, List.of());
        }
        InputStream stream = new ResponseReleasingInputStream(in, response);
        if (download != null) {
            stream = new CountingInputStream(stream, total, download::onProgress);
        }
        // the response stays open: closing the returned stream releases the connection
        return new WireResponse(response.getCode(), headers, null, stream, version, List.of(),
                () -> toTrailers(entity));
    }

    private static byte[] drainWithProgress(InputStream in, long total, DownloadProgressListener listener) throws IOException {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long read = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            read += n;
            listener.onProgress(read, total);
        }
        if (read == 0 && total == 0) {
            listener.onProgress(0, 0);
        }
        return out.toByteArray();
    }

    private static HttpClient.Version toVersion(ProtocolVersion version) {
        return version != null && version.getMajor() == 2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1;
    }

    private static List<Header> toHeaders(org.apache.hc.core5.http.Header[] headers) {
        if (headers == null || headers.length == 0) {
            return List.of();
        }
        List<Header> list = new ArrayList<>(headers.length);
        for (org.apache.hc.core5.http.Header header : headers) {
            list.add(new Header(header.getName(), header.getValue()));
        }
        return List.copyOf(list);
    }

    private static List<Header> toTrailers(HttpEntity entity) {
        try {
            List<? extends org.apache.hc.core5.http.Header> trailers = entity.getTrailers().get();
            if (trailers == null || trailers.isEmpty()) {
                return List.of();
            }
            List<Header> list = new ArrayList<>(trailers.size());
            for (org.apache.hc.core5.http.Header header : trailers) {
                list.add(new Header(header.getName(), header.getValue()));
            }
            return List.copyOf(list);
        } catch (RuntimeException ignored) {
            // trailer access is best-effort on the message layer
            return List.of();
        }
    }

    /**
     * Keeps the open response alive for the lifetime of the streamed entity body and releases
     * it (returning the connection to the pool) when the caller closes the stream.
     */
    private static final class ResponseReleasingInputStream extends FilterInputStream {

        private final ClassicHttpResponse response;

        ResponseReleasingInputStream(InputStream in, ClassicHttpResponse response) {
            super(in);
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } finally {
                response.close();
            }
        }
    }

    /**
     * Adapts the core's {@link CookieStore} (ADR-0032/0039) to HttpClient 5's
     * {@code CookieStore}, best-effort in both directions: a store failure must not fail the
     * call.
     */
    static final class JdkCookieStoreAdapter implements org.apache.hc.client5.http.cookie.CookieStore {

        private final CookieStore store;

        JdkCookieStoreAdapter(CookieStore store) {
            this.store = store;
        }

        @Override
        public void addCookie(org.apache.hc.client5.http.cookie.Cookie cookie) {
            try {
                java.net.HttpCookie hc = new java.net.HttpCookie(cookie.getName(), cookie.getValue());
                if (cookie.getDomain() != null) {
                    hc.setDomain(cookie.getDomain());
                }
                if (cookie.getPath() != null) {
                    hc.setPath(cookie.getPath());
                }
                Instant expiry = cookie.getExpiryInstant();
                if (expiry != null) {
                    hc.setMaxAge(Math.max(0, (expiry.toEpochMilli() - System.currentTimeMillis()) / 1000));
                }
                store.add(cookieOriginUri(cookie), hc);
            } catch (RuntimeException ignored) {
                // best-effort persistence
            }
        }

        private static URI cookieOriginUri(org.apache.hc.client5.http.cookie.Cookie cookie) {
            String host = cookie.getDomain() != null && !cookie.getDomain().isBlank() ? cookie.getDomain() : "localhost";
            String path = cookie.getPath() != null && !cookie.getPath().isBlank() ? cookie.getPath() : "/";
            return URI.create("http://" + host + (path.startsWith("/") ? path : "/" + path));
        }

        @Override
        public List<org.apache.hc.client5.http.cookie.Cookie> getCookies() {
            try {
                List<org.apache.hc.client5.http.cookie.Cookie> cookies = new ArrayList<>();
                for (java.net.HttpCookie hc : store.getCookies()) {
                    if (hc.hasExpired()) {
                        continue;
                    }
                    BasicClientCookie cookie = new BasicClientCookie(hc.getName(), hc.getValue());
                    if (hc.getDomain() != null) {
                        cookie.setDomain(hc.getDomain());
                    }
                    if (hc.getPath() != null) {
                        cookie.setPath(hc.getPath());
                    }
                    if (hc.getMaxAge() > 0) {
                        cookie.setExpiryDate(Instant.now().plusSeconds(hc.getMaxAge()));
                    }
                    cookies.add(cookie);
                }
                return cookies;
            } catch (RuntimeException e) {
                return List.of();
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean clearExpired(Date date) {
            return clearExpired(date.toInstant());
        }

        @Override
        public boolean clearExpired(Instant date) {
            try {
                for (URI uri : store.getURIs()) {
                    for (java.net.HttpCookie cookie : store.get(uri)) {
                        if (cookie.hasExpired()) {
                            store.remove(uri, cookie);
                        }
                    }
                }
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public void clear() {
            try {
                store.removeAll();
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }
    }

    /**
     * Best-effort {@link Authenticator} mapping (ADR-0058 §Decision.5): HttpClient 5 asks the
     * provider on an authentication challenge; the provider consults the JDK authenticator for
     * the challenged host and returns its credentials (Basic).
     */
    static final class JdkAuthenticatorCredentialsProvider implements CredentialsProvider {

        private final Authenticator authenticator;

        JdkAuthenticatorCredentialsProvider(Authenticator authenticator) {
            this.authenticator = authenticator;
        }

        @Override
        public Credentials getCredentials(AuthScope authScope, HttpContext context) {
            if (authScope == null || authScope.getHost() == null) {
                return null;
            }
            try {
                String host = authScope.getHost();
                PasswordAuthentication credentials = authenticator.requestPasswordAuthenticationInstance(
                        host, InetAddress.getByName(host), authScope.getPort(), "http", null, null, null,
                        Authenticator.RequestorType.SERVER);
                if (credentials == null || credentials.getPassword() == null) {
                    return null;
                }
                return new UsernamePasswordCredentials(credentials.getUserName(), credentials.getPassword());
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Adapts a plain {@link Executor} (the core's {@code ModuleInfo.getExecutor()} contract) to
     * the {@link ExecutorService} the engine's async path stores. The lifecycle methods affect
     * the adapter, not the wrapped executor, so a user-supplied executor outlives the engine.
     */
    static final class ExecutorServiceAdapter extends java.util.concurrent.AbstractExecutorService {

        private final Executor delegate;

        ExecutorServiceAdapter(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            // the wrapped executor is owned by the caller
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return false;
        }
    }
}
