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
import java.nio.file.Files;
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
        result.setHttpCache(moduleInfo.getHttpCache());

        if (httpEngine != null) {
            result.setHttpEngine(httpEngine);
        } else if (httpClient != null) {
            result.setHttpClient(httpClient);
        }
        String url = createUrl();
        result.setUrl(url);
        result.setHttpMethod(requestMeta.httpMethod());

        List<Header> headers = createHeaders(url);

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
        // Header names to drop on cross-origin redirects: the ambient AuthProvider
        // names minus the ones the controller explicitly declares (static or dynamic
        // @RequestHeader) — an explicit value is the caller's intentional choice and
        // must survive the redirect (ADR-0002), not be treated as an ambient credential.
        List<Header> authHeaders = moduleInfo.getAuthProvider().getAuthHeaders();
        if (authHeaders == null || authHeaders.isEmpty()) {
            result.setAuthHeaderNames(List.of());
        } else {
            Set<String> explicitNames = new HashSet<>();
            for (Header h : methodMetadata.staticHeaders()) {
                if (h.name() != null) {
                    explicitNames.add(h.name().toLowerCase(Locale.ROOT));
                }
            }
            for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
                if (p.isRequestHeader() && p.requestHeaderName() != null) {
                    explicitNames.add(p.requestHeaderName().toLowerCase(Locale.ROOT));
                }
            }
            List<String> authNames = new ArrayList<>(authHeaders.size());
            for (int i = 0, size = authHeaders.size(); i < size; i++) {
                String name = authHeaders.get(i).name();
                if (name != null && !explicitNames.contains(name.toLowerCase(Locale.ROOT))) {
                    authNames.add(name);
                }
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
        Long knownBodySize = null;
        List<MultipartPart> parts = null;
        if (RequestType.APPLICATION_JSON.equals(requestType)) {
            BodyPayload payload = createJsonBodyPayload(effectiveContentType, isContentTypeExplicit);
            body = payload.bytes();
            bodyPublisherSupplier = payload.publisherSupplier();
            knownBodySize = payload.knownSize();
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
                boolean hasContentEncoding = hasHeaderNamed(headers, "Content-Encoding");
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
        result.setKnownBodySize(knownBodySize);
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

        Duration effectiveTimeout = HttpTemplate.effectiveSocketTimeout(result.getTimeout(), moduleInfo);
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
                            : UriEncoder.encode(arg.toString());
                    path = path.replace(p.placeholderToken(), substitutedValue);
                }
            }
        }

        String targetUrl = null;
        if (methodMetadata.hasTargetUrl() && args != null && methodMetadata.targetUrlParamIndex() < args.length) {
            Object arg = args[methodMetadata.targetUrlParamIndex()];
            if (arg == null) {
                throw new IllegalArgumentException("@TargetUrl parameter value cannot be null");
            }
            targetUrl = arg.toString();
        }

        if (targetUrl != null) {
            if (path == null || path.isEmpty()) {
                return targetUrl;
            }
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path;
            }
            int queryOrFrag = -1;
            int qIdx = targetUrl.indexOf('?');
            int hIdx = targetUrl.indexOf('#');
            if (qIdx >= 0 && hIdx >= 0) {
                queryOrFrag = Math.min(qIdx, hIdx);
            } else if (qIdx >= 0) {
                queryOrFrag = qIdx;
            } else {
                queryOrFrag = hIdx;
            }
            if (queryOrFrag >= 0) {
                String basePart = targetUrl.substring(0, queryOrFrag);
                String restPart = targetUrl.substring(queryOrFrag);
                // The verb path may carry its own static query (e.g. @Get("/items?size=10"));
                // merge it before the target's query/fragment so both survive.
                int pathQueryIdx = path.indexOf('?');
                if (pathQueryIdx < 0) {
                    return joinUrlPath(basePart, path) + restPart;
                }
                String pathPart = joinUrlPath(basePart, path.substring(0, pathQueryIdx));
                String pathQuery = path.substring(pathQueryIdx + 1);
                if (restPart.startsWith("?")) {
                    return pathPart + "?" + pathQuery + "&" + restPart.substring(1);
                }
                return pathPart + "?" + pathQuery + restPart;
            }
            return joinUrlPath(targetUrl, path);
        }

        return !path.startsWith("http")
                ? moduleInfo.getEnvironment().getUrl() + path
                : path;
    }

    static String joinUrlPath(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        } else {
            return base + path;
        }
    }

    private List<Header> createHeaders(String url) {
        List<Header> authHeaders = moduleInfo.getAuthProvider().getAuthHeaders();
        List<Header> staticHeaders = methodMetadata.staticHeaders();
        List<Header> result;
        if (authHeaders == null || authHeaders.isEmpty()) {
            result = new ArrayList<>(staticHeaders);
        } else if (staticHeaders.isEmpty()) {
            result = new ArrayList<>(authHeaders);
        } else {
            // Candidate #14: merge without an intermediate HashSet and per-name
            // lowercased strings — both header lists are small, so a direct
            // case-insensitive scan is cheaper than building and hashing a name
            // set. Parity: ambient headers not overridden by a static one keep
            // their order, then all static headers (a static declaration wins
            // over the ambient header with the same name).
            result = new ArrayList<>(authHeaders.size() + staticHeaders.size());
            for (int i = 0, authSize = authHeaders.size(); i < authSize; i++) {
                Header ambient = authHeaders.get(i);
                boolean overridden = false;
                for (Header s : staticHeaders) {
                    if (s.name().equalsIgnoreCase(ambient.name())) {
                        overridden = true;
                        break;
                    }
                }
                if (!overridden) {
                    result.add(ambient);
                }
            }
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
                            removeHeaderByName(result, headerName);
                            appendHeaderValues(result, headerName, entry.getValue());
                        }
                        continue;
                    }

                    String name = p.requestHeaderName();
                    removeHeaderByName(result, name);
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

        applyTracingHeaders(result, url);

        return result;
    }

    // Candidate #14: in-place removal without a per-invocation Predicate instance —
    // removeIf(h -> h.name().equalsIgnoreCase(name)) allocated a lambda per dynamic
    // header on every request. Null names are skipped (ambient providers are a
    // public seam) instead of failing inside the predicate.
    private static void removeHeaderByName(List<Header> result, String name) {
        for (int i = result.size() - 1; i >= 0; i--) {
            Header h = result.get(i);
            if (h.name() != null && h.name().equalsIgnoreCase(name)) {
                result.remove(i);
            }
        }
    }

    // Candidate #14: plain scan instead of stream().anyMatch(...) — a stream
    // pipeline allocates a Stream, a pipeline stage, and a predicate per check,
    // and the checks run on the per-request hot path.
    private static boolean hasHeaderNamed(List<Header> headers, String name) {
        for (int i = 0, size = headers.size(); i < size; i++) {
            Header h = headers.get(i);
            if (h.name() != null && h.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void applyTracingHeaders(List<Header> result, String url) {
        // An active @Trace annotation overrides the client level (ADR-0042 §6.3);
        // a missing or disabled one falls back to the client-level TracePropagator (§6.4)
        Trace effectiveTrace = methodMetadata.effectiveTrace();
        if (effectiveTrace == null || !effectiveTrace.enabled()) {
            if (moduleInfo != null && moduleInfo.getTracePropagator() != null) {
                applyTracePropagator(result, url);
            }
            return;
        }
        if (effectiveTrace.w3c()) {
            boolean hasTraceparent = hasHeaderNamed(result, "traceparent");
            if (!hasTraceparent || effectiveTrace.forceNew()) {
                if (effectiveTrace.forceNew()) {
                    removeHeaderByName(result, "traceparent");
                }
                TraceContext tc = TraceContext.random();
                result.add(new Header("traceparent", tc.toTraceparent()));
            }
        } else {
            String headerName = effectiveTrace.header().trim();
            boolean hasHeader = hasHeaderNamed(result, headerName);
            if (!hasHeader || effectiveTrace.forceNew()) {
                if (effectiveTrace.forceNew()) {
                    removeHeaderByName(result, headerName);
                }
                String id = UUID.randomUUID().toString();
                result.add(new Header(headerName, id));
            }
        }
    }

    // Amendment #301 (ADR-0042): the URL is reused from build() — re-running
    // createUrl() here would repeat path-variable substitution and query
    // joining on every ambient propagation, doubling URL assembly allocations.
    private void applyTracePropagator(List<Header> result, String url) {
        // Candidate #14: pass the live merge list — the TraceRequestContext compact
        // constructor applies the single unmodifiable view. Wrapping it here as well
        // allocated a second pure wrapper object per ambient-trace request.
        TraceRequestContext traceContext = new TraceRequestContext(
                controller,
                method,
                url,
                requestMeta.httpMethod(),
                result
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
                        // Deliberately the raw annotation name, not the ADR-0017 effective name:
                        // for a Map, name() is an optional namespace prefix — blank sends keys as-is (Amendment #296).
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
            String effectiveContentType,
            Long knownSize
    ) {}

    private BodyPayload createJsonBodyPayload(String effectiveContentType, boolean isContentTypeExplicit) {
        Object body = createBody(method, args);
        if (body == null) {
            return new BodyPayload(null, null, effectiveContentType, null);
        }

        Charset charset = parseCharset(effectiveContentType, StandardCharsets.UTF_8);

        if (body instanceof HttpRequest.BodyPublisher bp) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            return new BodyPayload(null, () -> bp, ct, null);
        }

        if (body instanceof Flow.Publisher<?> publisher) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            Flow.Publisher<ByteBuffer> adapted = adaptToByteBufferPublisher(publisher, charset);
            return new BodyPayload(null, () -> HttpRequest.BodyPublishers.fromPublisher(adapted), ct, null);
        }

        if (body instanceof InputStream inputStream) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            return new BodyPayload(null, () -> HttpRequest.BodyPublishers.ofInputStream(() -> inputStream), ct, null);
        }

        if (body instanceof Path path) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            return new BodyPayload(null, () -> {
                try {
                    return HttpRequest.BodyPublishers.ofFile(path);
                } catch (FileNotFoundException e) {
                    throw new UncheckedIOException(e);
                }
            }, ct, fileSizeBytes(path));
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
            }, ct, fileSizeBytes(path));
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
            return new BodyPayload(null, publisherSupplier, ct, null);
        }

        if (body instanceof byte[] rawBytes) {
            return new BodyPayload(rawBytes, null, effectiveContentType, null);
        }

        if (body instanceof String stringBody) {
            return new BodyPayload(stringBody.getBytes(charset), null, effectiveContentType, null);
        }

        PayloadCodec codec = moduleInfo.getCodecRegistry().resolve(effectiveContentType);
        if (codec == null) {
            throw new IllegalStateException("No PayloadCodec registered for Content-Type '"
                    + effectiveContentType + "'. Register one via JRestlyClient.builder().codec(...) "
                    + "or send a raw body type (String, byte[], InputStream, Path, File, Supplier, "
                    + "Flow.Publisher, BodyPublisher).");
        }
        byte[] bodyBytes = codec.serialize(body, effectiveContentType);
        return new BodyPayload(bodyBytes, null, effectiveContentType, null);
    }

    // ADR-0036 Amendment #298: the engine-known size of a file body, for the
    // metrics byte-accounting seam — the size is readable without touching the
    // stream the send reads from; a vanished file degrades to unknown (null)
    static Long fileSizeBytes(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }

    static Flow.Publisher<ByteBuffer> adaptToByteBufferPublisher(Flow.Publisher<?> source, Charset charset) {
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
                switch (item) {
                    case ByteBuffer bb -> subscriber.onNext(bb);
                    case byte[] b -> subscriber.onNext(ByteBuffer.wrap(b));
                    case String s -> subscriber.onNext(ByteBuffer.wrap(s.getBytes(charset)));
                    case null -> {}
                    default -> subscriber.onNext(ByteBuffer.wrap(item.toString().getBytes(charset)));
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

    List<MultipartPart> createMultipartParts() {
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
                    // Deliberately the raw annotation name, not the ADR-0017 effective name:
                    // for a Map, name() is an optional namespace prefix — blank sends keys as-is (Amendment #296).
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
                List<String> strItems = items.stream().map(v -> textValue(v, "parameter")).toList();
                parts.add(createPartFromValue(name, String.join(",", strItems), explicitContentType));
            }
            case SSV -> {
                List<String> strItems = items.stream().map(v -> textValue(v, "parameter")).toList();
                parts.add(createPartFromValue(name, String.join(" ", strItems), explicitContentType));
            }
            case TSV -> {
                List<String> strItems = items.stream().map(v -> textValue(v, "parameter")).toList();
                parts.add(createPartFromValue(name, String.join("\t", strItems), explicitContentType));
            }
            case PIPES -> {
                List<String> strItems = items.stream().map(v -> textValue(v, "parameter")).toList();
                parts.add(createPartFromValue(name, String.join("|", strItems), explicitContentType));
            }
            case BRACKETS -> {
                String bracketName = name + "[]";
                for (Object item : items) {
                    parts.add(createPartFromValue(bracketName, item, explicitContentType));
                }
            }
            case EXPLODE, DEFAULT -> {
                for (Object item : items) {
                    parts.add(createPartFromValue(name, item, explicitContentType));
                }
            }
        }
    }

    private MultipartPart createPartFromValue(String partName, Object value, String explicitContentType) {
        if (explicitContentType == null || explicitContentType.isEmpty()) {
            if (value instanceof byte[]) {
                throw new IllegalArgumentException(
                        "A byte[] multipart part requires an explicit Content-Type: declare "
                                + "@RequestParam(contentType = \"application/octet-stream\") to send raw bytes, "
                                + "or encode the bytes to text explicitly (e.g. Base64) before passing.");
            }
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

    static Charset parseCharset(String contentType, Charset defaultCharset) {
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
        if (arg == null) {
            throw new IllegalArgumentException("Multipart file argument cannot be null");
        }
        return switch (arg) {
            case FileContent content -> MultipartPart.file(partName, content.fileName(), explicitContentType,
                    content.content(), content.contentLength());
            case byte[] ignored -> throw new IllegalArgumentException(
                    "A bare byte[] cannot be a multipart file: the wire file name cannot be derived from it. "
                            + "Wrap in-memory bytes in FileContent.of(fileName, bytes)");
            case File file -> createFilePartFromPath(partName, file.toPath(), explicitContentType);
            case Path p -> createFilePartFromPath(partName, p, explicitContentType);
            case String str -> createFilePartFromPath(partName, Path.of(str), explicitContentType);
            default -> throw new IllegalArgumentException(
                    "Unsupported multipart file argument of type " + arg.getClass().getName()
                            + ". Expected Path, File, String, or FileContent");
        };
    }

    private MultipartPart createFilePartFromPath(String partName, Path path, String explicitContentType) {
        if (path.getFileName() == null) {
            throw new IllegalArgumentException("Path must have a file name: " + path);
        }
        return MultipartPart.file(partName, path.getFileName().toString(), explicitContentType, path, null);
    }

    // Unpacks collections, arrays, and varargs into individual part values
    private static List<?> multipartFileValues(Object arg) {
        return switch (arg) {
            case null -> List.of();
            case Collection<?> collection -> collection.stream().filter(Objects::nonNull).toList();
            case Object[] array -> Arrays.stream(array).filter(Objects::nonNull).toList();
            default -> List.of(arg);
        };
    }

    byte[] createUrlEncodedBody() {
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


    private static void appendHeaderValues(List<Header> result, String name, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object o : collection) {
                if (o != null) {
                    result.add(new Header(name, textValue(o, "header")));
                }
            }
        } else if (value.getClass().isArray() && !byte[].class.equals(value.getClass())) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object o = java.lang.reflect.Array.get(value, i);
                if (o != null) {
                    result.add(new Header(name, textValue(o, "header")));
                }
            }
        } else {
            result.add(new Header(name, textValue(value, "header")));
        }
    }

    // Header and parameter positions are text on the wire; a byte[] has no defined
    // text rendering, so it fails fast instead of silently sending "[B@...'" garbage.
    private static String textValue(Object value, String position) {
        return switch (value) {
            case null -> "null";
            case byte[] ignored -> throw new IllegalArgumentException(
                    "A byte[] cannot be a " + position + " value: header and parameter values are text on the wire. "
                            + "Send raw bytes as @RequestBody, or encode the bytes to text explicitly "
                            + "(e.g. Base64 or new String(bytes, StandardCharsets.ISO_8859_1)) before passing.");
            default -> value.toString();
        };
    }

    private static void appendParamValues(List<Param> result, String name, Object value, CollectionFormat format) {
        if (value == null) {
            return;
        }

        if (value instanceof Collection<?> collection) {
            List<String> items = collection.stream()
                    .filter(Objects::nonNull)
                    .map(o -> textValue(o, "parameter"))
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
                    items.add(textValue(o, "parameter"));
                }
            }
            if (items.isEmpty()) {
                return;
            }
            appendFormattedItems(result, name, items, format);
            return;
        }

        result.add(new Param(name, textValue(value, "parameter")));
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
            case EXPLODE, DEFAULT -> {
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
