package ru.jrestly.engine.okhttp;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.tls.OkHostnameVerifier;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import ru.jrestly.ModuleInfo;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineOptions;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.Header;
import ru.jrestly.http.MultipartBodyPublisher;
import ru.jrestly.http.UploadProgressListener;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link HttpEngine} backed by Square OkHttp (ADR-0058, phase 2).
 *
 * <p>Parity contract with the zero-dependency {@link JdkHttpEngine} (ADR-0058 §Decision.4):
 * <ul>
 *   <li>{@code options.timeout()} — per-call deadline via the call's own okio timeout
 *       (connect + write + read);</li>
 *   <li>{@code options.version()} — best-effort protocol pinning: OkHttp negotiates at the
 *       client level, so a derived client (sharing the connection pool and dispatcher) is used
 *       for a pinned version; {@code HTTP_2} keeps the upgrade/fallback list
 *       {@code [H2, HTTP/1.1]};</li>
 *   <li>{@code options.streamResponse()} — the body is delivered as a live
 *       {@link java.io.InputStream} (the OkHttp response body stream, the response stays open
 *       until the stream is closed); trailers are reported in byte-buffering mode only, where
 *       the protocol has finished delivering the body;</li>
 *   <li>upload/download progress listeners — counting wrappers over the request/response
 *       streams; the final {@code (total, total)} event and the zero-length {@code (0, 0)}
 *       semantics of ADR-0030/0033 are preserved;</li>
 *   <li>{@code close()} releases the dispatcher executor and the connection pool only when the
 *       engine owns the client.</li>
 * </ul>
 *
 * <p>The streaming/multipart request bodies arrive at the engine as JDK
 * {@link HttpRequest.BodyPublisher}s ({@link WireRequest#bodyPublisherSupplier()},
 * {@link WireRequest#parts()}); they are drained into an {@link InputStream} with a bounded
 * queue (backpressure: one chunk in flight).
 */
public final class OkHttpEngine implements HttpEngine {

    private static final List<Protocol> HTTP_2_PROTOCOLS = List.of(Protocol.HTTP_2, Protocol.HTTP_1_1);
    private static final List<Protocol> HTTP_1_PROTOCOLS = List.of(Protocol.HTTP_1_1);

    /**
     * Best-effort trust manager for user-supplied {@link SSLContext}s (ADR-0058 §Decision.5):
     * the peer chain is still validated by the socket factory's own context during the
     * handshake; this instance only feeds OkHttp's peer-chain cleaner, and the no-op cleaner
     * keeps the full chain intact.
     */
    private static final X509TrustManager SOCKET_FACTORY_TRUST_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    };

    private final OkHttpClient baseClient;
    private final boolean ownsClient;
    private final boolean ownsDispatcherExecutor;
    private final Map<HttpClient.Version, OkHttpClient> pinnedClients = new ConcurrentHashMap<>();

    /**
     * Constructs an {@link OkHttpEngine} with an internally managed default {@link OkHttpClient}.
     * The client is released on {@link #close()}.
     */
    public OkHttpEngine() {
        this(new OkHttpClient.Builder().build(), true, true);
    }

    /**
     * Constructs an {@link OkHttpEngine} wrapping an existing {@link OkHttpClient} without ownership.
     * The client will not be closed on {@link #close()}.
     *
     * @param client the OkHttp client, must not be {@code null}
     */
    public OkHttpEngine(OkHttpClient client) {
        this(client, false, false);
    }

    /**
     * Constructs an {@link OkHttpEngine} wrapping an existing {@link OkHttpClient} with optional
     * lifecycle ownership.
     *
     * @param client the OkHttp client, must not be {@code null}
     * @param ownsClient {@code true} if {@link #close()} should release the client's resources
     */
    public OkHttpEngine(OkHttpClient client, boolean ownsClient) {
        this(client, ownsClient, false);
    }

    private OkHttpEngine(OkHttpClient client, boolean ownsClient, boolean ownsDispatcherExecutor) {
        this.baseClient = Objects.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
        this.ownsDispatcherExecutor = ownsDispatcherExecutor;
    }

    /**
     * Creates an {@link OkHttpEngine} wrapping the specified {@link OkHttpClient} without
     * lifecycle ownership.
     *
     * @param client the OkHttp client, must not be {@code null}
     * @return the HTTP engine instance
     */
    public static OkHttpEngine from(OkHttpClient client) {
        return new OkHttpEngine(client, false, false);
    }

    /**
     * Creates a new {@link OkHttpEngine} with an internally managed {@link OkHttpClient}
     * configured from {@link ModuleInfo} (the OkHttp twin of
     * {@link JdkHttpEngine#create(ModuleInfo)}): connect/read/write timeouts, TLS
     * (ADR-0020), cookies (ADR-0032/0039), proxy (ADR-0037, per connection via a
     * client-level {@link ProxySelector}), the 401-challenge authenticator
     * (ADR-0037, best-effort Basic), and the async executor (ADR-0026). Redirects and connection retries are left to the declarative layer.
     *
     * @param moduleInfo the client module configuration, must not be {@code null}
     * @return a new managed HTTP engine instance
     */
    public static OkHttpEngine create(ModuleInfo moduleInfo) {
        Objects.requireNonNull(moduleInfo, "moduleInfo");
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(moduleInfo.getConnectTimeout()))
                .readTimeout(Duration.ofMillis(moduleInfo.getSocketTimeout()))
                .writeTimeout(Duration.ofMillis(moduleInfo.getSocketTimeout()))
                // the jRestly redirect/retry policies run in the declarative layer
                .followRedirects(false)
                .retryOnConnectionFailure(false);

        SSLContext sslContext = moduleInfo.getSslContext();
        if (sslContext != null) {
            builder.sslSocketFactory(sslContext.getSocketFactory(), SOCKET_FACTORY_TRUST_MANAGER);
        }
        SSLParameters sslParameters = moduleInfo.getSslParameters();
        if (sslParameters != null && "HTTPS".equalsIgnoreCase(sslParameters.getEndpointIdentificationAlgorithm())) {
            builder.hostnameVerifier(OkHostnameVerifier.INSTANCE);
        }
        CookieHandler cookieHandler = moduleInfo.getCookieHandler();
        // the core's cookie configuration (ADR-0032/0039) always yields a CookieManager,
        // which exposes the store; a bare CookieHandler carries no store to mirror
        if (cookieHandler instanceof CookieManager cookieManager) {
            builder.cookieJar(new CookieStoreJar(cookieManager.getCookieStore()));
        }
        Authenticator authenticator = moduleInfo.getAuthenticator();
        if (authenticator != null) {
            builder.addInterceptor(new BasicAuthChallengeInterceptor(authenticator));
        }
        ProxySelector proxySelector = moduleInfo.getProxySelector();
        if (proxySelector != null) {
            // OkHttp consults the selector per connection, mirroring the JDK engine's per-request resolution
            builder.proxySelector(proxySelector);
        }
        // The Dispatcher needs an ExecutorService; the core's ModuleInfo exposes the broader Executor
        Executor executor = moduleInfo.getExecutor();
        ExecutorService dispatcherExecutor;
        boolean ownsDispatcherExecutor;
        if (executor instanceof ExecutorService service) {
            dispatcherExecutor = service;
            ownsDispatcherExecutor = false;
        } else if (executor != null) {
            dispatcherExecutor = new DelegatingExecutorService(executor);
            ownsDispatcherExecutor = false;
        } else if (moduleInfo.isVirtualThreads()) {
            dispatcherExecutor = Executors.newVirtualThreadPerTaskExecutor();
            ownsDispatcherExecutor = true;
        } else {
            dispatcherExecutor = null;
            ownsDispatcherExecutor = true;
        }
        if (dispatcherExecutor != null) {
            builder.dispatcher(new Dispatcher(dispatcherExecutor));
        }
        return new OkHttpEngine(builder.build(), true, ownsDispatcherExecutor);
    }

    /**
     * Returns the underlying base {@link OkHttpClient}.
     *
     * @return the OkHttp client
     */
    public OkHttpClient getOkHttpClient() {
        return baseClient;
    }

    @Override
    public WireResponse send(WireRequest request, HttpEngineOptions options) throws IOException, InterruptedException {
        Call call = clientFor(options).newCall(toOkHttpRequest(request, options));
        applyTimeout(call, options);
        Response response = call.execute();
        try {
            return options != null && options.streamResponse()
                    ? toStreamingWireResponse(response, downloadListener(options))
                    : toBufferedWireResponse(response, downloadListener(options));
        } catch (IOException | RuntimeException | Error e) {
            // the buffered path closes the response itself; this covers the streaming path
            // and any failure before the body was consumed
            response.close();
            throw e;
        }
    }

    @Override
    public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
        Call call = clientFor(options).newCall(toOkHttpRequest(request, options));
        applyTimeout(call, options);
        boolean stream = options != null && options.streamResponse();
        DownloadProgressListener download = downloadListener(options);
        CompletableFuture<WireResponse> future = new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call c, Response response) {
                // the prototype closed the response unconditionally in this callback, which
                // made streaming bodies unreadable in async mode (ADR-0058)
                try {
                    if (stream) {
                        future.complete(toStreamingWireResponse(response, download));
                    } else {
                        future.complete(toBufferedWireResponse(response, download));
                    }
                } catch (Throwable t) {
                    response.close();
                    future.completeExceptionally(t);
                }
            }
        });
        return future;
    }

    @Override
    public void close() {
        if (ownsClient) {
            // version-pinned clients derived from the base client share its dispatcher and pool
            if (ownsDispatcherExecutor) {
                baseClient.dispatcher().executorService().shutdown();
            }
            baseClient.connectionPool().evictAll();
        }
    }

    private static DownloadProgressListener downloadListener(HttpEngineOptions options) {
        return options != null ? options.downloadProgressListener() : null;
    }

    private static void applyTimeout(Call call, HttpEngineOptions options) {
        if (options != null && options.timeout() != null) {
            // OkHttp 4.x: the per-call deadline is the call's own okio timeout
            // (OkHttp 5 exposes a typed Call.callTimeout(long, TimeUnit) instead)
            call.timeout().timeout(options.timeout().toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    // OkHttp negotiates the protocol at client level; a pinned version gets a derived client
    // that shares the base client's connection pool and dispatcher (best-effort, ADR-0058).
    private OkHttpClient clientFor(HttpEngineOptions options) {
        HttpClient.Version version = options != null ? options.version() : null;
        if (version == null) {
            return baseClient;
        }
        return pinnedClients.computeIfAbsent(version, pinned -> baseClient.newBuilder()
                .protocols(pinned == HttpClient.Version.HTTP_2 ? HTTP_2_PROTOCOLS : HTTP_1_PROTOCOLS)
                .build());
    }

    private Request toOkHttpRequest(WireRequest request, HttpEngineOptions options) {
        Request.Builder builder = new Request.Builder().url(request.uri().toString());

        if (request.headers() != null) {
            for (Header header : request.headers()) {
                builder.addHeader(header.name(), header.value());
            }
        }
        if (request.contentType() != null) {
            // set-style: replaces a duplicated Content-Type carried in request.headers(),
            // mirroring JdkHttpEngine
            builder.header("Content-Type", request.contentType());
        }

        UploadProgressListener upload = options != null ? options.uploadProgressListener() : null;
        MediaType mediaType = request.contentType() != null ? MediaType.parse(request.contentType()) : null;
        RequestBody body = buildBody(request, mediaType, upload);
        builder.method(request.method().name(), body);
        return builder.build();
    }

    private RequestBody buildBody(WireRequest request, MediaType mediaType, UploadProgressListener upload) {
        HttpRequest.BodyPublisher publisher = null;
        if (request.parts() != null) {
            publisher = MultipartBodyPublisher.of(request.boundary(), request.parts());
        } else if (request.bodyPublisherSupplier() != null) {
            publisher = request.bodyPublisherSupplier().get();
        }
        if (publisher != null) {
            // the JDK BodyPublisher reports -1 for unknown length (chunked transfer)
            long contentLength = publisher.contentLength();
            return countingBody(new PublisherInputStream(publisher), mediaType, contentLength, upload);
        }
        if (request.body() != null) {
            return countingBody(new ByteArrayInputStream(request.body()), mediaType, request.body().length, upload);
        }
        // no payload: methods that carry a body get an explicit empty one (Content-Length: 0),
        // all others go bodyless — mirrors the prototype and JdkHttpEngine's noBody()
        if (requiresEmptyBody(request.method().name())) {
            return countingBody(new ByteArrayInputStream(new byte[0]), mediaType, 0L, upload);
        }
        return null;
    }

    private static boolean requiresEmptyBody(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
    }

    private static RequestBody countingBody(InputStream in, MediaType mediaType, long contentLength, UploadProgressListener upload) {
        Source source = Okio.source(in);
        RequestBody body;
        if (upload == null) {
            body = new SourceRequestBody(mediaType, contentLength, source);
        } else {
            AtomicLong sent = new AtomicLong();
            Source counting = new Source() {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    long n = source.read(sink, byteCount);
                    if (n > 0) {
                        upload.onProgress(sent.addAndGet(n), contentLength);
                    }
                    return n;
                }

                @Override
                public Timeout timeout() {
                    return source.timeout();
                }

                @Override
                public void close() throws IOException {
                    source.close();
                }
            };
            body = new SourceRequestBody(mediaType, contentLength, counting);
        }
        if (upload != null && contentLength == 0) {
            // zero-length semantics of ADR-0030: exactly one (0, 0) event
            upload.onProgress(0, 0);
        }
        return body;
    }

    private WireResponse toBufferedWireResponse(Response response, DownloadProgressListener download) throws IOException {
        try {
            List<Header> headers = toHeaders(response.headers());
            long total = response.body() != null ? response.body().contentLength() : -1L;
            byte[] bodyBytes = null;
            if (response.body() != null) {
                bodyBytes = download != null
                        ? drainWithProgress(response.body().byteStream(), total, download)
                        : response.body().bytes();
            }
            // the body is fully consumed, so HTTP/1.1 trailers (if any) have been delivered
            return new WireResponse(response.code(), headers, bodyBytes, null,
                    toVersion(response.protocol()), toHeaders(response.trailers()));
        } finally {
            response.close();
        }
    }

    private WireResponse toStreamingWireResponse(Response response, DownloadProgressListener download) throws IOException {
        List<Header> headers = toHeaders(response.headers());
        HttpClient.Version version = toVersion(response.protocol());
        if (response.body() == null) {
            response.close();
            return new WireResponse(response.code(), headers, null, null, version, List.of());
        }
        long total = response.body().contentLength();
        InputStream in = response.body().byteStream();
        if (download != null) {
            in = new CountingInputStream(in, total, download);
        }
        // the response stays open: closing the returned stream releases the connection
        return new WireResponse(response.code(), headers, null, in, version, List.of());
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

    private static HttpClient.Version toVersion(Protocol protocol) {
        return switch (protocol) {
            case HTTP_2, H2_PRIOR_KNOWLEDGE, QUIC -> HttpClient.Version.HTTP_2;
            default -> HttpClient.Version.HTTP_1_1;
        };
    }

    /**
     * A {@link RequestBody} that writes the given {@link Source} to the wire with a declared
     * content length (or {@code -1} for chunked transfer). OkHttp 4.x has no
     * {@code RequestBody.create(MediaType, long, Source)} factory, so the body is assembled
     * directly; the source is closed once the write completes (the streaming/multipart
     * publishers supply a fresh body per transmission attempt).
     */
    static final class SourceRequestBody extends RequestBody {

        private final MediaType contentType;
        private final long contentLength;
        private final Source source;

        SourceRequestBody(MediaType contentType, long contentLength, Source source) {
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.source = source;
        }

        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            try {
                sink.writeAll(source);
                sink.flush();
            } finally {
                source.close();
            }
        }
    }

    private static List<Header> toHeaders(Headers headers) {
        if (headers == null || headers.size() == 0) {
            return List.of();
        }
        List<Header> list = new ArrayList<>(headers.size());
        for (int i = 0; i < headers.size(); i++) {
            list.add(new Header(headers.name(i), headers.value(i)));
        }
        return List.copyOf(list);
    }

    /**
     * Pulls a {@link HttpRequest.BodyPublisher} into a blocking {@link InputStream} with
     * credit-based backpressure (at most {@link #MAX_IN_FLIGHT_CHUNKS} chunks queued).
     *
     * <p>The queue is an unbounded {@link LinkedBlockingQueue}: entries are bounded by the
     * credit scheme, and the non-blocking {@code offer} is what keeps request-driven
     * publishers (the JDK's {@code BodyPublishers.ofInputStream}/concat, the core's
     * multipart body) from deadlocking — those emit synchronously inside {@code request()},
     * so a blocking {@code put} on a full queue would wait for the reader that has not
     * started yet. The scheme: the subscriber starts with {@code MAX_IN_FLIGHT_CHUNKS}
     * credits; every chunk the reader consumes releases exactly one credit, so the queue
     * never holds more than the cap. Publishers that emit from another thread are served
     * the same way.
     */
    static final class PublisherInputStream extends InputStream {

        private static final Object END_OF_STREAM = new Object();
        private static final int MAX_IN_FLIGHT_CHUNKS = 4;
        private static final long POLL_TIMEOUT_MS = 100;

        private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private byte[] current = new byte[0];
        private int position;
        // sticky end-of-stream: the END marker (and any error) is consumed exactly once,
        // subsequent reads must keep returning EOF instead of polling an empty queue
        private volatile boolean eof;

        PublisherInputStream(HttpRequest.BodyPublisher publisher) {
            publisher.subscribe(new DrainingSubscriber());
        }

        @Override
        public int read() throws IOException {
            if (position >= current.length) {
                byte[] next = nextChunk();
                if (next == null) {
                    return -1;
                }
                current = next;
                position = 0;
            }
            return current[position++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) {
                throw new NullPointerException("buffer");
            }
            if (offset < 0 || length < 0 || length > buffer.length - offset) {
                throw new IndexOutOfBoundsException("offset=" + offset + " length=" + length);
            }
            if (length == 0) {
                return 0;
            }
            int total = 0;
            while (total < length) {
                if (position >= current.length) {
                    byte[] next = nextChunk();
                    if (next == null) {
                        break;
                    }
                    current = next;
                    position = 0;
                }
                int n = Math.min(length - total, current.length - position);
                System.arraycopy(current, position, buffer, offset + total, n);
                position += n;
                total += n;
            }
            return total == 0 ? -1 : total;
        }

        @Override
        public int available() {
            return current.length - position;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // drain what the publisher has already produced so it stops, then cancel so a
            // request-driven source (e.g. a file InputStream) is released
            while (queue.poll() != null) {
                // drain
            }
            Flow.Subscription sub = subscription.get();
            if (sub != null) {
                sub.cancel();
            }
        }

        private byte[] nextChunk() throws IOException {
            if (eof) {
                return null;
            }
            Throwable failed = failure.get();
            if (failed != null) {
                eof = true;
                throw toIOException(failed);
            }
            try {
                while (true) {
                    Object item = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (item == END_OF_STREAM) {
                        eof = true;
                        return null;
                    }
                    if (item instanceof FailureHolder holder) {
                        eof = true;
                        throw toIOException(holder.error);
                    }
                    if (item != null) {
                        releaseCredit();
                        return (byte[]) item;
                    }
                    if (closed.get()) {
                        // closed mid-stream without the publisher finishing: treat as EOF
                        eof = true;
                        return null;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }

        private static IOException toIOException(Throwable error) {
            return error instanceof IOException ioe ? ioe : new IOException(error);
        }

        // one credit per consumed chunk: the publisher may keep at most
        // MAX_IN_FLIGHT_CHUNKS chunks ahead of the reader
        private void releaseCredit() {
            if (closed.get()) {
                return;
            }
            Flow.Subscription s = subscription.get();
            if (s != null) {
                s.request(1);
            }
        }

        private final class DrainingSubscriber implements Flow.Subscriber<ByteBuffer> {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription.set(s);
                // the initial credits: a request-driven publisher emits these chunks
                // synchronously in the constructor; the queue stays capped by the
                // credit scheme (see the class javadoc)
                s.request(MAX_IN_FLIGHT_CHUNKS);
            }

            @Override
            public void onNext(ByteBuffer item) {
                if (closed.get()) {
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                // never blocks: the queue is unbounded, the credit cap bounds its size
                queue.offer(chunk);
            }

            @Override
            public void onError(Throwable error) {
                queue.offer(new FailureHolder(error));
            }

            @Override
            public void onComplete() {
                queue.offer(END_OF_STREAM);
            }
        }

        private record FailureHolder(Throwable error) {
        }
    }

    /**
     * Download progress wrapper for streaming bodies: counts bytes and reports
     * {@code (bytesRead, total)}; {@code total} is the declared body length or {@code -1} for
     * chunked bodies, and a zero-length body fires exactly one {@code (0, 0)} event.
     */
    static final class CountingInputStream extends FilterInputStream {

        private final long total;
        private final DownloadProgressListener listener;
        private final AtomicLong bytesRead = new AtomicLong();
        private final AtomicBoolean zeroNotified = new AtomicBoolean();

        CountingInputStream(InputStream in, long total, DownloadProgressListener listener) {
            super(in);
            this.total = total;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                listener.onProgress(bytesRead.addAndGet(1), total);
            } else if (total == 0 && zeroNotified.compareAndSet(false, true)) {
                listener.onProgress(0, 0);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                listener.onProgress(bytesRead.addAndGet(n), total);
            } else if (n == -1 && total == 0 && zeroNotified.compareAndSet(false, true)) {
                listener.onProgress(0, 0);
            }
            return n;
        }
    }

    /**
     * Adapts the core's {@link CookieStore} (ADR-0032/0039) to OkHttp's {@link CookieJar}.
     */
    static final class CookieStoreJar implements CookieJar {

        private final CookieStore store;

        CookieStoreJar(CookieStore store) {
            this.store = store;
        }

        @Override
        public void saveFromResponse(okhttp3.HttpUrl url, List<Cookie> cookies) {
            URI uri = url.uri();
            for (Cookie cookie : cookies) {
                try {
                    java.net.HttpCookie hc = new java.net.HttpCookie(cookie.name(), cookie.value());
                    hc.setPath(cookie.path() != null ? cookie.path() : "/");
                    if (cookie.domain() != null) {
                        hc.setDomain(cookie.domain());
                    }
                    long expiresAt = cookie.expiresAt();
                    if (expiresAt > 0) {
                        hc.setMaxAge((int) Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000));
                    }
                    store.add(uri, hc);
                } catch (RuntimeException ignored) {
                    // best-effort persistence: a store failure must not fail the call
                }
            }
        }

        @Override
        public List<Cookie> loadForRequest(okhttp3.HttpUrl url) {
            try {
                List<Cookie> cookies = new ArrayList<>();
                for (java.net.HttpCookie hc : store.get(url.uri())) {
                    if (hc.hasExpired()) {
                        continue;
                    }
                    Cookie.Builder builder = new Cookie.Builder().name(hc.getName()).value(hc.getValue());
                    if (hc.getPath() != null) {
                        builder.path(hc.getPath());
                    }
                    if (hc.getDomain() != null) {
                        builder.domain(hc.getDomain());
                    }
                    long maxAge = hc.getMaxAge();
                    if (maxAge > 0) {
                        builder.expiresAt(System.currentTimeMillis() + maxAge * 1000);
                    }
                    cookies.add(builder.build());
                }
                return cookies;
            } catch (RuntimeException e) {
                return List.of();
            }
        }
    }

    /**
     * Best-effort {@link Authenticator} mapping (ADR-0058 §Decision.5): on a 401 without an
     * Authorization header the authenticator is asked for the target's credentials and the
     * request is retried once with a {@code Basic} header.
     */
    static final class BasicAuthChallengeInterceptor implements Interceptor {

        private final Authenticator authenticator;

        BasicAuthChallengeInterceptor(Authenticator authenticator) {
            this.authenticator = authenticator;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = chain.proceed(request);
            if (response.code() == 401 && request.header("Authorization") == null) {
                PasswordAuthentication credentials = null;
                try {
                    okhttp3.HttpUrl target = request.url();
                    // the no-arg override contract (the core's SimpleModuleInfo authenticators):
                    // the instance entry point arms the requesting context, then the user's
                    // getPasswordAuthentication() is invoked for a SERVER challenge
                    InetAddress host = InetAddress.getByName(target.host());
                    credentials = authenticator.requestPasswordAuthenticationInstance(
                            null, host, target.port(), target.host(), null, null, target.url(),
                            Authenticator.RequestorType.SERVER);
                } catch (IOException | RuntimeException e) {
                    // best-effort: keep the original 401
                }
                if (credentials != null && credentials.getPassword() != null) {
                    // char[] needs no charset: the password is already decoded
                    String userPass = credentials.getUserName() + ':' + new String(credentials.getPassword());
                    String token = Base64.getEncoder()
                            .encodeToString(userPass.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    response.close();
                    return chain.proceed(request.newBuilder().header("Authorization", "Basic " + token).build());
                }
            }
            return response;
        }
    }

    /**
     * Adapts a plain {@link Executor} (the core's {@code ModuleInfo.getExecutor()} contract)
     * to the {@link ExecutorService} the OkHttp {@link Dispatcher} requires. The OkHttp
     * dispatcher only uses {@code execute}/{@code submit} (enqueue callbacks and the cache
     * cleaner); the lifecycle methods affect the adapter, not the wrapped executor, so a
     * user-supplied executor outlives the engine by default. The {@code invoke*} bodies
     * follow the {@link java.util.concurrent.ThreadPoolExecutor} pattern.
     */
    static final class DelegatingExecutorService implements ExecutorService {

        private final Executor delegate;
        private final AtomicBoolean shutdown = new AtomicBoolean();

        DelegatingExecutorService(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return submit(task, null);
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            FutureTask<T> future = new FutureTask<>(task, result);
            delegate.execute(future);
            return future;
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            FutureTask<T> future = new FutureTask<>(task);
            delegate.execute(future);
            return future;
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return invokeAll(tasks, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException {
            Objects.requireNonNull(tasks);
            Objects.requireNonNull(unit);
            long nanos = unit.toNanos(timeout);
            List<FutureTask<T>> futures = new ArrayList<>(tasks.size());
            boolean interrupted = false;
            try {
                for (Callable<T> task : tasks) {
                    FutureTask<T> future = new FutureTask<>(task);
                    futures.add(future);
                    try {
                        delegate.execute(future);
                    } catch (RejectedExecutionException rx) {
                        if (!future.cancel(true)) {
                            throw rx;
                        }
                    }
                }
                for (FutureTask<T> future : futures) {
                    if (!future.isDone()) {
                        if (nanos <= 0) {
                            future.cancel(true);
                        } else {
                            try {
                                future.get(nanos, TimeUnit.NANOSECONDS);
                            } catch (ExecutionException | TimeoutException e) {
                                // the outcome is recorded in the returned future
                            }
                        }
                    }
                }
            } catch (InterruptedException ie) {
                interrupted = true;
                for (FutureTask<T> future : futures) {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
                throw ie;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return List.copyOf(futures);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
            try {
                return invokeAny(tasks, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (TimeoutException te) {
                // the MAX_VALUE nanos budget is ~292 years, so this is effectively unreachable;
                // the no-timeout overload's contract has no TimeoutException to surface
                throw new ExecutionException(te);
            }
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            Objects.requireNonNull(tasks);
            Objects.requireNonNull(unit);
            long nanos = unit.toNanos(timeout);
            List<FutureTask<T>> futures = new ArrayList<>(tasks.size());
            boolean interrupted = false;
            try {
                for (Callable<T> task : tasks) {
                    FutureTask<T> future = new FutureTask<>(task);
                    futures.add(future);
                    try {
                        delegate.execute(future);
                    } catch (RejectedExecutionException rx) {
                        if (!future.cancel(true)) {
                            throw rx;
                        }
                    }
                }
                for (int i = 0; i < futures.size(); i++) {
                    FutureTask<T> future = futures.get(i);
                    if (future.isDone()) {
                        try {
                            return resultOf(future, futures, i);
                        } catch (java.util.concurrent.CancellationException ce) {
                            // try the next completed task
                        }
                    }
                }
                for (FutureTask<T> future : futures) {
                    if (!future.isDone()) {
                        if (nanos <= 0) {
                            future.cancel(true);
                        } else {
                            try {
                                future.get(nanos, TimeUnit.NANOSECONDS);
                            } catch (ExecutionException | TimeoutException e) {
                                // the outcome is recorded in the returned future
                            }
                        }
                    }
                }
                for (int i = 0; i < futures.size(); i++) {
                    FutureTask<T> future = futures.get(i);
                    if (future.isDone()) {
                        try {
                            return resultOf(future, futures, i);
                        } catch (java.util.concurrent.CancellationException ce) {
                            // try the next completed task
                        }
                    }
                }
            } catch (InterruptedException ie) {
                interrupted = true;
                for (FutureTask<T> future : futures) {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
                throw ie;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            throw new TimeoutException();
        }

        private static <T> T resultOf(FutureTask<T> done, List<FutureTask<T>> all, int index)
                throws ExecutionException, InterruptedException {
            T result = done.get();
            for (int i = 0; i < all.size(); i++) {
                if (i != index) {
                    all.get(i).cancel(true);
                }
            }
            return result;
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            if (shutdown.get()) {
                return true;
            }
            Thread.sleep(Math.max(1, unit.toMillis(timeout)));
            return shutdown.get();
        }
    }
}
