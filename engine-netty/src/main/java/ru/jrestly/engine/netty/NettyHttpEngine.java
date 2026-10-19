package ru.jrestly.engine.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
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
import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link HttpEngine} backed by Netty 4.1 (ADR-0070).
 *
 * <p>Provides an ultra-high-throughput, non-blocking HTTP/1.1 transport leveraging
 * Netty's pooled off-heap {@link io.netty.buffer.ByteBuf} memory model, connection pooling,
 * and reactive event loops.
 *
 * <p>Parity contract with the zero-dependency {@link ru.jrestly.engine.JdkHttpEngine} (ADR-0058/0070):
 * <ul>
 *   <li>{@code options.timeout()} — per-call request and connection deadline;</li>
 *   <li>{@code options.version()} — best-effort protocol reporting;</li>
 *   <li>{@code options.streamResponse()} — body delivered as a streaming {@link java.io.InputStream}
 *       with reactive-to-blocking backpressure and guaranteed off-heap buffer release;</li>
 *   <li>upload/download progress listeners — tracking sent and received byte counts preserving
 *       the {@code (0, 0)} and {@code (total, total)} semantics of ADR-0030/0033;</li>
 *   <li>compression — transport remains passive: no implicit decompression or injected headers;</li>
 *   <li>{@code close()} releases event loops and channel pools only when owned by the engine.</li>
 * </ul>
 */
public final class NettyHttpEngine implements HttpEngine {

    private final EventLoopGroup eventLoopGroup;
    private final boolean ownsGroup;
    private final long connectTimeoutMillis;
    private final SslContext sslContext;
    private final CookieHandler cookieHandler;
    private final AbstractChannelPoolMap<EndpointKey, FixedChannelPool> poolMap;

    /**
     * Constructs a {@link NettyHttpEngine} with an internally managed {@link NioEventLoopGroup}.
     * The event loop group is gracefully released on {@link #close()}.
     */
    public NettyHttpEngine() {
        this(new NioEventLoopGroup(), true, 0L, null, null);
    }

    /**
     * Constructs a {@link NettyHttpEngine} wrapping an existing {@link EventLoopGroup}
     * without lifecycle ownership.
     *
     * @param eventLoopGroup the Netty event loop group, must not be {@code null}
     */
    public NettyHttpEngine(EventLoopGroup eventLoopGroup) {
        this(eventLoopGroup, false, 0L, null, null);
    }

    /**
     * Constructs a {@link NettyHttpEngine} wrapping an existing {@link EventLoopGroup}
     * with optional lifecycle ownership.
     *
     * @param eventLoopGroup the Netty event loop group, must not be {@code null}
     * @param ownsGroup {@code true} if {@link #close()} should shut down the event loop group
     */
    public NettyHttpEngine(EventLoopGroup eventLoopGroup, boolean ownsGroup) {
        this(eventLoopGroup, ownsGroup, 0L, null, null);
    }

