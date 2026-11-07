package ru.jrestly.http;

import ru.jrestly.ModuleInfo;
import ru.jrestly.json.JsonCodec;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One declarative call: builds the wire request, dispatches it (sync or,
 * for {@code CompletableFuture<T>} returns, async), and decodes the response.
 */
public class HttpTemplate {
    private static final Set<HttpMethod> BODY_LOGGING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.PUT);

    // RFC 9110 obsolete date formats for Retry-After
    private static final DateTimeFormatter RFC850_DATE =
            DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH);
    private static final Set<String> RFC850_WEEKDAYS =
            Set.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");
    private static final DateTimeFormatter ASCTIME_DATE =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);
    private static final Pattern ASCTIME_SPACE_RUNS = Pattern.compile("\\s{2,}");

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
    private OnErrorParser onErrorParser;
    private List<Integer> expectStatuses;
    private Integer followRedirectsNumber;
    private HttpClient.Version httpVersion;
    private RetryPolicy retryPolicy = RetryPolicy.off();

    @SuppressWarnings("unchecked")
    public <T> T exchange() {
        WireRequest request = new WireRequest(httpMethod, createUri(), headers, contentType, body, multipartFormParts, multipartBoundary);

        logRequest(request);

        if (async) {
            long start = System.nanoTime();
            return (T) exchangeAsync(request, start);
        }

        try {
            long start = System.nanoTime();
            WireResponse response = executeWithRetries(request);
            return processResponse(response, start);
        } catch (IOException e) {
            logger.log(System.Logger.Level.ERROR,
                    "Request failed: " + request.method() + " " + request.uri(), e);

            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // async twin of exchange(): same tail, transport faults mapped like sync
    private CompletableFuture<Object> exchangeAsync(WireRequest request, long startNanos) {
        return executeAsyncWithRetries(request)
                .thenApply(response -> processResponse(response, startNanos))
                .exceptionally(ex -> {
                    Throwable cause = unwrapCause(ex);
                    if (cause instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    // a null-cause CompletionException survives unwrapCause (hence the guard)
                    switch (cause) {
                        case RuntimeException re when !(re instanceof CompletionException) -> throw re;
                        case Error err -> throw err;
                        case null -> throw new NullPointerException("cause is null");
                        default -> {
                            logger.log(System.Logger.Level.ERROR,
                                    "Request failed: " + request.method() + " " + request.uri(), cause);
                            throw new RuntimeException(cause);
                        }
                    }
                });
    }

    private static Throwable unwrapCause(Throwable throwable) {
        while (throwable instanceof CompletionException completion && completion.getCause() != null) {
            throwable = completion.getCause();
        }
        return throwable;
    }

    // shared tail of both exchange paths: auth capture, logging, @OnError, status check, decoding
    @SuppressWarnings("unchecked")
    private <T> T processResponse(WireResponse response, long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        List<Header> responseHeaders = response.headers();
        if (authDetailsConsumer != null) {
            authDetailsConsumer.accept(responseHeaders);
        }

        logResponse(response, elapsedMs);

        byte[] bytes = response.body();
        int statusCode = response.statusCode();
        String payload = (bytes != null && bytes.length > 0) ? new String(bytes, StandardCharsets.UTF_8) : null;

        if (onErrorParser != null && onErrorParser.canParse(statusCode)) {
            Object errorObject = (payload != null)
                    ? jsonCodec.deserialize(payload, onErrorParser.getErrorClass())
                    : null;
            throw new HandledException(errorObject, statusCode, responseHeaders);
        }

        if (!isExpectedStatus(statusCode)) {
            throw new UnexpectedStatusException(statusCode, payload == null ? "" : payload, responseHeaders);
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
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            return null;
        }

        return jsonCodec.deserialize(payload, returnType);
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
            response = send(request);
        }

        logExhaustedRedirects(response);
        return response;
    }

    // Async twin: same hop semantics, chained over sendAsync
    private CompletableFuture<WireResponse> executeAsyncWithRedirects(WireRequest request, int hopsLeft) {
        return sendAsync(request).thenCompose(response -> {
            WireRequest next = nextHop(request, response);
            if (next == null || hopsLeft <= 0) {
                logExhaustedRedirects(response);
                return CompletableFuture.completedFuture(response);
            }
            return executeAsyncWithRedirects(next, hopsLeft - 1);
        });
    }

    private WireResponse executeWithRetries(WireRequest request) throws IOException, InterruptedException {
        RetryPolicy policy = retryPolicy;
        int maxAttempts = policy.getMaxAttempts();

        for (int attempt = 1; ; attempt++) {
            boolean last = attempt == maxAttempts;
            WireResponse response;
            try {
                response = executeWithRedirects(request);
            } catch (IOException e) {
                if (last || !policy.isRetryTransport()) {
                    throw e;
                }
                scheduleRetry(attempt, maxAttempts, e.getClass().getSimpleName(), null, policy);
                continue;
            }

            if (!last && isStatusRetry(policy, response)) {
                scheduleRetry(attempt, maxAttempts, "HTTP " + response.statusCode(), response, policy);
                continue;
            }
            if (!last && policy.isRetryDecodeFailures()) {
                try {
                    probePayloadDecode(response);
                } catch (RuntimeException e) {
                    scheduleRetry(attempt, maxAttempts,
                            "decode (" + e.getClass().getSimpleName() + ")", response, policy);
                    continue;
                }
            }
            return response;
        }
    }

    private long retryDelayMillis(int attempt, int maxAttempts, String failure, WireResponse response, RetryPolicy policy) {
        long delayMillis = policy.nextDelayMillis(attempt, retryAfterDelayMillis(response), ThreadLocalRandom.current());
        logger.log(System.Logger.Level.DEBUG,
                "Attempt " + attempt + "/" + maxAttempts + " failed: " + failure
                        + " — retrying in " + delayMillis + " ms");
        return delayMillis;
    }

    private void scheduleRetry(int attempt, int maxAttempts, String failure, WireResponse response, RetryPolicy policy)
            throws InterruptedException {
        long delayMillis = retryDelayMillis(attempt, maxAttempts, failure, response, policy);
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
    }

    private CompletableFuture<WireResponse> executeAsyncWithRetries(WireRequest request) {
        return attemptAsync(request, 1, retryPolicy.getMaxAttempts());
    }

    private CompletableFuture<WireResponse> attemptAsync(WireRequest request, int attempt, int maxAttempts) {
        RetryPolicy policy = retryPolicy;
        boolean last = attempt == maxAttempts;
        int hopsLeft = followRedirectsNumber == null ? 0 : followRedirectsNumber;

        return executeAsyncWithRedirects(request, hopsLeft)
                .<CompletableFuture<WireResponse>>handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrapCause(failure);
                        if (cause instanceof InterruptedException) {
                            return CompletableFuture.failedFuture(failure);
                        }
                        if (cause instanceof IOException && !last && policy.isRetryTransport()) {
                            return scheduleAsyncRetry(request, attempt, maxAttempts, cause.getClass().getSimpleName(), null, policy);
                        }
                        return CompletableFuture.failedFuture(failure);
                    }

                    if (!last && isStatusRetry(policy, response)) {
                        return scheduleAsyncRetry(request, attempt, maxAttempts, "HTTP " + response.statusCode(), response, policy);
                    }
                    if (!last && policy.isRetryDecodeFailures()) {
                        try {
                            probePayloadDecode(response);
                        } catch (RuntimeException e) {
                            return scheduleAsyncRetry(request, attempt, maxAttempts,
                                    "decode (" + e.getClass().getSimpleName() + ")", response, policy);
                        }
                    }
                    return CompletableFuture.completedFuture(response);
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<WireResponse> scheduleAsyncRetry(
            WireRequest request, int attempt, int maxAttempts, String failure, WireResponse response, RetryPolicy policy) {
        long delayMillis = retryDelayMillis(attempt, maxAttempts, failure, response, policy);
        if (delayMillis <= 0) {
            return attemptAsync(request, attempt + 1, maxAttempts);
        }
        return CompletableFuture.runAsync(() -> { },
                        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> attemptAsync(request, attempt + 1, maxAttempts));
    }

    private boolean isStatusRetry(RetryPolicy policy, WireResponse response) {
        int status = response.statusCode();
        if (onErrorParser != null && onErrorParser.canParse(status)) {
            if (!policy.isRetryOnErrorStatuses()) {
                return false;
            }
        } else if (isExpectedStatus(status)) {
            return false;
        }
        if (!policy.getRetryableStatuses().contains(status)) {
            return false;
        }
        return policy.isRetryNonIdempotent() || !isNonIdempotent(httpMethod);
    }

    private static boolean isNonIdempotent(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PATCH;
    }

    // Probes if the payload can be deserialized; throws codec exception if invalid
    void probePayloadDecode(WireResponse response) {
        int status = response.statusCode();
        if (onErrorParser != null && onErrorParser.canParse(status)) {
            return;
        }
        if (!isExpectedStatus(status)) {
            return;
        }

        byte[] bytes = response.body();
        String payload = (bytes != null && bytes.length > 0) ? new String(bytes, StandardCharsets.UTF_8) : null;
        if (payload == null) {
            return;
        }
        if (String.class.equals(returnType)) {
            return;
        }
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            return;
        }

        jsonCodec.deserialize(payload, returnType);
    }

    // Parses Retry-After header as delta-seconds or HTTP-date (IMF-fixdate, RFC 850, asctime)
    Long retryAfterDelayMillis(WireResponse response) {
        if (response == null) {
            return null;
        }
        String raw = response.headers().stream()
                .filter(h -> h.name() != null && h.name().equalsIgnoreCase("Retry-After"))
                .map(Header::value)
                .findFirst()
                .orElse(null);
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value) * 1_000L;
        } catch (NumberFormatException ignored) {
        }
        try {
            return delayFromParsedDate(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
        } catch (DateTimeParseException ignored) {
        }
        String rfc850Body = value;
        int comma = value.indexOf(',');
        if (comma > 0 && RFC850_WEEKDAYS.contains(value.substring(0, comma))) {
            rfc850Body = value.substring(comma + 1).trim();
        }
        try {
            return delayFromParsedDate(pivotRfc850Year(ZonedDateTime.parse(rfc850Body, RFC850_DATE)));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return delayFromParsedDate(
                    ZonedDateTime.parse(ASCTIME_SPACE_RUNS.matcher(value).replaceAll(" "), ASCTIME_DATE));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static long delayFromParsedDate(ZonedDateTime date) {
        return Math.max(0L, date.toInstant().toEpochMilli() - System.currentTimeMillis());
    }

    // 2-digit year pivot: 80-99 maps to 1980-1999
    private static ZonedDateTime pivotRfc850Year(ZonedDateTime date) {
        return date.getYear() % 100 >= 80 ? date.minusYears(100) : date;
    }

    // next redirect hop, or null when the chain stops (no @FollowRedirects, non-redirect status, missing Location)
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

    private WireResponse send(WireRequest request) throws IOException, InterruptedException {
        long start = System.nanoTime();
        HttpResponse<byte[]> response = httpClient.send(toHttpRequest(request), HttpResponse.BodyHandlers.ofByteArray());
        logPerHop(request, response, start);
        return toWireResponse(response);
    }

    private CompletableFuture<WireResponse> sendAsync(WireRequest request) {
        long start = System.nanoTime();
        return httpClient.sendAsync(toHttpRequest(request), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    logPerHop(request, response, start);
                    return toWireResponse(response);
                });
    }

    private HttpRequest toHttpRequest(WireRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(Duration.ofMillis(moduleInfo.getSocketTimeout()));

        if (httpVersion != null) {
            builder.version(httpVersion);
        }

        if (request.headers() != null) {
            for (Header header : request.headers()) {
                builder.header(header.name(), header.value());
            }
        }
        if (request.contentType() != null) {
            builder.setHeader("Content-Type", request.contentType());
        }

        if (request.method() == HttpMethod.GET && request.body() == null) {
            builder.GET();
        } else {
            builder.method(request.method().name(), bodyPublisher(request));
        }

        return builder.build();
    }

    private void logPerHop(WireRequest request, HttpResponse<byte[]> response, long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        // per-hop timing; the total (redirect chain included) is logged in the response INFO line
        logger.log(System.Logger.Level.DEBUG,
                request.method() + " " + request.uri() + " -> " + response.statusCode() + " in " + elapsedMs + " ms");
    }

    private WireResponse toWireResponse(HttpResponse<byte[]> response) {
        List<Header> headers = response.headers().map().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(value -> new Header(entry.getKey(), value)))
                .toList();

        return new WireResponse(response.statusCode(), headers, response.body());
    }

    // a fresh publisher per send: stream suppliers are single-use, a redirect re-send must get a live body
    private HttpRequest.BodyPublisher bodyPublisher(WireRequest request) {
        if (request.parts() != null) {
            return MultipartBodyPublisher.of(request.boundary(), request.parts());
        }
        return request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());
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
                .map(param -> encode(param.name()) + "=" + encode(param.value()))
                .collect(Collectors.joining("&"));

        return URI.create(url + (url.contains("?") ? "&" : "?") + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

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
        return statusCode >= 200 && statusCode < 300;
    }

    public void setLogger(System.Logger logger) {
        this.logger = logger;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void setHeaders(List<Header> headers) {
        this.headers = headers;
    }

    public void setAuthHeaderNames(List<String> authHeaderNames) {
        this.authHeaderNames = authHeaderNames;
    }

    public void setAuthDetailsConsumer(Consumer<List<Header>> authDetailsConsumer) {
        this.authDetailsConsumer = authDetailsConsumer;
    }

    public void setRequestParams(List<Param> requestParams) {
        this.requestParams = requestParams;
    }

    public void setMultipartFormParts(List<MultipartPart> multipartFormParts) {
        this.multipartFormParts = multipartFormParts;
    }

    public void setMultipartBoundary(String multipartBoundary) {
        this.multipartBoundary = multipartBoundary;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public void setReturnType(Type returnType) {
        this.returnType = returnType;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public void setOnErrorParser(OnErrorParser onErrorParser) {
        this.onErrorParser = onErrorParser;
    }

    public void setExpectStatuses(List<Integer> expectStatuses) {
        this.expectStatuses = expectStatuses;
    }

    public void setFollowRedirectsNumber(Integer followRedirectsNumber) {
        this.followRedirectsNumber = followRedirectsNumber;
    }

    public void setHttpVersion(HttpClient.Version httpVersion) {
        this.httpVersion = httpVersion;
    }

    public void setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.off();
    }

    public void setModuleInfo(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;
    }

    public void setJsonCodec(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }
}
