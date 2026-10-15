package ru.jrestly.http;

import ru.jrestly.ModuleInfo;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseContext;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.interceptor.SimpleRequestContext;
import ru.jrestly.interceptor.SimpleResponseContext;
import ru.jrestly.json.JsonCodec;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
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
public class HttpTemplate {

    // ADR-0009 candidate 1: the request-log body section covers POST, PATCH,
    // DELETE and PUT (PUT was missing — 0.5.0 carry-over). GET/HEAD stay out
    // deliberately: RFC 9110 allows a GET body but leaves its semantics
    // undefined. Static, not a per-request Arrays.asList — logRequest runs
    // on every exchange.
    private static final Set<HttpMethod> BODY_LOGGING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.PUT);

    // ADR-0009 candidate 3 + ADR-0010: the HTTP-date form of Retry-After.
    // All three RFC 9110 date grammars are honored — a recipient that parses
    // an HTTP-date "MUST accept all three HTTP-date formats" (RFC 9110,
    // 5.6.7): the live IMF-fixdate plus the obsolete RFC 850 and asctime(3)
    // forms. asctime carries no zone — "values in the asctime format are
    // assumed to be in UTC" (RFC 9110), so the UTC zone is prescribed, not
    // assumed by us.
    private static final DateTimeFormatter RETRY_AFTER_HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;
    // RFC 850 without the weekday prefix — the full weekday name is validated
    // separately (RETRY_AFTER_RFC850_WEEKDAYS) but not parsed as a field: as
    // a parsed EEEE field, java.time cross-checks it against the date and
    // trips on the two-digit-year mapping before the pivot below can fix it
    private static final DateTimeFormatter RETRY_AFTER_RFC850_DATE =
            DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH);
    private static final Set<String> RETRY_AFTER_RFC850_WEEKDAYS =
            Set.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");
    private static final DateTimeFormatter RETRY_AFTER_ASCTIME_DATE =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private System.Logger logger;

    private HttpClient httpClient;
    private ModuleInfo moduleInfo;
    private JsonCodec jsonCodec;

    private String url;
    private HttpMethod httpMethod;
    private String contentType;
    private List<Header> headers;
    private List<String> authHeaderNames;
    private Consumer<List<Header>> authDetailsConsumer;
    private List<Param> requestParams;
    private byte[] body;
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
    private Executor executor;
    private Duration timeout;

    /**
     * Creates an empty template. Not intended for direct use: instances
     * are assembled by {@link HttpTemplateBuilder} and populated through
     * the {@code set*} setters before {@link #exchange()} runs.
     */
    public HttpTemplate() {
    }

    /**
     * Executes the call and returns the decoded result. For
     * {@code CompletableFuture<T>} return types the returned value is the
     * future itself; see {@link #exchangeAsync(WireRequest, long, RequestContext)}.
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
    public <T> T exchange() {
        WireRequest request = new WireRequest(httpMethod, createUri(), headers, contentType, body, multipartFormParts, multipartBoundary);

        long start = System.nanoTime();
        boolean hasInterceptors = !requestInterceptors.isEmpty() || !responseInterceptors.isEmpty();
        RequestContext requestContext = hasInterceptors ? createRequestContext() : null;
        WireRequest effectiveRequest = request;

        if (hasInterceptors) {
            try {
                for (RequestInterceptor interceptor : requestInterceptors) {
                    WireRequest next = interceptor.intercept(effectiveRequest, requestContext);
                    if (next == null) {
                        throw new NullPointerException("RequestInterceptor returned null WireRequest");
                    }
                    effectiveRequest = next;
                }
            } catch (Throwable t) {
                invokeResponseInterceptors(requestContext, effectiveRequest, null, t, start);
                if (async) {
                    CompletableFuture<Object> failed = new CompletableFuture<>();
                    failed.completeExceptionally(t);
                    return (T) failed;
                }
                if (t instanceof RuntimeException re) {
                    throw re;
                } else if (t instanceof Error err) {
                    throw err;
                } else {
                    throw new RuntimeException(t);
                }
            }
        }

        logRequest(effectiveRequest);

        if (async) {
            return (T) exchangeAsync(effectiveRequest, start, requestContext);
        }

        WireResponse response = null;
        Throwable failure = null;
        try {
            response = executeWithRetries(effectiveRequest);
            return processResponse(effectiveRequest, response, start);
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
            if (hasInterceptors) {
                invokeResponseInterceptors(requestContext, effectiveRequest, response, failure, start);
            }
        }
    }

    // Async twin of exchange(): the same tail via processResponse, with
    // @OnError / status-check exceptions kept as-is and transport failures
    // mapped to the exceptions a synchronous call would throw (both become
    // the cause of the future's exceptional completion), and response
    // interceptors triggered non-blockingly upon completion.
    private CompletableFuture<Object> exchangeAsync(
            WireRequest request,
            long startNanos,
            RequestContext requestContext
    ) {
        AtomicReference<WireResponse> responseRef = new AtomicReference<>();
        boolean hasResponseInterceptors = !responseInterceptors.isEmpty() && requestContext != null;

        java.util.function.Function<WireResponse, Object> processor = response -> {
            if (hasResponseInterceptors) {
                responseRef.set(response);
            }
            try {
                return processResponse(request, response, startNanos);
            } catch (IOException e) {
                // ADR-0015 Aspect 3: a stream-consumption failure on the
                // error path is a transport-class failure — thenApply
                // unwraps the CompletionException, so the mapping below
                // sees the plain IOException, as the sync twin's
                // catch (IOException) does
                throw new CompletionException(e);
            }
        };

        CompletableFuture<WireResponse> retriesFuture = executeAsyncWithRetries(request);
        CompletableFuture<Object> stage = executor != null
                ? retriesFuture.thenApplyAsync(processor, executor)
                : retriesFuture.thenApply(processor);

        CompletableFuture<Object> future = stage.exceptionally(ex -> {
            Throwable cause = unwrapCause(ex);
            // the future completes exceptionally with this exception as the
            // CompletionException cause of join() / ExecutionException cause of get()
            if (cause instanceof RuntimeException re && !(cause instanceof CompletionException)) {
                if (cause instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw re; // HandledException, ClientErrorException, ServerErrorException, custom typed exceptions
            }
            if (cause instanceof Error err) {
                throw err;
            }
            logger.log(System.Logger.Level.ERROR,
                    "Request failed: " + request.method() + " " + request.uri(), cause);
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(cause);
        });

        if (hasResponseInterceptors) {
            java.util.function.BiConsumer<Object, Throwable> interceptorAction = (res, ex) -> {
                Throwable failure = ex != null ? unwrapCause(ex) : null;
                invokeResponseInterceptors(
                        requestContext,
                        request,
                        responseRef.get(),
                        failure,
                        startNanos
                );
            };
            return executor != null
                    ? future.whenCompleteAsync(interceptorAction, executor)
                    : future.whenComplete(interceptorAction);
        }

        return future;
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
            return HttpTemplate.class.getMethod("exchange");
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
        Duration callDuration = Duration.ofNanos(System.nanoTime() - startNanos);

        List<Header> responseHeaders = response.headers();
        int statusCode = response.statusCode();

        // ADR-0022: Populate response headers holder and thread-local captor
        if (responseHeadersHolder != null) {
            responseHeadersHolder.populate(statusCode, responseHeaders, callDuration);
        }
        Consumer<ResponseHeaders> captor = ResponseHeaders.CURRENT_CAPTOR.get();
        if (captor != null) {
            captor.accept(new ResponseHeaders(statusCode, responseHeaders, callDuration));
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
            if (onErrorParser != null && onErrorParser.canParse(statusCode)) {
                String errorPayload = consumePayload(stream);
                handleOnError(statusCode, errorPayload, responseHeaders);
            }
            if (!isExpectedStatus(statusCode)) {
                String rawPayload = consumePayload(stream);
                throw createUnexpectedStatusException(statusCode, rawPayload == null ? "" : rawPayload, responseHeaders);
            }
            // ADR-0016 Aspect 1 & 5: Stream<String> returns line stream;
            // Stream<ServerSentEvent> returns parsed event stream
            if (isStreamType(returnType, String.class)) {
                logger.log(System.Logger.Level.INFO, streamingPayloadLine("streaming lines", responseHeaders));
                Charset charset = resolveCharset(responseHeaders);
                Stream<String> lineStream = new BufferedReader(new InputStreamReader(stream, charset))
                        .lines()
                        .onClose(() -> closeQuietly(stream));
                return (T) lineStream;
            }
            if (isStreamType(returnType, ServerSentEvent.class)) {
                logger.log(System.Logger.Level.INFO, streamingPayloadLine("streaming events", responseHeaders));
                Charset charset = resolveCharset(responseHeaders);
                Stream<String> lineStream = new BufferedReader(new InputStreamReader(stream, charset))
                        .lines()
                        .onClose(() -> closeQuietly(stream));
                Stream<ServerSentEvent> eventStream = ServerSentEvent.parse(lineStream);
                return (T) eventStream;
            }
            // ADR-0015 Aspect 5: the payload line is skipped for stream
            // responses — logging must not consume the body
            logger.log(System.Logger.Level.INFO, streamingPayloadLine("streaming", responseHeaders));
            if (optional) {
                if (statusCode == 404 || statusCode == 304 || statusCode == 204) {
                    closeQuietly(stream);
                    return (T) Optional.empty();
                }
                if (InputStream.class.equals(returnType)) {
                    return (T) Optional.of(stream);
                }
            }
            return (T) stream;
        }

        byte[] bytes = response.body();
        String payload = (bytes != null && bytes.length > 0) ? new String(bytes, StandardCharsets.UTF_8) : null;

        if (onErrorParser != null && onErrorParser.canParse(statusCode)) {
            handleOnError(statusCode, payload, responseHeaders);
        }

        if (!isExpectedStatus(statusCode)) {
            throw createUnexpectedStatusException(statusCode, payload == null ? "" : payload, responseHeaders);
        }

        if (optional) {
            if (statusCode == 404 || statusCode == 304 || statusCode == 204 || payload == null) {
                logger.log(System.Logger.Level.INFO, "No response or empty/status for Optional return.");
                return (T) Optional.empty();
            }
            if (String.class.equals(returnType)) {
                logger.log(System.Logger.Level.INFO, "Response size: " + payload.length() + " bytes\n" + payload);
                return (T) Optional.of(payload);
            }
            if (Void.class.equals(returnType) || void.class.equals(returnType)) {
                return (T) Optional.empty();
            }
            logger.log(System.Logger.Level.INFO, "Response size: " + payload.length() + " bytes\n" + jsonCodec.prettyPrint(payload));
            Object deserialized = jsonCodec.deserialize(payload, returnType);
            return (T) Optional.ofNullable(deserialized);
        }

        if (payload == null) {
            logger.log(System.Logger.Level.INFO, "No response.");
            return null;
        }

        if (String.class.equals(returnType)) {
            logger.log(System.Logger.Level.INFO, "Response size: " + payload.length() + " bytes\n" + payload);
            return (T) payload;
        }

        logger.log(System.Logger.Level.INFO, "Response size: " + payload.length() + " bytes\n" + jsonCodec.prettyPrint(payload));
        // ADR-0009 candidate 2: the primitive and the boxed form are one
        // contract — a void/Void method never decodes the body
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            return null;
        }

        return jsonCodec.deserialize(payload, returnType);
    }

    // ADR-0015 Aspect 3: the error paths' consumption — the stream's
    // readAllBytes is the byte[] path's payload in stream form: an empty
    // stream is a null payload, as an empty body is. The close follows the
    // JDK contract (read to EOF, then close)
    private static String consumePayload(InputStream stream) throws IOException {
        try (stream) {
            byte[] bytes = stream.readAllBytes();
            return bytes.length > 0 ? new String(bytes, StandardCharsets.UTF_8) : null;
        }
    }

    static boolean isStreamType(Type type, Class<?> elementClass) {
        if (type instanceof ParameterizedType pt && Stream.class.equals(pt.getRawType())) {
            Type[] args = pt.getActualTypeArguments();
            return args.length == 1 && elementClass.equals(args[0]);
        }
        return false;
    }

    static Charset resolveCharset(List<Header> headers) {
        if (headers == null) {
            return StandardCharsets.UTF_8;
        }
        for (Header header : headers) {
            if (header.name() != null && header.name().equalsIgnoreCase("Content-Type")) {
                String value = header.value();
                if (value != null) {
                    Charset cs = parseCharsetFromContentType(value);
                    if (cs != null) {
                        return cs;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset parseCharsetFromContentType(String contentType) {
        String[] parts = contentType.split(";");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int eq = part.indexOf('=');
            if (eq > 0) {
                String key = part.substring(0, eq).trim();
                if ("charset".equalsIgnoreCase(key)) {
                    String charsetName = part.substring(eq + 1).trim();
                    if (charsetName.startsWith("\"") && charsetName.endsWith("\"") && charsetName.length() >= 2) {
                        charsetName = charsetName.substring(1, charsetName.length() - 1).trim();
                    }
                    if (charsetName.isEmpty()) {
                        return StandardCharsets.UTF_8;
                    }
                    try {
                        return Charset.forName(charsetName);
                    } catch (Exception e) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return null;
    }

    private void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            logger.log(System.Logger.Level.INFO, "Failed to close the response stream", e);
        }
    }

    // ADR-0015 & ADR-0016: streaming variant of the payload log line
    private static String streamingPayloadLine(String prefix, List<Header> headers) {
        String contentLength = headers.stream()
                .filter(h -> h.name() != null && h.name().equalsIgnoreCase("Content-Length"))
                .map(Header::value)
                .findFirst()
                .orElse(null);
        return contentLength == null
                ? prefix + " (length unknown)"
                : prefix + ", Content-Length: " + contentLength;
    }

    // Handles redirects according to HTTP spec:
    // 301/302/303 downgrade to GET and drop the body; 307/308 re-send the method and body.
    private WireResponse executeWithRedirects(WireRequest request) throws IOException, InterruptedException {
        int hopsLeft = followRedirectsNumber == null ? 0 : followRedirectsNumber;

        WireResponse response = send(request);
        while (hopsLeft > 0) {
            WireRequest next = nextHop(request, response);
            if (next == null) {
                break;
            }
            request = next;
            hopsLeft--;
            closeIntermediateStream(response);
            response = send(request);
        }

        logExhaustedRedirects(response);
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
    private WireResponse executeWithRetries(WireRequest request) throws IOException, InterruptedException {
        if (!retryPolicy.isEnabled()) {
            return executeWithRedirects(request);
        }

        int maxAttempts = retryPolicy.maxAttempts();

        for (int attempt = 1; ; attempt++) {
            boolean last = attempt == maxAttempts;

            WireResponse response;
            String failure;
            try {
                response = executeWithRedirects(request);
                failure = "HTTP " + response.statusCode();
            } catch (InterruptedException e) {
                // the caller's thread was interrupted — fail fast, never retried
                throw e;
            } catch (IOException e) {
                if (last || !retryPolicy.isRetryTransport()) {
                    throw e;
                }
                response = null;
                failure = e.getClass().getSimpleName();
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
                        scheduleRetry(attempt, maxAttempts, "decode (" + e.getClass().getSimpleName() + ")", response);
                        continue;
                    }
                }
                return response;
            }

            scheduleRetry(attempt, maxAttempts, failure, response);
        }
    }

    // One DEBUG line per failed attempt, then the wait: full jitter over
    // [0, bound], or the deterministic max(bound, Retry-After) when the
    // failed response carries a parseable Retry-After header, delta-seconds
    // or HTTP-date (ADR-0008 Aspect 4, ADR-0009 candidate 3)
    private void scheduleRetry(int attempt, int maxAttempts, String failure, WireResponse response) throws InterruptedException {
        // ADR-0015 Aspect 2: every scheduled retry discards the failed
        // attempt — its stream (if any) is closed before the wait; a
        // transport failure carries no response and is a no-op
        closeIntermediateStream(response);
        long delayMillis = retryPolicy.nextDelayMillis(attempt, retryAfterDelayMillis(response), ThreadLocalRandom.current());
        logger.log(System.Logger.Level.DEBUG,
                "Attempt " + attempt + "/" + maxAttempts + " failed: " + failure + " — retrying in " + delayMillis + " ms");
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
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
            if (status == 404 || status == 304 || status == 204 || payload == null) {
                return true;
            }
            if (String.class.equals(returnType) || Void.class.equals(returnType) || void.class.equals(returnType)) {
                return true;
            }
            jsonCodec.deserialize(payload, returnType);
            return true;
        }
        if (payload == null) {
            return true; // no body
        }
        if (String.class.equals(returnType)) {
            return true; // returned raw, never decoded
        }
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            return true; // body discarded (ADR-0009 candidate 2)
        }
        jsonCodec.deserialize(payload, returnType);
        return true;
    }

    // Both RFC 9110 forms of Retry-After (ADR-0009 candidate 3): delta-seconds
    // -> seconds x 1000 (as before, in milliseconds); HTTP-date — all three
    // RFC 9110 date grammars (IMF-fixdate, then the obsolete RFC 850 and
    // asctime(3) forms per ADR-0010) -> max(0, date - now) — a past date
    // means "you may have retried already", the floor mirrors the integer
    // form, where a small value never shrinks the max(bound, ...) below the
    // bound. Absent, null-valued, or unparseable — null (pure jitter).
    // Package-private seam — the unit tests exercise the parser directly.
    static Long retryAfterDelayMillis(WireResponse response) {
        if (response == null) {
            return null;
        }
        for (Header header : response.headers()) {
            if (header.name() != null && header.name().equalsIgnoreCase("Retry-After")) {
                return parseRetryAfter(header.value());
            }
        }
        return null;
    }

    private static Long parseRetryAfter(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value) * 1_000L;
        } catch (NumberFormatException e) {
            // not delta-seconds — try the HTTP-date forms below
        }
        // All three RFC 9110 HTTP-date grammars (ADR-0010): the live
        // IMF-fixdate first, then the two obsolete forms — the formats are
        // mutually exclusive by grammar (comma placement, zone, field order),
        // so the try order is just the RFC's presentation order.
        try {
            return delayFromParsedDate(ZonedDateTime.parse(value, RETRY_AFTER_HTTP_DATE));
        } catch (DateTimeParseException e) {
            // not IMF-fixdate — try the obsolete RFC 850 form
        }
        // The RFC 850 form starts with a full weekday name — validate it,
        // then parse the rest (see the RETRY_AFTER_RFC850_DATE note)
        String rfc850Body = value;
        int comma = value.indexOf(',');
        if (comma > 0 && RETRY_AFTER_RFC850_WEEKDAYS.contains(value.substring(0, comma))) {
            rfc850Body = value.substring(comma + 1).trim();
        }
        try {
            return delayFromParsedDate(
                    pivotRfc850Year(ZonedDateTime.parse(rfc850Body, RETRY_AFTER_RFC850_DATE)));
        } catch (DateTimeParseException e) {
            // not RFC 850 — try the obsolete asctime(3) form
        }
        try {
            // asctime may carry a double space before a one-digit day (the
            // RFC 9110 example does) — collapse runs of spaces for this
            // attempt only; a one- or two-digit day parses either way
            return delayFromParsedDate(
                    ZonedDateTime.parse(value.replaceAll("\\s{2,}", " "), RETRY_AFTER_ASCTIME_DATE));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // A parsed HTTP-date sets a deterministic lower bound: a past date means
    // "you may have retried already" and is floored at 0 (mirrors the integer
    // form, where a small value never shrinks the max(bound, ...) below the
    // bound)
    private static long delayFromParsedDate(ZonedDateTime date) {
        return Math.max(0L, date.toInstant().toEpochMilli() - System.currentTimeMillis());
    }

    // java.time maps a two-digit year to the current century (94 -> 2094);
    // the classic pivot that legacy parsers used (80-99 -> previous century)
    // is applied after parsing, so 94 reads as 1994
    private static ZonedDateTime pivotRfc850Year(ZonedDateTime date) {
        return date.getYear() % 100 > 80 ? date.minusYears(100) : date;
    }

    // Async twin: same hop semantics, chained over sendAsync
    private CompletableFuture<WireResponse> executeAsyncWithRedirects(WireRequest request, int hopsLeft) {
        return sendAsync(request).thenCompose(response -> {
            WireRequest next = nextHop(request, response);
            if (next == null || hopsLeft <= 0) {
                return CompletableFuture.completedFuture(response);
            }
            closeIntermediateStream(response);
            return executeAsyncWithRedirects(next, hopsLeft - 1);
        }).thenApply(response -> {
            logExhaustedRedirects(response);
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
    private CompletableFuture<WireResponse> executeAsyncWithRetries(WireRequest request) {
        int hopsLeft = followRedirectsNumber == null ? 0 : followRedirectsNumber;
        if (!retryPolicy.isEnabled()) {
            return executeAsyncWithRedirects(request, hopsLeft);
        }
        return attemptAsync(request, 1, retryPolicy.maxAttempts(), hopsLeft);
    }

    // One attempt, or the next one after the wait: the exact sync decisions —
    // only an IOException with retryTransport on and a non-last attempt is
    // retried; InterruptedException (fail fast) and any other failure type
    // pass through to exchangeAsync's mapping. The handle step yields the
    // next stage (handle unwraps a returned CompletionStage at runtime); the
    // identity compose only fixes the static type.
    private CompletableFuture<WireResponse> attemptAsync(WireRequest request, int attempt, int maxAttempts, int hopsLeft) {
        boolean last = attempt == maxAttempts;
        return executeAsyncWithRedirects(request, hopsLeft)
                .<CompletableFuture<WireResponse>>handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrapCause(failure);
                        if (!(cause instanceof IOException) || last || !retryPolicy.isRetryTransport()) {
                            return CompletableFuture.<WireResponse>failedFuture(cause);
                        }
                        return scheduleAsyncRetry(request, attempt, maxAttempts, cause.getClass().getSimpleName(), null, hopsLeft);
                    }
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
                                        "decode (" + e.getClass().getSimpleName() + ")", response, hopsLeft);
                            }
                        }
                        return CompletableFuture.<WireResponse>completedFuture(response);
                    }
                    return scheduleAsyncRetry(request, attempt, maxAttempts, "HTTP " + response.statusCode(), response, hopsLeft);
                })
                .thenCompose(Function.identity());
    }

    // The async wait: the sync twin's exact DEBUG line, then the next attempt
    // after the delay on CompletableFuture.delayedExecutor; a zero jitter draw
    // skips the scheduler call, as the sync twin skips Thread.sleep(0)
    private CompletableFuture<WireResponse> scheduleAsyncRetry(WireRequest request, int attempt, int maxAttempts,
                                                               String failure, WireResponse response, int hopsLeft) {
        // ADR-0015 Aspect 2, async twin: the same discard — the failed
        // attempt's stream (if any) is closed before the deferred wait
        closeIntermediateStream(response);
        long delayMillis = retryPolicy.nextDelayMillis(attempt, retryAfterDelayMillis(response), ThreadLocalRandom.current());
        logger.log(System.Logger.Level.DEBUG,
                "Attempt " + attempt + "/" + maxAttempts + " failed: " + failure + " — retrying in " + delayMillis + " ms");
        if (delayMillis <= 0) {
            return attemptAsync(request, attempt + 1, maxAttempts, hopsLeft);
        }
        Executor scheduler = executor != null
                ? CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, executor)
                : CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS);
        return CompletableFuture.runAsync(() -> {
        }, scheduler)
                .thenCompose(ignored -> attemptAsync(request, attempt + 1, maxAttempts, hopsLeft));
    }

    // The next redirect hop, or null if the chain stops here
    // (no @FollowRedirects, a non-redirect status, or a missing Location)
    private WireRequest nextHop(WireRequest request, WireResponse response) {
        if (followRedirectsNumber == null) {
            return null;
        }

        String location = redirectLocation(response);
        if (location == null) {
            return null;
        }

        boolean preserveMethod = response.statusCode() == 307 || response.statusCode() == 308;
        HttpMethod nextMethod = preserveMethod ? request.method() : HttpMethod.GET;

        URI nextUri = request.uri().resolve(location);
        List<Header> nextHeaders = request.headers();
        if (isCrossOrigin(request.uri(), nextUri)) {
            // Drop default client auth headers on cross-origin redirects to prevent credential leakage.
            // Explicit @RequestHeader values are preserved as intentionally set by the caller.
            nextHeaders = dropAuthHeaders(nextHeaders);
            logger.log(System.Logger.Level.INFO,
                    "Cross-origin redirect to " + nextUri + ", auth headers dropped");
        }

        logger.log(System.Logger.Level.INFO,
                "Following redirect (" + response.statusCode() + ") to: " + location);

        return new WireRequest(
                nextMethod,
                nextUri,
                nextHeaders,
                preserveMethod ? request.contentType() : null,
                preserveMethod ? request.body() : null,
                preserveMethod ? request.parts() : null,
                preserveMethod ? request.boundary() : null);
    }

    private void logExhaustedRedirects(WireResponse response) {
        if (followRedirectsNumber != null && redirectLocation(response) != null) {
            logger.log(System.Logger.Level.INFO, "Too many redirects, returning the last response");
        }
    }

    // ADR-0015 Aspect 2: the effective return type picks the body handler —
    // an InputStream return takes the body as a live stream (ofInputStream),
    // everything else materializes bytes as before (ofByteArray). Package-
    // private seam — the unit tests exercise the branch directly.
    WireResponse send(WireRequest request) throws IOException, InterruptedException {
        long start = System.nanoTime();
        if (streamBody) {
            HttpResponse<InputStream> response =
                    httpClient.send(toHttpRequest(request), HttpResponse.BodyHandlers.ofInputStream());
            logPerHop(request, response.statusCode(), start);
            return handleDecompression(toWireResponse(response));
        }
        HttpResponse<byte[]> response =
                httpClient.send(toHttpRequest(request), HttpResponse.BodyHandlers.ofByteArray());
        logPerHop(request, response.statusCode(), start);
        return handleDecompression(toWireResponse(response));
    }

    // Async twin of send(): the same branch, chained over sendAsync
    private CompletableFuture<WireResponse> sendAsync(WireRequest request) {
        long start = System.nanoTime();
        if (streamBody) {
            CompletableFuture<HttpResponse<InputStream>> raw =
                    httpClient.sendAsync(toHttpRequest(request), HttpResponse.BodyHandlers.ofInputStream());
            java.util.function.Function<HttpResponse<InputStream>, WireResponse> mapper = response -> {
                logPerHop(request, response.statusCode(), start);
                try {
                    return handleDecompression(toWireResponse(response));
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            };
            return executor != null ? raw.thenApplyAsync(mapper, executor) : raw.thenApply(mapper);
        }
        CompletableFuture<HttpResponse<byte[]>> raw =
                httpClient.sendAsync(toHttpRequest(request), HttpResponse.BodyHandlers.ofByteArray());
        java.util.function.Function<HttpResponse<byte[]>, WireResponse> mapper = response -> {
            logPerHop(request, response.statusCode(), start);
            try {
                return handleDecompression(toWireResponse(response));
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        };
        return executor != null ? raw.thenApplyAsync(mapper, executor) : raw.thenApply(mapper);
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
    public WireResponse handleDecompression(WireResponse response) throws IOException {
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

        boolean isGzip = "gzip".equals(encoding) || "x-gzip".equals(encoding);
        boolean isDeflate = "deflate".equals(encoding);

        if (!isGzip && !isDeflate) {
            return response;
        }

        List<Header> strippedHeaders = response.headers().stream()
                .filter(h -> h.name() == null || (!h.name().equalsIgnoreCase("Content-Encoding")
                        && !h.name().equalsIgnoreCase("Content-Length")))
                .toList();

        if (response.stream() != null) {
            InputStream decompressedStream = isGzip
                    ? new GZIPInputStream(response.stream())
                    : new InflaterInputStream(response.stream());
            return new WireResponse(response.statusCode(), strippedHeaders, null, decompressedStream);
        }

        byte[] rawBody = response.body();
        if (rawBody == null || rawBody.length == 0) {
            return new WireResponse(response.statusCode(), strippedHeaders, rawBody, null);
        }

        byte[] decompressedBytes = isGzip ? decompressGzip(rawBody) : decompressDeflate(rawBody);
        return new WireResponse(response.statusCode(), strippedHeaders, decompressedBytes, null);
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

    private HttpRequest toHttpRequest(WireRequest request) {
        Duration effectiveTimeout = timeout != null
                ? timeout
                : Duration.ofMillis(moduleInfo.getSocketTimeout());

        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(effectiveTimeout);

        if (httpVersion != null) {
            builder.version(httpVersion);
        }

        if (request.headers() != null) {
            for (Header header : request.headers()) {
                builder.header(header.name(), header.value());
            }
        }
        if (request.contentType() != null) {
            builder.header("Content-Type", request.contentType());
        }

        if (request.method() == HttpMethod.GET && request.body() == null) {
            builder.GET();
        } else {
            builder.method(request.method().name(), bodyPublisher(request));
        }

        return builder.build();
    }

    private void logPerHop(WireRequest request, int statusCode, long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        // per-hop timing; the total (redirect chain included) is logged in the response INFO line
        logger.log(System.Logger.Level.DEBUG,
                request.method() + " " + request.uri() + " -> " + statusCode + " in " + elapsedMs + " ms");
    }

    // The body arrives as byte[] (ofByteArray) or as a live InputStream
    // (ofInputStream) — exactly one slot of the wire model is filled
    @SuppressWarnings("unchecked") // the else branch: the body is the ofByteArray path's byte[]
    private static WireResponse toWireResponse(HttpResponse<?> response) {
        Object body = response.body();
        List<Header> headers = toHeaders(response);
        if (body instanceof InputStream stream) {
            return new WireResponse(response.statusCode(), headers, null, stream);
        }
        return new WireResponse(response.statusCode(), headers, (byte[]) body, null);
    }

    private static List<Header> toHeaders(HttpResponse<?> response) {
        return response.headers().map().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(value -> new Header(entry.getKey(), value)))
                .toList();
    }

    // ADR-0015 Aspect 2: an intermediate hop's stream (a redirect hop, a
    // retried attempt) is single-use — closed before the next send, so the
    // connection is not left half-read; only the final response's stream
    // reaches the caller (or is consumed by the error path). A close
    // failure is logged, not thrown: body hygiene, not the call's fate
    private void closeIntermediateStream(WireResponse response) {
        if (response != null) {
            closeQuietly(response.stream());
        }
    }

    // a fresh publisher per send: stream suppliers are single-use, a redirect re-send must get a live body
    private HttpRequest.BodyPublisher bodyPublisher(WireRequest request) {
        HttpRequest.BodyPublisher publisher;
        if (request.parts() != null) {
            publisher = MultipartBodyPublisher.of(request.boundary(), request.parts());
        } else if (request.body() == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else {
            publisher = HttpRequest.BodyPublishers.ofByteArray(request.body());
        }

        if (uploadProgressListener != null) {
            publisher = new ProgressBodyPublisher(publisher, uploadProgressListener);
        }
        return publisher;
    }

    private String redirectLocation(WireResponse response) {
        int statusCode = response.statusCode();
        if (statusCode != 301 && statusCode != 302 && statusCode != 303 && statusCode != 307 && statusCode != 308) {
            return null;
        }

        return response.headers().stream()
                .filter(h -> h.name() != null && h.name().equalsIgnoreCase("Location"))
                .map(Header::value)
                .findFirst()
                .orElse(null);
    }

    private static boolean isCrossOrigin(URI from, URI to) {
        if (from.getScheme() == null || to.getScheme() == null
                || !from.getScheme().equalsIgnoreCase(to.getScheme())) {
            return true;
        }
        if (from.getHost() == null || to.getHost() == null
                || !from.getHost().equalsIgnoreCase(to.getHost())) {
            return true;
        }
        return effectivePort(from) != effectivePort(to);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private List<Header> dropAuthHeaders(List<Header> headers) {
        if (authHeaderNames == null || authHeaderNames.isEmpty()) {
            return headers;
        }

        return headers.stream()
                .filter(header -> authHeaderNames.stream()
                        .noneMatch(name -> name.equalsIgnoreCase(header.name())))
                .toList();
    }

    private URI createUri() {
        if (requestParams == null || requestParams.isEmpty()) {
            return URI.create(url);
        }

        String query = requestParams.stream()
                .map(param -> {
                    String encodedName = encode(param.name());
                    return param.value() == null
                            ? encodedName
                            : encodedName + "=" + encode(param.value());
                })
                .collect(Collectors.joining("&"));

        return appendQuery(url, query);
    }

    /**
     * Appends a query string to a URL while ensuring RFC 3986 fragment placement.
     * If the URL contains a fragment ({@code #anchor}), the query is inserted before the fragment.
     *
     * @param url the base URL
     * @param query the query string to append
     * @return the combined URI
     */
    static URI appendQuery(String url, String query) {
        if (query == null || query.isEmpty()) {
            return URI.create(url);
        }
        int hashIndex = url.indexOf('#');
        String base = hashIndex >= 0 ? url.substring(0, hashIndex) : url;
        String fragment = hashIndex >= 0 ? url.substring(hashIndex) : "";

        String separator = base.contains("?") ? "&" : "?";
        return URI.create(base + separator + query + fragment);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
        long size = MultipartBodyPublisher.of(request.boundary(), request.parts()).contentLength();
        return size >= 0 ? String.valueOf(size) : "unknown (chunked)";
    }

    private void logResponse(WireResponse response, long elapsedMs) {
        String headers = response.headers().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        logger.log(System.Logger.Level.INFO,
                "Response: HTTP " + response.statusCode() + " in " + elapsedMs + " ms\n" + headers);
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
    public void setLogger(System.Logger logger) {
        this.logger = logger;
    }

    /**
     * The HTTP client the call is dispatched through (sync send or
     * {@code sendAsync}).
     *
     * @param httpClient the client (always set by the builder)
     */
    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * The executor used for asynchronous tasks and retry delays (ADR-0026).
     *
     * @param executor the executor, or {@code null} for default
     */
    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * Returns the executor configured for this template, or {@code null} if default (ADR-0026).
     *
     * @return the executor, or {@code null}
     */
    public Executor getExecutor() {
        return executor;
    }

    /**
     * Sets the request timeout configured for this template (ADR-0027).
     *
     * @param timeout the timeout, or {@code null} to use client default
     */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Returns the request timeout configured for this template, or {@code null} if default (ADR-0027).
     *
     * @return the timeout, or {@code null}
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * The fully resolved request URL: the method path against the
     * environment's base URL, with {@code @PathVariable} placeholders
     * substituted, or the absolute URL declared on the method annotation.
     *
     * @param url the resolved URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * The HTTP method of the call.
     *
     * @param httpMethod the method
     */
    public void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    /**
     * The Content-Type of the request body, or {@code null} for a
     * body-less call. For multipart requests it carries the
     * {@code multipart/form-data} type with the per-call boundary.
     *
     * @param contentType the type, or {@code null}
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * The headers the request carries: the client's auth headers plus the
     * values bound to {@code @RequestHeader} parameters.
     *
     * @param headers the headers
     */
    public void setHeaders(List<Header> headers) {
        this.headers = headers;
    }

    /**
     * The request headers.
     *
     * @return the headers, or {@code null}
     */
    public List<Header> getHeaders() {
        return headers;
    }

    /**
     * The names of the headers that carry credentials — dropped from the
     * request on a cross-origin redirect so they are not leaked to another
     * origin.
     *
     * @param authHeaderNames the header names (may be empty)
     */
    public void setAuthHeaderNames(List<String> authHeaderNames) {
        this.authHeaderNames = authHeaderNames;
    }

    /**
     * The callback that captures the auth details from each response (an
     * {@code @SetAuthDetails} token), or {@code null} when the method
     * declares none.
     *
     * @param authDetailsConsumer the callback, or {@code null}
     */
    public void setAuthDetailsConsumer(Consumer<List<Header>> authDetailsConsumer) {
        this.authDetailsConsumer = authDetailsConsumer;
    }

    /**
     * The query parameters appended to the URL (URL-encoded), or
     * {@code null} for the request types that encode their parameters into
     * the body (multipart, urlencoded).
     *
     * @param requestParams the parameters, or {@code null}
     */
    public void setRequestParams(List<Param> requestParams) {
        this.requestParams = requestParams;
    }

    /**
     * The parts of the multipart body, or {@code null} for non-multipart
     * calls.
     *
     * @param multipartFormParts the parts, or {@code null}
     */
    public void setMultipartFormParts(List<MultipartPart> multipartFormParts) {
        this.multipartFormParts = multipartFormParts;
    }

    /**
     * The boundary of the multipart body, or {@code null} for
     * non-multipart calls.
     *
     * @param multipartBoundary the boundary, or {@code null}
     */
    public void setMultipartBoundary(String multipartBoundary) {
        this.multipartBoundary = multipartBoundary;
    }

    /**
     * The request body bytes, or {@code null} for body-less calls and for
     * multipart calls (whose body is the parts).
     *
     * @param body the body, or {@code null}
     */
    public void setBody(byte[] body) {
        this.body = body;
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
    public void setReturnType(Type returnType) {
        this.returnType = returnType;
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
    public void setAsync(boolean async) {
        this.async = async;
    }

    /**
     * Whether the method's return type is {@code Optional<T>} (or {@code CompletableFuture<Optional<T>>}).
     *
     * @param optional true for Optional-returning methods
     */
    public void setOptional(boolean optional) {
        this.optional = optional;
    }

    /**
     * Whether the request carries conditional headers (e.g. {@code If-None-Match}, {@code If-Modified-Since}).
     *
     * @param conditional true for conditional requests
     */
    public void setConditional(boolean conditional) {
        this.conditional = conditional;
    }

    /**
     * Returns whether the request carries conditional headers.
     *
     * @return true for conditional requests
     */
    public boolean isConditional() {
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
    public void setStreamBody(boolean streamBody) {
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
    public void setOnErrorParser(OnErrorParser onErrorParser) {
        this.onErrorParser = onErrorParser;
    }

    /**
     * The {@code @OnError} parser, or {@code null} when none is configured.
     *
     * @return the parser
     */
    public OnErrorParser getOnErrorParser() {
        return onErrorParser;
    }

    /**
     * The status codes the call accepts, or {@code null} for the default
     * (any 2xx).
     *
     * @param expectStatuses the accepted statuses, or {@code null}
     */
    public void setExpectStatuses(List<Integer> expectStatuses) {
        this.expectStatuses = expectStatuses;
    }

    /**
     * The maximum number of redirect hops the call follows, or
     * {@code null} to follow none.
     *
     * @param followRedirectsNumber the hop count, or {@code null}
     */
    public void setFollowRedirectsNumber(Integer followRedirectsNumber) {
        this.followRedirectsNumber = followRedirectsNumber;
    }

    /**
     * The HTTP version of the request, or {@code null} for the client's
     * default.
     *
     * @param httpVersion the version, or {@code null}
     */
    public void setHttpVersion(HttpClient.Version httpVersion) {
        this.httpVersion = httpVersion;
    }

    /**
     * The retry policy of the call — the method's {@code @Retry} policy
     * when the method declares one, otherwise the client-level policy.
     *
     * @param retryPolicy the policy
     */
    public void setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    /**
     * The module info the call reads: the socket timeout applied to every
     * request.
     *
     * @param moduleInfo the module info (always set by the builder)
     */
    public void setModuleInfo(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;
    }

    /**
     * The JSON codec that decodes the response payload and the
     * {@code @OnError} error objects.
     *
     * @param jsonCodec the codec (always set by the builder)
     */
    public void setJsonCodec(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    /**
     * Sets the request interceptors for this call.
     *
     * @param requestInterceptors the request interceptors
     */
    public void setRequestInterceptors(List<RequestInterceptor> requestInterceptors) {
        this.requestInterceptors = requestInterceptors != null ? List.copyOf(requestInterceptors) : List.of();
    }

    /**
     * The configured request interceptors.
     *
     * @return unmodifiable list of request interceptors, never {@code null}
     */
    public List<RequestInterceptor> getRequestInterceptors() {
        return requestInterceptors;
    }

    /**
     * Sets the response interceptors for this call.
     *
     * @param responseInterceptors the response interceptors
     */
    public void setResponseInterceptors(List<ResponseInterceptor> responseInterceptors) {
        this.responseInterceptors = responseInterceptors != null ? List.copyOf(responseInterceptors) : List.of();
    }

    /**
     * The configured response interceptors.
     *
     * @return unmodifiable list of response interceptors, never {@code null}
     */
    public List<ResponseInterceptor> getResponseInterceptors() {
        return responseInterceptors;
    }

    /**
     * Sets the controller interface class being invoked.
     *
     * @param controllerClass the controller interface class
     */
    public void setControllerClass(Class<?> controllerClass) {
        this.controllerClass = controllerClass;
    }

    /**
     * The controller interface class being invoked.
     *
     * @return the controller class
     */
    public Class<?> getControllerClass() {
        return controllerClass;
    }

    /**
     * Sets the controller method being invoked.
     *
     * @param method the method being invoked
     */
    public void setMethod(Method method) {
        this.method = method;
    }

    /**
     * The controller method being invoked.
     *
     * @return the method
     */
    public Method getMethod() {
        return method;
    }

    /**
     * Sets the invocation arguments.
     *
     * @param args the invocation arguments
     */
    public void setArgs(Object[] args) {
        this.args = args;
    }

    /**
     * The invocation arguments.
     *
     * @return the invocation arguments
     */
    public Object[] getArgs() {
        return args;
    }

    /**
     * Sets the response headers holder (ADR-0022).
     *
     * @param responseHeadersHolder the holder to populate with response metadata
     */
    public void setResponseHeadersHolder(ResponseHeaders responseHeadersHolder) {
        this.responseHeadersHolder = responseHeadersHolder;
    }

    /**
     * The response headers holder (ADR-0022).
     *
     * @return the holder, or {@code null}
     */
    public ResponseHeaders getResponseHeadersHolder() {
        return responseHeadersHolder;
    }

    /**
     * Sets the upload progress listener (ADR-0030).
     *
     * @param uploadProgressListener the listener, or {@code null}
     */
    public void setUploadProgressListener(UploadProgressListener uploadProgressListener) {
        this.uploadProgressListener = uploadProgressListener;
    }

    /**
     * The upload progress listener (ADR-0030).
     *
     * @return the listener, or {@code null}
     */
    public UploadProgressListener getUploadProgressListener() {
        return uploadProgressListener;
    }

    private void handleOnError(int statusCode, String payload, List<Header> responseHeaders) {
        Class<?> targetErrorClass = onErrorParser.getErrorClass(statusCode);
        Object errorObject = (payload != null && !payload.isEmpty() && targetErrorClass != Void.class)
                ? jsonCodec.deserialize(payload, targetErrorClass)
                : null;

        if (Throwable.class.isAssignableFrom(targetErrorClass)) {
            if (errorObject instanceof RuntimeException re) {
                throw re;
            } else if (errorObject instanceof Error err) {
                throw err;
            } else if (errorObject instanceof Throwable t) {
                throw new RuntimeException(t);
            } else {
                Throwable instance = instantiateThrowable(targetErrorClass, statusCode, responseHeaders);
                if (instance instanceof RuntimeException re) {
                    throw re;
                } else if (instance instanceof Error err) {
                    throw err;
                } else {
                    throw new RuntimeException(instance);
                }
            }
        }

        throw new HandledException(errorObject, statusCode, responseHeaders);
    }

    private static Throwable instantiateThrowable(Class<?> clazz, int statusCode, List<Header> responseHeaders) {
        try {
            var ctor = clazz.getConstructor(Object.class, int.class, List.class);
            return (Throwable) ctor.newInstance(null, statusCode, responseHeaders);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            var ctor = clazz.getConstructor(String.class, int.class);
            return (Throwable) ctor.newInstance("HTTP " + statusCode, statusCode);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            var ctor = clazz.getConstructor(String.class);
            return (Throwable) ctor.newInstance("HTTP " + statusCode);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            var ctor = clazz.getConstructor();
            return (Throwable) ctor.newInstance();
        } catch (ReflectiveOperationException ignored) {
        }
        return new HandledException(null, statusCode, responseHeaders);
    }

    private static UnexpectedStatusException createUnexpectedStatusException(
            int statusCode, String rawBody, List<Header> responseHeaders) {
        if (statusCode >= 400 && statusCode < 500) {
            return new ClientErrorException(statusCode, rawBody, responseHeaders);
        }
        if (statusCode >= 500 && statusCode < 600) {
            return new ServerErrorException(statusCode, rawBody, responseHeaders);
        }
        return new UnexpectedStatusException(statusCode, rawBody, responseHeaders);
    }
}