    private NettyHttpEngine(EventLoopGroup eventLoopGroup, boolean ownsGroup, long connectTimeoutMillis,
                            SslContext sslContext, CookieHandler cookieHandler) {
        this.eventLoopGroup = Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
        this.ownsGroup = ownsGroup;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.sslContext = sslContext;
        this.cookieHandler = cookieHandler;
        this.poolMap = new AbstractChannelPoolMap<>() {
            @Override
            protected FixedChannelPool newPool(EndpointKey key) {
                Bootstrap b = new Bootstrap()
                        .group(eventLoopGroup)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.SO_KEEPALIVE, true)
                        .remoteAddress(key.host(), key.port());
                if (connectTimeoutMillis > 0) {
                    b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, connectTimeoutMillis));
                }
                return new FixedChannelPool(
                        b,
                        new HttpChannelPoolHandler(key),
                        ChannelHealthChecker.ACTIVE,
                        FixedChannelPool.AcquireTimeoutAction.FAIL,
                        connectTimeoutMillis > 0 ? connectTimeoutMillis : 10_000L,
                        50,
                        200
                );
            }
        };
    }

    /**
     * Creates a new {@link NettyHttpEngine} configured according to {@link ModuleInfo}.
     *
     * @param moduleInfo the client module configuration, must not be {@code null}
     * @return the configured Netty HTTP engine
     */
    public static NettyHttpEngine create(ModuleInfo moduleInfo) {
        Objects.requireNonNull(moduleInfo, "moduleInfo");
        SslContext nettySsl = null;
        SSLContext jdkSsl = moduleInfo.getSslContext();
        if (jdkSsl != null) {
            @SuppressWarnings("deprecation")
            JdkSslContext deprecatedJdk = new JdkSslContext(jdkSsl, true, ClientAuth.NONE);
            nettySsl = deprecatedJdk;
        }
        return new NettyHttpEngine(
                new NioEventLoopGroup(),
                true,
                moduleInfo.getConnectTimeout(),
                nettySsl,
                moduleInfo.getCookieHandler()
        );
    }

    /**
     * Returns the underlying Netty {@link EventLoopGroup}.
     *
     * @return the event loop group
     */
    public EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    @Override
    public WireResponse send(WireRequest request, HttpEngineOptions options) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        try {
            long timeoutMillis = options != null && options.timeout() != null
                    ? options.timeout().toMillis()
                    : 60_000L;
            return sendAsync(request, options).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            if (cause instanceof InterruptedException ie) {
                throw ie;
            }
            throw new IOException(cause != null ? cause : e);
        } catch (TimeoutException e) {
            throw new IOException("Request timed out", e);
        }
    }

    @Override
    public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
        Objects.requireNonNull(request, "request");
        EndpointKey key = endpointKey(request.uri());
        ChannelPool pool = poolMap.get(key);
        CompletableFuture<WireResponse> future = new CompletableFuture<>();

        ScheduledFuture<?> timeoutTask = null;
        if (options != null && options.timeout() != null && !options.timeout().isZero() && !options.timeout().isNegative()) {
            Duration timeout = options.timeout();
            timeoutTask = eventLoopGroup.schedule(() -> {
                future.completeExceptionally(new IOException("Request timed out"));
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        final ScheduledFuture<?> finalTimeoutTask = timeoutTask;

        pool.acquire().addListener((Future<Channel> f) -> {
            if (!f.isSuccess()) {
                if (finalTimeoutTask != null) {
                    finalTimeoutTask.cancel(false);
                }
                future.completeExceptionally(toIOException(f.cause()));
                return;
            }
            Channel channel = f.getNow();
            ExchangeHandler handler = new ExchangeHandler(channel, pool, key, request, options, future, finalTimeoutTask);
            channel.pipeline().addLast("exchange", handler);
            handler.sendRequest();
        });

        return future;
    }

    @Override
    public void close() {
        poolMap.close();
        if (ownsGroup) {
            eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private static EndpointKey endpointKey(URI uri) {
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "http";
        String host = uri.getHost() != null ? uri.getHost() : "localhost";
        int port = uri.getPort();
        if (port <= 0) {
            port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        }
        return new EndpointKey(scheme, host, port);
    }

    private SslContext resolveSslContext() {
        if (sslContext != null) {
            return sslContext;
        }
        try {
            return SslContextBuilder.forClient().build();
        } catch (SSLException e) {
            throw new RuntimeException("Failed to create default client SslContext", e);
        }
    }

    private static IOException toIOException(Throwable error) {
        return error instanceof IOException ioe ? ioe : new IOException(error != null ? error.getMessage() : "Unknown I/O error", error);
    }

    private static byte[] readAll(java.net.http.HttpRequest.BodyPublisher publisher) {
        try (PublisherInputStream in = new PublisherInputStream(publisher)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read request body publisher", e);
        }
    }

    private static boolean requiresEmptyBody(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
    }

    record EndpointKey(String scheme, String host, int port) {}

    private final class HttpChannelPoolHandler implements ChannelPoolHandler {

        private final EndpointKey key;

        HttpChannelPoolHandler(EndpointKey key) {
            this.key = key;
        }

        @Override
        public void channelCreated(Channel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            if ("https".equalsIgnoreCase(key.scheme())) {
                SslContext sslCtx = resolveSslContext();
                pipeline.addLast("ssl", sslCtx.newHandler(ch.alloc(), key.host(), key.port()));
            }
            pipeline.addLast("codec", new HttpClientCodec());
        }

        @Override
        public void channelAcquired(Channel ch) throws Exception {
            // Channel acquired for an exchange
        }

        @Override
        public void channelReleased(Channel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            if (pipeline.get("exchange") != null) {
                pipeline.remove("exchange");
            }
        }
    }

    private final class ExchangeHandler extends ChannelInboundHandlerAdapter {

        private final Channel channel;
        private final ChannelPool pool;
        private final EndpointKey key;
        private final WireRequest request;
        private final HttpEngineOptions options;
        private final CompletableFuture<WireResponse> future;
        private final ScheduledFuture<?> timeoutTask;

        private final ByteArrayOutputStream bufferOut = new ByteArrayOutputStream();
        private final AtomicBoolean completed = new AtomicBoolean(false);

        private int statusCode;
        private List<Header> responseHeaders = List.of();
        private long declaredLength = -1L;
        private boolean isKeepAlive = true;
        private NettyChunkedInputStream stream = null;
        private long bytesRead = 0;

        ExchangeHandler(Channel channel, ChannelPool pool, EndpointKey key, WireRequest request,
                        HttpEngineOptions options, CompletableFuture<WireResponse> future,
                        ScheduledFuture<?> timeoutTask) {
            this.channel = channel;
            this.pool = pool;
            this.key = key;
            this.request = request;
            this.options = options;
            this.future = future;
            this.timeoutTask = timeoutTask;
        }

        void sendRequest() {
            try {
                byte[] bodyBytes = null;
                if (request.parts() != null) {
                    java.net.http.HttpRequest.BodyPublisher publisher = MultipartBodyPublisher.of(request.boundary(), request.parts());
                    bodyBytes = readAll(publisher);
                } else if (request.bodyPublisherSupplier() != null) {
                    java.net.http.HttpRequest.BodyPublisher publisher = request.bodyPublisherSupplier().get();
                    bodyBytes = readAll(publisher);
                } else if (request.body() != null) {
                    bodyBytes = request.body();
                }

                String path = request.uri().getRawPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                String rawQuery = request.uri().getRawQuery();
                String requestUri = rawQuery != null ? path + "?" + rawQuery : path;

                io.netty.handler.codec.http.HttpMethod method =
                        io.netty.handler.codec.http.HttpMethod.valueOf(request.method().name());

                UploadProgressListener uploadListener = options != null ? options.uploadProgressListener() : null;

                if (uploadListener != null && bodyBytes != null && bodyBytes.length > 0) {
                    final long total = bodyBytes.length;
                    HttpRequest initial = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, requestUri);
                    populateHeaders(initial.headers(), request, key, total);
                    channel.write(initial);

                    int chunkSize = 16384;
                    int offset = 0;
                    while (offset < bodyBytes.length) {
                        int len = Math.min(chunkSize, bodyBytes.length - offset);
                        ByteBuf chunkBuf = channel.alloc().buffer(len);
                        chunkBuf.writeBytes(bodyBytes, offset, len);
                        offset += len;
                        long sent = offset;
                        channel.write(new DefaultHttpContent(chunkBuf)).addListener(f -> {
                            if (f.isSuccess()) {
                                uploadListener.onProgress(sent, total);
                            }
                        });
                    }
                    channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                } else if (bodyBytes != null && bodyBytes.length > 0) {
                    ByteBuf content = channel.alloc().buffer(bodyBytes.length);
                    content.writeBytes(bodyBytes);
                    FullHttpRequest fullRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, requestUri, content);
                    populateHeaders(fullRequest.headers(), request, key, bodyBytes.length);
                    channel.writeAndFlush(fullRequest);
                } else if (bodyBytes != null && bodyBytes.length == 0) {
                    if (uploadListener != null) {
                        uploadListener.onProgress(0, 0);
                    }
                    FullHttpRequest fullRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, requestUri, Unpooled.EMPTY_BUFFER);
                    populateHeaders(fullRequest.headers(), request, key, 0);
                    channel.writeAndFlush(fullRequest);
                } else if (requiresEmptyBody(method.name())) {
                    if (uploadListener != null) {
                        uploadListener.onProgress(0, 0);
                    }
                    FullHttpRequest fullRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, requestUri, Unpooled.EMPTY_BUFFER);
                    populateHeaders(fullRequest.headers(), request, key, 0);
                    channel.writeAndFlush(fullRequest);
                } else {
                    FullHttpRequest fullRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, requestUri, Unpooled.EMPTY_BUFFER);
                    populateHeaders(fullRequest.headers(), request, key, -1);
                    channel.writeAndFlush(fullRequest);
                }
            } catch (Throwable t) {
                failExchange(t);
            }
        }

        private void populateHeaders(HttpHeaders headers, WireRequest req, EndpointKey endpoint, long contentLength) {
            boolean defaultPort = endpoint.port() <= 0
                    || ("http".equalsIgnoreCase(endpoint.scheme()) && endpoint.port() == 80)
                    || ("https".equalsIgnoreCase(endpoint.scheme()) && endpoint.port() == 443);
            headers.set(HttpHeaderNames.HOST, defaultPort ? endpoint.host() : endpoint.host() + ":" + endpoint.port());

            String overrideContentType = req.contentType();
            if (req.headers() != null) {
                for (Header h : req.headers()) {
                    if (overrideContentType != null && HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(h.name())) {
                        continue;
                    }
                    headers.add(h.name(), h.value());
                }
            }

            if (cookieHandler != null) {
                try {
                    Map<String, List<String>> cookieHeaders = cookieHandler.get(req.uri(), Collections.emptyMap());
                    if (cookieHeaders != null) {
                        for (Map.Entry<String, List<String>> entry : cookieHeaders.entrySet()) {
                            if (entry.getValue() != null) {
                                for (String val : entry.getValue()) {
                                    headers.add(entry.getKey(), val);
                                }
                            }
                        }
                    }
                } catch (IOException ignored) {}
            }

            if (overrideContentType != null) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, overrideContentType);
            }

            if (contentLength >= 0) {
                headers.set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
            }

            if (!headers.contains(HttpHeaderNames.CONNECTION)) {
                headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                DownloadProgressListener downloadListener = options != null ? options.downloadProgressListener() : null;

                if (msg instanceof HttpResponse resp) {
                    this.statusCode = resp.status().code();
                    this.responseHeaders = extractHeaders(resp.headers());
                    this.isKeepAlive = HttpUtil.isKeepAlive(resp);
                    this.declaredLength = HttpUtil.getContentLength(resp, -1L);

                    if (cookieHandler != null) {
                        Map<String, List<String>> headerMap = new HashMap<>();
                        for (Header h : responseHeaders) {
                            headerMap.computeIfAbsent(h.name(), k -> new ArrayList<>()).add(h.value());
                        }
                        try {
                            cookieHandler.put(request.uri(), headerMap);
                        } catch (IOException ignored) {}
                    }

                    if (options != null && options.streamResponse()) {
                        cancelTimeout();
                        this.stream = new NettyChunkedInputStream(channel, pool, declaredLength, downloadListener);
                        WireResponse response = new WireResponse(
                                statusCode,
                                responseHeaders,
                                null,
                                stream,
                                HttpClient.Version.HTTP_1_1,
                                List.of(),
                                stream::getTrailers
                        );
                        future.complete(response);
                        return;
                    }

                    if (declaredLength == 0 && downloadListener != null) {
                        downloadListener.onProgress(0, 0);
                    }
                }

                if (msg instanceof HttpContent content) {
                    if (stream != null) {
                        ByteBuf buf = content.content();
                        if (buf.isReadable()) {
                            stream.offerChunk(buf.retain());
                        }
                        if (msg instanceof LastHttpContent last) {
                            stream.offerLastContent(last);
                        }
                    } else {
                        ByteBuf buf = content.content();
                        if (buf.isReadable()) {
                            byte[] chunk = new byte[buf.readableBytes()];
                            buf.readBytes(chunk);
                            bufferOut.write(chunk, 0, chunk.length);
                            bytesRead += chunk.length;
                            if (downloadListener != null) {
                                downloadListener.onProgress(bytesRead, declaredLength);
                            }
                        }
                        if (msg instanceof LastHttpContent last) {
                            finishBuffered(last);
                        }
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        private void finishBuffered(LastHttpContent last) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            cancelTimeout();
            List<Header> trailers = List.of();
            if (!last.trailingHeaders().isEmpty()) {
                List<Header> list = new ArrayList<>(last.trailingHeaders().size());
                for (Map.Entry<String, String> entry : last.trailingHeaders()) {
                    list.add(new Header(entry.getKey(), entry.getValue()));
                }
                trailers = List.copyOf(list);
            }
            final List<Header> finalTrailers = trailers;
            WireResponse response = new WireResponse(
                    statusCode,
                    responseHeaders,
                    bufferOut.toByteArray(),
                    null,
                    HttpClient.Version.HTTP_1_1,
                    finalTrailers,
                    () -> finalTrailers
            );
            future.complete(response);

            try {
                channel.pipeline().remove(this);
            } catch (Exception ignored) {}

            if (isKeepAlive && channel.isActive()) {
                pool.release(channel);
            } else {
                channel.close();
                pool.release(channel);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            failExchange(cause);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            failExchange(new EOFException("Connection closed prematurely"));
        }

        private void failExchange(Throwable cause) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            cancelTimeout();
            if (stream != null) {
                stream.offerError(cause);
            } else if (!future.isDone()) {
                future.completeExceptionally(toIOException(cause));
            }
            try {
                channel.pipeline().remove(this);
            } catch (Exception ignored) {}
            channel.close();
            pool.release(channel);
        }

        private void cancelTimeout() {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
        }

        private List<Header> extractHeaders(HttpHeaders headers) {
            List<Header> list = new ArrayList<>(headers.size());
            for (Map.Entry<String, String> entry : headers) {
                list.add(new Header(entry.getKey(), entry.getValue()));
            }
            return List.copyOf(list);
        }
    }
}
