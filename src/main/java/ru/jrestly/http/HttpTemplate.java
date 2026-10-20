package ru.jrestly.http;

import ru.jrestly.ModuleInfo;
import ru.jrestly.codec.CodecRegistry;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.compression.ContentCompressor;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseContext;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.interceptor.SimpleRequestContext;
import ru.jrestly.interceptor.SimpleResponseContext;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.resilience.CircuitBreaker;
import ru.jrestly.resilience.CircuitBreakerOpenException;
import ru.jrestly.resilience.RateLimiter;
import ru.jrestly.resilience.RateLimitExceededException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import ru.jrestly.metrics.ClientMetricsListener;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One declarative call: assembles the wire request, executes it (optionally
 * following redirects), and post-processes the response into the declared
 * return type.
 *
 * <p>When the controller method returns {@code CompletableFuture<T>}, the
 * call is dispatched non-blockingly via {@code HttpClient.sendAsync} and the
 * returned future completes with exactly what the synchronous twin would
 * return. On failure the future completes exceptionally with the same
 * exception the synchronous call would throw (a {@link HandledException}
 * variant or a {@link RuntimeException} wrapping the transport failure) —
 * per standard {@code CompletableFuture} semantics, {@code join()} surfaces
 * it as the cause of a {@code CompletionException} and {@code get()} as the
 * cause of an {@code ExecutionException}.
 *
 * <p>When the controller method returns {@link java.io.InputStream} (or
 * {@code CompletableFuture<InputStream>}), {@code Stream<String>} or
 * {@code Stream<ServerSentEvent>}, the body is streamed via
 * {@code BodyHandlers.ofInputStream()} without intermediate buffering and
 * bypasses the JSON codec on success (ADR-0015, ADR-0016). On error statuses the
 * stream is consumed to produce the typed exception.
 */
class HttpTemplate {

