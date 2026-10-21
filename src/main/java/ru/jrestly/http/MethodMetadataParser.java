package ru.jrestly.http;

import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.Authorization;
import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.FollowRedirects;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Order;
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
import ru.jrestly.annotation.TargetUrl;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Stateless collaborator responsible for parsing annotations, parameter descriptors,
 * policies, and reflection metadata from controller methods and interfaces (ADR-0090).
 * <p>
 * Extracts the reflection-parsing concern from {@link MethodMetadata}, allowing
 * {@code MethodMetadata} to act as a focused, lightweight immutable value object.
 */
final class MethodMetadataParser {

    private MethodMetadataParser() {
        throw new UnsupportedOperationException("Stateless collaborator");
    }

    record ResolvedInterceptors(
            List<Class<? extends RequestInterceptor>> requestInterceptors,
            List<Class<? extends ResponseInterceptor>> responseInterceptors
    ) {}

    static MethodMetadata parse(Method method, Class<?> controller) {
        RequestMeta requestMeta = parseRequestMeta(method);
        List<Class<?>> hierarchy = collectInterfaceHierarchy(controller);

        String methodContentType = parseMethodContentType(method);
        String interfaceContentType = parseInterfaceContentType(hierarchy);
        String methodAccept = parseMethodAccept(method);
        String interfaceAccept = parseInterfaceAccept(hierarchy);
        Trace interfaceTrace = parseInterfaceTrace(hierarchy);
        Trace methodTrace = method != null ? method.getAnnotation(Trace.class) : null;
        Trace effectiveTrace = methodTrace != null ? methodTrace : interfaceTrace;

        CollectionFormat defaultCollectionFormat = parseDefaultCollectionFormat(method, hierarchy);
        OnErrorParser onErrorParser = parseOnErrorParser(method, hierarchy);
        List<Integer> expectStatuses = parseExpectStatuses(method);
        Integer followRedirects = parseFollowRedirects(method);
        HttpClient.Version httpVersion = parseHttpVersion(method, hierarchy);
        RetryPolicy retryPolicy = parseRetryPolicy(method);
        Duration timeout = parseTimeout(method, hierarchy);

        ResolvedInterceptors interceptors = parseInterceptors(method, hierarchy);
        List<Class<? extends ClientMetricsListener>> metricsListeners = parseMetricsListeners(method, hierarchy);
        MethodMetadata.EffectiveCompression compression = parseCompression(method, hierarchy);
        ru.jrestly.annotation.CircuitBreaker cb = parseCircuitBreaker(method, hierarchy);
        ru.jrestly.annotation.RateLimiter rl = parseRateLimiter(method, hierarchy);
        MethodMetadata.ReturnTypeInfo returnType = parseReturnType(method != null ? method.getGenericReturnType() : Object.class);

        int targetUrlIdx = -1;
        int respHeadersIdx = -1;
        int uploadProgressIdx = -1;
        int downloadProgressIdx = -1;
        int reqBodyIdx = -1;
        boolean hasPathVars = false;
        boolean hasReqHeaders = false;
        boolean hasCookies = false;
        boolean hasReqParams = false;
        boolean hasMultipart = false;

        Parameter[] params = method != null ? method.getParameters() : new Parameter[0];
        MethodMetadata.ParameterDescriptor[] descriptors = new MethodMetadata.ParameterDescriptor[params.length];

        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            Class<?> type = p.getType();

            boolean isTargetUrl = p.isAnnotationPresent(TargetUrl.class);
            if (isTargetUrl && targetUrlIdx < 0) {
                targetUrlIdx = i;
            }

            boolean isPathVar = p.isAnnotationPresent(PathVariable.class);
            String pathVarName = null;
            boolean pathVarEncoded = false;
            if (isPathVar) {
                hasPathVars = true;
                PathVariable pv = p.getAnnotation(PathVariable.class);
                pathVarName = pv.name().isEmpty() ? p.getName() : pv.name();
                pathVarEncoded = pv.encoded();
            }

            boolean isReqHeader = p.isAnnotationPresent(RequestHeader.class);
            String reqHeaderName = null;
            if (isReqHeader) {
                hasReqHeaders = true;
                RequestHeader rh = p.getAnnotation(RequestHeader.class);
                reqHeaderName = rh.name().isEmpty() ? p.getName() : rh.name();
            }

            boolean isCookie = p.isAnnotationPresent(Cookie.class);
            String cookieName = null;
            if (isCookie) {
                hasCookies = true;
                Cookie c = p.getAnnotation(Cookie.class);
                cookieName = c.name().isEmpty() ? p.getName() : c.name();
            }

            boolean isReqParam = p.isAnnotationPresent(RequestParam.class);
            String reqParamName = null;
            String rawReqParamName = "";
            String reqParamContentType = null;
            CollectionFormat reqParamFormat = defaultCollectionFormat;
            if (isReqParam) {
                hasReqParams = true;
                RequestParam rp = p.getAnnotation(RequestParam.class);
                rawReqParamName = rp.name();
                reqParamName = rp.name().isEmpty() ? p.getName() : rp.name();
                reqParamContentType = rp.contentType().isEmpty() ? null : rp.contentType();
                reqParamFormat = (rp.format() != CollectionFormat.DEFAULT)
                        ? rp.format()
                        : defaultCollectionFormat;
            }

            boolean isReqBody = p.isAnnotationPresent(RequestBody.class);
            if (isReqBody && reqBodyIdx < 0) {
                reqBodyIdx = i;
            }

            boolean isMultiFile = p.isAnnotationPresent(MultipartFormFile.class);
            String multiName = null;
            String multiContentType = null;
            if (isMultiFile) {
                hasMultipart = true;
                MultipartFormFile mff = p.getAnnotation(MultipartFormFile.class);
                multiName = mff.partName();
                multiContentType = mff.contentType().isEmpty() ? null : mff.contentType();
            }

            boolean isRespHeaders = ResponseHeaders.class.equals(type);
            if (isRespHeaders && respHeadersIdx < 0) {
                respHeadersIdx = i;
            }

            boolean isUploadListener = UploadProgressListener.class.equals(type);
            if (isUploadListener && uploadProgressIdx < 0) {
                uploadProgressIdx = i;
            }

            boolean isDownloadListener = DownloadProgressListener.class.equals(type);
            if (isDownloadListener && downloadProgressIdx < 0) {
                downloadProgressIdx = i;
            }

            String placeholderToken = isPathVar ? "{" + pathVarName + "}" : null;
            descriptors[i] = new MethodMetadata.ParameterDescriptor(
                    i,
                    isPathVar,
                    pathVarName,
                    placeholderToken,
                    pathVarEncoded,
                    isReqHeader,
                    reqHeaderName,
                    isCookie,
                    cookieName,
                    isReqParam,
                    reqParamName,
                    rawReqParamName,
                    reqParamContentType,
                    reqParamFormat,
                    isReqBody,
                    isMultiFile,
                    multiName,
                    multiContentType,
                    isRespHeaders,
                    isUploadListener,
                    isDownloadListener,
                    isTargetUrl
            );
        }

