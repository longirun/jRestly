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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
 */
public class HttpTemplate {
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

    /**
     * Executes the call and returns the decoded result. For
     * {@code CompletableFuture<T>} return types the returned value is the
     * future itself; see {@link #exchangeAsync(WireRequest, long)}.
     */
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
            WireResponse response = executeWithRedirects(request);
            return processResponse(request, response, start);
        } catch (IOException e) {
            logger.log(System.Logger.Level.ERROR,
                    "Request failed: " + request.method() + " " + request.uri(), e);

            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // Async twin of exchange(): the same tail via processResponse, with
    // @OnError / status-check exceptions kept as-is and transport failures
    // mapped to the exceptions a synchronous call would throw (both become
    // the cause of the future's exceptional completion)
    private CompletableFuture<Object> exchangeAsync(WireRequest request, long startNanos) {
        int hopsLeft = followRedirectsNumber == null ? 0 : followRedirectsNumber;
        return executeAsyncWithRedirects(request, hopsLeft)
                .thenApply(response -> processResponse(request, response, startNanos))
                .exceptionally(ex -> {
                    Throwable cause = unwrapCause(ex);
                    // the future completes exceptionally with this exception as the
                    // CompletionException cause of join() / ExecutionException cause of get()
                    if (cause instanceof HandledException) {
                        throw (RuntimeException) cause; // UnexpectedStatusException included
                    }
                    logger.log(System.Logger.Level.ERROR,
                            "Request failed: " + request.method() + " " + request.uri(), cause);
                    if (cause instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new RuntimeException(cause);
                });
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
    private <T> T processResponse(WireRequest request, WireResponse response, long startNanos) {
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
        if (Void.class.equals(returnType)) {
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
                return CompletableFuture.completedFuture(response);
            }
            return executeAsyncWithRedirects(next, hopsLeft - 1);
        }).thenApply(response -> {
            logExhaustedRedirects(response);
            return response;
        });
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
            builder.header("Content-Type", request.contentType());
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

        if (Arrays.asList(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE).contains(request.method())) {
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

    public void setModuleInfo(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;
    }

    public void setJsonCodec(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }
}
