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

import java.io.IOException;
import java.io.InputStream;
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
    // ADR-0066, ADR-0092: redirect executor collaborator, built lazily on the first hop
    private RedirectExecutor redirectExecutor;
    // ADR-0091: retry execution collaborator, built lazily from the fields populated before exchange() runs
    private RetryExecutor retryExecutor;
    // ADR-0094: response processor collaborator, built lazily from the fields populated before exchange() runs
    private ResponseProcessor responseProcessor;

    void setHttpCache(ru.jrestly.cache.HttpCache httpCache) {
        this.httpCache = httpCache;
    }

    ru.jrestly.cache.HttpCache getHttpCache() {
        return httpCache;
    }

    // Lazily built (redirects only run when @FollowRedirects is present, so the
    // zero-redirect hot path allocates nothing) from the fields the builder
    // populated before exchange() runs.
    private RedirectExecutor redirectExecutor() {
        if (redirectExecutor == null) {
            redirectExecutor = new RedirectExecutor(followRedirectsNumber, authHeaderNames, logger);
        }
        return redirectExecutor;
    }

    private RetryExecutor retryExecutor() {
        if (retryExecutor == null) {
            retryExecutor = new RetryExecutor(new RetryExecutor.RetryConfig(
                    retryPolicy,
                    httpMethod,
                    logger,
                    metricsListener,
                    onErrorParser,
                    expectStatuses,
                    conditional,
                    optional,
                    returnType,
                    this::responseCodecOrNull,
                    executor
            ));
        }
        return retryExecutor;
    }

    private ResponseProcessor responseProcessor() {
        if (responseProcessor == null) {
            responseProcessor = new ResponseProcessor(new ResponseProcessor.ResponseConfig(
                    returnType,
                    optional,
                    responseHeadersHolder,
                    authDetailsConsumer,
                    onErrorParser,
                    expectStatuses,
                    conditional,
                    this::responseCodec,
                    logger,
                    isSanitizeLogs()
            ));
        }
        return responseProcessor;
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

        // ADR-0063 pre-wire consultation is delegated to CacheDispatch (lookup)
        ru.jrestly.cache.CachedResponse cachedEntry = null;
        boolean isCacheHit = false;
        if (httpCache != null) {
            CacheDispatch.CacheLookup cacheLookup = CacheDispatch.lookup(effectiveRequest, httpCache);
            effectiveRequest = cacheLookup.request();
            cachedEntry = cacheLookup.entry();
            isCacheHit = cacheLookup.isCacheHit();
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
                response = CacheDispatch.synthesizeHit(cachedEntry, streamBody);
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
            WireResponse cachedResp = CacheDispatch.synthesizeHit(cachedEntry, streamBody);
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
    // (delegated to CacheDispatch, which owns the conditional-header scan — ADR-0093)
    static boolean hasHeader(WireRequest req, String name) {
        return CacheDispatch.hasHeader(req, name);
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
    // logging, @OnError handling, status check, payload decoding (ADR-0094: delegates to ResponseProcessor)
    @SuppressWarnings("unchecked")
    private <T> T processResponse(WireRequest request, WireResponse response, long startNanos)
            throws IOException {
        return responseProcessor().process(request, response, startNanos);
    }

    // Handles redirects according to HTTP spec:
    // 301/302/303 downgrade to GET and drop the body; 307/308 re-send the method and body.
    // ADR-0002, ADR-0066, ADR-0092: delegates to RedirectExecutor
    private WireResponse executeWithRedirects(WireRequest request) throws IOException, InterruptedException {
        return redirectExecutor().execute(request, this::send);
    }

    // ADR-0008, ADR-0011, ADR-0091: delegates to RetryExecutor
    private WireResponse executeWithRetries(
            WireRequest request,
            RequestContext requestContext,
            int[] attemptsHolder,
            int attemptOffset
    ) throws IOException, InterruptedException {
        return retryExecutor().execute(request, requestContext, attemptsHolder, attemptOffset, this::executeWithRedirects);
    }

    boolean isRetryableStatus(WireResponse response) {
        return retryExecutor().isRetryableStatus(response);
    }

    boolean probePayloadDecode(WireResponse response) {
        return retryExecutor().probePayloadDecode(response);
    }

    // Async twin: same hop semantics, delegates to RedirectExecutor (ADR-0002, ADR-0066, ADR-0092)
    private CompletableFuture<WireResponse> executeAsyncWithRedirects(WireRequest request) {
        return redirectExecutor().executeAsync(request, this::sendAsync);
    }

    // ADR-0008, ADR-0011, ADR-0091, ADR-0092: async twin delegates to RetryExecutor and RedirectExecutor
    private CompletableFuture<WireResponse> executeAsyncWithRetries(
            WireRequest request,
            RequestContext requestContext,
            AtomicInteger attemptsRef,
            int attemptOffset
    ) {
        return retryExecutor().executeAsync(request, requestContext, attemptsRef, attemptOffset, this::executeAsyncWithRedirects);
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
     * or {@code deflate} (ADR-0021, ADR-0076, ADR-0087). Delegated to
     * {@link ResponseDecompressor}.
     *
     * @param response the raw wire response
     * @return the decompressed wire response with stripped encoding headers
     * @throws IOException on I/O or decompression failure
     */
    WireResponse handleDecompression(WireResponse response) throws IOException {
        return ResponseDecompressor.decompress(response, moduleInfo);
    }


    private String resolveTraceId(WireRequest request) {
        String traceId = ru.jrestly.tracing.TraceIdExtractor.extract(request);
        if (traceId == null && moduleInfo != null && moduleInfo.getTraceIdSupplier() != null) {
            traceId = moduleInfo.getTraceIdSupplier().get();
        }
        return traceId;
    }

    private boolean isSanitizeLogs() {
        return moduleInfo == null || moduleInfo.isSanitizeSensitiveData();
    }

    private void logPerHop(WireRequest request, int statusCode, long startNanos) {
        String traceId = resolveTraceId(request);
        HttpDiagnosticLogger.logPerHop(logger, request, statusCode, startNanos, traceId, isSanitizeLogs());
    }



    // ADR-0015 Aspect 2: an intermediate hop's stream (a redirect hop, a
    // retried attempt) is single-use — closed before the next send, so the
    // connection is not left half-read; only the final response's stream
    // reaches the caller (or is consumed by the error path). A close
    // failure is logged, not thrown: body hygiene, not the call's fate
    private void closeIntermediateStream(WireResponse response) {
        redirectExecutor().closeIntermediateStream(response);
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
        String traceId = resolveTraceId(request);
        HttpDiagnosticLogger.logRequest(logger, request, traceId, isSanitizeLogs());
    }

    private void logResponse(WireResponse response, long elapsedMs) {
        String traceId = ru.jrestly.tracing.TraceIdExtractor.extract(response);
        if (traceId == null && moduleInfo != null && moduleInfo.getTraceIdSupplier() != null) {
            traceId = moduleInfo.getTraceIdSupplier().get();
        }
        HttpDiagnosticLogger.logResponse(logger, response, elapsedMs, traceId, isSanitizeLogs());
    }

    private boolean isExpectedStatus(int statusCode) {
        return HttpErrorHandler.isExpectedStatus(expectStatuses, conditional, optional, statusCode);
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
    // pinned directly without a wire round-trip. Delegated to HttpErrorHandler (ADR-0088).
    void handleOnError(int statusCode, String payload, List<Header> responseHeaders, Charset payloadCharset) {
        HttpErrorHandler.handleOnError(
                onErrorParser, () -> responseCodec(responseHeaders), statusCode, payload, responseHeaders, payloadCharset);
    }

    private UnexpectedStatusException createUnexpectedStatusException(
            int statusCode, String rawBody, List<Header> responseHeaders) {
        return HttpErrorHandler.createUnexpectedStatusException(
                statusCode, rawBody, responseHeaders, responseCodecOrNull(responseHeaders));
    }
}
