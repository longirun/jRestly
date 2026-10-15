package ru.jrestly.http;

import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.FollowRedirects;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Order;
import ru.jrestly.annotation.Patch;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Put;
import ru.jrestly.annotation.QueryCollectionFormat;
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.SetAuthDetails;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.annotation.Trace;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.annotation.WithMetrics;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.metrics.ClientMetricsListener;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable metadata extracted and cached per (Method, ControllerClass) pair.
 * Eliminates repetitive reflection scans, annotation searches, and allocations
 * from the proxy dispatch hot-path (ADR-0051).
 */
final class MethodMetadata {

    private static final Map<MethodKey, MethodMetadata> CACHE = new ConcurrentHashMap<>();

    private final RequestMeta requestMeta;
    private final String methodContentType;
    private final String interfaceContentType;
    private final String methodAccept;
    private final String interfaceAccept;
    private final Trace interfaceTrace;
    private final CollectionFormat defaultCollectionFormat;
    private final OnErrorParser onErrorParser;
    private final List<Integer> expectStatuses;
    private final Integer followRedirectsNumber;
    private final HttpClient.Version httpVersion;
    private final RetryPolicy retryPolicy;
    private final Duration timeout;
    private final List<Class<? extends RequestInterceptor>> declarativeRequestInterceptorClasses;
    private final List<Class<? extends ResponseInterceptor>> declarativeResponseInterceptorClasses;
    private final List<Class<? extends ClientMetricsListener>> declarativeMetricsListenerClasses;
    private final EffectiveCompression compression;
    private final ru.jrestly.annotation.CircuitBreaker circuitBreakerAnnotation;
    private final ru.jrestly.annotation.RateLimiter rateLimiterAnnotation;
    private final ReturnTypeInfo returnTypeInfo;
    private final int responseHeadersParamIndex;
    private final int uploadProgressParamIndex;
    private final int downloadProgressParamIndex;
    private final String authDetailsHeaderName;

    record MethodKey(Method method, Class<?> controllerClass) {}

    record ReturnTypeInfo(Type effectiveReturnType, boolean async, boolean optional, boolean streamBody) {}

    record EffectiveCompression(boolean explicit, boolean enabled, CompressionAlgorithm algorithm, int minSizeBytes) {}

    private MethodMetadata(
            RequestMeta requestMeta,
            String methodContentType,
            String interfaceContentType,
            String methodAccept,
            String interfaceAccept,
            Trace interfaceTrace,
            CollectionFormat defaultCollectionFormat,
            OnErrorParser onErrorParser,
            List<Integer> expectStatuses,
            Integer followRedirectsNumber,
            HttpClient.Version httpVersion,
            RetryPolicy retryPolicy,
            Duration timeout,
            List<Class<? extends RequestInterceptor>> declarativeRequestInterceptorClasses,
            List<Class<? extends ResponseInterceptor>> declarativeResponseInterceptorClasses,
            List<Class<? extends ClientMetricsListener>> declarativeMetricsListenerClasses,
            EffectiveCompression compression,
            ru.jrestly.annotation.CircuitBreaker circuitBreakerAnnotation,
            ru.jrestly.annotation.RateLimiter rateLimiterAnnotation,
            ReturnTypeInfo returnTypeInfo,
            int responseHeadersParamIndex,
            int uploadProgressParamIndex,
            int downloadProgressParamIndex,
            String authDetailsHeaderName
    ) {
        this.requestMeta = requestMeta;
        this.methodContentType = methodContentType;
        this.interfaceContentType = interfaceContentType;
        this.methodAccept = methodAccept;
        this.interfaceAccept = interfaceAccept;
        this.interfaceTrace = interfaceTrace;
        this.defaultCollectionFormat = defaultCollectionFormat;
        this.onErrorParser = onErrorParser;
        this.expectStatuses = expectStatuses;
        this.followRedirectsNumber = followRedirectsNumber;
        this.httpVersion = httpVersion;
        this.retryPolicy = retryPolicy;
        this.timeout = timeout;
        this.declarativeRequestInterceptorClasses = declarativeRequestInterceptorClasses;
        this.declarativeResponseInterceptorClasses = declarativeResponseInterceptorClasses;
        this.declarativeMetricsListenerClasses = declarativeMetricsListenerClasses;
        this.compression = compression;
        this.circuitBreakerAnnotation = circuitBreakerAnnotation;
        this.rateLimiterAnnotation = rateLimiterAnnotation;
        this.returnTypeInfo = returnTypeInfo;
        this.responseHeadersParamIndex = responseHeadersParamIndex;
        this.uploadProgressParamIndex = uploadProgressParamIndex;
        this.downloadProgressParamIndex = downloadProgressParamIndex;
        this.authDetailsHeaderName = authDetailsHeaderName;
    }

