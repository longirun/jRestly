package ru.jrestly;

import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.CircuitBreaker;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.QueryCollectionFormat;
import ru.jrestly.annotation.RateLimiter;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.TargetUrl;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.annotation.Trace;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.annotation.WithMetrics;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.RequestType;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.http.UploadProgressListener;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared eager-validation rules for controller declarations, used by both
 * passes of {@link ControllerValidator}: the interface pass and the method
 * pass.
 *
 * <p>Every rule takes a bare problem prefix — {@code Api} for an interface or
 * {@code get()} for a method — and formats the same message shape in both
 * passes. The rules are pure: they read annotations, never mutate the
 * controller, and append problems in declaration order. Keeping the two
 * passes on one implementation is deliberate: class-level and method-level
 * declarations share the same invariants (ADR-0025, ADR-0041), and a rule
 * fixed in one pass must not drift from the other.
 */
final class AnnotationChecks {

    private AnnotationChecks() {
    }

    /**
     * Checks repeatable name-bearing declarations: {@code @RequestHeader} and
     * {@code @RequestDefaultParam} must have a non-blank name.
     */
    static void declarations(AnnotatedElement element, String prefix, List<String> problems) {
        for (RequestHeader header : element.getAnnotationsByType(RequestHeader.class)) {
            if (header.name().isBlank()) {
                problems.add(prefix + ": @RequestHeader name must not be blank");
            }
        }
        for (RequestDefaultParam param : element.getAnnotationsByType(RequestDefaultParam.class)) {
            if (param.name().isBlank()) {
                problems.add(prefix + ": @RequestDefaultParam name must not be blank");
            }
        }
    }

    /**
     * Checks timeout, compression, tracing, circuit-breaker and rate-limiter
     * policies declared on an interface or method.
     */
    static void policies(AnnotatedElement element, String prefix, List<String> problems) {
        for (Timeout timeout : element.getAnnotationsByType(Timeout.class)) {
            if (timeout.value() <= 0) {
                problems.add(prefix + ": @Timeout value must be strictly positive, was " + timeout.value());
            }
        }
        for (Compress compress : element.getAnnotationsByType(Compress.class)) {
            ControllerValidator.validateCompress(prefix, compress, problems);
        }
        for (Trace trace : element.getAnnotationsByType(Trace.class)) {
            if (trace.header().isBlank()) {
                problems.add(prefix + ": @Trace header must not be blank");
            }
        }
        for (CircuitBreaker cb : element.getAnnotationsByType(CircuitBreaker.class)) {
            ControllerValidator.validateCircuitBreaker(prefix, cb, problems);
        }
        for (RateLimiter rl : element.getAnnotationsByType(RateLimiter.class)) {
            ControllerValidator.validateRateLimiter(prefix, rl, problems);
        }
    }

    /**
     * Checks media-type, cookie and error-mapping declarations, including
     * conflicting {@code @OnError} status mappings within the same element.
     */
    static void media(AnnotatedElement element, String prefix, List<String> problems) {
        for (Accept accept : element.getAnnotationsByType(Accept.class)) {
            if (accept.value().length == 0) {
                problems.add(prefix + ": @Accept value must not be empty");
            }
            for (String val : accept.value()) {
                if (val.isBlank()) {
                    problems.add(prefix + ": @Accept media type must not be blank");
                }
                if (val.contains("\r") || val.contains("\n")) {
                    problems.add(prefix + ": @Accept media type must not contain CRLF characters");
                }
            }
        }
        for (ContentType contentType : element.getAnnotationsByType(ContentType.class)) {
            if (contentType.value().isBlank()) {
                problems.add(prefix + ": @ContentType must not be blank");
            }
            if (contentType.value().contains("\r") || contentType.value().contains("\n")) {
                problems.add(prefix + ": @ContentType must not contain CRLF characters");
            }
        }
        for (Cookie cookie : element.getAnnotationsByType(Cookie.class)) {
            if (cookie.name().isBlank()) {
                problems.add(prefix + ": @Cookie name must not be blank");
            }
            if (cookie.name().contains("\r") || cookie.name().contains("\n")) {
                problems.add(prefix + ": @Cookie name must not contain CRLF characters");
            }
            if (cookie.value().contains("\r") || cookie.value().contains("\n")) {
                problems.add(prefix + ": @Cookie value must not contain CRLF characters");
            }
        }
        for (QueryCollectionFormat qcf : element.getAnnotationsByType(QueryCollectionFormat.class)) {
            if (qcf.value() == null) {
                problems.add(prefix + ": @QueryCollectionFormat value must not be null");
            }
        }
        Map<Integer, Class<?>> seenStatuses = new LinkedHashMap<>();
        for (OnError onError : element.getAnnotationsByType(OnError.class)) {
            for (int status : onError.statuses()) {
                if (status < 100 || status > 599) {
                    problems.add(prefix + ": @OnError status must be a valid HTTP status (100..599), was " + status);
                } else {
                    Class<?> prev = seenStatuses.put(status, onError.errorObject());
                    if (prev != null && !prev.equals(onError.errorObject())) {
                        problems.add(prefix + ": @OnError duplicate status " + status
                                + " mapped to conflicting classes: " + prev.getSimpleName()
                                + " and " + onError.errorObject().getSimpleName());
                    }
                }
            }
        }
    }

