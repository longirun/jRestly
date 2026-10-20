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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
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
        }
        String url = UrlComposer.compose(requestMeta, methodMetadata, args, moduleInfo.getEnvironment().getUrl());
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
                explicitNames.add(h.name().toLowerCase(Locale.ROOT));
            }
            for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
                if (p.isRequestHeader()) {
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
            BodyComposer.BodyPayload payload = BodyComposer.compose(moduleInfo, methodMetadata, requestMeta, args,
                    effectiveContentType, isContentTypeExplicit);
            body = payload.bytes();
            bodyPublisherSupplier = payload.publisherSupplier();
            knownBodySize = payload.knownSize();
            effectiveContentType = payload.effectiveContentType();
            result.setContentType(effectiveContentType);
        } else if (RequestType.APPLICATION_FORM_URLENCODED.equals(requestType)) {
            body = createUrlEncodedBody();
            result.setContentType(effectiveContentType);
        } else if (RequestType.MULTIPART_FORM_DATA.equals(requestType)) {
            parts = BodyComposer.composeParts(moduleInfo, methodMetadata, requestMeta, args);

            String boundary = "jrestly-" + UUID.randomUUID().toString().replace("-", "");
            if (effectiveContentType.toLowerCase(Locale.ROOT).contains("multipart/")) {
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
                boolean hasContentEncoding = HeaderComposer.hasHeaderNamed(headers, "Content-Encoding");
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
        result.setRetryPolicy(ResilienceComposer.retryPolicy(moduleInfo, methodMetadata));
        ResolvedInterceptors resolvedInterceptors = resolveInterceptors();
        result.setRequestInterceptors(resolvedInterceptors.requestInterceptors());
        result.setResponseInterceptors(resolvedInterceptors.responseInterceptors());
        result.setResponseHeadersHolder(resolveResponseHeadersHolder());
        result.setUploadProgressListener(resolveUploadProgressListener());
        result.setDownloadProgressListener(resolveDownloadProgressListener());
        result.setMetricsListener(resolveMetricsListener());
        result.setExecutor(executor != null ? executor : moduleInfo.getExecutor());
        result.setTimeout(ResilienceComposer.timeout(methodMetadata));
        result.setCircuitBreaker(ResilienceComposer.circuitBreaker(moduleInfo, methodMetadata));
        result.setRateLimiter(ResilienceComposer.rateLimiter(moduleInfo, methodMetadata));
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
        if (args != null && methodMetadata.responseHeadersParamIndex() >= 0
                && methodMetadata.responseHeadersParamIndex() < args.length) {
            if (args[methodMetadata.responseHeadersParamIndex()] instanceof ResponseHeaders holder) {
                return holder;
            }
        }
        return null;
    }

    private UploadProgressListener resolveUploadProgressListener() {
        if (args != null && methodMetadata.uploadProgressParamIndex() >= 0
                && methodMetadata.uploadProgressParamIndex() < args.length) {
            if (args[methodMetadata.uploadProgressParamIndex()] instanceof UploadProgressListener listener) {
                return listener;
            }
        }
        return null;
    }

    private DownloadProgressListener resolveDownloadProgressListener() {
        if (args != null && methodMetadata.downloadProgressParamIndex() >= 0
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
        for (int i = 0, size = headers.size(); i < size; i++) {
            Header h = headers.get(i);
            String name = h.name();
            if (name != null && (name.equalsIgnoreCase("if-none-match") || name.equalsIgnoreCase("if-modified-since")
                    || name.equalsIgnoreCase("if-match") || name.equalsIgnoreCase("if-unmodified-since"))) {
                return true;
            }
        }
        return false;
    }

    private List<Header> createHeaders(String url) {
        // Header/cookie merge is its own collaborator (ADR-0083); tracing is applied
        // afterwards so its headers stay last on the wire.
        List<Header> result = HeaderComposer.compose(moduleInfo, methodMetadata, args);
        applyTracingHeaders(result, url);
        return result;
    }

    private void applyTracingHeaders(List<Header> result, String url) {
        // An active @Trace annotation overrides the client level (ADR-0042 §6.3);
        // a missing or disabled one falls back to the client-level TracePropagator (§6.4)
        Trace effectiveTrace = methodMetadata.effectiveTrace();
        if (effectiveTrace == null || !effectiveTrace.enabled()) {
            if (moduleInfo.getTracePropagator() != null) {
                applyTracePropagator(result, url);
            } else if (moduleInfo.getTraceIdSupplier() != null) {
                if (!HeaderComposer.hasHeaderNamed(result, "X-Request-ID") && !HeaderComposer.hasHeaderNamed(result, "traceparent")) {
                    String traceId = moduleInfo.getTraceIdSupplier().get();
                    if (traceId != null && !traceId.isBlank()) {
                        result.add(new Header("X-Request-ID", traceId.trim()));
                    }
                }
            }
            return;
        }
        if (effectiveTrace.w3c()) {
            boolean hasTraceparent = HeaderComposer.hasHeaderNamed(result, "traceparent");
            if (!hasTraceparent || effectiveTrace.forceNew()) {
                if (effectiveTrace.forceNew()) {
                    HeaderComposer.removeHeaderByName(result, "traceparent");
                }
                TraceContext tc = TraceContext.random();
                result.add(new Header("traceparent", tc.toTraceparent()));
            }
        } else {
            String headerName = effectiveTrace.header().trim();
            boolean hasHeader = HeaderComposer.hasHeaderNamed(result, headerName);
            if (!hasHeader || effectiveTrace.forceNew()) {
                if (effectiveTrace.forceNew()) {
                    HeaderComposer.removeHeaderByName(result, headerName);
                }
                String id = UUID.randomUUID().toString();
                result.add(new Header(headerName, id));
            }
        }
    }

    // Amendment #301 (ADR-0042): the URL is reused from build() — re-running
    // UrlComposer.compose here would repeat path-variable substitution and query
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

    private Consumer<List<Header>> getAuthDetailsConsumer() {
        if (methodMetadata.authDetailsHeaderName() != null) {
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
        return methodMetadata.defaultCollectionFormat();
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
        return methodMetadata.onErrorParser();
    }

    private List<Integer> createExpectStatuses() {
        return methodMetadata.expectStatuses();
    }

    private Integer getFollowRedirectsNumber() {
        return methodMetadata.followRedirectsNumber();
    }

    private HttpClient.Version getHttpVersion() {
        return methodMetadata.httpVersion();
    }

    private static void appendParamValues(List<Param> result, String name, Object value, CollectionFormat format) {
        if (value instanceof Collection<?> collection) {
            List<String> items = collection.stream()
                    .filter(Objects::nonNull)
                    .map(o -> HeaderComposer.textValue(o, "parameter"))
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
                    items.add(HeaderComposer.textValue(o, "parameter"));
                }
            }
            if (items.isEmpty()) {
                return;
            }
            appendFormattedItems(result, name, items, format);
            return;
        }

        result.add(new Param(name, HeaderComposer.textValue(value, "parameter")));
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
        for (Class<? extends ClientMetricsListener> clazz : methodMetadata.declarativeMetricsListenerClasses()) {
            Object instance = getOrCreateInterceptor(clazz);
            if (instance instanceof ClientMetricsListener listener) {
                listeners.add(listener);
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
        MethodMetadata.EffectiveCompression metaComp = methodMetadata.compression();
        if (metaComp.explicit()) {
            return new EffectiveCompression(metaComp.enabled(), metaComp.algorithm(), metaComp.minSizeBytes());
        }

        if (moduleInfo.getRequestCompressionAlgorithm() != null
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
        ContentCompressor custom = moduleInfo.getContentCompressor(algorithm.encoding());
        if (custom != null) {
            return custom;
        }
        return ContentCompressors.get(algorithm);
    }

}