    /**
     * Resolves and caches the metadata for the given method and controller interface.
     *
     * @param method the controller method
     * @param controller the controller interface class
     * @return the immutable metadata
     */
    static MethodMetadata of(Method method, Class<?> controller) {
        return CACHE.computeIfAbsent(new MethodKey(method, controller), key -> parse(key.method(), key.controllerClass()));
    }

    /** Clears the cache, for testing. */
    static void clearCache() {
        CACHE.clear();
    }

    private static MethodMetadata parse(Method method, Class<?> controller) {
        RequestMeta requestMeta = parseRequestMeta(method);
        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);

        String methodContentType = parseMethodContentType(method);
        String interfaceContentType = parseInterfaceContentType(hierarchy);
        String methodAccept = parseMethodAccept(method);
        String interfaceAccept = parseInterfaceAccept(hierarchy);
        Trace interfaceTrace = parseInterfaceTrace(hierarchy);
        CollectionFormat defaultCollectionFormat = parseDefaultCollectionFormat(method, hierarchy);
        OnErrorParser onErrorParser = parseOnErrorParser(method, hierarchy);
        List<Integer> expectStatuses = parseExpectStatuses(method);
        Integer followRedirects = parseFollowRedirects(method);
        HttpClient.Version httpVersion = parseHttpVersion(method, hierarchy);
        RetryPolicy retryPolicy = parseRetryPolicy(method);
        Duration timeout = parseTimeout(method, hierarchy);

        ResolvedInterceptors interceptors = parseInterceptors(method, hierarchy);
        List<Class<? extends ClientMetricsListener>> metricsListeners = parseMetricsListeners(method, hierarchy);
        EffectiveCompression compression = parseCompression(method, hierarchy);
        ru.jrestly.annotation.CircuitBreaker cb = parseCircuitBreaker(method, hierarchy);
        ru.jrestly.annotation.RateLimiter rl = parseRateLimiter(method, hierarchy);
        ReturnTypeInfo returnType = parseReturnType(method != null ? method.getGenericReturnType() : Object.class);

        int respHeadersIdx = -1;
        int uploadProgressIdx = -1;
        int downloadProgressIdx = -1;
        if (method != null) {
            Parameter[] params = method.getParameters();
            for (int i = 0; i < params.length; i++) {
                Class<?> type = params[i].getType();
                if (respHeadersIdx < 0 && ResponseHeaders.class.equals(type)) {
                    respHeadersIdx = i;
                } else if (uploadProgressIdx < 0 && UploadProgressListener.class.equals(type)) {
                    uploadProgressIdx = i;
                } else if (downloadProgressIdx < 0 && DownloadProgressListener.class.equals(type)) {
                    downloadProgressIdx = i;
                }
            }
        }

        String authDetailsHeader = null;
        if (method != null && method.isAnnotationPresent(SetAuthDetails.class)) {
            authDetailsHeader = method.getAnnotation(SetAuthDetails.class).headerName();
        }