    /**
     * Checks one method parameter: payload bindings must match the request
     * type, and HTTP parameter annotations must not be combined with each
     * other or with side-channel hook parameters.
     */
    static void parameter(Parameter param, RequestType requestType, String prefix, List<String> problems) {
        if (param.isAnnotationPresent(RequestBody.class)) {
            if (!RequestType.APPLICATION_JSON.equals(requestType)) {
                problems.add(prefix + ": @RequestBody parameter requires requestType = APPLICATION_JSON");
            }
            if (param.isAnnotationPresent(RequestParam.class)
                    || param.isAnnotationPresent(PathVariable.class)
                    || param.isAnnotationPresent(RequestHeader.class)
                    || param.isAnnotationPresent(Cookie.class)
                    || param.isAnnotationPresent(MultipartFormFile.class)) {
                problems.add(prefix + ": parameter must not combine @RequestBody with other HTTP parameter annotations");
            }
        }
        if (param.isAnnotationPresent(RequestParam.class)) {
            RequestParam rp = param.getAnnotation(RequestParam.class);
            if (rp.format() == null) {
                problems.add(prefix + ": @RequestParam format must not be null");
            }
        }
        if (param.isAnnotationPresent(Cookie.class)) {
            Cookie cookie = param.getAnnotation(Cookie.class);
            if (cookie.name().contains("\r") || cookie.name().contains("\n")) {
                problems.add(prefix + ": @Cookie parameter name must not contain CRLF characters");
            }
            if (param.isAnnotationPresent(RequestParam.class)
                    || param.isAnnotationPresent(PathVariable.class)
                    || param.isAnnotationPresent(RequestHeader.class)
                    || param.isAnnotationPresent(RequestBody.class)
                    || param.isAnnotationPresent(MultipartFormFile.class)) {
                problems.add(prefix + ": parameter must not combine @Cookie with other HTTP parameter annotations");
            }
        }
        if (param.isAnnotationPresent(MultipartFormFile.class)) {
            MultipartFormFile part = param.getAnnotation(MultipartFormFile.class);
            if (part.partName().isBlank()) {
                problems.add(prefix + ": @MultipartFormFile partName must not be blank");
            }
            if (!RequestType.MULTIPART_FORM_DATA.equals(requestType)) {
                problems.add(prefix + ": @MultipartFormFile parameter requires requestType = MULTIPART_FORM_DATA");
            }
            if (param.isAnnotationPresent(RequestParam.class)) {
                problems.add(prefix + ": parameter must not combine @MultipartFormFile with @RequestParam"
                        + " (only the file part is assembled, the @RequestParam binding is silently dropped)");
            }
        }
        if (param.isAnnotationPresent(TargetUrl.class)) {
            Class<?> type = param.getType();
            if (!URI.class.isAssignableFrom(type) && !URL.class.isAssignableFrom(type)
                    && !CharSequence.class.isAssignableFrom(type)) {
                problems.add(prefix + ": @TargetUrl parameter must be of type java.net.URI, java.net.URL, or CharSequence/String");
            }
            if (hasHttpBindingAnnotation(param)) {
                problems.add(prefix + ": parameter must not combine @TargetUrl with other HTTP parameter annotations");
            }
        }
        if (ResponseHeaders.class.equals(param.getType())) {
            if (hasHttpBindingAnnotation(param) || param.isAnnotationPresent(TargetUrl.class)) {
                problems.add(prefix + ": ResponseHeaders parameter must not be annotated with HTTP parameter annotations");
            }
        }
        if (UploadProgressListener.class.equals(param.getType())) {
            if (hasHttpBindingAnnotation(param) || param.isAnnotationPresent(TargetUrl.class)) {
                problems.add(prefix + ": UploadProgressListener parameter must not be annotated with HTTP parameter annotations");
            }
        }
        if (DownloadProgressListener.class.equals(param.getType())) {
            if (hasHttpBindingAnnotation(param) || param.isAnnotationPresent(TargetUrl.class)) {
                problems.add(prefix + ": DownloadProgressListener parameter must not be annotated with HTTP parameter annotations");
            }
        }
    }

