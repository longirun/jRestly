package ru.jrestly.http;

import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.annotation.Trace;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.metrics.ClientMetricsListener;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable metadata extracted and cached per (Method, ControllerClass) pair.
 * Eliminates repetitive reflection scans, annotation searches, and allocations
 * from the proxy dispatch hot-path (ADR-0051).
 * <p>
 * Parsing and reflection analysis are encapsulated in {@link MethodMetadataParser} (ADR-0090).
 */
final class MethodMetadata {

    private static final Map<MethodKey, MethodMetadata> CACHE = new ConcurrentHashMap<>();

    private final RequestMeta requestMeta;
    private final String methodContentType;
    private final String interfaceContentType;
    private final String methodAccept;
    private final String interfaceAccept;
    private final Trace interfaceTrace;
    private final Trace effectiveTrace;
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
    private final int targetUrlParamIndex;
    private final int responseHeadersParamIndex;
    private final int uploadProgressParamIndex;
    private final int downloadProgressParamIndex;
    private final String authDetailsHeaderName;

    // Zero-Reflection Hot-Path Additions
    private final List<Header> staticHeaders;
    private final Map<String, String> staticCookies;
    private final List<Param> staticDefaultParams;
    private final boolean hasAuthAnnotation;
    private final ParameterDescriptor[] parameterDescriptors;
    private final boolean hasPathVariables;
    private final boolean hasRequestHeaders;
    private final boolean hasCookieParams;
    private final boolean hasRequestParams;
    private final boolean hasMultipartParams;
    private final int requestBodyIndex;
    private final String staticCookieHeader;
    private final boolean hasExplicitContentTypeInStaticHeaders;
    private final boolean isStaticConditional;
    private final boolean hasTrace;

    record MethodKey(Method method, Class<?> controllerClass) {}

    record ReturnTypeInfo(Type effectiveReturnType, boolean async, boolean optional, boolean streamBody) {}

    record EffectiveCompression(boolean explicit, boolean enabled, CompressionAlgorithm algorithm, int minSizeBytes) {}

    record ParameterDescriptor(
            int index,
            boolean isPathVariable,
            String pathVariableName,
            String placeholderToken,
            boolean pathVariableEncoded,
            boolean isRequestHeader,
            String requestHeaderName,
            boolean isCookie,
            String cookieName,
            boolean isRequestParam,
            String requestParamName,
            String rawRequestParamName,
            String requestParamContentType,
            CollectionFormat requestParamFormat,
            boolean isRequestBody,
            boolean isMultipartFile,
            String multipartPartName,
            String multipartExplicitContentType,
            boolean isResponseHeaders,
            boolean isUploadProgressListener,
            boolean isDownloadProgressListener,
            boolean isTargetUrl
    ) {}

    MethodMetadata(
            RequestMeta requestMeta,
            String methodContentType,
            String interfaceContentType,
            String methodAccept,
            String interfaceAccept,
            Trace interfaceTrace,
            Trace effectiveTrace,
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
            int targetUrlParamIndex,
            int responseHeadersParamIndex,
            int uploadProgressParamIndex,
            int downloadProgressParamIndex,
            String authDetailsHeaderName,
            List<Header> staticHeaders,
            Map<String, String> staticCookies,
            List<Param> staticDefaultParams,
            boolean hasAuthAnnotation,
            ParameterDescriptor[] parameterDescriptors,
            boolean hasPathVariables,
            boolean hasRequestHeaders,
            boolean hasCookieParams,
            boolean hasRequestParams,
            boolean hasMultipartParams,
            int requestBodyIndex,
            String staticCookieHeader,
            boolean hasExplicitContentTypeInStaticHeaders,
            boolean isStaticConditional,
            boolean hasTrace
    ) {
        this.requestMeta = requestMeta;
        this.methodContentType = methodContentType;
        this.interfaceContentType = interfaceContentType;
        this.methodAccept = methodAccept;
        this.interfaceAccept = interfaceAccept;
        this.interfaceTrace = interfaceTrace;
        this.effectiveTrace = effectiveTrace;
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
        this.targetUrlParamIndex = targetUrlParamIndex;
        this.responseHeadersParamIndex = responseHeadersParamIndex;
        this.uploadProgressParamIndex = uploadProgressParamIndex;
        this.downloadProgressParamIndex = downloadProgressParamIndex;
        this.authDetailsHeaderName = authDetailsHeaderName;
        this.staticHeaders = staticHeaders;
        this.staticCookies = staticCookies;
        this.staticDefaultParams = staticDefaultParams;
        this.hasAuthAnnotation = hasAuthAnnotation;
        this.parameterDescriptors = parameterDescriptors;
        this.hasPathVariables = hasPathVariables;
        this.hasRequestHeaders = hasRequestHeaders;
        this.hasCookieParams = hasCookieParams;
        this.hasRequestParams = hasRequestParams;
        this.hasMultipartParams = hasMultipartParams;
        this.requestBodyIndex = requestBodyIndex;
        this.staticCookieHeader = staticCookieHeader;
        this.hasExplicitContentTypeInStaticHeaders = hasExplicitContentTypeInStaticHeaders;
        this.isStaticConditional = isStaticConditional;
        this.hasTrace = hasTrace;
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

    static MethodMetadata parse(Method method, Class<?> controller) {
        return MethodMetadataParser.parse(method, controller);
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

    Trace effectiveTrace() {
        return effectiveTrace;
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

    int targetUrlParamIndex() {
        return targetUrlParamIndex;
    }

    boolean hasTargetUrl() {
        return targetUrlParamIndex >= 0;
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

    List<Header> staticHeaders() {
        return staticHeaders;
    }

    Map<String, String> staticCookies() {
        return staticCookies;
    }

    List<Param> staticDefaultParams() {
        return staticDefaultParams;
    }

    boolean hasAuthAnnotation() {
        return hasAuthAnnotation;
    }

    ParameterDescriptor[] parameterDescriptors() {
        return parameterDescriptors;
    }

    boolean hasPathVariables() {
        return hasPathVariables;
    }

    boolean hasRequestHeaders() {
        return hasRequestHeaders;
    }

    boolean hasCookieParams() {
        return hasCookieParams;
    }

    boolean hasRequestParams() {
        return hasRequestParams;
    }

    boolean hasMultipartParams() {
        return hasMultipartParams;
    }

    int requestBodyIndex() {
        return requestBodyIndex;
    }

    String staticCookieHeader() {
        return staticCookieHeader;
    }

    boolean hasExplicitContentTypeInStaticHeaders() {
        return hasExplicitContentTypeInStaticHeaders;
    }

    boolean isStaticConditional() {
        return isStaticConditional;
    }

    boolean hasTrace() {
        return hasTrace;
    }
}