        return new MethodMetadata(
                requestMeta,
                methodContentType,
                interfaceContentType,
                methodAccept,
                interfaceAccept,
                interfaceTrace,
                defaultCollectionFormat,
                onErrorParser,
                expectStatuses,
                followRedirects,
                httpVersion,
                retryPolicy,
                timeout,
                interceptors.requestInterceptors(),
                interceptors.responseInterceptors(),
                metricsListeners,
                compression,
                cb,
                rl,
                returnType,
                respHeadersIdx,
                uploadProgressIdx,
                downloadProgressIdx,
                authDetailsHeader
        );
    }

    private static RequestMeta parseRequestMeta(Method method) {
        if (method == null) {
            return null;
        }
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

    private static List<Class<?>> collectInterfaceHierarchy(Class<?> clazz) {
        List<Class<?>> result = new ArrayList<>();
        if (clazz != null) {
            collectInterfaces(clazz, result);
        }
        return result;
    }

    private static void collectInterfaces(Class<?> current, List<Class<?>> result) {
        for (Class<?> parent : current.getInterfaces()) {
            collectInterfaces(parent, result);
        }
        if (!result.contains(current)) {
            result.add(current);
        }
    }

    private static String parseMethodContentType(Method method) {
        if (method != null && method.isAnnotationPresent(ContentType.class)) {
            return method.getAnnotation(ContentType.class).value();
        }
        return null;
    }

    private static String parseInterfaceContentType(List<Class<?>> hierarchy) {
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(ContentType.class)) {
                return iface.getAnnotation(ContentType.class).value();
            }
        }
        return null;
    }

    private static String parseMethodAccept(Method method) {
        if (method != null && method.isAnnotationPresent(Accept.class)) {
            return String.join(", ", method.getAnnotation(Accept.class).value());
        }
        return null;
    }

    private static String parseInterfaceAccept(List<Class<?>> hierarchy) {
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(Accept.class)) {
                return String.join(", ", iface.getAnnotation(Accept.class).value());
            }
        }
        return null;
    }

    private static Trace parseInterfaceTrace(List<Class<?>> hierarchy) {
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(Trace.class)) {
                return iface.getAnnotation(Trace.class);
            }
        }
        return null;
    }

    private static CollectionFormat parseDefaultCollectionFormat(Method method, List<Class<?>> hierarchy) {
        if (method != null && method.isAnnotationPresent(QueryCollectionFormat.class)) {
            CollectionFormat format = method.getAnnotation(QueryCollectionFormat.class).value();
            if (format != null && format != CollectionFormat.DEFAULT) {
                return format;
            }
        }
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

    private static OnErrorParser parseOnErrorParser(Method method, List<Class<?>> hierarchy) {
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

    private static List<Integer> parseExpectStatuses(Method method) {
        if (method != null && method.isAnnotationPresent(ExpectStatus.class)) {
            int[] statuses = method.getAnnotation(ExpectStatus.class).statuses();
            return Arrays.stream(statuses).boxed().toList();
        }
        return null;
    }

    private static Integer parseFollowRedirects(Method method) {
        if (method != null && method.isAnnotationPresent(FollowRedirects.class)) {
            return method.getAnnotation(FollowRedirects.class).count();
        }
        return null;
    }

    private static HttpClient.Version parseHttpVersion(Method method, List<Class<?>> hierarchy) {
        if (method != null && method.isAnnotationPresent(HttpVersion.class)) {
            return method.getAnnotation(HttpVersion.class).value();
        }
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(HttpVersion.class)) {
                return iface.getAnnotation(HttpVersion.class).value();
            }
        }
        return null;
    }

    private static RetryPolicy parseRetryPolicy(Method method) {
        if (method != null && method.isAnnotationPresent(Retry.class)) {
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
        return null;
    }

    private static Duration parseTimeout(Method method, List<Class<?>> hierarchy) {
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

    private record ResolvedInterceptors(
            List<Class<? extends RequestInterceptor>> requestInterceptors,
            List<Class<? extends ResponseInterceptor>> responseInterceptors
    ) {}

    @SuppressWarnings("unchecked")
    private static ResolvedInterceptors parseInterceptors(Method method, List<Class<?>> hierarchy) {
        List<Class<? extends RequestInterceptor>> reqs = new ArrayList<>();
        List<Class<? extends ResponseInterceptor>> resps = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();

        for (Class<?> iface : hierarchy) {
            WithInterceptor[] annotations = iface.getAnnotationsByType(WithInterceptor.class);
            for (WithInterceptor ann : annotations) {
                for (Class<?> clazz : sortedByOrder(ann.value())) {
                    if (clazz != null && seen.add(clazz)) {
                        if (RequestInterceptor.class.isAssignableFrom(clazz)) {
                            reqs.add((Class<? extends RequestInterceptor>) clazz);
                        }
                        if (ResponseInterceptor.class.isAssignableFrom(clazz)) {
                            resps.add((Class<? extends ResponseInterceptor>) clazz);
                        }
                    }
                }
            }
        }

        if (method != null) {
            WithInterceptor[] methodAnnotations = method.getAnnotationsByType(WithInterceptor.class);
            for (WithInterceptor ann : methodAnnotations) {
                for (Class<?> clazz : sortedByOrder(ann.value())) {
                    if (clazz != null && seen.add(clazz)) {
                        if (RequestInterceptor.class.isAssignableFrom(clazz)) {
                            reqs.add((Class<? extends RequestInterceptor>) clazz);
                        }
                        if (ResponseInterceptor.class.isAssignableFrom(clazz)) {
                            resps.add((Class<? extends ResponseInterceptor>) clazz);
                        }
                    }
                }
            }
        }

        reqs.sort(java.util.Comparator.comparingInt(MethodMetadata::orderOf));
        resps.sort(java.util.Comparator.comparingInt(MethodMetadata::orderOf));

        return new ResolvedInterceptors(
                Collections.unmodifiableList(reqs),
                Collections.unmodifiableList(resps)
        );
    }

    private static Class<?>[] sortedByOrder(Class<?>[] classes) {
        if (classes.length <= 1) {
            return classes;
        }
        Class<?>[] sorted = classes.clone();
        Arrays.sort(sorted, java.util.Comparator.comparingInt(MethodMetadata::orderOf));
        return sorted;
    }

    private static int orderOf(Class<?> clazz) {
        Order ann = clazz.getAnnotation(Order.class);
        return ann != null ? ann.value() : Order.DEFAULT;
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends ClientMetricsListener>> parseMetricsListeners(Method method, List<Class<?>> hierarchy) {
        List<Class<? extends ClientMetricsListener>> list = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> iface : hierarchy) {
            WithMetrics[] annotations = iface.getAnnotationsByType(WithMetrics.class);
            for (WithMetrics ann : annotations) {
                for (Class<?> clazz : ann.value()) {
                    if (clazz != null && seen.add(clazz) && ClientMetricsListener.class.isAssignableFrom(clazz)) {
                        list.add((Class<? extends ClientMetricsListener>) clazz);
                    }
                }
            }
        }
        if (method != null) {
            WithMetrics[] methodAnnotations = method.getAnnotationsByType(WithMetrics.class);
            for (WithMetrics ann : methodAnnotations) {
                for (Class<?> clazz : ann.value()) {
                    if (clazz != null && seen.add(clazz) && ClientMetricsListener.class.isAssignableFrom(clazz)) {
                        list.add((Class<? extends ClientMetricsListener>) clazz);
                    }
                }
            }
        }
        return Collections.unmodifiableList(list);
    }

    private static EffectiveCompression parseCompression(Method method, List<Class<?>> hierarchy) {
        if (method != null && method.isAnnotationPresent(Compress.class)) {
            Compress ann = method.getAnnotation(Compress.class);
            if (!ann.enabled()) {
                return new EffectiveCompression(true, false, CompressionAlgorithm.NONE, 0);
            }
            return new EffectiveCompression(true, true, ann.algorithm(), Math.max(0, ann.minSizeBytes()));
        }

        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(Compress.class)) {
                Compress ann = iface.getAnnotation(Compress.class);
                if (!ann.enabled()) {
                    return new EffectiveCompression(true, false, CompressionAlgorithm.NONE, 0);
                }
                return new EffectiveCompression(true, true, ann.algorithm(), Math.max(0, ann.minSizeBytes()));
            }
        }

        return new EffectiveCompression(false, false, CompressionAlgorithm.NONE, 0);
    }

    private static ru.jrestly.annotation.CircuitBreaker parseCircuitBreaker(Method method, List<Class<?>> hierarchy) {
        if (method != null && method.isAnnotationPresent(ru.jrestly.annotation.CircuitBreaker.class)) {
            return method.getAnnotation(ru.jrestly.annotation.CircuitBreaker.class);
        }
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(ru.jrestly.annotation.CircuitBreaker.class)) {
                return iface.getAnnotation(ru.jrestly.annotation.CircuitBreaker.class);
            }
        }
        return null;
    }

    private static ru.jrestly.annotation.RateLimiter parseRateLimiter(Method method, List<Class<?>> hierarchy) {
        if (method != null && method.isAnnotationPresent(ru.jrestly.annotation.RateLimiter.class)) {
            return method.getAnnotation(ru.jrestly.annotation.RateLimiter.class);
        }
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(ru.jrestly.annotation.RateLimiter.class)) {
                return iface.getAnnotation(ru.jrestly.annotation.RateLimiter.class);
            }
        }
        return null;
    }

    private static ReturnTypeInfo parseReturnType(Type genericReturnType) {
        Type effective;
        boolean async = false;
        boolean optional = false;

        if (genericReturnType instanceof ParameterizedType parameterized
                && CompletableFuture.class.equals(parameterized.getRawType())) {
            async = true;
            effective = parameterized.getActualTypeArguments()[0];
        } else {
            effective = genericReturnType;
        }

        if (effective instanceof ParameterizedType parameterized
                && Optional.class.equals(parameterized.getRawType())) {
            optional = true;
            effective = parameterized.getActualTypeArguments()[0];
        } else if (Optional.class.equals(effective)) {
            optional = true;
            effective = Object.class;
        }

        boolean streamBody = InputStream.class.equals(effective)
                || HttpTemplate.isStreamType(effective, String.class)
                || HttpTemplate.isStreamType(effective, ServerSentEvent.class);

        return new ReturnTypeInfo(effective, async, optional, streamBody);
    }

    // Accessors

    RequestMeta requestMeta() {
        return requestMeta;
    }

    String methodContentType() {
        return methodContentType;
    }

    String interfaceContentType() {
        return interfaceContentType;
    }

    String methodAccept() {
        return methodAccept;
    }

    String interfaceAccept() {
        return interfaceAccept;
    }

    Trace interfaceTrace() {
        return interfaceTrace;
    }

    CollectionFormat defaultCollectionFormat() {
        return defaultCollectionFormat;
    }

    OnErrorParser onErrorParser() {
        return onErrorParser;
    }

    List<Integer> expectStatuses() {
        return expectStatuses;
    }

    Integer followRedirectsNumber() {
        return followRedirectsNumber;
    }

    HttpClient.Version httpVersion() {
        return httpVersion;
    }

    RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    Duration timeout() {
        return timeout;
    }

    List<Class<? extends RequestInterceptor>> declarativeRequestInterceptorClasses() {
        return declarativeRequestInterceptorClasses;
    }

    List<Class<? extends ResponseInterceptor>> declarativeResponseInterceptorClasses() {
        return declarativeResponseInterceptorClasses;
    }

    List<Class<? extends ClientMetricsListener>> declarativeMetricsListenerClasses() {
        return declarativeMetricsListenerClasses;
    }

    EffectiveCompression compression() {
        return compression;
    }

    ru.jrestly.annotation.CircuitBreaker circuitBreakerAnnotation() {
        return circuitBreakerAnnotation;
    }

    ru.jrestly.annotation.RateLimiter rateLimiterAnnotation() {
        return rateLimiterAnnotation;
    }

    ReturnTypeInfo returnTypeInfo() {
        return returnTypeInfo;
    }

    int responseHeadersParamIndex() {
        return responseHeadersParamIndex;
    }

    int uploadProgressParamIndex() {
        return uploadProgressParamIndex;
    }

    int downloadProgressParamIndex() {
        return downloadProgressParamIndex;
    }

    String authDetailsHeaderName() {
        return authDetailsHeaderName;
    }
}
