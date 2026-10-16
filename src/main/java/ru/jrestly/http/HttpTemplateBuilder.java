package ru.jrestly.http;

import ru.jrestly.AuthProvider;
import ru.jrestly.ModuleInfo;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.compression.ContentCompressor;
import ru.jrestly.compression.ContentCompressors;
import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.FollowRedirects;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Patch;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Put;
import ru.jrestly.annotation.QueryCollectionFormat;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.SetAuthDetails;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.annotation.Trace;
import ru.jrestly.annotation.Order;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.annotation.WithMetrics;
import ru.jrestly.tracing.TraceContext;
import ru.jrestly.tracing.TracePropagator;
import ru.jrestly.tracing.TraceRequestContext;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.metrics.ClientMetricsListener;
import ru.jrestly.resilience.CircuitBreaker;
import ru.jrestly.resilience.CircuitBreakerConfig;
import ru.jrestly.resilience.CircuitBreakerRegistry;
import ru.jrestly.resilience.RateLimiter;
import ru.jrestly.resilience.RateLimiterConfig;
import ru.jrestly.resilience.RateLimiterRegistry;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.HttpCookie;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Builds and executes the HTTP request pipeline for a single controller-method
 * invocation.
 *
 * <p>Instantiated on every proxy dispatch (see
 * {@link ru.jrestly.AbstractHttpClient#createController}) from the
 * module's configuration, the client's {@link HttpClient}, the invoked
 * method, its arguments, and the controller interface; discarded after
 * {@link #execute()} — the stateful engine lives in the produced template.
 */
public class HttpTemplateBuilder {

    private static final CircuitBreakerRegistry DEFAULT_CIRCUIT_BREAKER_REGISTRY = CircuitBreakerRegistry.ofDefaults();
    private static final RateLimiterRegistry DEFAULT_RATE_LIMITER_REGISTRY = RateLimiterRegistry.ofDefaults();

    private final ModuleInfo moduleInfo;
    private final HttpClient httpClient;
    private final ru.jrestly.engine.HttpEngine httpEngine;
    private final Method method;
    private final Object[] args;
    private final Class<?> controller;

    private RequestMeta requestMeta;
    private MethodMetadata methodMetadata;
    private Executor executor;
    private Map<Class<?>, Object> interceptorCache;
    private System.Logger customLogger;

    /**
     * Creates a builder for the given invocation using a pluggable {@link ru.jrestly.engine.HttpEngine} (ADR-0052).
     *
     * @param moduleInfo the module configuration (environment, codec, auth provider, retry policy)
     * @param httpEngine the client's transport engine
     * @param method the invoked controller method; its verb annotation drives the request
     * @param args the invocation arguments
     * @param controller the proxied controller interface; names the request logger
     */
    public HttpTemplateBuilder(ModuleInfo moduleInfo, ru.jrestly.engine.HttpEngine httpEngine, Method method, Object[] args, Class<?> controller) {
        this.moduleInfo = moduleInfo;
        this.httpEngine = httpEngine;
        this.httpClient = (httpEngine instanceof ru.jrestly.engine.JdkHttpEngine jdk) ? jdk.getHttpClient() : null;
        this.method = method;
        this.args = args;
        this.controller = controller;
    }

    /**
     * Creates a builder for the given invocation with a JDK {@link HttpClient}.
     *
     * @param moduleInfo the module configuration (environment, codec, auth provider, retry policy)
     * @param httpClient the client's preconfigured {@link HttpClient}
     * @param method the invoked controller method; its verb annotation drives the request
     * @param args the invocation arguments
     * @param controller the proxied controller interface; names the request logger
     */
    public HttpTemplateBuilder(ModuleInfo moduleInfo, HttpClient httpClient, Method method, Object[] args, Class<?> controller) {
        this(moduleInfo, httpClient != null ? ru.jrestly.engine.JdkHttpEngine.from(httpClient) : null, method, args, controller);
    }

    /**
     * Sets the custom executor for asynchronous execution and retry delays (ADR-0026).
     *
     * @param executor the executor
     * @return this builder, for chaining
     */
    public HttpTemplateBuilder executor(Executor executor) {
        this.executor = executor;
        return this;
    }

    /**
     * Sets the cache for declarative interceptor instances (ADR-0029).
     *
     * @param interceptorCache the cache map
     * @return this builder, for chaining
     */
    public HttpTemplateBuilder interceptorCache(Map<Class<?>, Object> interceptorCache) {
        this.interceptorCache = interceptorCache;
        return this;
    }

    /**
     * Sets the custom logger for request and response logging.
     *
     * @param logger the custom logger
     * @return this builder, for chaining
     */
    public HttpTemplateBuilder logger(System.Logger logger) {
        this.customLogger = logger;
        return this;
    }

    /**
     * Builds and executes the declarative HTTP request.
     *
     * @param <T> the expected response payload type
     * @return the decoded response payload or future
     */
    public <T> T execute() {
        return build().exchange();
    }

    /**
     * Assembles the template for the invocation this builder was created
     * for.
     *
     * <p>The verb annotation fixes the HTTP method, the path, and the
     * request type (an unannotated method is a controller-validation error
     * at proxy creation; the {@link UnsupportedOperationException} thrown
     * here is the backstop). {@code @PathVariable} arguments are
     * URL-encoded into the path; a path not starting with {@code http} is
     * prefixed with the environment's base URL. Headers combine the
     * module's auth headers with the {@code @RequestHeader} arguments.
     * {@code @RequestParam} and {@code @RequestDefaultParam} arguments
     * become query parameters — for form and multipart requests they go
     * into the body instead.
     *
     * <p>The body follows the request type: JSON — the {@code @RequestBody}
     * argument serialized by the module's codec (a {@code String} argument
     * is sent as is; a {@code GET} never carries a body); urlencoded — the
     * request parameters URL-encoded into the body; multipart —
     * {@code @MultipartFormFile} and {@code @RequestParam} parts under a
     * generated {@code jrestly-<uuid>} boundary. A
     * {@code CompletableFuture<T>} return type marks the call async with
     * {@code T} as the wire contract. The {@code @OnError} parser is
     * installed only when the annotation is present; {@code @ExpectStatus},
     * {@code @FollowRedirects}, and {@code @HttpVersion} are carried
     * through when present; a per-method {@code @Retry} replaces the
     * client's retry policy entirely (ADR-0008).
     *
     * @return the assembled template, ready for execution
     */
    HttpTemplate build() {
        methodMetadata = MethodMetadata.of(method, controller);
        requestMeta = methodMetadata.requestMeta();

        HttpTemplate result = new HttpTemplate();
        RequestType requestType = requestMeta.requestType();

        result.setLogger(customLogger != null ? customLogger : System.getLogger(controller.getName()));
        result.setModuleInfo(moduleInfo);
        result.setJsonCodec(moduleInfo.getJsonCodec());
        result.setCodecRegistry(moduleInfo.getCodecRegistry());

        if (httpEngine != null) {
            result.setHttpEngine(httpEngine);
        } else if (httpClient != null) {
            result.setHttpClient(httpClient);
        }
        result.setUrl(createUrl());
        result.setHttpMethod(requestMeta.httpMethod());

        List<Header> headers = createHeaders();

        String effectiveContentType;
        String declarativeContentType = methodMetadata.methodContentType() != null
                ? methodMetadata.methodContentType()
                : methodMetadata.interfaceContentType();

        Header explicitContentTypeHeader = null;
        if (methodMetadata.hasExplicitContentTypeInStaticHeaders() || methodMetadata.hasRequestHeaders()) {
            for (Header h : headers) {
                if ("Content-Type".equalsIgnoreCase(h.name())) {
                    explicitContentTypeHeader = h;
                    break;
                }
            }
        }
        boolean isContentTypeExplicit = explicitContentTypeHeader != null || declarativeContentType != null;
        if (explicitContentTypeHeader != null) {
            effectiveContentType = explicitContentTypeHeader.value();
            headers.remove(explicitContentTypeHeader);
        } else if (declarativeContentType != null) {
            effectiveContentType = declarativeContentType;
        } else {
            effectiveContentType = requestType.mime();
        }

        result.setHeaders(headers);
        result.setConditional(isConditionalRequest(headers));
        // Header names to drop on cross-origin redirects
        List<Header> authHeaders = moduleInfo.getAuthProvider().getAuthHeaders();
        if (authHeaders == null || authHeaders.isEmpty()) {
            result.setAuthHeaderNames(List.of());
        } else {
            List<String> authNames = new ArrayList<>(authHeaders.size());
            for (int i = 0, size = authHeaders.size(); i < size; i++) {
                authNames.add(authHeaders.get(i).name());
            }
            result.setAuthHeaderNames(authNames);
        }
        result.setAuthDetailsConsumer(getAuthDetailsConsumer());
        if (!RequestType.MULTIPART_FORM_DATA.equals(requestType) && !RequestType.APPLICATION_FORM_URLENCODED.equals(requestType)) {
            if (!methodMetadata.hasRequestParams() && methodMetadata.staticDefaultParams().isEmpty()) {
                result.setRequestParams(List.of());
            } else {
                result.setRequestParams(createRequestParams());
            }
        }

        byte[] body = null;
        Supplier<? extends HttpRequest.BodyPublisher> bodyPublisherSupplier = null;
        List<MultipartPart> parts = null;
        if (RequestType.APPLICATION_JSON.equals(requestType)) {
            BodyPayload payload = createJsonBodyPayload(effectiveContentType, isContentTypeExplicit);
            body = payload.bytes();
            bodyPublisherSupplier = payload.publisherSupplier();
            effectiveContentType = payload.effectiveContentType();
            result.setContentType(effectiveContentType);
        } else if (RequestType.APPLICATION_FORM_URLENCODED.equals(requestType)) {
            body = createUrlEncodedBody();
            result.setContentType(effectiveContentType);
        } else if (RequestType.MULTIPART_FORM_DATA.equals(requestType)) {
            parts = createMultipartParts();

            String boundary = "jrestly-" + UUID.randomUUID().toString().replace("-", "");
            if (effectiveContentType != null && effectiveContentType.toLowerCase(Locale.ROOT).contains("multipart/")) {
                if (!effectiveContentType.toLowerCase(Locale.ROOT).contains("boundary=")) {
                    effectiveContentType = effectiveContentType + "; boundary=" + boundary;
                }
            } else {
                effectiveContentType = "multipart/form-data; boundary=" + boundary;
            }
            result.setContentType(effectiveContentType);
            result.setMultipartBoundary(boundary);
        }

        // ADR-0041: Request body compression
        if (body != null && body.length > 0) {
            EffectiveCompression compression = resolveCompression();
            if (compression.enabled() && body.length >= compression.minSizeBytes()) {
                boolean hasContentEncoding = headers.stream()
                        .anyMatch(h -> h.name() != null && h.name().equalsIgnoreCase("Content-Encoding"));
                if (!hasContentEncoding) {
                    ContentCompressor compressor = resolveCompressor(compression.algorithm());
                    if (compressor != null) {
                        try {
                            body = compressor.compress(body);
                            headers.add(new Header("Content-Encoding", compressor.encoding()));
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to compress request body with " + compressor.encoding(), e);
                        }
                    }
                }
            }
        }

        result.setBody(body);
        result.setBodyPublisherSupplier(bodyPublisherSupplier);
        result.setMultipartFormParts(parts);

        MethodMetadata.ReturnTypeInfo rt = methodMetadata.returnTypeInfo();
        result.setAsync(rt.async());
        result.setOptional(rt.optional());
        result.setStreamBody(rt.streamBody());
        result.setReturnType(rt.effectiveReturnType());

        result.setOnErrorParser(createOnErrorParser());
        result.setExpectStatuses(createExpectStatuses());
        result.setFollowRedirectsNumber(getFollowRedirectsNumber());
        result.setHttpVersion(getHttpVersion());
        result.setRetryPolicy(resolveRetryPolicy());
        ResolvedInterceptors resolvedInterceptors = resolveInterceptors();
        result.setRequestInterceptors(resolvedInterceptors.requestInterceptors());
        result.setResponseInterceptors(resolvedInterceptors.responseInterceptors());
        result.setResponseHeadersHolder(resolveResponseHeadersHolder());
        result.setUploadProgressListener(resolveUploadProgressListener());
        result.setDownloadProgressListener(resolveDownloadProgressListener());
        result.setMetricsListener(resolveMetricsListener());
        result.setExecutor(executor != null ? executor : moduleInfo.getExecutor());
        result.setTimeout(resolveTimeout());
        result.setCircuitBreaker(resolveCircuitBreaker());
        result.setRateLimiter(resolveRateLimiter());
        result.setTokenManager(moduleInfo.getTokenManager());
        boolean retryAuth = moduleInfo.isRetryOnUnauthorized() && !methodMetadata.hasAuthAnnotation();
        result.setRetryOnUnauthorized(retryAuth);
        result.setControllerClass(controller);
        result.setMethod(method);
        result.setArgs(args);

        Duration effectiveTimeout = result.getTimeout() != null
                ? result.getTimeout()
                : Duration.ofMillis(moduleInfo.getSocketTimeout());
        ru.jrestly.engine.HttpEngineOptions options = new ru.jrestly.engine.HttpEngineOptions(
                effectiveTimeout,
                getHttpVersion(),
                rt.streamBody(),
                result.getUploadProgressListener(),
                result.getDownloadProgressListener()
        );
        result.setEngineOptions(options);

        return result;
    }

    private ResponseHeaders resolveResponseHeadersHolder() {
        if (methodMetadata != null && methodMetadata.responseHeadersParamIndex() >= 0 && args != null
                && methodMetadata.responseHeadersParamIndex() < args.length) {
            if (args[methodMetadata.responseHeadersParamIndex()] instanceof ResponseHeaders holder) {
                return holder;
            }
        }
        return null;
    }

    private UploadProgressListener resolveUploadProgressListener() {
        if (methodMetadata != null && methodMetadata.uploadProgressParamIndex() >= 0 && args != null
                && methodMetadata.uploadProgressParamIndex() < args.length) {
            if (args[methodMetadata.uploadProgressParamIndex()] instanceof UploadProgressListener listener) {
                return listener;
            }
        }
        return null;
    }

    private DownloadProgressListener resolveDownloadProgressListener() {
        if (methodMetadata != null && methodMetadata.downloadProgressParamIndex() >= 0 && args != null
                && methodMetadata.downloadProgressParamIndex() < args.length) {
            if (args[methodMetadata.downloadProgressParamIndex()] instanceof DownloadProgressListener listener) {
                return listener;
            }
        }
        return null;
    }


    private boolean isConditionalRequest(List<Header> headers) {
        if (!methodMetadata.hasRequestHeaders()) {
            return methodMetadata.isStaticConditional();
        }
        if (headers == null) {
            return false;
        }
        for (int i = 0, size = headers.size(); i < size; i++) {
            Header h = headers.get(i);
            if (h.name() != null) {
                String name = h.name();
                if (name.equalsIgnoreCase("if-none-match") || name.equalsIgnoreCase("if-modified-since")
                        || name.equalsIgnoreCase("if-match") || name.equalsIgnoreCase("if-unmodified-since")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String createUrl() {
        String path = requestMeta.path();
        if (methodMetadata.hasPathVariables() && args != null) {
            for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
                if (p.isPathVariable() && p.index() < args.length) {
                    Object arg = args[p.index()];
                    if (arg == null) {
                        throw new IllegalArgumentException("Path variable '" + p.pathVariableName() + "' value cannot be null");
                    }
                    String substitutedValue = p.pathVariableEncoded()
                            ? arg.toString()
                            : URLEncoder.encode(arg.toString(), StandardCharsets.UTF_8).replace("+", "%20");
                    path = path.replace("${" + p.pathVariableName() + "}", substitutedValue);
                }
            }
        }
        return !path.startsWith("http")
                ? moduleInfo.getEnvironment().getUrl() + path
                : path;
    }

    private List<Header> createHeaders() {
        List<Header> authHeaders = moduleInfo.getAuthProvider().getAuthHeaders();
        List<Header> staticHeaders = methodMetadata.staticHeaders();
        List<Header> result;
        if (authHeaders == null || authHeaders.isEmpty()) {
            result = new ArrayList<>(staticHeaders);
        } else if (staticHeaders.isEmpty()) {
            result = new ArrayList<>(authHeaders);
        } else {
            result = new ArrayList<>(authHeaders);
            Set<String> staticNames = new HashSet<>();
            for (Header h : staticHeaders) {
                staticNames.add(h.name().toLowerCase(Locale.ROOT));
            }
            result.removeIf(h -> staticNames.contains(h.name().toLowerCase(Locale.ROOT)));
            result.addAll(staticHeaders);
        }

        if (methodMetadata.hasRequestHeaders() && args != null) {
            for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
                if (p.isRequestHeader() && p.index() < args.length) {
                    Object arg = args[p.index()];
                    if (arg == null) {
                        continue;
                    }

                    if (arg instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (entry.getKey() == null || entry.getValue() == null) {
                                continue;
                            }
                            String headerName = String.valueOf(entry.getKey());
                            result.removeIf(h -> h.name().equalsIgnoreCase(headerName));
                            appendHeaderValues(result, headerName, entry.getValue());
                        }
                        continue;
                    }

                    String name = p.requestHeaderName();
                    result.removeIf(h -> h.name().equalsIgnoreCase(name));
                    appendHeaderValues(result, name, arg);
                }
            }
        }

        String cookieHeader = resolveCookies();
        if (cookieHeader != null) {
            boolean hasCookieHeader = false;
            for (int i = 0; i < result.size(); i++) {
                Header h = result.get(i);
                if ("Cookie".equalsIgnoreCase(h.name())) {
                    hasCookieHeader = true;
                    result.set(i, new Header("Cookie", h.value() + "; " + cookieHeader));
                    break;
                }
            }
            if (!hasCookieHeader) {
                result.add(new Header("Cookie", cookieHeader));
            }
        }

        if (moduleInfo != null && moduleInfo.isDecompressionEnabled()) {
            boolean hasAcceptEncoding = false;
            for (int i = 0, size = result.size(); i < size; i++) {
                Header h = result.get(i);
                if (h.name() != null && h.name().equalsIgnoreCase("Accept-Encoding")) {
                    hasAcceptEncoding = true;
                    break;
                }
            }
            if (!hasAcceptEncoding) {
                result.add(new Header("Accept-Encoding", "gzip, deflate"));
            }
        }

        applyTracingHeaders(result);

        return result;
    }

    private void applyTracingHeaders(List<Header> result) {
        Trace effectiveTrace = methodMetadata.effectiveTrace();
        if (effectiveTrace != null) {
            if (!effectiveTrace.enabled()) {
                return;
            }
            if (effectiveTrace.w3c()) {
                boolean hasTraceparent = result.stream()
                        .anyMatch(h -> h.name() != null && h.name().equalsIgnoreCase("traceparent"));
                if (!hasTraceparent || effectiveTrace.forceNew()) {
                    if (effectiveTrace.forceNew()) {
                        result.removeIf(h -> h.name() != null && h.name().equalsIgnoreCase("traceparent"));
                    }
                    TraceContext tc = TraceContext.random();
                    result.add(new Header("traceparent", tc.toTraceparent()));
                }
            } else {
                String headerName = effectiveTrace.header().trim();
                boolean hasHeader = result.stream()
                        .anyMatch(h -> h.name() != null && h.name().equalsIgnoreCase(headerName));
                if (!hasHeader || effectiveTrace.forceNew()) {
                    if (effectiveTrace.forceNew()) {
                        result.removeIf(h -> h.name() != null && h.name().equalsIgnoreCase(headerName));
                    }
                    String id = UUID.randomUUID().toString();
                    result.add(new Header(headerName, id));
                }
            }
            return;
        }

        if (moduleInfo != null && moduleInfo.getTracePropagator() != null) {
            TraceRequestContext traceContext = new TraceRequestContext(
                    controller,
                    method,
                    createUrl(),
                    requestMeta.httpMethod(),
                    Collections.unmodifiableList(result)
            );
            Map<String, String> injected = moduleInfo.getTracePropagator().inject(traceContext);
            if (injected != null) {
                for (Map.Entry<String, String> entry : injected.entrySet()) {
                    String name = entry.getKey();
                    String value = entry.getValue();
                    if (name != null && value != null) {
                        boolean exists = false;
                        for (int i = 0, size = result.size(); i < size; i++) {
                            Header h = result.get(i);
                            if (h.name() != null && h.name().equalsIgnoreCase(name)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            result.add(new Header(name, value));
                        }
                    }
                }
            }
        }
    }

    private String resolveCookies() {
        if (!methodMetadata.hasCookieParams()) {
            return methodMetadata.staticCookieHeader();
        }

        Map<String, String> cookies = methodMetadata.staticCookies().isEmpty()
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(methodMetadata.staticCookies());

        if (args != null) {
            for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
                if (p.isCookie() && p.index() < args.length) {
                    Object arg = args[p.index()];
                    if (arg == null) {
                        cookies.remove(p.cookieName());
                        continue;
                    }

                    if (arg instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (entry.getKey() != null) {
                                String key = String.valueOf(entry.getKey());
                                if (entry.getValue() != null) {
                                    cookies.put(key, String.valueOf(entry.getValue()));
                                } else {
                                    cookies.remove(key);
                                }
                            }
                        }
                        continue;
                    }

                    if (arg instanceof HttpCookie httpCookie) {
                        cookies.put(httpCookie.getName(), httpCookie.getValue());
                        continue;
                    }

                    cookies.put(p.cookieName(), String.valueOf(arg));
                }
            }
        }

        if (cookies.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private Consumer<List<Header>> getAuthDetailsConsumer() {
        if (methodMetadata != null && methodMetadata.authDetailsHeaderName() != null) {
            String headerName = methodMetadata.authDetailsHeaderName();
            AuthProvider authProvider = moduleInfo.getAuthProvider();

            return responseHeaders -> authProvider.captureAuthDetails(headerName, responseHeaders);
        }

        return null;
    }

    private List<Param> createRequestParams() {
        List<Param> result = new ArrayList<>(methodMetadata.staticDefaultParams());
        CollectionFormat defaultCollectionFormat = resolveDefaultCollectionFormat();

        if (methodMetadata.hasRequestParams() && args != null) {
            for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
                if (p.isRequestParam() && p.index() < args.length) {
                    Object arg = args[p.index()];
                    if (arg == null) {
                        continue;
                    }

                    CollectionFormat effectiveFormat = p.requestParamFormat() != null
                            ? p.requestParamFormat()
                            : defaultCollectionFormat;

                    if (arg instanceof Map<?, ?> map) {
                        String prefix = p.rawRequestParamName();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (entry.getKey() == null || entry.getValue() == null) {
                                continue;
                            }
                            String paramName = prefix.isEmpty()
                                    ? String.valueOf(entry.getKey())
                                    : prefix + "[" + entry.getKey() + "]";
                            result.removeIf(existing -> existing.name().equals(paramName));
                            appendParamValues(result, paramName, entry.getValue(), effectiveFormat);
                        }
                        continue;
                    }

                    String name = p.requestParamName();
                    result.removeIf(existing -> existing.name().equals(name));
                    appendParamValues(result, name, arg, effectiveFormat);
                }
            }
        }

        return result;
    }

    private CollectionFormat resolveDefaultCollectionFormat() {
        return methodMetadata != null ? methodMetadata.defaultCollectionFormat() : CollectionFormat.EXPLODE;
    }

    private record BodyPayload(
            byte[] bytes,
            Supplier<? extends HttpRequest.BodyPublisher> publisherSupplier,
            String effectiveContentType
    ) {}

    private BodyPayload createJsonBodyPayload(String effectiveContentType, boolean isContentTypeExplicit) {
        Object body = createBody(method, args);
        if (body == null) {
            return new BodyPayload(null, null, effectiveContentType);
        }

        Charset charset = parseCharset(effectiveContentType, StandardCharsets.UTF_8);

        if (body instanceof HttpRequest.BodyPublisher bp) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            return new BodyPayload(null, () -> bp, ct);
        }

        if (body instanceof Flow.Publisher<?> publisher) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            Flow.Publisher<ByteBuffer> adapted = adaptToByteBufferPublisher(publisher, charset);
            return new BodyPayload(null, () -> HttpRequest.BodyPublishers.fromPublisher(adapted), ct);
        }

        if (body instanceof InputStream inputStream) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            return new BodyPayload(null, () -> HttpRequest.BodyPublishers.ofInputStream(() -> inputStream), ct);
        }

        if (body instanceof Path path) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            return new BodyPayload(null, () -> {
                try {
                    return HttpRequest.BodyPublishers.ofFile(path);
                } catch (FileNotFoundException e) {
                    throw new UncheckedIOException(e);
                }
            }, ct);
        }

        if (body instanceof File file) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            Path path = file.toPath();
            return new BodyPayload(null, () -> {
                try {
                    return HttpRequest.BodyPublishers.ofFile(path);
                } catch (FileNotFoundException e) {
                    throw new UncheckedIOException(e);
                }
            }, ct);
        }

        if (body instanceof Supplier<?> supplier) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            Supplier<HttpRequest.BodyPublisher> publisherSupplier = () -> {
                Object supplied = supplier.get();
                if (supplied == null) {
                    return HttpRequest.BodyPublishers.noBody();
                }
                if (supplied instanceof InputStream stream) {
                    return HttpRequest.BodyPublishers.ofInputStream(() -> stream);
                }
                if (supplied instanceof HttpRequest.BodyPublisher bp) {
                    return bp;
                }
                if (supplied instanceof Flow.Publisher<?> pub) {
                    return HttpRequest.BodyPublishers.fromPublisher(adaptToByteBufferPublisher(pub, charset));
                }
                if (supplied instanceof Path p) {
                    try {
                        return HttpRequest.BodyPublishers.ofFile(p);
                    } catch (FileNotFoundException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                if (supplied instanceof File f) {
                    try {
                        return HttpRequest.BodyPublishers.ofFile(f.toPath());
                    } catch (FileNotFoundException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                if (supplied instanceof byte[] b) {
                    return HttpRequest.BodyPublishers.ofByteArray(b);
                }
                if (supplied instanceof String s) {
                    return HttpRequest.BodyPublishers.ofByteArray(s.getBytes(charset));
                }
                throw new IllegalArgumentException("Unsupported Supplier body type: " + supplied.getClass().getName());
            };
            return new BodyPayload(null, publisherSupplier, ct);
        }

        if (body instanceof byte[] rawBytes) {
            return new BodyPayload(rawBytes, null, effectiveContentType);
        }

        if (body instanceof String stringBody) {
            return new BodyPayload(stringBody.getBytes(charset), null, effectiveContentType);
        }

        PayloadCodec codec = moduleInfo.getCodecRegistry().resolve(effectiveContentType);
        if (codec == null) {
            throw new IllegalStateException("No PayloadCodec registered for Content-Type '"
                    + effectiveContentType + "'. Register one via JRestlyClient.builder().codec(...) "
                    + "or send a raw body type (String, byte[], InputStream, Path, File, Supplier, "
                    + "Flow.Publisher, BodyPublisher).");
        }
        byte[] bodyBytes = codec.serialize(body, effectiveContentType);
        return new BodyPayload(bodyBytes, null, effectiveContentType);
    }

    private static Flow.Publisher<ByteBuffer> adaptToByteBufferPublisher(Flow.Publisher<?> source, Charset charset) {
        if (source == null) {
            return null;
        }
        return subscriber -> source.subscribe(new Flow.Subscriber<Object>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(Object item) {
                if (item instanceof ByteBuffer bb) {
                    subscriber.onNext(bb);
                } else if (item instanceof byte[] b) {
                    subscriber.onNext(ByteBuffer.wrap(b));
                } else if (item instanceof String s) {
                    subscriber.onNext(ByteBuffer.wrap(s.getBytes(charset)));
                } else if (item != null) {
                    subscriber.onNext(ByteBuffer.wrap(item.toString().getBytes(charset)));
                }
            }

            @Override
            public void onError(Throwable throwable) {
                subscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
    }

    private Object createBody(Method method, Object[] args) {
        if (requestMeta.httpMethod() == HttpMethod.GET || methodMetadata.requestBodyIndex() < 0 || args == null) {
            return null;
        }
        int idx = methodMetadata.requestBodyIndex();
        return idx < args.length ? args[idx] : null;
    }

    private List<MultipartPart> createMultipartParts() {
        if (!RequestType.MULTIPART_FORM_DATA.equals(requestMeta.requestType())) {
            throw new UnsupportedOperationException("Cannot create multipart entity");
        }

        List<MultipartPart> parts = new ArrayList<>();
        if (args == null) {
            return parts;
        }

        CollectionFormat defaultCollectionFormat = resolveDefaultCollectionFormat();

        for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
            if (p.index() >= args.length) {
                continue;
            }
            Object arg = args[p.index()];

            if (p.isMultipartFile()) {
                for (Object value : multipartFileValues(arg)) {
                    parts.add(createFilePart(p.multipartPartName(), value, p.multipartExplicitContentType()));
                }
            } else if (p.isRequestParam()) {
                if (arg == null) {
                    continue;
                }
                String explicitContentType = p.requestParamContentType();
                CollectionFormat effectiveFormat = p.requestParamFormat() != null
                        ? p.requestParamFormat()
                        : defaultCollectionFormat;

                if (arg instanceof Map<?, ?> map) {
                    String prefix = p.rawRequestParamName();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null) {
                            continue;
                        }
                        String partName = prefix.isEmpty()
                                ? String.valueOf(entry.getKey())
                                : prefix + "[" + entry.getKey() + "]";
                        Object val = entry.getValue();
                        if (val instanceof Collection<?> collection) {
                            List<?> nonNull = collection.stream().filter(Objects::nonNull).toList();
                            appendMultipartCollectionParts(parts, partName, nonNull, explicitContentType, effectiveFormat);
                        } else if (val instanceof Object[] array) {
                            List<?> nonNull = Arrays.stream(array).filter(Objects::nonNull).toList();
                            appendMultipartCollectionParts(parts, partName, nonNull, explicitContentType, effectiveFormat);
                        } else {
                            parts.add(createPartFromValue(partName, val, explicitContentType));
                        }
                    }
                    continue;
                }

                String name = p.requestParamName();
                if (arg instanceof Collection<?> collection) {
                    List<?> nonNull = collection.stream().filter(Objects::nonNull).toList();
                    appendMultipartCollectionParts(parts, name, nonNull, explicitContentType, effectiveFormat);
                } else if (arg instanceof Object[] array) {
                    List<?> nonNull = Arrays.stream(array).filter(Objects::nonNull).toList();
                    appendMultipartCollectionParts(parts, name, nonNull, explicitContentType, effectiveFormat);
                } else {
                    parts.add(createPartFromValue(name, arg, explicitContentType));
                }
            }
        }

        return parts;
    }

    private void appendMultipartCollectionParts(List<MultipartPart> parts, String name, List<?> items,
                                                String explicitContentType, CollectionFormat format) {
        if (items.isEmpty()) {
            return;
        }

        CollectionFormat effectiveFormat = (format == null || format == CollectionFormat.DEFAULT)
                ? CollectionFormat.EXPLODE
                : format;

        switch (effectiveFormat) {
            case CSV -> {
                List<String> strItems = items.stream().map(Object::toString).toList();
                parts.add(createPartFromValue(name, String.join(",", strItems), explicitContentType));
            }
            case SSV -> {
                List<String> strItems = items.stream().map(Object::toString).toList();
                parts.add(createPartFromValue(name, String.join(" ", strItems), explicitContentType));
            }
            case TSV -> {
                List<String> strItems = items.stream().map(Object::toString).toList();
                parts.add(createPartFromValue(name, String.join("\t", strItems), explicitContentType));
            }
            case PIPES -> {
                List<String> strItems = items.stream().map(Object::toString).toList();
                parts.add(createPartFromValue(name, String.join("|", strItems), explicitContentType));
            }
            case BRACKETS -> {
                String bracketName = name + "[]";
                for (Object item : items) {
                    parts.add(createPartFromValue(bracketName, item, explicitContentType));
                }
            }
            case EXPLODE -> {
                for (Object item : items) {
                    parts.add(createPartFromValue(name, item, explicitContentType));
                }
            }
            default -> {
                for (Object item : items) {
                    parts.add(createPartFromValue(name, item, explicitContentType));
                }
            }
        }
    }

    private MultipartPart createPartFromValue(String partName, Object value, String explicitContentType) {
        if (explicitContentType == null || explicitContentType.isEmpty()) {
            return MultipartPart.text(partName, value != null ? value.toString() : "");
        }
        Charset charset = parseCharset(explicitContentType, StandardCharsets.UTF_8);
        if (value instanceof byte[] bytes) {
            return MultipartPart.part(partName, explicitContentType, bytes);
        }
        PayloadCodec codec = moduleInfo.getCodecRegistry().resolve(explicitContentType);
        if (codec != null) {
            if (value instanceof String str) {
                return MultipartPart.part(partName, explicitContentType, str.getBytes(charset));
            }
            return MultipartPart.part(partName, explicitContentType, codec.serialize(value, explicitContentType));
        }
        if (value instanceof String str) {
            return MultipartPart.part(partName, explicitContentType, str.getBytes(charset));
        }
        return MultipartPart.part(partName, explicitContentType,
                value != null ? value.toString().getBytes(charset) : new byte[0]);
    }

    private static Charset parseCharset(String contentType, Charset defaultCharset) {
        if (contentType == null || contentType.isEmpty()) {
            return defaultCharset;
        }
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx >= 0) {
            String csName = contentType.substring(idx + 8).trim();
            int semi = csName.indexOf(';');
            if (semi >= 0) {
                csName = csName.substring(0, semi).trim();
            }
            if (csName.startsWith("\"") && csName.endsWith("\"") && csName.length() >= 2) {
                csName = csName.substring(1, csName.length() - 1);
            }
            try {
                return Charset.forName(csName);
            } catch (Exception ignored) {
                return defaultCharset;
            }
        }
        return defaultCharset;
    }

    private MultipartPart createFilePart(String partName, Object arg, String explicitContentType) {
        if (arg instanceof FileContent content) {
            return MultipartPart.file(partName, content.fileName(), explicitContentType,
                    content.content(), content.contentLength());
        }
        if (arg instanceof byte[]) {
            throw new IllegalArgumentException(
                    "A bare byte[] cannot be a multipart file: the wire file name cannot be derived from it. "
                            + "Wrap in-memory bytes in FileContent.of(fileName, bytes)");
        }
        Path path = arg instanceof File file
                ? file.toPath()
                : arg instanceof Path p
                        ? p
                        : arg instanceof String str
                                ? Path.of(str)
                                : null;
        if (path == null) {
            throw new IllegalArgumentException(
                    "Unsupported multipart file argument of type " + arg.getClass().getName()
                            + ". Expected Path, File, String, or FileContent");
        }
        if (path.getFileName() == null) {
            throw new IllegalArgumentException("Path must have a file name: " + path);
        }
        return MultipartPart.file(partName, path.getFileName().toString(), explicitContentType, path, null);
    }

    // Unpacks collections, arrays, and varargs into individual part values
    private static List<?> multipartFileValues(Object arg) {
        if (arg == null) {
            return List.of();
        }
        if (arg instanceof Collection<?> collection) {
            return collection.stream().filter(Objects::nonNull).toList();
        }
        if (arg instanceof Object[] array) {
            return Arrays.stream(array).filter(Objects::nonNull).toList();
        }
        return List.of(arg);
    }

    private byte[] createUrlEncodedBody() {
        if (!RequestType.APPLICATION_FORM_URLENCODED.equals(requestMeta.requestType())) {
            throw new UnsupportedOperationException("Cannot create urlencoded entity");
        }

        if (methodMetadata.parameterDescriptors().length == 0) {
            throw new UnsupportedOperationException("Urlencoded method has no parameters");
        }

        List<Param> params = createRequestParams();
        if (params.isEmpty()) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0, size = params.size(); i < size; i++) {
            Param param = params.get(i);
            if (i > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(param.name(), StandardCharsets.UTF_8));
            sb.append('=');
            if (param.value() != null) {
                sb.append(URLEncoder.encode(param.value(), StandardCharsets.UTF_8));
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private OnErrorParser createOnErrorParser() {
        return methodMetadata != null ? methodMetadata.onErrorParser() : null;
    }

    private List<Integer> createExpectStatuses() {
        return methodMetadata != null ? methodMetadata.expectStatuses() : null;
    }

    private Integer getFollowRedirectsNumber() {
        return methodMetadata != null ? methodMetadata.followRedirectsNumber() : null;
    }

    private HttpClient.Version getHttpVersion() {
        return methodMetadata != null ? methodMetadata.httpVersion() : null;
    }

    private RetryPolicy resolveRetryPolicy() {
        if (methodMetadata != null && methodMetadata.retryPolicy() != null) {
            return methodMetadata.retryPolicy();
        }

        return moduleInfo.getRetryPolicy();
    }

    private Duration resolveTimeout() {
        return methodMetadata != null ? methodMetadata.timeout() : null;
    }

    private String resolveInterfaceAccept() {
        return methodMetadata != null ? methodMetadata.interfaceAccept() : null;
    }

    private String resolveMethodAccept() {
        return methodMetadata != null ? methodMetadata.methodAccept() : null;
    }

    private String resolveInterfaceContentType() {
        return methodMetadata != null ? methodMetadata.interfaceContentType() : null;
    }

    private String resolveMethodContentType() {
        return methodMetadata != null ? methodMetadata.methodContentType() : null;
    }

    private static void appendHeaderValues(List<Header> result, String name, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object o : collection) {
                if (o != null) {
                    result.add(new Header(name, o.toString()));
                }
            }
        } else if (value.getClass().isArray() && !byte[].class.equals(value.getClass())) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object o = java.lang.reflect.Array.get(value, i);
                if (o != null) {
                    result.add(new Header(name, o.toString()));
                }
            }
        } else {
            result.add(new Header(name, value.toString()));
        }
    }

    private static void appendParamValues(List<Param> result, String name, Object value, CollectionFormat format) {
        if (value == null) {
            return;
        }

        if (value instanceof Collection<?> collection) {
            List<String> items = collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
            if (items.isEmpty()) {
                return;
            }
            appendFormattedItems(result, name, items, format);
            return;
        }

        if (value.getClass().isArray() && !byte[].class.equals(value.getClass())) {
            int length = java.lang.reflect.Array.getLength(value);
            List<String> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object o = java.lang.reflect.Array.get(value, i);
                if (o != null) {
                    items.add(o.toString());
                }
            }
            if (items.isEmpty()) {
                return;
            }
            appendFormattedItems(result, name, items, format);
            return;
        }

        result.add(new Param(name, value.toString()));
    }

    private static void appendFormattedItems(List<Param> result, String name, List<String> items, CollectionFormat format) {
        CollectionFormat effectiveFormat = (format == null || format == CollectionFormat.DEFAULT)
                ? CollectionFormat.EXPLODE
                : format;

        switch (effectiveFormat) {
            case CSV -> result.add(new Param(name, String.join(",", items)));
            case SSV -> result.add(new Param(name, String.join(" ", items)));
            case TSV -> result.add(new Param(name, String.join("\t", items)));
            case PIPES -> result.add(new Param(name, String.join("|", items)));
            case BRACKETS -> {
                String bracketName = name + "[]";
                for (String item : items) {
                    result.add(new Param(bracketName, item));
                }
            }
            case EXPLODE -> {
                for (String item : items) {
                    result.add(new Param(name, item));
                }
            }
            default -> {
                for (String item : items) {
                    result.add(new Param(name, item));
                }
            }
        }
    }

    private record ResolvedInterceptors(
            List<RequestInterceptor> requestInterceptors,
            List<ResponseInterceptor> responseInterceptors
    ) {
    }

    private ResolvedInterceptors resolveInterceptors() {
        List<RequestInterceptor> reqs = new ArrayList<>(moduleInfo.getRequestInterceptors());
        List<ResponseInterceptor> resps = new ArrayList<>(moduleInfo.getResponseInterceptors());

        if (methodMetadata != null) {
            for (Class<? extends RequestInterceptor> clazz : methodMetadata.declarativeRequestInterceptorClasses()) {
                Object instance = getOrCreateInterceptor(clazz);
                if (instance instanceof RequestInterceptor req) {
                    reqs.add(req);
                }
            }
            for (Class<? extends ResponseInterceptor> clazz : methodMetadata.declarativeResponseInterceptorClasses()) {
                Object instance = getOrCreateInterceptor(clazz);
                if (instance instanceof ResponseInterceptor resp) {
                    resps.add(resp);
                }
            }
        }

        return new ResolvedInterceptors(
                Collections.unmodifiableList(reqs),
                Collections.unmodifiableList(resps)
        );
    }

    private Object getOrCreateInterceptor(Class<?> clazz) {
        if (interceptorCache != null) {
            return interceptorCache.computeIfAbsent(clazz, this::instantiateInterceptor);
        }
        return instantiateInterceptor(clazz);
    }

    private Object instantiateInterceptor(Class<?> clazz) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate interceptor " + clazz.getName()
                    + ". Ensure it has an accessible no-arg constructor.", e);
        }
    }

    private ClientMetricsListener resolveMetricsListener() {
        List<ClientMetricsListener> listeners = new ArrayList<>();
        if (moduleInfo.getMetricsListener() != null) {
            listeners.add(moduleInfo.getMetricsListener());
        }
        if (methodMetadata != null) {
            for (Class<? extends ClientMetricsListener> clazz : methodMetadata.declarativeMetricsListenerClasses()) {
                Object instance = getOrCreateInterceptor(clazz);
                if (instance instanceof ClientMetricsListener listener) {
                    listeners.add(listener);
                }
            }
        }
        if (listeners.isEmpty()) {
            return null;
        }
        if (listeners.size() == 1) {
            return listeners.getFirst();
        }
        return ClientMetricsListener.composite(listeners.toArray(new ClientMetricsListener[0]));
    }

    private record EffectiveCompression(boolean enabled, CompressionAlgorithm algorithm, int minSizeBytes) {}

    private static final EffectiveCompression DEFAULT_NO_COMPRESSION =
            new EffectiveCompression(false, CompressionAlgorithm.NONE, 0);

    private EffectiveCompression resolveCompression() {
        if (methodMetadata != null) {
            MethodMetadata.EffectiveCompression metaComp = methodMetadata.compression();
            if (metaComp.explicit()) {
                return new EffectiveCompression(metaComp.enabled(), metaComp.algorithm(), metaComp.minSizeBytes());
            }
        }

        if (moduleInfo != null && moduleInfo.getRequestCompressionAlgorithm() != null
                && moduleInfo.getRequestCompressionAlgorithm() != CompressionAlgorithm.NONE) {
            return new EffectiveCompression(true, moduleInfo.getRequestCompressionAlgorithm(),
                    Math.max(0, moduleInfo.getRequestCompressionThreshold()));
        }

        return DEFAULT_NO_COMPRESSION;
    }

    private ContentCompressor resolveCompressor(CompressionAlgorithm algorithm) {
        if (algorithm == null || algorithm == CompressionAlgorithm.NONE) {
            return null;
        }
        if (moduleInfo != null) {
            ContentCompressor custom = moduleInfo.getContentCompressor(algorithm.encoding());
            if (custom != null) {
                return custom;
            }
        }
        return ContentCompressors.get(algorithm);
    }

    private CircuitBreaker resolveCircuitBreaker() {
        if (methodMetadata != null && methodMetadata.circuitBreakerAnnotation() != null) {
            ru.jrestly.annotation.CircuitBreaker ann = methodMetadata.circuitBreakerAnnotation();
            if (!ann.enabled()) {
                return null;
            }
            return getCircuitBreakerFromAnnotation(ann);
        }

        return moduleInfo != null ? moduleInfo.getCircuitBreaker() : null;
    }

    private CircuitBreaker getCircuitBreakerFromAnnotation(ru.jrestly.annotation.CircuitBreaker ann) {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .failureRateThreshold(ann.failureRateThreshold())
                .minimumNumberOfCalls(ann.minimumNumberOfCalls())
                .slidingWindowSize(ann.slidingWindowSize())
                .waitDurationInOpenState(Duration.ofMillis(ann.waitDurationMs()))
                .permittedNumberOfCallsInHalfOpenState(ann.permittedNumberOfCallsInHalfOpenState())
                .build();
        CircuitBreakerRegistry registry = (moduleInfo != null && moduleInfo.getCircuitBreakerRegistry() != null)
                ? moduleInfo.getCircuitBreakerRegistry()
                : DEFAULT_CIRCUIT_BREAKER_REGISTRY;
        return registry.getOrCreate(ann.name(), config);
    }

    private RateLimiter resolveRateLimiter() {
        if (methodMetadata != null && methodMetadata.rateLimiterAnnotation() != null) {
            ru.jrestly.annotation.RateLimiter ann = methodMetadata.rateLimiterAnnotation();
            if (!ann.enabled()) {
                return null;
            }
            return getRateLimiterFromAnnotation(ann);
        }

        return moduleInfo != null ? moduleInfo.getRateLimiter() : null;
    }

    private RateLimiter getRateLimiterFromAnnotation(ru.jrestly.annotation.RateLimiter ann) {
        RateLimiterConfig config = RateLimiterConfig.builder()
                .limitForPeriod(ann.limitForPeriod())
                .limitRefreshPeriod(Duration.ofMillis(ann.limitRefreshPeriodMs()))
                .timeoutDuration(Duration.ofMillis(ann.timeoutDurationMs()))
                .build();
        RateLimiterRegistry registry = (moduleInfo != null && moduleInfo.getRateLimiterRegistry() != null)
                ? moduleInfo.getRateLimiterRegistry()
                : DEFAULT_RATE_LIMITER_REGISTRY;
        return registry.getOrCreate(ann.name(), config);
    }
}