    /**
     * Checks method-wide parameter cardinality and payload presence: at most
     * one dynamic URL and one of each side-channel hook, at most one
     * {@code @RequestBody}, no body on {@code @Get}, and at least one body
     * parameter on form/multipart methods.
     */
    static void parameterCounts(Method method, RequestType requestType,
                                String prefix, List<String> problems) {
        int targetUrlCount = 0;
        int responseHeadersCount = 0;
        int uploadProgressCount = 0;
        int downloadProgressCount = 0;
        int requestBodyCount = 0;
        boolean hasMultipartPayload = false;
        boolean hasFormPayload = false;
        for (Parameter param : method.getParameters()) {
            if (param.isAnnotationPresent(TargetUrl.class)) {
                targetUrlCount++;
            }
            if (ResponseHeaders.class.equals(param.getType())) {
                responseHeadersCount++;
            }
            if (UploadProgressListener.class.equals(param.getType())) {
                uploadProgressCount++;
            }
            if (DownloadProgressListener.class.equals(param.getType())) {
                downloadProgressCount++;
            }
            if (param.isAnnotationPresent(RequestBody.class)) {
                requestBodyCount++;
            }
            if (param.isAnnotationPresent(MultipartFormFile.class) || param.isAnnotationPresent(RequestParam.class)) {
                hasMultipartPayload = true;
            }
            if (param.isAnnotationPresent(RequestParam.class)) {
                hasFormPayload = true;
            }
        }
        if (targetUrlCount > 1) {
            problems.add(prefix + ": multiple parameters annotated with @TargetUrl, at most one is allowed");
        }
        if (responseHeadersCount > 1) {
            problems.add(prefix + ": multiple parameters of type ResponseHeaders, at most one is allowed");
        }
        if (uploadProgressCount > 1) {
            problems.add(prefix + ": multiple parameters of type UploadProgressListener, at most one is allowed");
        }
        if (downloadProgressCount > 1) {
            problems.add(prefix + ": multiple parameters of type DownloadProgressListener, at most one is allowed");
        }
        if (requestBodyCount > 1) {
            problems.add(prefix + ": multiple parameters annotated with @RequestBody, at most one is allowed");
        }
        if (method.isAnnotationPresent(Get.class) && requestBodyCount > 0) {
            problems.add(prefix + ": @Get method must not declare @RequestBody parameter");
        }
        if (requestType == RequestType.MULTIPART_FORM_DATA) {
            if (!hasMultipartPayload) {
                problems.add(prefix + ": MULTIPART_FORM_DATA method must have at least one parameter");
            }
        } else if (requestType == RequestType.APPLICATION_FORM_URLENCODED) {
            if (!hasFormPayload) {
                problems.add(prefix + ": APPLICATION_FORM_URLENCODED method must have at least one parameter");
            }
        }
    }

    /**
     * Checks {@code @WithInterceptor} and {@code @WithMetrics} class
     * declarations on an interface or method.
     */
    static void interceptorsAndMetrics(AnnotatedElement element, String prefix, List<String> problems) {
        for (WithInterceptor withInterceptor : element.getAnnotationsByType(WithInterceptor.class)) {
            for (Class<?> interceptorClass : withInterceptor.value()) {
                ControllerValidator.validateInterceptorClass(interceptorClass, prefix, problems);
            }
        }
        for (WithMetrics withMetrics : element.getAnnotationsByType(WithMetrics.class)) {
            for (Class<?> metricsClass : withMetrics.value()) {
                ControllerValidator.validateMetricsClass(metricsClass, prefix, problems);
            }
        }
    }

    private static boolean hasHttpBindingAnnotation(Parameter param) {
        return param.isAnnotationPresent(RequestParam.class)
                || param.isAnnotationPresent(PathVariable.class)
                || param.isAnnotationPresent(RequestHeader.class)
                || param.isAnnotationPresent(Cookie.class)
                || param.isAnnotationPresent(RequestBody.class)
                || param.isAnnotationPresent(MultipartFormFile.class);
    }
}