    // ADR-0009 candidate 1: the request-log body section covers POST, PATCH,
    // DELETE and PUT (PUT was missing — 0.5.0 carry-over). GET/HEAD stay out
    // deliberately: RFC 9110 allows a GET body but leaves its semantics
    // undefined. Static, not a per-request Arrays.asList — logRequest runs
    // on every exchange.
    private static final Set<HttpMethod> BODY_LOGGING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.PUT);

    private System.Logger logger;

    private ru.jrestly.engine.HttpEngine httpEngine;
    private ModuleInfo moduleInfo;
    private JsonCodec jsonCodec;
    private CodecRegistry codecRegistry;

    private String url;
    private HttpMethod httpMethod;
    private String contentType;
    private List<Header> headers;
    private List<String> authHeaderNames;
    private Consumer<List<Header>> authDetailsConsumer;
    private List<Param> requestParams;
    private byte[] body;
    private Supplier<? extends HttpRequest.BodyPublisher> bodyPublisherSupplier;
    private Long knownBodySize;
    private List<MultipartPart> multipartFormParts;
    private String multipartBoundary;
    private Type returnType;
    private boolean async;
    private boolean optional;
    private boolean conditional;
    private boolean streamBody;
    private OnErrorParser onErrorParser;
    private List<Integer> expectStatuses;
    private Integer followRedirectsNumber;
    private HttpClient.Version httpVersion;
    private RetryPolicy retryPolicy = RetryPolicy.off();
    private List<RequestInterceptor> requestInterceptors = List.of();
    private List<ResponseInterceptor> responseInterceptors = List.of();
    private Class<?> controllerClass;
    private Method method;
    private Object[] args;
    private ResponseHeaders responseHeadersHolder;
    private UploadProgressListener uploadProgressListener;
    private DownloadProgressListener downloadProgressListener;
    private Executor executor;
    private Duration timeout;
    private ClientMetricsListener metricsListener;
    private CircuitBreaker circuitBreaker;
    private RateLimiter rateLimiter;
    private ru.jrestly.auth.TokenManager tokenManager;
    private boolean retryOnUnauthorized;
    private ru.jrestly.engine.HttpEngineOptions engineOptions;
    private ru.jrestly.cache.HttpCache httpCache;
    // ADR-0066: redirect policy collaborator, built lazily on the first hop
    private Redirects redirects;

    void setHttpCache(ru.jrestly.cache.HttpCache httpCache) {
        this.httpCache = httpCache;
    }

    ru.jrestly.cache.HttpCache getHttpCache() {
        return httpCache;
    }

    // Lazily built (redirects only run when @FollowRedirects is present, so the
    // zero-redirect hot path allocates nothing) from the fields the builder
    // populated before exchange() runs.
    private Redirects redirects() {
        if (redirects == null) {
            redirects = new Redirects(followRedirectsNumber, authHeaderNames, logger);
        }
        return redirects;
    }

    /**
     * Creates an empty template. Not intended for direct use: instances
     * are assembled by {@link HttpTemplateBuilder} and populated through
     * the {@code set*} setters before {@link #exchange()} runs.
     */
    HttpTemplate() {
    }

    /**
     * Executes the call and returns the decoded result. For
     * {@code CompletableFuture<T>} return types the returned value is the
     * future itself; see {@link #exchangeAsync(WireRequest, WireRequest, long, RequestContext, boolean, ru.jrestly.cache.CachedResponse)}.
     *
     * @param <T> the type the controller method declares as its return type
     * @return the payload decoded to {@code T} by the JSON codec, the raw
     *         body when {@code T} is {@link String}, the live body stream
     *         when {@code T} is {@link java.io.InputStream}, line stream
     *         when {@code T} is {@code Stream<String>}, event stream when
     *         {@code T} is {@code Stream<ServerSentEvent>}, {@code null}
     *         when the method returns {@code void}/{@link Void}, or the
     *         future itself when the method returns {@code CompletableFuture<T>}
     */
    @SuppressWarnings("unchecked")
    <T> T exchange() {
        WireRequest request = new WireRequest(httpMethod, UrlComposer.resolve(url, requestParams), headers, contentType, body, multipartFormParts, multipartBoundary, bodyPublisherSupplier, knownBodySize);

        long start = System.nanoTime();
        boolean hasInterceptors = !requestInterceptors.isEmpty() || !responseInterceptors.isEmpty();
        boolean hasMetrics = metricsListener != null;
        RequestContext requestContext = (hasInterceptors || hasMetrics) ? createRequestContext() : null;
        WireRequest effectiveRequest = request;

        if (hasInterceptors) {
            try {
                effectiveRequest = applyRequestInterceptors(request, requestContext);
            } catch (Throwable t) {
                invokeResponseInterceptors(requestContext, effectiveRequest, null, t, start);
                if (hasMetrics) {
                    // ADR-0036 Amendment #258: the interceptor failed before any wire
                    // transmission, so pair onCallStart with onCallComplete (1:1,
                    // in-flight gauges stay balanced) and report the true count — 0.
                    MetricsNotifier.callStart(metricsListener, logger, requestContext, effectiveRequest);
                    MetricsNotifier.callComplete(
                            metricsListener, logger, requestContext, effectiveRequest, null, t, start, 0);
                }
                if (async) {
                    CompletableFuture<Object> failed = new CompletableFuture<>();
                    failed.completeExceptionally(t);
                    return (T) failed;
                }
                switch (t) {
                    case RuntimeException re -> throw re;
                    case Error err -> throw err;
                    case null -> throw new NullPointerException("t is null");
                    default -> throw new RuntimeException(t);
                }
            }
        }

        ru.jrestly.cache.CachedResponse cachedEntry = null;
        if (httpCache != null && effectiveRequest.method() == HttpMethod.GET) {
            cachedEntry = httpCache.get(effectiveRequest).orElse(null);
        }
        boolean requestNoCache = false;
        if (cachedEntry != null) {
            ru.jrestly.cache.CacheControl reqCc = ru.jrestly.cache.CacheControl.parse(effectiveRequest.headers());
            requestNoCache = reqCc.noCache();
        }
        boolean isCacheHit = (cachedEntry != null && cachedEntry.isFresh() && !cachedEntry.mustRevalidate() && !requestNoCache);
        if (cachedEntry != null && !isCacheHit && cachedEntry.hasValidators()) {
            if (cachedEntry.etag().isPresent() && !hasHeader(effectiveRequest, "If-None-Match")) {
                effectiveRequest = effectiveRequest.withHeader("If-None-Match", cachedEntry.etag().get());
            }
            if (cachedEntry.lastModified().isPresent() && !hasHeader(effectiveRequest, "If-Modified-Since")) {
                effectiveRequest = effectiveRequest.withHeader("If-Modified-Since", cachedEntry.lastModified().get());
            }
        }

        logRequest(effectiveRequest);
        MetricsNotifier.callStart(metricsListener, logger, requestContext, effectiveRequest);

        if (async) {
            return (T) exchangeAsync(request, effectiveRequest, start, requestContext, isCacheHit, cachedEntry);
        }

        WireResponse response = null;
        Throwable failure = null;
        int[] attemptsHolder = (hasMetrics || circuitBreaker != null) ? new int[] {1} : null;
        try {
            WireResponse shortCircuited = (requestContext != null)
                    ? requestContext.shortCircuitResponse().orElse(null)
                    : null;
            if (shortCircuited != null) {
                if (attemptsHolder != null) {
                    attemptsHolder[0] = 0;
                }
                response = handleDecompression(shortCircuited);
            } else if (isCacheHit) {
                if (attemptsHolder != null) {
                    attemptsHolder[0] = 0;
                }
                response = cachedEntry.toWireResponse();
                if (streamBody && response.body() != null) {
                    response = CacheDispatch.toStreamWireResponse(response);
                }
            } else {
                try {
                    if (rateLimiter != null) {
                        rateLimiter.acquire();
                    }
                    if (circuitBreaker != null) {
                        circuitBreaker.acquirePermission();
                    }
                } catch (Throwable preWireFailure) {
                    // ADR-0036 Amendment #259: the call died before any wire
                    // transmission — report the true count, 0, so attempts()
                    // equals the number of onAttemptComplete callbacks.
                    if (attemptsHolder != null) {
                        attemptsHolder[0] = 0;
                    }
                    throw preWireFailure;
                }
                response = executeWithRetries(effectiveRequest, requestContext, attemptsHolder, 0);
                if (response.statusCode() == 401 && retryOnUnauthorized && tokenManager != null) {
                    closeIntermediateStream(response);
                    tokenManager.invalidate();
                    effectiveRequest = applyRequestInterceptors(request, requestContext);
                    // ADR-0036 Amendment #194: attempts() is the total across all
                    // transmissions of the call — the refresh phase must not
                    // clobber the first phase's count; Amendment #196: listener
                    // attempt numbers stay continuous (per-call) across phases;
                    // Amendment #300: the accumulation runs on completion (success
                    // OR failure), mirroring the async twin's whenComplete — a
                    // refresh phase that dies on the wire must not leave the
                    // holder at the phase-local count
                    int firstPhaseAttempts = attemptsHolder != null ? attemptsHolder[0] : 1;
                    try {
                        response = executeWithRetries(effectiveRequest, requestContext, attemptsHolder, firstPhaseAttempts);
                    } finally {
                        if (attemptsHolder != null) {
                            attemptsHolder[0] = firstPhaseAttempts + attemptsHolder[0];
                        }
                    }
                }
                response = CacheDispatch.handle(effectiveRequest, response, httpCache, streamBody);
            }
            T result = processResponse(effectiveRequest, response, start);
            int attempts = attemptsHolder != null ? attemptsHolder[0] : 1;
            if (circuitBreaker != null && attempts > 0) {
                circuitBreaker.recordSuccess(System.nanoTime() - start);
            }
            return result;
        } catch (IOException e) {
            failure = e;
            logger.log(System.Logger.Level.ERROR,
                    "Request failed: " + effectiveRequest.method() + " " + effectiveRequest.uri(), e);

            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            failure = e;
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            int attempts = attemptsHolder != null ? attemptsHolder[0] : 1;
            if (failure != null && circuitBreaker != null && attempts > 0
                    && !(failure instanceof CircuitBreakerOpenException)
                    && !(failure instanceof RateLimitExceededException)) {
                circuitBreaker.recordFailure(System.nanoTime() - start, failure);
            }
            if (hasInterceptors) {
                invokeResponseInterceptors(requestContext, effectiveRequest, response, failure, start);
            }
            if (hasMetrics) {
                MetricsNotifier.callComplete(
                        metricsListener, logger, requestContext, effectiveRequest, response, failure, start, attempts);
            }
        }
    }

    private WireRequest applyRequestInterceptors(WireRequest baseRequest, RequestContext requestContext) {
        WireRequest current = baseRequest;
        for (RequestInterceptor interceptor : requestInterceptors) {
            WireRequest next = interceptor.intercept(current, requestContext);
            if (next == null) {
                throw new NullPointerException("RequestInterceptor returned null WireRequest");
            }
            current = next;
            if (requestContext != null && requestContext.shortCircuitResponse().isPresent()) {
                break;
            }
        }
        return current;
    }

    // Async twin of exchange(): the same tail via processResponse, with
    // @OnError / status-check exceptions kept as-is and transport failures
    // mapped to the exceptions a synchronous call would throw (both become
    // the cause of the future's exceptional completion), and response
    // interceptors triggered non-blockingly upon completion.
    private CompletableFuture<Object> exchangeAsync(
            WireRequest initialRequest,
            WireRequest request,
            long startNanos,
            RequestContext requestContext,
            boolean isCacheHit,
            ru.jrestly.cache.CachedResponse cachedEntry
    ) {
        AtomicReference<WireResponse> responseRef = new AtomicReference<>();
        AtomicReference<WireRequest> activeRequestRef = new AtomicReference<>(request);
        AtomicInteger attemptsRef = new AtomicInteger(1);
        boolean hasResponseInterceptors = !responseInterceptors.isEmpty() && requestContext != null;
        boolean hasMetrics = metricsListener != null && requestContext != null;

        WireResponse shortCircuited = (requestContext != null)
                ? requestContext.shortCircuitResponse().orElse(null)
                : null;

        CompletableFuture<WireResponse> retriesFuture;
        if (shortCircuited != null) {
            attemptsRef.set(0);
            try {
                WireResponse decompressed = handleDecompression(shortCircuited);
                retriesFuture = CompletableFuture.completedFuture(decompressed);
            } catch (IOException e) {
                retriesFuture = new CompletableFuture<>();
                retriesFuture.completeExceptionally(e);
            }
        } else if (isCacheHit) {
            attemptsRef.set(0);
            WireResponse cachedResp = cachedEntry.toWireResponse();
            if (streamBody && cachedResp.body() != null) {
                cachedResp = CacheDispatch.toStreamWireResponse(cachedResp);
            }
            retriesFuture = CompletableFuture.completedFuture(cachedResp);
        } else {
            if (rateLimiter != null) {
                if (!rateLimiter.tryAcquire(rateLimiter.config().timeoutDuration())) {
                    Throwable t = new RateLimitExceededException(rateLimiter.name());
                    if (hasResponseInterceptors) {
                        invokeResponseInterceptors(requestContext, request, null, t, startNanos);
                    }
                    if (hasMetrics) {
                        // ADR-0036 Amendment #259: no wire transmission happened —
                        // report the true count, 0.
                        MetricsNotifier.callComplete(metricsListener, logger, requestContext, request, null, t, startNanos, 0);
                    }
                    CompletableFuture<Object> failed = new CompletableFuture<>();
                    failed.completeExceptionally(t);
                    return failed;
                }
            }
            if (circuitBreaker != null) {
                if (!circuitBreaker.tryAcquirePermission()) {
                    Throwable t = new CircuitBreakerOpenException(circuitBreaker.name(), circuitBreaker.remainingWaitDuration());
                    if (hasResponseInterceptors) {
                        invokeResponseInterceptors(requestContext, request, null, t, startNanos);
                    }
                    if (hasMetrics) {
                        // ADR-0036 Amendment #259: no wire transmission happened —
                        // report the true count, 0.
                        MetricsNotifier.callComplete(metricsListener, logger, requestContext, request, null, t, startNanos, 0);
                    }
                    CompletableFuture<Object> failed = new CompletableFuture<>();
                    failed.completeExceptionally(t);
                    return failed;
                }
            }
            retriesFuture = executeAsyncWithRetries(request, requestContext, attemptsRef, 0);
        }

        CompletableFuture<WireResponse> authGuardedFuture = (retryOnUnauthorized && tokenManager != null && shortCircuited == null && !isCacheHit)
                ? retriesFuture.thenCompose(response -> {
                    if (response.statusCode() == 401) {
                        closeIntermediateStream(response);
                        tokenManager.invalidate();
                        // ADR-0036 Amendment #194: attempts() is the total across all
                        // transmissions of the call — the refresh phase must not
                        // clobber the first phase's count
                        int firstPhaseAttempts = attemptsRef.get();
                        try {
                            WireRequest refreshed = applyRequestInterceptors(initialRequest, requestContext);
                            activeRequestRef.set(refreshed);
                            // Amendment #196: listener attempt numbers stay
                            // continuous (per-call) across phases
                            CompletableFuture<WireResponse> secondPhase = executeAsyncWithRetries(refreshed, requestContext, attemptsRef, firstPhaseAttempts);
                            // accumulate on phase completion (success or failure),
                            // before the terminal stages read attemptsRef
                            secondPhase.whenComplete((ignored, phaseFailure) ->
                                    attemptsRef.accumulateAndGet(firstPhaseAttempts, Integer::sum));
                            return secondPhase;
                        } catch (Throwable t) {
                            CompletableFuture<WireResponse> failed = new CompletableFuture<>();
                            failed.completeExceptionally(t);
                            return failed;
                        }
                    }
                    return CompletableFuture.completedFuture(response);
                })
                : retriesFuture;

        CompletableFuture<WireResponse> cacheHandledFuture = (httpCache != null && !isCacheHit)
                ? authGuardedFuture.thenApply(response -> CacheDispatch.handle(activeRequestRef.get(), response, httpCache, streamBody))
                : authGuardedFuture;

        java.util.function.Function<WireResponse, Object> processor = response -> {
            if (hasResponseInterceptors || hasMetrics) {
                responseRef.set(response);
            }
            try {
                return processResponse(activeRequestRef.get(), response, startNanos);
            } catch (IOException e) {
                // ADR-0015 Aspect 3: a stream-consumption failure on the
                // error path is a transport-class failure — thenApply
                // unwraps the CompletionException, so the mapping below
                // sees the plain IOException, as the sync twin's
                // catch (IOException) does
                throw new CompletionException(e);
            }
        };

        CompletableFuture<Object> stage = executor != null
                ? cacheHandledFuture.thenApplyAsync(processor, executor)
                : cacheHandledFuture.thenApply(processor);

        CompletableFuture<Object> future = stage.exceptionally(ex -> {
            Throwable cause = unwrapCause(ex);
            // the future completes exceptionally with this exception as the
            // CompletionException cause of join() / ExecutionException cause of get()
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            switch (cause) {
                case RuntimeException re when !(re instanceof CompletionException) -> throw re;
                case Error err -> throw err;
                case null -> throw new NullPointerException("cause is null");
                default -> {
                    logger.log(System.Logger.Level.ERROR,
                            "Request failed: " + activeRequestRef.get().method() + " " + activeRequestRef.get().uri(), cause);
                    throw new RuntimeException(cause);
                }
            }
        });

        if (hasResponseInterceptors || hasMetrics) {
            java.util.function.BiConsumer<Object, Throwable> interceptorAction = (res, ex) -> {
                Throwable failure = ex != null ? unwrapCause(ex) : null;
                if (hasResponseInterceptors) {
                    invokeResponseInterceptors(
                            requestContext,
                            activeRequestRef.get(),
                            responseRef.get(),
                            failure,
                            startNanos
                    );
                }
                if (hasMetrics) {
                    MetricsNotifier.callComplete(
                            metricsListener,
                            logger,
                            requestContext,
                            activeRequestRef.get(),
                            responseRef.get(),
                            failure,
                            startNanos,
                            attemptsRef.get()
                    );
                }
            };
            future = executor != null
                    ? future.whenCompleteAsync(interceptorAction, executor)
                    : future.whenComplete(interceptorAction);
        }

        if (circuitBreaker != null && shortCircuited == null && !isCacheHit) {
            java.util.function.BiConsumer<Object, Throwable> cbAction = (res, ex) -> {
                long duration = System.nanoTime() - startNanos;
                if (ex == null) {
                    circuitBreaker.recordSuccess(duration);
                } else {
                    Throwable failure = unwrapCause(ex);
                    if (!(failure instanceof CircuitBreakerOpenException) && !(failure instanceof RateLimitExceededException)) {
                        circuitBreaker.recordFailure(duration, failure);
                    }
                }
            };
            future = executor != null
                    ? future.whenCompleteAsync(cbAction, executor)
                    : future.whenComplete(cbAction);
        }

        return future;
    }

    // Package-private seam: unit tests verify all defensive branches
    static boolean hasHeader(WireRequest req, String name) {
        if (req == null || req.headers() == null) {
            return false;
        }
        for (Header h : req.headers()) {
            if (h != null && h.name() != null && h.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private RequestContext createRequestContext() {
        String modName = (moduleInfo != null && moduleInfo.getName() != null)
                ? moduleInfo.getName()
                : "default";
        Class<?> ctrlClass = controllerClass != null ? controllerClass : HttpTemplate.class;
        Method mtd = method != null ? method : fallbackMethod();
        Object[] callArgs = args != null ? args : new Object[0];
        return new SimpleRequestContext(modName, ctrlClass, mtd, callArgs);
    }

    private static Method fallbackMethod() {
        try {
            return HttpTemplate.class.getDeclaredMethod("exchange");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("exchange method not found", e);
        }
    }

    private void invokeResponseInterceptors(
            RequestContext requestContext,
            WireRequest request,
            WireResponse response,
            Throwable failure,
            long startNanos
    ) {
        if (responseInterceptors.isEmpty() || requestContext == null) {
            return;
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        ResponseContext context = new SimpleResponseContext(
                requestContext,
                request,
                response,
                failure,
                duration
        );
        for (ResponseInterceptor interceptor : responseInterceptors) {
            try {
                interceptor.onResponse(context);
            } catch (Throwable t) {
                if (logger != null) {
                    logger.log(System.Logger.Level.WARNING, "ResponseInterceptor failed", t);
                }
            }
        }
    }

    private static Throwable unwrapCause(Throwable throwable) {
        while (throwable instanceof CompletionException completion && completion.getCause() != null) {
            throwable = completion.getCause();
        }
        return throwable;
    }

    // Shared tail of both exchange paths: auth-details capture, response
    // logging, @OnError handling, status check, payload decoding
    @SuppressWarnings("unchecked")
    private <T> T processResponse(WireRequest request, WireResponse response, long startNanos)
            throws IOException {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        List<Header> responseHeaders = response.headers();
        int statusCode = response.statusCode();
        // RFC 9110 §8.3.2: the declared Content-Type charset is authoritative
        // for text payloads — every String materialization honors it and
        // falls back to UTF-8, matching the line/SSE streaming path (ADR-0016)
        Charset payloadCharset = ResponseBody.resolveCharset(responseHeaders);

        // ADR-0022 & ADR-0038: Populate response headers holder and thread-local captor (lazy duration allocation)
        Consumer<ResponseHeaders> captor = ResponseHeaders.CURRENT_CAPTOR.get();
        ResponseHeaders capturedHeaderHolder = null;
        if (responseHeadersHolder != null || captor != null) {
            Duration callDuration = Duration.ofNanos(System.nanoTime() - startNanos);
            if (responseHeadersHolder != null) {
                responseHeadersHolder.populate(statusCode, responseHeaders, callDuration, response.version(), response.trailers());
            }
            if (captor != null) {
                capturedHeaderHolder = new ResponseHeaders(statusCode, responseHeaders, callDuration, response.version(), response.trailers());
                captor.accept(capturedHeaderHolder);
            }
        }

        if (authDetailsConsumer != null) {
            authDetailsConsumer.accept(responseHeaders);
        }

        logResponse(response, elapsedMs);

        // ADR-0015 Aspect 3: an InputStream return declares byte binding —
        // the success path hands the stream over untouched (codec-free),
        // the error paths consume it fully and keep the byte[] contract
        // (a payload cap is a revisit-on-demand, not a 0.x default)
        InputStream stream = response.stream();
        if (stream != null) {
            // ADR-0059: Terminal streaming hook for late-arriving trailers
            stream = ResponseBody.wrapForTrailers(stream, response.trailersSupplier(),
                    responseHeadersHolder, capturedHeaderHolder);

            if (onErrorParser != null && onErrorParser.canParse(statusCode)) {
                String errorPayload = ResponseBody.consumePayload(stream, payloadCharset);
                handleOnError(statusCode, errorPayload, responseHeaders, payloadCharset);
            }
            if (!isExpectedStatus(statusCode)) {
                String rawPayload = ResponseBody.consumePayload(stream, payloadCharset);
                throw createUnexpectedStatusException(statusCode, rawPayload == null ? "" : rawPayload, responseHeaders);
            }
            // ADR-0016 Aspect 1 & 5: Stream<String> returns line stream;
            // Stream<ServerSentEvent> returns parsed event stream
            if (ResponseBody.isStreamType(returnType, String.class)) {
                if (logger.isLoggable(System.Logger.Level.INFO)) {
                    logger.log(System.Logger.Level.INFO, ResponseBody.streamingPayloadLine("streaming lines", responseHeaders));
                }
                Stream<String> lineStream = ResponseBody.lineStream(stream, payloadCharset, logger);
                if (optional) {
                    // ADR-0023 × ADR-0016: a missing resource is Optional.empty()
                    // with the stream closed, never a stream to drain; success
                    // wraps the artifact, never hands it over raw
                    if (ResponseBody.isMissingResource(statusCode)) {
                        lineStream.close();
                        return (T) Optional.empty();
                    }
                    return (T) Optional.of(lineStream);
                }
                return (T) lineStream;
            }
            if (ResponseBody.isStreamType(returnType, ServerSentEvent.class)) {
                if (logger.isLoggable(System.Logger.Level.INFO)) {
                    logger.log(System.Logger.Level.INFO, ResponseBody.streamingPayloadLine("streaming events", responseHeaders));
                }
                Stream<ServerSentEvent> eventStream = ResponseBody.eventStream(stream, payloadCharset, logger);
                if (optional) {
                    // ADR-0023 × ADR-0016: the same missing-resource rule as the
                    // line stream — close propagates through the parser to the
                    // wire stream
                    if (ResponseBody.isMissingResource(statusCode)) {
                        eventStream.close();
                        return (T) Optional.empty();
                    }
                    return (T) Optional.of(eventStream);
                }
                return (T) eventStream;
            }
            // ADR-0015 Aspect 5: the payload line is skipped for stream
            // responses — logging must not consume the body
            if (logger.isLoggable(System.Logger.Level.INFO)) {
                logger.log(System.Logger.Level.INFO, ResponseBody.streamingPayloadLine("streaming", responseHeaders));
            }
            if (optional) {
                if (ResponseBody.isMissingResource(statusCode)) {
                    ResponseBody.closeQuietly(stream, logger);
                    return (T) Optional.empty();
                }
                if (InputStream.class.equals(returnType)) {
                    return (T) Optional.of(stream);
                }
            }
            return (T) stream;
        }

        byte[] bytes = response.body();

        if (onErrorParser != null && onErrorParser.canParse(statusCode)) {
            String errorPayload = (bytes != null && bytes.length > 0) ? new String(bytes, payloadCharset) : null;
            handleOnError(statusCode, errorPayload, responseHeaders, payloadCharset);
        }

        if (!isExpectedStatus(statusCode)) {
            String errorPayload = (bytes != null && bytes.length > 0) ? new String(bytes, payloadCharset) : "";
            throw createUnexpectedStatusException(statusCode, errorPayload, responseHeaders);
        }

        // ADR-0066 step 5c: the buffered decode legs live on ResponseBody. The
        // codec is supplied lazily — on the byte[]/String/Void legs it must not
        // be resolved at all (a bare template used to survive those), and on
        // the DTO leg it is resolved exactly once per leg, shared by the log
        // preview and the deserialize (ADR-0077).
        if (optional) {
            return (T) ResponseBody.bufferedOptional(statusCode, bytes, returnType, payloadCharset,
                    ResponseBody.contentTypeOf(responseHeaders), () -> responseCodec(responseHeaders), logger);
        }
        return (T) ResponseBody.bufferedRaw(bytes, returnType, payloadCharset,
                ResponseBody.contentTypeOf(responseHeaders), () -> responseCodec(responseHeaders), logger);
    }

    // Handles redirects according to HTTP spec:
    // 301/302/303 downgrade to GET and drop the body; 307/308 re-send the method and body.
    private WireResponse executeWithRedirects(WireRequest request) throws IOException, InterruptedException {
        if (followRedirectsNumber == null) {
            return send(request);
        }

        Redirects redirects = redirects();
        int hopsLeft = followRedirectsNumber;

        WireResponse response = send(request);
        while (hopsLeft > 0) {
            WireRequest next = redirects.nextHop(request, response);
            if (next == null) {
                break;
            }
            request = next;
            hopsLeft--;
            closeIntermediateStream(response);
            response = send(request);
        }

        redirects.logExhausted(response);
        return response;
    }

    // ADR-0008 Aspect 3+6: the retry loop operates on the RAW response, before
    // processResponse runs, so auth-capture, logging, @OnError decoding, and
    // the status check fire exactly once, on the final response. A disabled
    // policy is a straight pass-through — off-by-default users see no change.
    // Re-sending the same WireRequest is safe: toHttpRequest builds a fresh
    // HttpRequest per send with a per-send body publisher.
    // ADR-0011 Aspect 2: when the distinct decode opt-in is on, a
    // status-acceptable response is additionally checked by the side-effect-
    // free decode probe — the third failure-kind (transport / status /
    // decode) — without breaking the "classified exactly once" property.
    private WireResponse executeWithRetries(
            WireRequest request,
            RequestContext requestContext,
            int[] attemptsHolder,
            int attemptOffset
    ) throws IOException, InterruptedException {
        if (!retryPolicy.isEnabled()) {
            long attemptStart = System.nanoTime();
            WireResponse response = null;
            Throwable failure = null;
            try {
                response = executeWithRedirects(request);
                return response;
            } catch (Throwable t) {
                failure = t;
                throw t;
            } finally {
                MetricsNotifier.attemptComplete(metricsListener, logger, requestContext, request, 1 + attemptOffset, response, failure, attemptStart);
            }
        }

        int maxAttempts = retryPolicy.maxAttempts();
        long lastDelayMillis = 0L;

        for (int attempt = 1; ; attempt++) {
            if (attemptsHolder != null) {
                attemptsHolder[0] = attempt;
            }
            boolean last = attempt == maxAttempts;

            long attemptStart = System.nanoTime();
            WireResponse response;
            String failure;
            Throwable failureThrowable = null;
            try {
                response = executeWithRedirects(request);
                failure = "HTTP " + response.statusCode();
                if (!isExpectedStatus(response.statusCode())) {
                    failureThrowable = createUnexpectedStatusException(response.statusCode(), ResponseBody.extractText(response), response.headers());
                }
            } catch (InterruptedException e) {
                // the caller's thread was interrupted — fail fast, never retried
                failureThrowable = e;
                MetricsNotifier.attemptComplete(metricsListener, logger, requestContext, request, attempt + attemptOffset, null, failureThrowable, attemptStart);
                throw e;
            } catch (IOException e) {
                failureThrowable = e;
                MetricsNotifier.attemptComplete(metricsListener, logger, requestContext, request, attempt + attemptOffset, null, failureThrowable, attemptStart);
                if (last || !retryPolicy.isRetryTransport()) {
                    throw e;
                }
                response = null;
                failure = e.getClass().getSimpleName();
            }

            if (response != null) {
                MetricsNotifier.attemptComplete(metricsListener, logger, requestContext, request, attempt + attemptOffset, response, failureThrowable, attemptStart);
            }

            if (response != null && (last || !isRetryableStatus(response))) {
                // ADR-0011 Aspect 2: the status logic accepted the response, but
                // a complete body the codec cannot bind is still not final when
                // the distinct decode opt-in is on. The probe is the payload-
                // decode branch of processResponse reproduced side-effect-free,
                // so the side-effectful tail still runs exactly once, on the
                // truly final response. On the last attempt it is not called at
                // all — the final processResponse throws the codec's own
                // unchecked exception (ADR-0011 Aspect 3: no new type).
                if (!last && retryPolicy.isRetryDecodeFailures()) {
                    try {
                        probePayloadDecode(response);
                    } catch (RuntimeException e) {
                        lastDelayMillis = scheduleRetry(attempt, maxAttempts, "decode (" + e.getClass().getSimpleName() + ")", response, requestContext, request, e, lastDelayMillis, attemptOffset);
                        continue;
                    }
                }
                return response;
            }

            lastDelayMillis = scheduleRetry(attempt, maxAttempts, failure, response, requestContext, request, failureThrowable, lastDelayMillis, attemptOffset);
        }
    }

    // One DEBUG line per failed attempt, then the wait: full jitter over
    // [0, bound], or the deterministic max(bound, Retry-After) when the
    // failed response carries a parseable Retry-After header, delta-seconds
    // or HTTP-date (ADR-0008 Aspect 4, ADR-0009 candidate 3, ADR-0040).
    // Backoff and the log line are phase-local (the retry-policy window is
    // per phase); the listener number is per-call (ADR-0036 Amendment #196).
    private long scheduleRetry(
            int attempt,
            int maxAttempts,
            String failure,
            WireResponse response,
            RequestContext requestContext,
            WireRequest request,
            Throwable failureThrowable,
            long previousDelayMillis,
            int attemptOffset
    ) throws InterruptedException {
        // ADR-0015 Aspect 2: every scheduled retry discards the failed
        // attempt — its stream (if any) is closed before the wait; a
        // transport failure carries no response and is a no-op
        closeIntermediateStream(response);
        long delayMillis = retryPolicy.nextDelayMillis(attempt, previousDelayMillis, RetryAfter.delayMillis(response), ThreadLocalRandom.current());
        // ADR-0076: honor the log-level gate, as logRequest/logResponse do —
        // a disabled DEBUG must not build the message string (the line is
        // produced on every failed retry attempt of the zero-garbage hot path)
        if (logger.isLoggable(System.Logger.Level.DEBUG)) {
            logger.log(System.Logger.Level.DEBUG,
                    "Attempt " + attempt + "/" + maxAttempts + " failed: " + failure + " — retrying in " + delayMillis + " ms");
        }
        MetricsNotifier.retry(metricsListener, logger, requestContext, request, attempt + attemptOffset, failureThrowable, delayMillis);
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
        return delayMillis;
    }

    // The loop's failure predicate — the exact decisions processResponse makes,
    // so "what the loop calls a failure" and "what processResponse calls a
    // failure" cannot drift apart (ADR-0008 Aspect 3)
    private boolean isRetryableStatus(WireResponse response) {
        int status = response.statusCode();

        if (isExpectedStatus(status)) {
            return false; // success — the loop ends
        }
        if (onErrorParser != null && onErrorParser.canParse(status) && !retryPolicy.isRetryOnErrorStatuses()) {
            return false; // a declared @OnError status is a terminal contract by default
        }
        if (!retryPolicy.getRetryableStatuses().contains(status)) {
            return false; // not in the retryable set — a bare 404 is a deterministic client error
        }
        return retryPolicy.isRetryNonIdempotent() || !isNonIdempotent(httpMethod);
    }

    // RFC 9110: of this library's methods, POST and PATCH are non-idempotent
    private static boolean isNonIdempotent(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PATCH;
    }

    // ADR-0011 Aspect 2: the decode probe — the payload-decode branch of
    // processResponse reproduced side-effect-free (no auth-capture, no log,
    // no exception construction), so the retry loop can ask "would this
    // payload decode" without classifying the response: the side-effectful
    // processResponse still runs exactly once, on the truly final response.
    // It returns true whenever the payload decode cannot fail — @OnError
    // statuses and unexpected statuses classify before the payload decode,
    // so their scope (terminal / retryOnErrorStatuses) falls out of the
    // probe's shape rather than a special case. The same predicates
    // processResponse uses (onErrorParser, isExpectedStatus, returnType),
    // so the two cannot drift apart (ADR-0008 Aspect 3, decode axis).
    // A non-bindable body throws the codec's own unchecked exception,
    // unwrapped (ADR-0011 Aspect 3: no new type).
    boolean probePayloadDecode(WireResponse response) {
        int status = response.statusCode();
        if (onErrorParser != null && onErrorParser.canParse(status)) {
            return true; // @OnError classifies before the payload decode
        }
        if (!isExpectedStatus(status)) {
            return true; // the status check classifies before the payload decode
        }
        if (response.stream() != null) {
            return true; // ADR-0015 Aspect 3: byte binding — nothing is decoded, the decode cannot fail
        }
        byte[] bytes = response.body();
        String payload = (bytes != null && bytes.length > 0) ? new String(bytes, StandardCharsets.UTF_8) : null;
        if (optional) {
            if (ResponseBody.isMissingResource(status) || payload == null) {
                return true;
            }
            if (String.class.equals(returnType) || byte[].class.equals(returnType) || Void.class.equals(returnType) || void.class.equals(returnType)) {
                return true;
            }
            responseCodec(response.headers()).deserialize(bytes, returnType, ResponseBody.contentTypeOf(response.headers()));
            return true;
        }
        if (payload == null) {
            return true; // no body
        }
        if (String.class.equals(returnType) || byte[].class.equals(returnType)) {
            return true; // returned raw, never decoded
        }
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            return true; // body discarded (ADR-0009 candidate 2)
        }
        responseCodec(response.headers()).deserialize(bytes, returnType, ResponseBody.contentTypeOf(response.headers()));
        return true;
    }

    // Async twin: same hop semantics, chained over sendAsync
    private CompletableFuture<WireResponse> executeAsyncWithRedirects(WireRequest request, int hopsLeft) {
        if (followRedirectsNumber == null) {
            return sendAsync(request);
        }
        return executeAsyncHop(request, hopsLeft, redirects());
    }

    private CompletableFuture<WireResponse> executeAsyncHop(WireRequest request, int hopsLeft, Redirects redirects) {
        return sendAsync(request).thenCompose(response -> {
            WireRequest next = redirects.nextHop(request, response);
            if (next == null || hopsLeft <= 0) {
                return CompletableFuture.completedFuture(response);
            }
            closeIntermediateStream(response);
            return executeAsyncHop(next, hopsLeft - 1, redirects);
        }).thenApply(response -> {
            redirects.logExhausted(response);
            return response;
        });
    }

    // Async twin of executeWithRetries (ADR-0008 Aspect 6): the same loop
    // chained over sendAsync, a disabled policy a straight pass-through. The
    // backoff wait runs on CompletableFuture.delayedExecutor — non-blocking,
    // no parked virtual thread per retry — and exchangeAsync's final exception
    // mapping stays untouched. The decode axis (ADR-0011 Aspect 2) is mirrored
    // verbatim: a status-acceptable response is additionally probe-checked
    // under the distinct opt-in, exactly as the sync twin does.
    private CompletableFuture<WireResponse> executeAsyncWithRetries(
            WireRequest request,
            RequestContext requestContext,
            AtomicInteger attemptsRef,
            int attemptOffset
    ) {
        int hopsLeft = followRedirectsNumber == null ? 0 : followRedirectsNumber;
        if (!retryPolicy.isEnabled()) {
            long attemptStart = System.nanoTime();
            return executeAsyncWithRedirects(request, hopsLeft).whenComplete((response, failure) -> {
                Throwable unwrapped = failure != null ? unwrapCause(failure) : null;
                MetricsNotifier.attemptComplete(metricsListener, logger, requestContext, request, 1 + attemptOffset, response, unwrapped, attemptStart);
            });
        }
        return attemptAsync(request, 1, retryPolicy.maxAttempts(), hopsLeft, requestContext, attemptsRef, 0L, attemptOffset);
    }

    // One attempt, or the next one after the wait: the exact sync decisions —
    // only an IOException with retryTransport on and a non-last attempt is
    // retried; InterruptedException (fail fast) and any other failure type
    // pass through to exchangeAsync's mapping. The handle step yields the
    // next stage (handle unwraps a returned CompletionStage at runtime); the
    // identity compose only fixes the static type.
    private CompletableFuture<WireResponse> attemptAsync(
            WireRequest request,
            int attempt,
            int maxAttempts,
            int hopsLeft,
            RequestContext requestContext,
            AtomicInteger attemptsRef,
            long previousDelayMillis,
            int attemptOffset
    ) {
        attemptsRef.set(attempt);
        boolean last = attempt == maxAttempts;
        long attemptStart = System.nanoTime();
        return executeAsyncWithRedirects(request, hopsLeft)
                .<CompletableFuture<WireResponse>>handle((response, failure) -> {
                    Throwable cause = failure != null ? unwrapCause(failure) : null;
                    if (cause != null) {
                        MetricsNotifier.attemptComplete(metricsListener, logger, requestContext, request, attempt + attemptOffset, null, cause, attemptStart);
                        if (!(cause instanceof IOException) || last || !retryPolicy.isRetryTransport()) {
                            return CompletableFuture.<WireResponse>failedFuture(cause);
                        }
                        return scheduleAsyncRetry(request, attempt, maxAttempts, cause.getClass().getSimpleName(), null, hopsLeft, requestContext, cause, attemptsRef, previousDelayMillis, attemptOffset);
                    }
                    Throwable statusFailure = !isExpectedStatus(response.statusCode())
                            ? createUnexpectedStatusException(response.statusCode(), ResponseBody.extractText(response), response.headers())
                            : null;
                    MetricsNotifier.attemptComplete(metricsListener, logger, requestContext, request, attempt + attemptOffset, response, statusFailure, attemptStart);
                    if (last || !isRetryableStatus(response)) {
                        // ADR-0011 Aspect 2, async twin: the status logic accepted
                        // the response, but a complete body the codec cannot bind
                        // is still not final under the distinct decode opt-in. The
                        // probe is the payload-decode branch of processResponse
                        // reproduced side-effect-free, so exchangeAsync's
                        // side-effectful tail still runs exactly once. On the last
                        // attempt it is not called at all — the final
                        // processResponse throws the codec's own unchecked
                        // exception (ADR-0011 Aspect 3: no new type). The probe
                        // is a plain synchronous call: the handle callback already
                        // runs on the completing thread.
                        if (!last && retryPolicy.isRetryDecodeFailures()) {
                            try {
                                probePayloadDecode(response);
                            } catch (RuntimeException e) {
                                return scheduleAsyncRetry(request, attempt, maxAttempts,
                                        "decode (" + e.getClass().getSimpleName() + ")", response, hopsLeft, requestContext, e, attemptsRef, previousDelayMillis, attemptOffset);
                            }
                        }
                        return CompletableFuture.<WireResponse>completedFuture(response);
                    }
                    return scheduleAsyncRetry(request, attempt, maxAttempts, "HTTP " + response.statusCode(), response, hopsLeft, requestContext, statusFailure, attemptsRef, previousDelayMillis, attemptOffset);
                })
                .thenCompose(Function.identity());
    }

    // The async wait: the sync twin's exact DEBUG line, then the next attempt
    // after the delay on CompletableFuture.delayedExecutor; a zero jitter draw
    // skips the scheduler call, as the sync twin skips Thread.sleep(0)
    private CompletableFuture<WireResponse> scheduleAsyncRetry(
            WireRequest request,
            int attempt,
            int maxAttempts,
            String failure,
            WireResponse response,
            int hopsLeft,
            RequestContext requestContext,
            Throwable failureThrowable,
            AtomicInteger attemptsRef,
            long previousDelayMillis,
            int attemptOffset
    ) {
        // ADR-0015 Aspect 2, async twin: the same discard — the failed
        // attempt's stream (if any) is closed before the deferred wait
        closeIntermediateStream(response);
        long delayMillis = retryPolicy.nextDelayMillis(attempt, previousDelayMillis, RetryAfter.delayMillis(response), ThreadLocalRandom.current());
        // ADR-0076: honor the log-level gate, as logRequest/logResponse do —
        // a disabled DEBUG must not build the message string (the line is
        // produced on every failed retry attempt of the zero-garbage hot path)
        if (logger.isLoggable(System.Logger.Level.DEBUG)) {
            logger.log(System.Logger.Level.DEBUG,
                    "Attempt " + attempt + "/" + maxAttempts + " failed: " + failure + " — retrying in " + delayMillis + " ms");
        }
        MetricsNotifier.retry(metricsListener, logger, requestContext, request, attempt + attemptOffset, failureThrowable, delayMillis);
        if (delayMillis <= 0) {
            return attemptAsync(request, attempt + 1, maxAttempts, hopsLeft, requestContext, attemptsRef, 0L, attemptOffset);
        }
        Executor scheduler = executor != null
                ? CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, executor)
                : CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS);
        return CompletableFuture.runAsync(() -> {
        }, scheduler)
                .thenCompose(ignored -> attemptAsync(request, attempt + 1, maxAttempts, hopsLeft, requestContext, attemptsRef, delayMillis, attemptOffset));
    }

    // ADR-0015 Aspect 2: the effective return type picks the body handler —
    // an InputStream return takes the body as a live stream (ofInputStream),
    // everything else materializes bytes as before (ofByteArray). Package-
    // private seam — the unit tests exercise the branch directly.
    WireResponse send(WireRequest request) throws IOException, InterruptedException {
        long start = System.nanoTime();
        ru.jrestly.engine.HttpEngineOptions options = this.engineOptions;
        if (options == null) {
            Duration effectiveTimeout = effectiveSocketTimeout(timeout, moduleInfo);
            options = new ru.jrestly.engine.HttpEngineOptions(
                    effectiveTimeout,
                    httpVersion,
                    streamBody,
                    uploadProgressListener,
                    downloadProgressListener
            );
        }
        WireResponse response = httpEngine.send(request, options);
        logPerHop(request, response.statusCode(), start);
        return handleDecompression(response);
    }

    // Async twin of send(): the same branch, chained over sendAsync
    private CompletableFuture<WireResponse> sendAsync(WireRequest request) {
        long start = System.nanoTime();
        ru.jrestly.engine.HttpEngineOptions options = this.engineOptions;
        if (options == null) {
            Duration effectiveTimeout = effectiveSocketTimeout(timeout, moduleInfo);
            options = new ru.jrestly.engine.HttpEngineOptions(
                    effectiveTimeout,
                    httpVersion,
                    streamBody,
                    uploadProgressListener,
                    downloadProgressListener
            );
        }
        CompletableFuture<WireResponse> raw = httpEngine.sendAsync(request, options);
        java.util.function.Function<WireResponse, WireResponse> mapper = response -> {
            logPerHop(request, response.statusCode(), start);
            try {
                return handleDecompression(response);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        };
        return executor != null ? raw.thenApplyAsync(mapper, executor) : raw.thenApply(mapper);
    }

    // ADR-0027: the effective request deadline. A method-level @Timeout always wins;
    // otherwise the client socket timeout applies, where 0 means "no timeout" and is
    // expressed as null (engines leave their own deadline unset, while the JDK
    // engine cannot accept Duration.ZERO at all).
    static Duration effectiveSocketTimeout(Duration methodTimeout, ModuleInfo moduleInfo) {
        if (methodTimeout != null) {
            return methodTimeout;
        }
        int millis = moduleInfo.getSocketTimeout();
        return millis == 0 ? null : Duration.ofMillis(millis);
    }

    /**
     * Decompresses the response payload if transparent decompression is
     * enabled and {@code Content-Encoding} is {@code gzip}, {@code x-gzip},
     * or {@code deflate} (ADR-0021).
     *
     * @param response the raw wire response
     * @return the decompressed wire response with stripped encoding headers
     * @throws IOException on I/O or decompression failure
     */
    WireResponse handleDecompression(WireResponse response) throws IOException {
        if (moduleInfo == null || !moduleInfo.isDecompressionEnabled() || response == null) {
            return response;
        }

        String contentEncoding = null;
        for (Header header : response.headers()) {
            if (header.name() != null && header.name().equalsIgnoreCase("Content-Encoding")) {
                contentEncoding = header.value();
                break;
            }
        }

        if (contentEncoding == null) {
            return response;
        }

        String encoding = contentEncoding.trim().toLowerCase(Locale.ROOT);
        if ("identity".equals(encoding)) {
            return response;
        }

        ContentCompressor customCompressor = moduleInfo.getContentCompressor(encoding);
        boolean isGzip = "gzip".equals(encoding) || "x-gzip".equals(encoding);
        boolean isDeflate = "deflate".equals(encoding);

        if (!isGzip && !isDeflate && customCompressor == null) {
            return response;
        }

        // ADR-0076: the plain loop replaces the stream pipeline — the same
        // strip (Content-Encoding / Content-Length, case-insensitive), the
        // nameless headers kept, order preserved; no Stream machinery and no
        // second list, the WireResponse compact constructor does the one
        // defensive copy of this array-backed list
        List<Header> strippedHeaders = new ArrayList<>(response.headers().size());
        for (Header header : response.headers()) {
            if (header.name() != null
                    && (header.name().equalsIgnoreCase("Content-Encoding")
                    || header.name().equalsIgnoreCase("Content-Length"))) {
                continue;
            }
            strippedHeaders.add(header);
        }

        if (response.stream() != null) {
            InputStream decompressedStream;
            if (customCompressor != null) {
                decompressedStream = customCompressor.decompress(response.stream());
            } else if (isGzip) {
                decompressedStream = new GZIPInputStream(response.stream());
            } else {
                decompressedStream = new InflaterInputStream(response.stream());
            }
            return new WireResponse(response.statusCode(), strippedHeaders, null, decompressedStream, response.version(), response.trailers(), response.trailersSupplier());
        }

        byte[] rawBody = response.body();
        if (rawBody == null || rawBody.length == 0) {
            return new WireResponse(response.statusCode(), strippedHeaders, rawBody, null, response.version(), response.trailers(), response.trailersSupplier());
        }

        byte[] decompressedBytes;
        if (customCompressor != null) {
            decompressedBytes = customCompressor.decompress(rawBody);
        } else if (isGzip) {
            decompressedBytes = decompressGzip(rawBody);
        } else {
            decompressedBytes = decompressDeflate(rawBody);
        }
        return new WireResponse(response.statusCode(), strippedHeaders, decompressedBytes, null, response.version(), response.trailers(), response.trailersSupplier());
    }

    private static byte[] decompressGzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gis.readAllBytes();
        }
    }

    private static byte[] decompressDeflate(byte[] compressed) throws IOException {
        try {
            try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                return iis.readAllBytes();
            }
        } catch (ZipException e) {
            Inflater rawInflater = new Inflater(true);
            try (InflaterInputStream rawIisIn = new InflaterInputStream(new ByteArrayInputStream(compressed), rawInflater)) {
                return rawIisIn.readAllBytes();
            } finally {
                rawInflater.end();
            }
        }
    }


    private void logPerHop(WireRequest request, int statusCode, long startNanos) {
        // ADR-0076: honor the log-level gate, as logRequest/logResponse do —
        // a disabled DEBUG must not build the message string (the line is
        // produced on every send, every hop, of the zero-garbage hot path)
        if (!logger.isLoggable(System.Logger.Level.DEBUG)) {
            return;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        // per-hop timing; the total (redirect chain included) is logged in the response INFO line
        logger.log(System.Logger.Level.DEBUG,
                request.method() + " " + request.uri() + " -> " + statusCode + " in " + elapsedMs + " ms");
    }



    // ADR-0015 Aspect 2: an intermediate hop's stream (a redirect hop, a
    // retried attempt) is single-use — closed before the next send, so the
    // connection is not left half-read; only the final response's stream
    // reaches the caller (or is consumed by the error path). A close
    // failure is logged, not thrown: body hygiene, not the call's fate
    private void closeIntermediateStream(WireResponse response) {
        if (response != null) {
            ResponseBody.closeQuietly(response.stream(), logger);
        }
    }


    /**
     * Logs the outgoing request at INFO — the method, URI and headers,
     * plus a body section for the methods that carry one (POST, PATCH,
     * DELETE, PUT; GET and HEAD log without a body). Runs once per call,
     * before dispatch; redirect hops are logged per-hop at DEBUG.
     *
     * <p>Protected hook: a subclass may override it to observe or extend
     * the request log.
     *
     * @param request the wire request about to be sent
     */
    protected void logRequest(WireRequest request) {
        if (!logger.isLoggable(System.Logger.Level.INFO)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(request.method()).append(" ").append(request.uri());
        sb.append("\n");

        if (request.headers() != null) {
            String headers = request.headers().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
            sb.append(headers);
        }

        if (BODY_LOGGING_METHODS.contains(request.method())) {
            sb.append("\n");

            if (request.contentType() != null && request.contentType().contains("multipart/form-data")) {
                if (request.parts() != null) {
                    sb.append(request.parts().stream()
                            .map(part -> "name=" + part.name()
                                    + (part.filename() == null ? "" : ", filename=" + part.filename()))
                            .collect(Collectors.joining(", ")));
                    sb.append("\n");
                }
                sb.append("Sending size: ").append(multipartSendSize(request));
            } else if (request.body() != null) {
                sb.append(new String(request.body(), StandardCharsets.UTF_8));
            } else if (request.bodyPublisherSupplier() != null) {
                sb.append("<streaming body>");
            } else {
                sb.append("No request body");
            }
        }

        logger.log(System.Logger.Level.INFO, "Request: " + sb);
    }

    private String multipartSendSize(WireRequest request) {
        if (request.parts() == null) {
            return "0";
        }
        long size = MultipartBodyPublisher.contentLength(request.boundary(), request.parts());
        return size >= 0 ? String.valueOf(size) : "unknown (chunked)";
    }

    private void logResponse(WireResponse response, long elapsedMs) {
        if (!logger.isLoggable(System.Logger.Level.INFO)) {
            return;
        }
        String headers = response.headers().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        String proto = response.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
        logger.log(System.Logger.Level.INFO,
                "Response: " + proto + " " + response.statusCode() + " in " + elapsedMs + " ms\n" + headers);
    }

    private boolean isExpectedStatus(int statusCode) {
        if (expectStatuses != null) {
            return expectStatuses.contains(statusCode);
        }
        if (statusCode >= 200 && statusCode < 300) {
            return true;
        }
        if (conditional && statusCode == 304) {
            return true;
        }
        if (optional && (statusCode == 304 || statusCode == 404)) {
            return true;
        }
        return false;
    }

    /**
     * The logger the call logs through — request/response lines, redirect
     * hops and retry attempts.
     *
     * @param logger the logger (always set by the builder)
     */
    void setLogger(System.Logger logger) {
        this.logger = logger;
    }

    System.Logger getLogger() {
        return logger;
    }

    /**
     * The HTTP transport engine the call is dispatched through (ADR-0052).
     *
     * @param httpEngine the HTTP engine
     */
    void setHttpEngine(ru.jrestly.engine.HttpEngine httpEngine) {
        this.httpEngine = httpEngine;
    }

    /**
     * Returns the configured {@link ru.jrestly.engine.HttpEngine}.
     *
     * @return the HTTP engine
     */
    ru.jrestly.engine.HttpEngine getHttpEngine() {
        return httpEngine;
    }

    void setEngineOptions(ru.jrestly.engine.HttpEngineOptions engineOptions) {
        this.engineOptions = engineOptions;
    }

    ru.jrestly.engine.HttpEngineOptions getEngineOptions() {
        return engineOptions;
    }

    /**
     * The HTTP client the call is dispatched through (sync send or
     * {@code sendAsync}).
     *
     * @param httpClient the client
     */
    void setHttpClient(HttpClient httpClient) {
        this.httpEngine = httpClient != null ? ru.jrestly.engine.JdkHttpEngine.from(httpClient) : null;
    }

    /**
     * Returns the underlying JDK HTTP client, or {@code null} if using a non-JDK engine.
     *
     * @return the JDK HTTP client, or {@code null}
     */
    HttpClient getHttpClient() {
        if (httpEngine instanceof ru.jrestly.engine.JdkHttpEngine jdk) {
            return jdk.getHttpClient();
        }
        return null;
    }

    /**
     * The executor used for asynchronous tasks and retry delays (ADR-0026).
     *
     * @param executor the executor, or {@code null} for default
     */
    void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * Returns the executor configured for this template, or {@code null} if default (ADR-0026).
     *
     * @return the executor, or {@code null}
     */
    Executor getExecutor() {
        return executor;
    }

    /**
     * Sets the request timeout configured for this template (ADR-0027).
     *
     * @param timeout the timeout, or {@code null} to use client default
     */
    void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Returns the request timeout configured for this template, or {@code null} if default (ADR-0027).
     *
     * @return the timeout, or {@code null}
     */
    Duration getTimeout() {
        return timeout;
    }

    /**
     * The fully resolved request URL: the method path against the
     * environment's base URL, with {@code @PathVariable} placeholders
     * substituted, or the absolute URL declared on the method annotation.
     *
     * @param url the resolved URL
     */
    void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the target URL string.
     *
     * @return the target URL string
     */
    String getUrl() {
        return url;
    }

    /**
     * The HTTP method of the call.
     *
     * @param httpMethod the method
     */
    void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    HttpMethod getHttpMethod() {
        return httpMethod;
    }

    /**
     * The Content-Type of the request body, or {@code null} for a
     * body-less call. For multipart requests it carries the
     * {@code multipart/form-data} type with the per-call boundary.
     *
     * @param contentType the type, or {@code null}
     */
    void setContentType(String contentType) {
        this.contentType = contentType;
    }

    String getContentType() {
        return contentType;
    }

    /**
     * The headers the request carries: the client's auth headers plus the
     * values bound to {@code @RequestHeader} parameters.
     *
     * @param headers the headers
     */
    void setHeaders(List<Header> headers) {
        this.headers = headers;
    }

    /**
     * The request headers.
     *
     * @return the headers, or {@code null}
     */
    List<Header> getHeaders() {
        return headers;
    }

    /**
     * The names of the headers that carry credentials — dropped from the
     * request on a cross-origin redirect so they are not leaked to another
     * origin.
     *
     * @param authHeaderNames the header names (may be empty)
     */
    void setAuthHeaderNames(List<String> authHeaderNames) {
        this.authHeaderNames = authHeaderNames;
    }

    List<String> getAuthHeaderNames() {
        return authHeaderNames;
    }

    /**
     * The callback that captures the auth details from each response (an
     * {@code @SetAuthDetails} token), or {@code null} when the method
     * declares none.
     *
     * @param authDetailsConsumer the callback, or {@code null}
     */
    void setAuthDetailsConsumer(Consumer<List<Header>> authDetailsConsumer) {
        this.authDetailsConsumer = authDetailsConsumer;
    }

    Consumer<List<Header>> getAuthDetailsConsumer() {
        return authDetailsConsumer;
    }

    /**
     * The query parameters appended to the URL (URL-encoded), or
     * {@code null} for the request types that encode their parameters into
     * the body (multipart, urlencoded).
     *
     * @param requestParams the parameters, or {@code null}
     */
    void setRequestParams(List<Param> requestParams) {
        this.requestParams = requestParams;
    }

    /**
     * Returns the configured request parameters.
     *
     * @return the parameters, or {@code null}
     */
    List<Param> getRequestParams() {
        return requestParams;
    }

    /**
     * The parts of the multipart body, or {@code null} for non-multipart
     * calls.
     *
     * @param multipartFormParts the parts, or {@code null}
     */
    void setMultipartFormParts(List<MultipartPart> multipartFormParts) {
        this.multipartFormParts = multipartFormParts;
    }

    List<MultipartPart> getMultipartFormParts() {
        return multipartFormParts;
    }

    /**
     * The boundary of the multipart body, or {@code null} for
     * non-multipart calls.
     *
     * @param multipartBoundary the boundary, or {@code null}
     */
    void setMultipartBoundary(String multipartBoundary) {
        this.multipartBoundary = multipartBoundary;
    }

    String getMultipartBoundary() {
        return multipartBoundary;
    }

    /**
     * The request body bytes, or {@code null} for body-less calls and for
     * multipart calls (whose body is the parts).
     *
     * @param body the body, or {@code null}
     */
    void setBody(byte[] body) {
        this.body = body;
    }

    /**
     * The raw request body bytes, or {@code null} if no body is present.
     *
     * @return the request body bytes, or {@code null}
     */
    byte[] getBody() {
        return body;
    }

    /**
     * The supplier of the streaming request {@link HttpRequest.BodyPublisher}, or {@code null}.
     *
     * @return the body publisher supplier
     */
    Supplier<? extends HttpRequest.BodyPublisher> getBodyPublisherSupplier() {
        return bodyPublisherSupplier;
    }

    /**
     * Sets the supplier of the streaming request {@link HttpRequest.BodyPublisher}.
     *
     * @param bodyPublisherSupplier the body publisher supplier, or {@code null}
     */
    void setBodyPublisherSupplier(Supplier<? extends HttpRequest.BodyPublisher> bodyPublisherSupplier) {
        this.bodyPublisherSupplier = bodyPublisherSupplier;
    }

    /**
     * The engine-known size in bytes of the streaming request body, or
     * {@code null} when unknown. Carried into the wire request for the metrics
     * byte-accounting seam (ADR-0036 Amendment #298).
     *
     * @return the known body size, or {@code null}
     */
    Long getKnownBodySize() {
        return knownBodySize;
    }

    /**
     * Sets the engine-known size in bytes of the streaming request body.
     *
     * @param knownBodySize the known body size in bytes, or {@code null} when unknown
     */
    void setKnownBodySize(Long knownBodySize) {
        this.knownBodySize = knownBodySize;
    }

    /**
     * The type the controller method declares as its return type — what the
     * response payload is decoded to: bound by the JSON codec, returned raw
     * for {@code String}, discarded for {@code void}/{@code Void}. For
     * {@code CompletableFuture}-returning methods it is the future's type
     * argument.
     *
     * @param returnType the declared type
     */
    void setReturnType(Type returnType) {
        this.returnType = returnType;
    }

    Type getReturnType() {
        return returnType;
    }

    /**
     * Whether the method returns {@code CompletableFuture<T>}: the call is
     * dispatched non-blockingly via {@code sendAsync} and {@link #exchange()}
     * returns the future itself.
     *
     * @param async true for {@code CompletableFuture}-returning methods; the
     *              builder sets it only in that case (the field default is
     *              {@code false})
     */
    void setAsync(boolean async) {
        this.async = async;
    }

    boolean isAsync() {
        return async;
    }

    /**
     * Whether the method's return type is {@code Optional<T>} (or {@code CompletableFuture<Optional<T>>}).
     *
     * @param optional true for Optional-returning methods
     */
    void setOptional(boolean optional) {
        this.optional = optional;
    }

    boolean isOptional() {
        return optional;
    }

    /**
     * Whether the request carries conditional headers (e.g. {@code If-None-Match}, {@code If-Modified-Since}).
     *
     * @param conditional true for conditional requests
     */
    void setConditional(boolean conditional) {
        this.conditional = conditional;
    }

    /**
     * Returns whether the request carries conditional headers.
     *
     * @return true for conditional requests
     */
    boolean isConditional() {
        return conditional;
    }

    /**
     * Whether the effective return type is {@code InputStream}: the
     * response body is then received via {@code BodyHandlers.ofInputStream()}
     * and carried in the {@link WireResponse#stream()} component instead of
     * materialized as bytes (ADR-0015, Aspect 2).
     *
     * @param streamBody true for {@code InputStream} return types; the
     *              builder sets it only in that case (the field default is
     *              {@code false})
     */
    void setStreamBody(boolean streamBody) {
        this.streamBody = streamBody;
    }

    // Package-private read for the wire-seam tests: builder detection is
    // gated on the flag itself, not on a stubbed send
    boolean streamBody() {
        return streamBody;
    }

    /**
     * The {@code @OnError} parser of the method, or {@code null} when the
     * method declares none: a matching status decodes the payload into the
     * error class and surfaces as a {@link HandledException}.
     *
     * @param onErrorParser the parser, or {@code null}
     */
    void setOnErrorParser(OnErrorParser onErrorParser) {
        this.onErrorParser = onErrorParser;
    }

    /**
     * The {@code @OnError} parser, or {@code null} when none is configured.
     *
     * @return the parser
     */
    OnErrorParser getOnErrorParser() {
        return onErrorParser;
    }

    /**
     * The status codes the call accepts, or {@code null} for the default
     * (any 2xx).
     *
     * @param expectStatuses the accepted statuses, or {@code null}
     */
    void setExpectStatuses(List<Integer> expectStatuses) {
        this.expectStatuses = expectStatuses;
    }

    List<Integer> getExpectStatuses() {
        return expectStatuses;
    }

    /**
     * The maximum number of redirect hops the call follows, or
     * {@code null} to follow none.
     *
     * @param followRedirectsNumber the hop count, or {@code null}
     */
    void setFollowRedirectsNumber(Integer followRedirectsNumber) {
        this.followRedirectsNumber = followRedirectsNumber;
    }

    Integer getFollowRedirectsNumber() {
        return followRedirectsNumber;
    }

    /**
     * The HTTP version of the request, or {@code null} for the client's
     * default.
     *
     * @param httpVersion the version, or {@code null}
     */
    void setHttpVersion(HttpClient.Version httpVersion) {
        this.httpVersion = httpVersion;
    }

    HttpClient.Version getHttpVersion() {
        return httpVersion;
    }

    /**
     * The retry policy of the call — the method's {@code @Retry} policy
     * when the method declares one, otherwise the client-level policy.
     *
     * @param retryPolicy the policy
     */
    void setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    /**
     * The module info the call reads: the socket timeout applied to every
     * request.
     *
     * @param moduleInfo the module info (always set by the builder)
     */
    void setModuleInfo(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;
    }

    /**
     * The JSON codec that decodes the response payload and the
     * {@code @OnError} error objects.
     *
     * @param jsonCodec the codec (always set by the builder)
     */
    void setJsonCodec(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
        // A directly-set codec invalidates a registry the caller did not
        // explicitly install; the derived registry is rebuilt lazily from this
        // codec (ADR-0060). The builder sets the registry *after* the codec, so
        // its explicit registry still wins.
        this.codecRegistry = null;
    }

    /**
     * The media-type-aware codec registry used to select the response codec by
     * the response {@code Content-Type} (ADR-0060). When unset, a JSON-only
     * registry is derived from {@link #setJsonCodec(JsonCodec)}.
     *
     * @param codecRegistry the registry (set by the builder)
     */
    void setCodecRegistry(CodecRegistry codecRegistry) {
        this.codecRegistry = codecRegistry;
    }

    private CodecRegistry effectiveCodecRegistry() {
        CodecRegistry registry = this.codecRegistry;
        return registry != null ? registry : CodecRegistry.of(jsonCodec);
    }

    // ADR-0060: pick the response codec by the response Content-Type, falling
    // back to the default (JSON) — a server without a Content-Type keeps working.
    private PayloadCodec responseCodec(List<Header> responseHeaders) {
        return effectiveCodecRegistry().resolveOrDefault(ResponseBody.contentTypeOf(responseHeaders));
    }

    // Best-effort codec lookup for bare unexpected-status bodies: the lookup is
    // part of the Problem Details resolution, so its failure is swallowed (the
    // lookup used to live inside the resolver's try; ADR-0066 / ADR-0064).
    private PayloadCodec responseCodecOrNull(List<Header> responseHeaders) {
        try {
            return responseCodec(responseHeaders);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Sets the request interceptors for this call.
     *
     * @param requestInterceptors the request interceptors
     */
    void setRequestInterceptors(List<RequestInterceptor> requestInterceptors) {
        this.requestInterceptors = requestInterceptors != null ? List.copyOf(requestInterceptors) : List.of();
    }

    /**
     * The configured request interceptors.
     *
     * @return unmodifiable list of request interceptors, never {@code null}
     */
    List<RequestInterceptor> getRequestInterceptors() {
        return requestInterceptors;
    }

    /**
     * Sets the response interceptors for this call.
     *
     * @param responseInterceptors the response interceptors
     */
    void setResponseInterceptors(List<ResponseInterceptor> responseInterceptors) {
        this.responseInterceptors = responseInterceptors != null ? List.copyOf(responseInterceptors) : List.of();
    }

    /**
     * The configured response interceptors.
     *
     * @return unmodifiable list of response interceptors, never {@code null}
     */
    List<ResponseInterceptor> getResponseInterceptors() {
        return responseInterceptors;
    }

    /**
     * Sets the controller interface class being invoked.
     *
     * @param controllerClass the controller interface class
     */
    void setControllerClass(Class<?> controllerClass) {
        this.controllerClass = controllerClass;
    }

    /**
     * The controller interface class being invoked.
     *
     * @return the controller class
     */
    Class<?> getControllerClass() {
        return controllerClass;
    }

    /**
     * Sets the controller method being invoked.
     *
     * @param method the method being invoked
     */
    void setMethod(Method method) {
        this.method = method;
    }

    /**
     * The controller method being invoked.
     *
     * @return the method
     */
    Method getMethod() {
        return method;
    }

    /**
     * Sets the invocation arguments.
     *
     * @param args the invocation arguments
     */
    void setArgs(Object[] args) {
        this.args = args;
    }

    /**
     * The invocation arguments.
     *
     * @return the invocation arguments
     */
    Object[] getArgs() {
        return args;
    }

    /**
     * Sets the response headers holder (ADR-0022).
     *
     * @param responseHeadersHolder the holder to populate with response metadata
     */
    void setResponseHeadersHolder(ResponseHeaders responseHeadersHolder) {
        this.responseHeadersHolder = responseHeadersHolder;
    }

    /**
     * The response headers holder (ADR-0022).
     *
     * @return the holder, or {@code null}
     */
    ResponseHeaders getResponseHeadersHolder() {
        return responseHeadersHolder;
    }

    /**
     * Sets the upload progress listener (ADR-0030).
     *
     * @param uploadProgressListener the listener, or {@code null}
     */
    void setUploadProgressListener(UploadProgressListener uploadProgressListener) {
        this.uploadProgressListener = uploadProgressListener;
    }

    /**
     * The upload progress listener (ADR-0030).
     *
     * @return the listener, or {@code null}
     */
    UploadProgressListener getUploadProgressListener() {
        return uploadProgressListener;
    }

    /**
     * Sets the download progress listener (ADR-0033).
     *
     * @param downloadProgressListener the listener, or {@code null}
     */
    void setDownloadProgressListener(DownloadProgressListener downloadProgressListener) {
        this.downloadProgressListener = downloadProgressListener;
    }

    /**
     * The download progress listener (ADR-0033).
     *
     * @return the listener, or {@code null}
     */
    DownloadProgressListener getDownloadProgressListener() {
        return downloadProgressListener;
    }

    /**
     * Sets the metrics and telemetry listener (ADR-0036).
     *
     * @param metricsListener the listener, or {@code null}
     */
    void setMetricsListener(ClientMetricsListener metricsListener) {
        this.metricsListener = metricsListener;
    }

    /**
     * The metrics and telemetry listener (ADR-0036).
     *
     * @return the listener, or {@code null}
     */
    ClientMetricsListener getMetricsListener() {
        return metricsListener;
    }

    /**
     * Sets the {@link CircuitBreaker} protecting this call (ADR-0043).
     *
     * @param circuitBreaker the circuit breaker, or {@code null}
     */
    void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Returns the configured {@link CircuitBreaker}.
     *
     * @return the circuit breaker, or {@code null}
     */
    CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Sets the {@link RateLimiter} controlling call throughput (ADR-0043).
     *
     * @param rateLimiter the rate limiter, or {@code null}
     */
    void setRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * Returns the configured {@link RateLimiter}.
     *
     * @return the rate limiter, or {@code null}
     */
    RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    /**
     * Sets the token manager for token caching and refresh (ADR-0046).
     *
     * @param tokenManager the token manager, or {@code null}
     */
    void setTokenManager(ru.jrestly.auth.TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    /**
     * Returns the configured {@link ru.jrestly.auth.TokenManager}.
     *
     * @return the token manager, or {@code null}
     */
    ru.jrestly.auth.TokenManager getTokenManager() {
        return tokenManager;
    }

    /**
     * Enables or disables transparent 401 Unauthorized retry with token cache invalidation (ADR-0046).
     *
     * @param retryOnUnauthorized {@code true} to enable 401 retry
     */
    void setRetryOnUnauthorized(boolean retryOnUnauthorized) {
        this.retryOnUnauthorized = retryOnUnauthorized;
    }

    /**
     * Whether transparent 401 Unauthorized retry is enabled.
     *
     * @return {@code true} if 401 retry is enabled
     */
    boolean isRetryOnUnauthorized() {
        return retryOnUnauthorized;
    }

    // ADR-0064: package-private on purpose — the @OnError dispatch (decode,
    // built-in fallback, ProblemDetail slicing, throwable instantiation) is
    // pinned directly without a wire round-trip.
    void handleOnError(int statusCode, String payload, List<Header> responseHeaders, Charset payloadCharset) {
        Class<?> targetErrorClass = onErrorParser.getErrorClass(statusCode);
        boolean isProblemException = ru.jrestly.problem.ProblemDetailException.class.isAssignableFrom(targetErrorClass);
        Class<?> deserializeClass = isProblemException ? ru.jrestly.problem.ProblemDetail.class : targetErrorClass;

        Object errorObject = null;
        if (payload != null && !payload.isEmpty() && deserializeClass != Void.class) {
            if (deserializeClass == ru.jrestly.problem.ProblemDetail.class) {
                try {
                    errorObject = responseCodec(responseHeaders).deserialize(
                            payload.getBytes(payloadCharset), deserializeClass, ResponseBody.contentTypeOf(responseHeaders));
                } catch (Throwable ignored) {
                }
            } else {
                errorObject = responseCodec(responseHeaders).deserialize(
                        payload.getBytes(payloadCharset), deserializeClass, ResponseBody.contentTypeOf(responseHeaders));
            }
        }

        if (errorObject == null && (targetErrorClass == ru.jrestly.problem.ProblemDetail.class || isProblemException) && payload != null && !payload.isBlank()) {
            try {
                errorObject = ru.jrestly.problem.ProblemDetail.fromJson(payload);
            } catch (Throwable ignored) {
            }
        }
        if (errorObject instanceof ru.jrestly.problem.ProblemDetail pd && pd.getStatus() <= 0) {
            pd.setStatus(statusCode);
        }
        if (isProblemException) {
            ru.jrestly.problem.ProblemDetail pd = (errorObject instanceof ru.jrestly.problem.ProblemDetail p)
                    ? p
                    : ru.jrestly.problem.ProblemDetail.forStatus(statusCode);
            errorObject = new ru.jrestly.problem.ProblemDetailException(pd, responseHeaders);
        }

        if (Throwable.class.isAssignableFrom(targetErrorClass)) {
            switch (errorObject) {
                case RuntimeException re -> throw re;
                case Error err -> throw err;
                case Throwable t -> throw new RuntimeException(t);
                case null, default -> {
                    Throwable instance = ProblemDetailResolver.instantiateThrowable(targetErrorClass, statusCode, responseHeaders);
                    switch (instance) {
                        case RuntimeException re -> throw re;
                        case Error err -> throw err;
                        case null -> throw new NullPointerException("instantiateThrowable returned null");
                        default -> throw new RuntimeException(instance);
                    }
                }
            }
        }

        throw new HandledException(errorObject, statusCode, responseHeaders);
    }

    private UnexpectedStatusException createUnexpectedStatusException(
            int statusCode, String rawBody, List<Header> responseHeaders) {
        ru.jrestly.problem.ProblemDetail problemDetail = ProblemDetailResolver.resolve(
                statusCode, rawBody, responseHeaders, ResponseBody.contentTypeOf(responseHeaders),
                responseCodecOrNull(responseHeaders));
        if (statusCode >= 400 && statusCode < 500) {
            return new ClientErrorException(statusCode, rawBody, responseHeaders, problemDetail);
        }
        if (statusCode >= 500 && statusCode < 600) {
            return new ServerErrorException(statusCode, rawBody, responseHeaders, problemDetail);
        }
        return new UnexpectedStatusException(statusCode, rawBody, responseHeaders, problemDetail);
    }
}
