package ru.jrestly.http;

import ru.jrestly.AuthProvider;
import ru.jrestly.ModuleInfo;
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
    private final Method method;
    private final Object[] args;
    private final Class<?> controller;

    private RequestMeta requestMeta;
    private Executor executor;
    private Map<Class<?>, Object> interceptorCache;
    private System.Logger customLogger;

    /**
     * Creates a builder for the given invocation.
     *
     * @param moduleInfo the module configuration (environment, codec, auth provider, retry policy)
     * @param httpClient the client's preconfigured {@link HttpClient}
     * @param method the invoked controller method; its verb annotation drives the request
     * @param args the invocation arguments
     * @param controller the proxied controller interface; names the request logger
     */
    public HttpTemplateBuilder(ModuleInfo moduleInfo, HttpClient httpClient, Method method, Object[] args, Class<?> controller) {
        this.moduleInfo = moduleInfo;
        this.httpClient = httpClient;
        this.method = method;
        this.args = args;
        this.controller = controller;
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
        requestMeta = resolveRequestMeta(method);

        HttpTemplate result = new HttpTemplate();
        RequestType requestType = requestMeta.requestType();

        result.setLogger(customLogger != null ? customLogger : System.getLogger(controller.getName()));
        result.setModuleInfo(moduleInfo);
        result.setJsonCodec(moduleInfo.getJsonCodec());

        result.setHttpClient(httpClient);
        result.setUrl(createUrl());
        result.setHttpMethod(requestMeta.httpMethod());

        List<Header> headers = createHeaders();

        String effectiveContentType;
        String declarativeContentType = resolveMethodContentType() != null
                ? resolveMethodContentType()
                : resolveInterfaceContentType();

        Header explicitContentTypeHeader = null;
        for (Header h : headers) {
            if ("Content-Type".equalsIgnoreCase(h.name())) {
                explicitContentTypeHeader = h;
                break;
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
        result.setAuthHeaderNames(moduleInfo.getAuthProvider().getAuthHeaders().stream()
                .map(Header::name)
                .toList());
        result.setAuthDetailsConsumer(getAuthDetailsConsumer());
        if (!RequestType.MULTIPART_FORM_DATA.equals(requestType) && !RequestType.APPLICATION_FORM_URLENCODED.equals(requestType)) {
            result.setRequestParams(createRequestParams());
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
        resolveReturnType(result, method.getGenericReturnType());
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
        boolean retryAuth = moduleInfo.isRetryOnUnauthorized();
        if (method != null && method.isAnnotationPresent(ru.jrestly.annotation.Authorization.class)) {
            retryAuth = false;
        }
        result.setRetryOnUnauthorized(retryAuth);
        result.setControllerClass(controller);
        result.setMethod(method);
        result.setArgs(args);

        return result;
    }

    private ResponseHeaders resolveResponseHeadersHolder() {
        if (method == null) {
            return null;
        }
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (ResponseHeaders.class.equals(parameters[i].getType())) {
                if (args != null && i < args.length && args[i] instanceof ResponseHeaders holder) {
                    return holder;
                }
                return null;
            }
        }
        return null;
    }

    private UploadProgressListener resolveUploadProgressListener() {
        if (method == null) {
            return null;
        }
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (UploadProgressListener.class.equals(parameters[i].getType())) {
                if (args != null && i < args.length && args[i] instanceof UploadProgressListener listener) {
                    return listener;
                }
                return null;
            }
        }
        return null;
    }

    private DownloadProgressListener resolveDownloadProgressListener() {
        if (method == null) {
            return null;
        }
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (DownloadProgressListener.class.equals(parameters[i].getType())) {
                if (args != null && i < args.length && args[i] instanceof DownloadProgressListener listener) {
                    return listener;
                }
                return null;
            }
        }
        return null;
    }

    // CompletableFuture<T> methods run non-blockingly on the client executor;
    // T itself (a class or parameterized type, including Void) is the wire
    // contract — the effective return type. An InputStream, Stream<String>, or
    // Stream<ServerSentEvent> effective return type declares streaming binding
    // (ADR-0015, ADR-0016): the body arrives as a live stream via
    // BodyHandlers.ofInputStream, codec-free
    private static void resolveReturnType(HttpTemplate result, Type genericReturnType) {
        Type effective;
        if (genericReturnType instanceof ParameterizedType parameterized
                && CompletableFuture.class.equals(parameterized.getRawType())) {
            result.setAsync(true);
            effective = parameterized.getActualTypeArguments()[0];
        } else {
            effective = genericReturnType;
        }

        if (effective instanceof ParameterizedType parameterized
                && Optional.class.equals(parameterized.getRawType())) {
            result.setOptional(true);
            effective = parameterized.getActualTypeArguments()[0];
        } else if (Optional.class.equals(effective)) {
            result.setOptional(true);
            effective = Object.class;
        }

        result.setReturnType(effective);
        if (InputStream.class.equals(effective)
                || HttpTemplate.isStreamType(effective, String.class)
                || HttpTemplate.isStreamType(effective, ServerSentEvent.class)) {
            result.setStreamBody(true);
        }
    }

    private static boolean isConditionalRequest(List<Header> headers) {
        if (headers == null) {
            return false;
        }
        for (Header h : headers) {
            if (h.name() != null) {
                String name = h.name().toLowerCase(Locale.ROOT);
                if (name.equals("if-none-match") || name.equals("if-modified-since")
                        || name.equals("if-match") || name.equals("if-unmodified-since")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String createUrl() {
        String path = requestMeta.path();

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(PathVariable.class)) {
                PathVariable pathAnnotation = parameters[i].getAnnotation(PathVariable.class);

                String name = pathAnnotation.name().isEmpty()
                        ? parameters[i].getName()
                        : pathAnnotation.name();

                if (args[i] == null) {
                    throw new IllegalArgumentException("Path variable '" + name + "' value cannot be null");
                }

                String substitutedValue = pathAnnotation.encoded()
                        ? args[i].toString()
                        : URLEncoder.encode(args[i].toString(), StandardCharsets.UTF_8).replace("+", "%20");
                path = path.replace("${" + name + "}", substitutedValue);
            }
        }

        return !path.startsWith("http")
                ? moduleInfo.getEnvironment().getUrl() + path
                : path;
    }

    private List<Header> createHeaders() {
        List<Header> result = new ArrayList<>(moduleInfo.getAuthProvider().getAuthHeaders());

        String ifaceAccept = resolveInterfaceAccept();
        if (ifaceAccept != null) {
            result.removeIf(h -> "Accept".equalsIgnoreCase(h.name()));
            result.add(new Header("Accept", ifaceAccept));
        }

        // Class-level headers (hierarchy order: parent interfaces first, sub-interface wins)
        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (Class<?> iface : hierarchy) {
            RequestHeader[] classHeaders = iface.getAnnotationsByType(RequestHeader.class);
            if (classHeaders.length > 0) {
                Set<String> declaredNames = Arrays.stream(classHeaders)
                        .map(h -> h.name().toLowerCase())
                        .collect(Collectors.toSet());
                result.removeIf(h -> declaredNames.contains(h.name().toLowerCase()));
                for (RequestHeader h : classHeaders) {
                    result.add(new Header(h.name(), h.value()));
                }
            }
        }

        String methodAccept = resolveMethodAccept();
        if (methodAccept != null) {
            result.removeIf(h -> "Accept".equalsIgnoreCase(h.name()));
            result.add(new Header("Accept", methodAccept));
        }

        // Method-level static headers
        if (method != null) {
            RequestHeader[] methodHeaders = method.getAnnotationsByType(RequestHeader.class);
            if (methodHeaders.length > 0) {
                Set<String> declaredNames = Arrays.stream(methodHeaders)
                        .map(h -> h.name().toLowerCase())
                        .collect(Collectors.toSet());
                result.removeIf(h -> declaredNames.contains(h.name().toLowerCase()));
                for (RequestHeader h : methodHeaders) {
                    result.add(new Header(h.name(), h.value()));
                }
            }

            // Parameter-level headers
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].isAnnotationPresent(RequestHeader.class)) {
                    Object arg = args[i];
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

                    RequestHeader headerAnnotation = parameters[i].getAnnotation(RequestHeader.class);
                    String name = headerAnnotation.name().isEmpty()
                            ? parameters[i].getName()
                            : headerAnnotation.name();

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
            boolean hasAcceptEncoding = result.stream()
                    .anyMatch(h -> h.name() != null && h.name().equalsIgnoreCase("Accept-Encoding"));
            if (!hasAcceptEncoding) {
                result.add(new Header("Accept-Encoding", "gzip, deflate"));
            }
        }

        applyTracingHeaders(result);

        return result;
    }

    private void applyTracingHeaders(List<Header> result) {
        Trace methodTrace = method != null ? method.getAnnotation(Trace.class) : null;
        Trace ifaceTrace = resolveInterfaceTrace();

        Trace effectiveTrace = methodTrace != null ? methodTrace : ifaceTrace;
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
                        boolean exists = result.stream()
                                .anyMatch(h -> h.name() != null && h.name().equalsIgnoreCase(name));
                        if (!exists) {
                            result.add(new Header(name, value));
                        }
                    }
                }
            }
        }
    }

    private Trace resolveInterfaceTrace() {
        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Trace trace = hierarchy.get(i).getAnnotation(Trace.class);
            if (trace != null) {
                return trace;
            }
        }
        return null;
    }

    private String resolveCookies() {
        Map<String, String> cookies = new java.util.LinkedHashMap<>();

        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (Class<?> iface : hierarchy) {
            Cookie[] ifaceCookies = iface.getAnnotationsByType(Cookie.class);
            for (Cookie c : ifaceCookies) {
                if (!c.name().isBlank()) {
                    cookies.put(c.name(), c.value());
                }
            }
        }

        if (method != null) {
            Cookie[] methodCookies = method.getAnnotationsByType(Cookie.class);
            for (Cookie c : methodCookies) {
                if (!c.name().isBlank()) {
                    cookies.put(c.name(), c.value());
                }
            }

            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].isAnnotationPresent(Cookie.class)) {
                    Cookie cookieAnnotation = parameters[i].getAnnotation(Cookie.class);
                    Object arg = args[i];
                    if (arg == null) {
                        String name = cookieAnnotation.name().isEmpty() ? parameters[i].getName() : cookieAnnotation.name();
                        cookies.remove(name);
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

                    String name = cookieAnnotation.name().isEmpty()
                            ? parameters[i].getName()
                            : cookieAnnotation.name();
                    cookies.put(name, String.valueOf(arg));
                }
            }
        }

        if (cookies.isEmpty()) {
            return null;
        }

        return cookies.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }

    private static List<Class<?>> collectInterfaceHierarchy(Class<?> clazz) {
        List<Class<?>> result = new ArrayList<>();
        collectInterfaces(clazz, result);
        return result;
    }

    private static void collectInterfaces(Class<?> current, List<Class<?>> result) {
        if (current == null || !current.isInterface()) {
            return;
        }
        for (Class<?> parent : current.getInterfaces()) {
            collectInterfaces(parent, result);
        }
        if (!result.contains(current)) {
            result.add(current);
        }
    }

    private RequestMeta resolveRequestMeta(Method method) {
        if (method.isAnnotationPresent(Get.class)) {
            Get annotation = method.getAnnotation(Get.class);
            return new RequestMeta(HttpMethod.GET, annotation.path(), RequestType.APPLICATION_JSON, annotation.params());
        }
        if (method.isAnnotationPresent(Post.class)) {
            Post annotation = method.getAnnotation(Post.class);
            return new RequestMeta(HttpMethod.POST, annotation.path(), annotation.requestType(), annotation.params());
        }
        if (method.isAnnotationPresent(Put.class)) {
            Put annotation = method.getAnnotation(Put.class);
            return new RequestMeta(HttpMethod.PUT, annotation.path(), annotation.requestType(), annotation.params());
        }
        if (method.isAnnotationPresent(Patch.class)) {
            Patch annotation = method.getAnnotation(Patch.class);
            return new RequestMeta(HttpMethod.PATCH, annotation.path(), annotation.requestType(), annotation.params());
        }
        if (method.isAnnotationPresent(Delete.class)) {
            Delete annotation = method.getAnnotation(Delete.class);
            return new RequestMeta(HttpMethod.DELETE, annotation.path(), annotation.requestType(), annotation.params());
        }

        throw new UnsupportedOperationException("No HTTP method annotation found on " + method);
    }

    private Consumer<List<Header>> getAuthDetailsConsumer() {
        if (method.isAnnotationPresent(SetAuthDetails.class)) {
            String headerName = method.getAnnotation(SetAuthDetails.class).headerName();
            AuthProvider authProvider = moduleInfo.getAuthProvider();

            return responseHeaders -> authProvider.captureAuthDetails(headerName, responseHeaders);
        }

        return null;
    }

    private List<Param> createRequestParams() {
        List<Param> defaultParams = new ArrayList<>();

        // Class-level default params (hierarchy order: parent interfaces first, sub-interface wins)
        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (Class<?> iface : hierarchy) {
            RequestDefaultParam[] classParams = iface.getAnnotationsByType(RequestDefaultParam.class);
            if (classParams.length > 0) {
                Set<String> declaredNames = Arrays.stream(classParams)
                        .map(RequestDefaultParam::name)
                        .collect(Collectors.toSet());
                defaultParams.removeIf(p -> declaredNames.contains(p.name()));
                for (RequestDefaultParam p : classParams) {
                    defaultParams.add(new Param(p.name(), p.value()));
                }
            }
        }

        // Method-level default params (verb annotation params + method-level @RequestDefaultParam)
        if (method != null) {
            List<RequestDefaultParam> methodDefaults = new ArrayList<>(Arrays.asList(requestMeta.defaultParams()));
            RequestDefaultParam[] methodDirectParams = method.getAnnotationsByType(RequestDefaultParam.class);
            for (RequestDefaultParam p : methodDirectParams) {
                methodDefaults.removeIf(existing -> existing.name().equals(p.name()));
                methodDefaults.add(p);
            }

            if (!methodDefaults.isEmpty()) {
                Set<String> declaredNames = methodDefaults.stream()
                        .map(RequestDefaultParam::name)
                        .collect(Collectors.toSet());
                defaultParams.removeIf(p -> declaredNames.contains(p.name()));
                for (RequestDefaultParam p : methodDefaults) {
                    defaultParams.add(new Param(p.name(), p.value()));
                }
            }
        }

        List<Param> result = new ArrayList<>(defaultParams);
        CollectionFormat defaultCollectionFormat = resolveDefaultCollectionFormat();

        if (method != null) {
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].isAnnotationPresent(RequestParam.class)) {
                    if (args[i] == null) {
                        continue;
                    }

                    Parameter parameter = parameters[i];
                    RequestParam annotation = parameter.getAnnotation(RequestParam.class);
                    CollectionFormat effectiveFormat = (annotation.format() != null && annotation.format() != CollectionFormat.DEFAULT)
                            ? annotation.format()
                            : defaultCollectionFormat;

                    if (args[i] instanceof Map<?, ?> map) {
                        String prefix = annotation.name();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (entry.getKey() == null || entry.getValue() == null) {
                                continue;
                            }
                            String paramName = prefix.isEmpty()
                                    ? String.valueOf(entry.getKey())
                                    : prefix + "[" + entry.getKey() + "]";
                            result.removeIf(p -> p.name().equals(paramName));
                            appendParamValues(result, paramName, entry.getValue(), effectiveFormat);
                        }
                        continue;
                    }

                    String name = annotation.name().isEmpty()
                            ? parameter.getName()
                            : annotation.name();

                    result.removeIf(p -> p.name().equals(name));
                    appendParamValues(result, name, args[i], effectiveFormat);
                }
            }
        }

        return result;
    }

    private CollectionFormat resolveDefaultCollectionFormat() {
        if (method != null && method.isAnnotationPresent(QueryCollectionFormat.class)) {
            CollectionFormat format = method.getAnnotation(QueryCollectionFormat.class).value();
            if (format != null && format != CollectionFormat.DEFAULT) {
                return format;
            }
        }

        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(QueryCollectionFormat.class)) {
                CollectionFormat format = iface.getAnnotation(QueryCollectionFormat.class).value();
                if (format != null && format != CollectionFormat.DEFAULT) {
                    return format;
                }
            }
        }

        return CollectionFormat.EXPLODE;
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

        String stringifiedBody = moduleInfo.getJsonCodec().serialize(body);
        return new BodyPayload(stringifiedBody.getBytes(StandardCharsets.UTF_8), null, effectiveContentType);
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

    private Object createBody(Method method, Object[] args){
        if (requestMeta.httpMethod() == HttpMethod.GET) {
            return null;
        }

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i ++) {
            if (parameters[i].isAnnotationPresent(RequestBody.class)) {
                return args[i];
            }
        }

        return null;
    }

    private List<MultipartPart> createMultipartParts() {
        if (!RequestType.MULTIPART_FORM_DATA.equals(requestMeta.requestType())) {
            throw new UnsupportedOperationException("Cannot create multipart entity");
        }

        List<MultipartPart> parts = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(MultipartFormFile.class)) {
                MultipartFormFile multipartAnnotation = parameters[i].getAnnotation(MultipartFormFile.class);
                String partName = multipartAnnotation.partName();
                String explicitContentType = multipartAnnotation.contentType().isEmpty()
                        ? null
                        : multipartAnnotation.contentType();

                for (Object value : multipartFileValues(args[i])) {
                    parts.add(createFilePart(partName, value, explicitContentType));
                }
            } else if (parameters[i].isAnnotationPresent(RequestParam.class)) {
                if (args[i] == null) {
                    continue;
                }

                Parameter parameter = parameters[i];
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);
                String explicitContentType = annotation.contentType().isEmpty()
                        ? null
                        : annotation.contentType();
                CollectionFormat effectiveFormat = (annotation.format() != null && annotation.format() != CollectionFormat.DEFAULT)
                        ? annotation.format()
                        : resolveDefaultCollectionFormat();

                if (args[i] instanceof Map<?, ?> map) {
                    String prefix = annotation.name();
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

                String name = annotation.name().isEmpty()
                        ? parameter.getName()
                        : annotation.name();

                if (args[i] instanceof Collection<?> collection) {
                    List<?> nonNull = collection.stream().filter(Objects::nonNull).toList();
                    appendMultipartCollectionParts(parts, name, nonNull, explicitContentType, effectiveFormat);
                } else if (args[i] instanceof Object[] array) {
                    List<?> nonNull = Arrays.stream(array).filter(Objects::nonNull).toList();
                    appendMultipartCollectionParts(parts, name, nonNull, explicitContentType, effectiveFormat);
                } else {
                    parts.add(createPartFromValue(name, args[i], explicitContentType));
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
        if (explicitContentType.toLowerCase(Locale.ROOT).contains("json")) {
            if (value instanceof String str) {
                return MultipartPart.part(partName, explicitContentType, str.getBytes(charset));
            }
            String json = moduleInfo.getJsonCodec().serialize(value);
            return MultipartPart.part(partName, explicitContentType, json.getBytes(charset));
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

        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            throw new UnsupportedOperationException("Urlencoded method has no parameters");
        }

        return createRequestParams().stream()
                .map(param -> URLEncoder.encode(param.name(), StandardCharsets.UTF_8)
                        + "="
                        + (param.value() != null ? URLEncoder.encode(param.value(), StandardCharsets.UTF_8) : ""))
                .collect(Collectors.joining("&"))
                .getBytes(StandardCharsets.UTF_8);
    }

    private OnErrorParser createOnErrorParser() {
        OnErrorParser parser = new OnErrorParser();
        boolean hasAnnotations = false;

        if (method != null) {
            OnError[] methodOnErrors = method.getAnnotationsByType(OnError.class);
            if (methodOnErrors.length > 0) {
                hasAnnotations = true;
                for (OnError onError : methodOnErrors) {
                    if (onError.statuses().length > 0) {
                        for (int status : onError.statuses()) {
                            parser.register(status, onError.errorObject());
                        }
                    } else {
                        parser.registerDefault(onError.errorObject());
                    }
                }
            }
        }

        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            OnError[] ifaceOnErrors = iface.getAnnotationsByType(OnError.class);
            if (ifaceOnErrors.length > 0) {
                hasAnnotations = true;
                for (OnError onError : ifaceOnErrors) {
                    if (onError.statuses().length > 0) {
                        for (int status : onError.statuses()) {
                            parser.register(status, onError.errorObject());
                        }
                    } else {
                        parser.registerDefault(onError.errorObject());
                    }
                }
            }
        }

        return hasAnnotations ? parser : null;
    }

    private List<Integer> createExpectStatuses() {
        if (method.isAnnotationPresent(ExpectStatus.class)) {
            int[] statuses = method.getAnnotation(ExpectStatus.class).statuses();
            return Arrays.stream(statuses).boxed().toList();
        }
        return null;
    }

    private Integer getFollowRedirectsNumber() {
        if (method.isAnnotationPresent(FollowRedirects.class)) {
            return method.getAnnotation(FollowRedirects.class).count();
        }

        return null;
    }

    private HttpClient.Version getHttpVersion() {
        if (method.isAnnotationPresent(HttpVersion.class)) {
            return method.getAnnotation(HttpVersion.class).value();
        }
        if (controller != null) {
            List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
            for (int i = hierarchy.size() - 1; i >= 0; i--) {
                Class<?> iface = hierarchy.get(i);
                if (iface.isAnnotationPresent(HttpVersion.class)) {
                    return iface.getAnnotation(HttpVersion.class).value();
                }
            }
        }
        return null;
    }

    // The per-method @Retry is a self-contained policy: its presence
    // replaces the client-level one entirely, no field-by-field merge
    // (ADR-0008, Aspect 2). times = 1 — the default — is the explicit
    // off-switch on a client that has retry enabled.
    private RetryPolicy resolveRetryPolicy() {
        if (method.isAnnotationPresent(Retry.class)) {
            Retry retry = method.getAnnotation(Retry.class);
            RetryPolicy.Builder builder = RetryPolicy.builder()
                    .maxAttempts(retry.times())
                    .initialBackoff(Duration.ofMillis(retry.initialBackoffMs()))
                    .maxBackoff(Duration.ofMillis(retry.maxBackoffMs()))
                    .retryableStatuses(Arrays.stream(retry.statuses()).boxed().toList())
                    .retryTransport(retry.transport())
                    .retryOnErrorStatuses(retry.onErrorStatuses())
                    .retryNonIdempotent(retry.nonIdempotent())
                    .retryDecodeFailures(retry.retryDecodeFailures())
                    .jitter(retry.jitter())
                    .backoffMultiplier(retry.multiplier())
                    .respectRetryAfter(retry.respectRetryAfter());
            if (retry.maxRetryAfterMs() >= 0) {
                builder.maxRetryAfter(Duration.ofMillis(retry.maxRetryAfterMs()));
            }
            return builder.build();
        }

        return moduleInfo.getRetryPolicy();
    }

    private Duration resolveTimeout() {
        if (method != null && method.isAnnotationPresent(Timeout.class)) {
            Timeout ann = method.getAnnotation(Timeout.class);
            if (ann.value() <= 0) {
                throw new IllegalArgumentException(method.getName() + "(): @Timeout value must be strictly positive, was " + ann.value());
            }
            if (ann.unit() == null) {
                throw new IllegalArgumentException(method.getName() + "(): @Timeout unit must not be null");
            }
            return Duration.of(ann.value(), ann.unit().toChronoUnit());
        }

        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(Timeout.class)) {
                Timeout ann = iface.getAnnotation(Timeout.class);
                if (ann.value() <= 0) {
                    throw new IllegalArgumentException(iface.getSimpleName() + ": @Timeout value must be strictly positive, was " + ann.value());
                }
                if (ann.unit() == null) {
                    throw new IllegalArgumentException(iface.getSimpleName() + ": @Timeout unit must not be null");
                }
                return Duration.of(ann.value(), ann.unit().toChronoUnit());
            }
        }

        return null;
    }

    private String resolveInterfaceAccept() {
        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(Accept.class)) {
                return String.join(", ", iface.getAnnotation(Accept.class).value());
            }
        }
        return null;
    }

    private String resolveMethodAccept() {
        if (method != null && method.isAnnotationPresent(Accept.class)) {
            return String.join(", ", method.getAnnotation(Accept.class).value());
        }
        return null;
    }

    private String resolveInterfaceContentType() {
        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(ContentType.class)) {
                return iface.getAnnotation(ContentType.class).value();
            }
        }
        return null;
    }

    private String resolveMethodContentType() {
        if (method != null && method.isAnnotationPresent(ContentType.class)) {
            return method.getAnnotation(ContentType.class).value();
        }
        return null;
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
        Set<Class<?>> seen = new HashSet<>();

        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (Class<?> iface : hierarchy) {
            WithInterceptor[] annotations = iface.getAnnotationsByType(WithInterceptor.class);
            for (WithInterceptor ann : annotations) {
                for (Class<?> clazz : sortedByOrder(ann.value())) {
                    addDeclarativeInterceptor(clazz, seen, reqs, resps);
                }
            }
        }

        if (method != null) {
            WithInterceptor[] methodAnnotations = method.getAnnotationsByType(WithInterceptor.class);
            for (WithInterceptor ann : methodAnnotations) {
                for (Class<?> clazz : sortedByOrder(ann.value())) {
                    addDeclarativeInterceptor(clazz, seen, reqs, resps);
                }
            }
        }

        reqs.sort(java.util.Comparator.comparingInt(i -> orderOf(i.getClass())));
        resps.sort(java.util.Comparator.comparingInt(i -> orderOf(i.getClass())));

        return new ResolvedInterceptors(
                Collections.unmodifiableList(reqs),
                Collections.unmodifiableList(resps)
        );
    }

    private void addDeclarativeInterceptor(
            Class<?> clazz,
            Set<Class<?>> seen,
            List<RequestInterceptor> reqs,
            List<ResponseInterceptor> resps
    ) {
        if (clazz == null || !seen.add(clazz)) {
            return;
        }
        Object instance = getOrCreateInterceptor(clazz);
        if (instance instanceof RequestInterceptor req) {
            reqs.add(req);
        }
        if (instance instanceof ResponseInterceptor resp) {
            resps.add(resp);
        }
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

    /**
     * Returns a new array of interceptor classes stably sorted by their {@link Order} value
     * (ascending — lower value runs first). Classes without {@code @Order} use
     * {@link Order#DEFAULT} ({@code 0}).
     *
     * @param classes the original interceptor class array from {@link WithInterceptor#value()}
     * @return a sorted copy; never modifies the original array
     */
    private static Class<?>[] sortedByOrder(Class<?>[] classes) {
        if (classes.length <= 1) {
            return classes;
        }
        Class<?>[] sorted = classes.clone();
        java.util.Arrays.sort(sorted,
                java.util.Comparator.comparingInt(HttpTemplateBuilder::orderOf));
        return sorted;
    }

    /**
     * Reads the {@link Order#value()} from the class annotation, or returns {@link Order#DEFAULT}.
     *
     * @param clazz the interceptor class
     * @return the order value
     */
    private static int orderOf(Class<?> clazz) {
        Order ann = clazz.getAnnotation(Order.class);
        return ann != null ? ann.value() : Order.DEFAULT;
    }


    private ClientMetricsListener resolveMetricsListener() {
        List<ClientMetricsListener> listeners = new ArrayList<>();
        if (moduleInfo.getMetricsListener() != null) {
            listeners.add(moduleInfo.getMetricsListener());
        }
        Set<Class<?>> seen = new HashSet<>();
        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (Class<?> iface : hierarchy) {
            WithMetrics[] annotations = iface.getAnnotationsByType(WithMetrics.class);
            for (WithMetrics ann : annotations) {
                for (Class<?> clazz : ann.value()) {
                    addDeclarativeMetricsListener(clazz, seen, listeners);
                }
            }
        }
        if (method != null) {
            WithMetrics[] methodAnnotations = method.getAnnotationsByType(WithMetrics.class);
            for (WithMetrics ann : methodAnnotations) {
                for (Class<?> clazz : ann.value()) {
                    addDeclarativeMetricsListener(clazz, seen, listeners);
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

    private void addDeclarativeMetricsListener(
            Class<?> clazz,
            Set<Class<?>> seen,
            List<ClientMetricsListener> listeners
    ) {
        if (clazz == null || !seen.add(clazz)) {
            return;
        }
        Object instance = getOrCreateInterceptor(clazz);
        if (instance instanceof ClientMetricsListener listener) {
            listeners.add(listener);
        }
    }

    private record EffectiveCompression(boolean enabled, CompressionAlgorithm algorithm, int minSizeBytes) {}

    private EffectiveCompression resolveCompression() {
        if (method != null && method.isAnnotationPresent(Compress.class)) {
            Compress ann = method.getAnnotation(Compress.class);
            if (!ann.enabled()) {
                return new EffectiveCompression(false, CompressionAlgorithm.NONE, 0);
            }
            return new EffectiveCompression(true, ann.algorithm(), Math.max(0, ann.minSizeBytes()));
        }

        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(Compress.class)) {
                Compress ann = iface.getAnnotation(Compress.class);
                if (!ann.enabled()) {
                    return new EffectiveCompression(false, CompressionAlgorithm.NONE, 0);
                }
                return new EffectiveCompression(true, ann.algorithm(), Math.max(0, ann.minSizeBytes()));
            }
        }

        if (moduleInfo != null && moduleInfo.getRequestCompressionAlgorithm() != null
                && moduleInfo.getRequestCompressionAlgorithm() != CompressionAlgorithm.NONE) {
            return new EffectiveCompression(true, moduleInfo.getRequestCompressionAlgorithm(),
                    Math.max(0, moduleInfo.getRequestCompressionThreshold()));
        }

        return new EffectiveCompression(false, CompressionAlgorithm.NONE, 0);
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
        if (method != null && method.isAnnotationPresent(ru.jrestly.annotation.CircuitBreaker.class)) {
            ru.jrestly.annotation.CircuitBreaker ann = method.getAnnotation(ru.jrestly.annotation.CircuitBreaker.class);
            if (!ann.enabled()) {
                return null;
            }
            return getCircuitBreakerFromAnnotation(ann);
        }

        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(ru.jrestly.annotation.CircuitBreaker.class)) {
                ru.jrestly.annotation.CircuitBreaker ann = iface.getAnnotation(ru.jrestly.annotation.CircuitBreaker.class);
                if (!ann.enabled()) {
                    return null;
                }
                return getCircuitBreakerFromAnnotation(ann);
            }
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
        if (method != null && method.isAnnotationPresent(ru.jrestly.annotation.RateLimiter.class)) {
            ru.jrestly.annotation.RateLimiter ann = method.getAnnotation(ru.jrestly.annotation.RateLimiter.class);
            if (!ann.enabled()) {
                return null;
            }
            return getRateLimiterFromAnnotation(ann);
        }

        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(ru.jrestly.annotation.RateLimiter.class)) {
                ru.jrestly.annotation.RateLimiter ann = iface.getAnnotation(ru.jrestly.annotation.RateLimiter.class);
                if (!ann.enabled()) {
                    return null;
                }
                return getRateLimiterFromAnnotation(ann);
            }
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