        String authDetailsHeader = null;
        if (method != null && method.isAnnotationPresent(SetAuthDetails.class)) {
            authDetailsHeader = method.getAnnotation(SetAuthDetails.class).headerName();
        }

        boolean hasAuth = method != null && method.isAnnotationPresent(Authorization.class);

        List<Header> staticHeaders = parseStaticHeaders(method, hierarchy, interfaceAccept, methodAccept);
        Map<String, String> staticCookies = parseStaticCookies(method, hierarchy);
        List<Param> staticDefaultParams = parseStaticDefaultParams(method, hierarchy, requestMeta);
        String staticCookieHeader = formatCookieHeader(staticCookies);
        boolean hasExplicitContentType = checkHasContentTypeHeader(staticHeaders);
        boolean isStaticConditional = checkIsConditional(staticHeaders);
        boolean hasTrace = effectiveTrace != null && effectiveTrace.enabled();

        return new MethodMetadata(
                requestMeta,
                methodContentType,
                interfaceContentType,
                methodAccept,
                interfaceAccept,
                interfaceTrace,
                effectiveTrace,
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
                targetUrlIdx,
                respHeadersIdx,
                uploadProgressIdx,
                downloadProgressIdx,
                authDetailsHeader,
                staticHeaders,
                staticCookies,
                staticDefaultParams,
                hasAuth,
                descriptors,
                hasPathVars,
                hasReqHeaders,
                hasCookies,
                hasReqParams,
                hasMultipart,
                reqBodyIdx,
                staticCookieHeader,
                hasExplicitContentType,
                isStaticConditional,
                hasTrace
        );
    }

    static List<Header> parseStaticHeaders(Method method, List<Class<?>> hierarchy, String interfaceAccept, String methodAccept) {
        Map<String, Header> map = new LinkedHashMap<>();
        for (Class<?> iface : hierarchy) {
            RequestHeader[] classHeaders = iface.getAnnotationsByType(RequestHeader.class);
            for (RequestHeader h : classHeaders) {
                removeCaseInsensitive(map, h.name());
                map.put(h.name(), new Header(h.name(), h.value()));
            }
        }
        if (interfaceAccept != null) {
            removeCaseInsensitive(map, "Accept");
            map.put("Accept", new Header("Accept", interfaceAccept));
        }
        if (method != null) {
            RequestHeader[] methodHeaders = method.getAnnotationsByType(RequestHeader.class);
            for (RequestHeader h : methodHeaders) {
                removeCaseInsensitive(map, h.name());
                map.put(h.name(), new Header(h.name(), h.value()));
            }
        }
        if (methodAccept != null) {
            removeCaseInsensitive(map, "Accept");
            map.put("Accept", new Header("Accept", methodAccept));
        }
        return List.copyOf(map.values());
    }

    static void removeCaseInsensitive(Map<String, ?> map, String name) {
        if (name == null) return;
        map.keySet().removeIf(k -> k.equalsIgnoreCase(name));
    }

    static Map<String, String> parseStaticCookies(Method method, List<Class<?>> hierarchy) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (Class<?> iface : hierarchy) {
            for (Cookie c : iface.getAnnotationsByType(Cookie.class)) {
                if (!c.name().isBlank()) {
                    cookies.put(c.name(), c.value());
                }
            }
        }
        if (method != null) {
            for (Cookie c : method.getAnnotationsByType(Cookie.class)) {
                if (!c.name().isBlank()) {
                    cookies.put(c.name(), c.value());
                }
            }
        }
        // Map.copyOf scrambles iteration order (salted per JVM run): the Cookie header
        // must keep declaration order, so the insertion-ordered map is wrapped instead
        return Collections.unmodifiableMap(cookies);
    }

    static List<Param> parseStaticDefaultParams(Method method, List<Class<?>> hierarchy, RequestMeta requestMeta) {
        Map<String, Param> map = new LinkedHashMap<>();
        for (Class<?> iface : hierarchy) {
            RequestDefaultParam[] classParams = iface.getAnnotationsByType(RequestDefaultParam.class);
            for (RequestDefaultParam p : classParams) {
                map.put(p.name(), new Param(p.name(), p.value()));
            }
        }
        if (method != null) {
            if (requestMeta != null && requestMeta.defaultParams() != null) {
                for (RequestDefaultParam p : requestMeta.defaultParams()) {
                    map.put(p.name(), new Param(p.name(), p.value()));
                }
            }
            RequestDefaultParam[] methodDirectParams = method.getAnnotationsByType(RequestDefaultParam.class);
            for (RequestDefaultParam p : methodDirectParams) {
                map.put(p.name(), new Param(p.name(), p.value()));
            }
        }
        return List.copyOf(map.values());
    }

    static String formatCookieHeader(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    static boolean checkHasContentTypeHeader(List<Header> headers) {
        if (headers == null) {
            return false;
        }
        for (Header h : headers) {
            if ("Content-Type".equalsIgnoreCase(h.name())) {
                return true;
            }
        }
        return false;
    }

    static boolean checkIsConditional(List<Header> headers) {
        if (headers == null) {
            return false;
        }
        for (Header h : headers) {
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

    static RequestMeta parseRequestMeta(Method method) {
        if (method == null) {
            return null;
        }
        if (method.isAnnotationPresent(Get.class)) {
            Get annotation = method.getAnnotation(Get.class);
            return new RequestMeta(HttpMethod.GET, annotation.value(), RequestType.APPLICATION_JSON, annotation.params());
        }
        if (method.isAnnotationPresent(Post.class)) {
            Post annotation = method.getAnnotation(Post.class);
            return new RequestMeta(HttpMethod.POST, annotation.value(), annotation.requestType(), annotation.params());
        }
        if (method.isAnnotationPresent(Put.class)) {
            Put annotation = method.getAnnotation(Put.class);
            return new RequestMeta(HttpMethod.PUT, annotation.value(), annotation.requestType(), annotation.params());
        }
        if (method.isAnnotationPresent(Patch.class)) {
            Patch annotation = method.getAnnotation(Patch.class);
            return new RequestMeta(HttpMethod.PATCH, annotation.value(), annotation.requestType(), annotation.params());
        }
        if (method.isAnnotationPresent(Delete.class)) {
            Delete annotation = method.getAnnotation(Delete.class);
            return new RequestMeta(HttpMethod.DELETE, annotation.value(), annotation.requestType(), annotation.params());
        }

        throw new UnsupportedOperationException("No HTTP method annotation found on " + method);
    }

    static List<Class<?>> collectInterfaceHierarchy(Class<?> clazz) {
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

    static String parseMethodContentType(Method method) {
        if (method != null && method.isAnnotationPresent(ContentType.class)) {
            return method.getAnnotation(ContentType.class).value();
        }
        return null;
    }

    static String parseInterfaceContentType(List<Class<?>> hierarchy) {
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(ContentType.class)) {
                return iface.getAnnotation(ContentType.class).value();
            }
        }
        return null;
    }

    static String parseMethodAccept(Method method) {
        if (method != null && method.isAnnotationPresent(Accept.class)) {
            return String.join(", ", method.getAnnotation(Accept.class).value());
        }
        return null;
    }

    static String parseInterfaceAccept(List<Class<?>> hierarchy) {
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(Accept.class)) {
                return String.join(", ", iface.getAnnotation(Accept.class).value());
            }
        }
        return null;
    }

    static Trace parseInterfaceTrace(List<Class<?>> hierarchy) {
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(Trace.class)) {
                return iface.getAnnotation(Trace.class);
            }
        }
        return null;
    }

    static CollectionFormat parseDefaultCollectionFormat(Method method, List<Class<?>> hierarchy) {
        if (method != null && method.isAnnotationPresent(QueryCollectionFormat.class)) {
            CollectionFormat format = method.getAnnotation(QueryCollectionFormat.class).value();
            if (format != CollectionFormat.DEFAULT) {
                return format;
            }
        }
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(QueryCollectionFormat.class)) {
                CollectionFormat format = iface.getAnnotation(QueryCollectionFormat.class).value();
                if (format != CollectionFormat.DEFAULT) {
                    return format;
                }
            }
        }
        return CollectionFormat.EXPLODE;
    }

    static OnErrorParser parseOnErrorParser(Method method, List<Class<?>> hierarchy) {
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

    static List<Integer> parseExpectStatuses(Method method) {
        if (method != null && method.isAnnotationPresent(ExpectStatus.class)) {
            int[] statuses = method.getAnnotation(ExpectStatus.class).statuses();
            return Arrays.stream(statuses).boxed().toList();
        }
        return null;
    }

    static Integer parseFollowRedirects(Method method) {
        if (method != null && method.isAnnotationPresent(FollowRedirects.class)) {
            return method.getAnnotation(FollowRedirects.class).count();
        }
        return null;
    }

    static HttpClient.Version parseHttpVersion(Method method, List<Class<?>> hierarchy) {
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

    static RetryPolicy parseRetryPolicy(Method method) {
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

    static Duration parseTimeout(Method method, List<Class<?>> hierarchy) {
        if (method != null && method.isAnnotationPresent(Timeout.class)) {
            Timeout ann = method.getAnnotation(Timeout.class);
            if (ann.value() <= 0) {
                throw new IllegalArgumentException(method.getName() + "(): @Timeout value must be strictly positive, was " + ann.value());
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
                return Duration.of(ann.value(), ann.unit().toChronoUnit());
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    static ResolvedInterceptors parseInterceptors(Method method, List<Class<?>> hierarchy) {
        List<Class<? extends RequestInterceptor>> reqs = new ArrayList<>();
        List<Class<? extends ResponseInterceptor>> resps = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();

        for (Class<?> iface : hierarchy) {
            WithInterceptor[] annotations = iface.getAnnotationsByType(WithInterceptor.class);
            for (WithInterceptor ann : annotations) {
                for (Class<?> clazz : sortedByOrder(ann.value())) {
                    if (seen.add(clazz)) {
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
                    if (seen.add(clazz)) {
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
        Arrays.sort(sorted, java.util.Comparator.comparingInt(MethodMetadataParser::orderOf));
        return sorted;
    }

    private static int orderOf(Class<?> clazz) {
        Order ann = clazz.getAnnotation(Order.class);
        return ann != null ? ann.value() : Order.DEFAULT;
    }

    @SuppressWarnings("unchecked")
    static List<Class<? extends ClientMetricsListener>> parseMetricsListeners(Method method, List<Class<?>> hierarchy) {
        List<Class<? extends ClientMetricsListener>> list = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> iface : hierarchy) {
            WithMetrics[] annotations = iface.getAnnotationsByType(WithMetrics.class);
            for (WithMetrics ann : annotations) {
                for (Class<?> clazz : ann.value()) {
                    if (seen.add(clazz) && ClientMetricsListener.class.isAssignableFrom(clazz)) {
                        list.add((Class<? extends ClientMetricsListener>) clazz);
                    }
                }
            }
        }
        if (method != null) {
            WithMetrics[] methodAnnotations = method.getAnnotationsByType(WithMetrics.class);
            for (WithMetrics ann : methodAnnotations) {
                for (Class<?> clazz : ann.value()) {
                    if (seen.add(clazz) && ClientMetricsListener.class.isAssignableFrom(clazz)) {
                        list.add((Class<? extends ClientMetricsListener>) clazz);
                    }
                }
            }
        }
        return Collections.unmodifiableList(list);
    }

    static MethodMetadata.EffectiveCompression parseCompression(Method method, List<Class<?>> hierarchy) {
        if (method != null && method.isAnnotationPresent(Compress.class)) {
            Compress ann = method.getAnnotation(Compress.class);
            if (!ann.enabled()) {
                return new MethodMetadata.EffectiveCompression(true, false, CompressionAlgorithm.NONE, 0);
            }
            return new MethodMetadata.EffectiveCompression(true, true, ann.algorithm(), Math.max(0, ann.minSizeBytes()));
        }

        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> iface = hierarchy.get(i);
            if (iface.isAnnotationPresent(Compress.class)) {
                Compress ann = iface.getAnnotation(Compress.class);
                if (!ann.enabled()) {
                    return new MethodMetadata.EffectiveCompression(true, false, CompressionAlgorithm.NONE, 0);
                }
                return new MethodMetadata.EffectiveCompression(true, true, ann.algorithm(), Math.max(0, ann.minSizeBytes()));
            }
        }

        return new MethodMetadata.EffectiveCompression(false, false, CompressionAlgorithm.NONE, 0);
    }

    static ru.jrestly.annotation.CircuitBreaker parseCircuitBreaker(Method method, List<Class<?>> hierarchy) {
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

    static ru.jrestly.annotation.RateLimiter parseRateLimiter(Method method, List<Class<?>> hierarchy) {
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

    static MethodMetadata.ReturnTypeInfo parseReturnType(Type genericReturnType) {
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
                || ResponseBody.isStreamType(effective, String.class)
                || ResponseBody.isStreamType(effective, ServerSentEvent.class)
                || ResponseBody.isTypedEventStream(effective)
                || ResponseBody.isDataStream(effective);

        return new MethodMetadata.ReturnTypeInfo(effective, async, optional, streamBody);
    }
}
