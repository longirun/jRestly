package ru.jrestly;

import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.FollowRedirects;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Patch;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Put;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.SetAuthDetails;
import ru.jrestly.annotation.TargetUrl;
import ru.jrestly.http.RequestType;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.UploadProgressListener;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.metrics.ClientMetricsListener;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a controller interface before its proxy is created.
 *
 * <p>Collects every non-synthetic, non-static method of the interface
 * and its parent interfaces (a sub-interface method wins over a parent
 * declaration with the same signature) and checks each one: it carries
 * exactly one HTTP verb annotation — {@code @Get}, {@code @Post},
 * {@code @Put}, {@code @Patch} or {@code @Delete} — and a form- or
 * multipart-typed method has at least one parameter to build a body from
 * ({@code @RequestParam} for form-urlencoded; {@code @MultipartFormFile} or
 * {@code @RequestParam} for multipart).
 *
 * <p>A payload binding must match the verb's request type: a
 * {@code @RequestBody} parameter is consumed only on the default
 * {@code APPLICATION_JSON} path, and a {@code @MultipartFormFile} only on
 * {@code MULTIPART_FORM_DATA}; either binding on a mismatched method is
 * reported here instead of being silently dropped from the wire request.
 * A {@code @MultipartFormFile} parameter must not also carry
 * {@code @RequestParam}: the multipart builder turns a parameter into a
 * single part (the file), so the request-param binding would be silently
 * dropped.
 *
 * <p>Also validates that static {@code @RequestHeader} and
 * {@code @RequestDefaultParam} declarations on controller interfaces
 * and methods have non-blank {@code name()} attributes.
 *
 * <p>Each verb path is checked against its {@code @PathVariable} parameters
 * (ADR-0025): every {@code {name}} placeholder must have a matching
 * parameter, and every {@code @PathVariable} must match a placeholder. The
 * path must also be a valid URI reference once placeholders are masked: a
 * space, CR/LF or an unmatched brace fails here instead of at send time
 * inside {@code URI.create}.
 *
 * <p>A binding whose name falls back to the Java parameter name
 * ({@code @PathVariable}, {@code @RequestParam}, {@code @RequestHeader},
 * {@code @Cookie} with a blank {@code name()} on a non-{@code Map} parameter)
 * requires the compiler's {@code -parameters} flag; without it the fallback
 * silently becomes {@code arg0}. That misconfiguration is reported here too.
 *
 * <p>Side-channel hook parameters — {@link ResponseHeaders},
 * {@link UploadProgressListener}, and {@link DownloadProgressListener} —
 * must not carry HTTP request-binding annotations ({@code @RequestParam},
 * {@code @PathVariable}, {@code @RequestHeader}, {@code @Cookie},
 * {@code @RequestBody}, or {@code @MultipartFormFile}), and each may
 * appear at most once per method.
 *
 * <p>A parameter annotated with {@link TargetUrl} supplies a dynamic request
 * URL or base URL override; it must be of type {@link java.net.URI},
 * {@link java.net.URL}, or {@link CharSequence}/{@link String}, must not
 * combine with other HTTP parameter annotations, and at most one such
 * parameter is allowed per method (ADR-0062).
 *
 * <p>Method-level {@code @ExpectStatus} declarations must specify a non-empty
 * array of HTTP status codes in the valid range {@code [100, 599]}.
 * Method-level {@code @FollowRedirects} declarations must specify a
 * non-negative redirect hop count ({@code count >= 0}).
 *
 * <p>Runs at proxy creation ({@link AbstractHttpClient#createController}):
 * with the module's controller validation enabled, a non-empty result
 * fails the creation with a {@link ControllerValidationException}; with
 * it disabled, the problems are only logged.
 */
public final class ControllerValidator {

    // {name} placeholders in a verb's path template (ADR-0025)
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([^}]*)}");

    private ControllerValidator() {
    }

    /**
     * Finds the validation problems of the given controller interface.
     *
     * @param controller the controller interface to validate (together
     *        with its parent interfaces)
     * @return the human-readable problems found, empty when the interface is valid
     */
    public static List<String> validate(Class<?> controller) {
        List<String> problems = new ArrayList<>();
        collectClassProblems(controller, problems, new HashSet<>());

        Map<String, Method> methods = new LinkedHashMap<>();
        collectMethods(controller, methods);

        for (Method method : methods.values()) {
            problems.addAll(validateMethod(method));
        }
        return problems;
    }

    static void collectClassProblems(Class<?> current, List<String> problems, Set<Class<?>> visited) {
        if (current == null || !current.isInterface() || !visited.add(current)) {
            return;
        }
        String prefix = current.getSimpleName();
        AnnotationChecks.declarations(current, prefix, problems);
        AnnotationChecks.policies(current, prefix, problems);
        AnnotationChecks.media(current, prefix, problems);
        AnnotationChecks.interceptorsAndMetrics(current, prefix, problems);
        for (Class<?> parent : current.getInterfaces()) {
            collectClassProblems(parent, problems, visited);
        }
    }

    static void collectMethods(Class<?> current, Map<String, Method> methods) {
        if (current == null || !current.isInterface()) {
            return;
        }
        for (Method method : current.getDeclaredMethods()) {
            // static interface methods never reach the proxy, synthetic ones are compiler artifacts
            if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            // sub-interface method wins over the parent declaration with the same signature
            methods.putIfAbsent(signature(method), method);
        }
        for (Class<?> parent : current.getInterfaces()) {
            collectMethods(parent, methods);
        }
    }

    private static String signature(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes());
    }

    static List<String> validateMethod(Method method) {
        List<String> problems = new ArrayList<>();
        if (!validateVerbAnnotation(method, problems)) {
            return problems;
        }

        String prefix = method.getName() + "()";
        validatePathVariableMismatch(method, problems);
        validatePath(prefix, resolvePath(method), problems);
        validateParameterNames(method, problems);

        AnnotationChecks.declarations(method, prefix, problems);
        validateVerbDefaultParams(method, prefix, problems);
        AnnotationChecks.policies(method, prefix, problems);
        validateMethodPolicies(method, prefix, problems);
        AnnotationChecks.media(method, prefix, problems);

        RequestType requestType = resolveRequestType(method);
        for (Parameter param : method.getParameters()) {
            AnnotationChecks.parameter(param, requestType, prefix, problems);
        }
        AnnotationChecks.parameterCounts(method, requestType, prefix, problems);
        AnnotationChecks.interceptorsAndMetrics(method, prefix, problems);
        return problems;
    }

    private static boolean validateVerbAnnotation(Method method, List<String> problems) {
        List<String> httpAnnotations = new ArrayList<>();
        if (method.isAnnotationPresent(Get.class)) {
            httpAnnotations.add("@Get");
        }
        if (method.isAnnotationPresent(Post.class)) {
            httpAnnotations.add("@Post");
        }
        if (method.isAnnotationPresent(Put.class)) {
            httpAnnotations.add("@Put");
        }
        if (method.isAnnotationPresent(Patch.class)) {
            httpAnnotations.add("@Patch");
        }
        if (method.isAnnotationPresent(Delete.class)) {
            httpAnnotations.add("@Delete");
        }
        if (httpAnnotations.size() == 1) {
            return true;
        }
        String problem = httpAnnotations.isEmpty()
                ? "missing HTTP method annotation, expected one of @Get/@Post/@Put/@Patch/@Delete"
                : "multiple HTTP method annotations " + httpAnnotations + ", exactly one is allowed";
        problems.add(method.getName() + "(): " + problem);
        return false;
    }

    private static void validateVerbDefaultParams(Method method, String prefix, List<String> problems) {
        for (RequestDefaultParam param : resolveVerbDefaultParams(method)) {
            if (param.name().isBlank()) {
                problems.add(prefix + ": @RequestDefaultParam name must not be blank");
            }
        }
    }

    private static void validateMethodPolicies(Method method, String prefix, List<String> problems) {
        if (method.isAnnotationPresent(Retry.class)) {
            validateRetry(prefix, method.getAnnotation(Retry.class), problems);
        }
        if (method.isAnnotationPresent(SetAuthDetails.class)) {
            SetAuthDetails authDetails = method.getAnnotation(SetAuthDetails.class);
            if (authDetails.headerName().isBlank()) {
                problems.add(prefix + ": @SetAuthDetails headerName must not be blank");
            }
        }
        if (method.isAnnotationPresent(ExpectStatus.class)) {
            validateExpectStatus(prefix, method.getAnnotation(ExpectStatus.class), problems);
        }
        if (method.isAnnotationPresent(FollowRedirects.class)) {
            validateFollowRedirects(prefix, method.getAnnotation(FollowRedirects.class), problems);
        }
    }

    static void validateInterceptorClass(Class<?> clazz, String prefix, List<String> problems) {
        if (clazz == null) {
            problems.add(prefix + ": @WithInterceptor class must not be null");
            return;
        }
        if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
            problems.add(prefix + ": @WithInterceptor class " + clazz.getSimpleName() + " must not be abstract or an interface");
            return;
        }
        if (!RequestInterceptor.class.isAssignableFrom(clazz) && !ResponseInterceptor.class.isAssignableFrom(clazz)) {
            problems.add(prefix + ": @WithInterceptor class " + clazz.getSimpleName() + " must implement RequestInterceptor or ResponseInterceptor");
            return;
        }
        try {
            clazz.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            problems.add(prefix + ": @WithInterceptor class " + clazz.getSimpleName() + " must declare a no-arg constructor");
        }
    }

    static void validateMetricsClass(Class<?> clazz, String prefix, List<String> problems) {
        if (clazz == null) {
            problems.add(prefix + ": @WithMetrics class must not be null");
            return;
        }
        if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
            problems.add(prefix + ": @WithMetrics class " + clazz.getSimpleName() + " must not be abstract or an interface");
            return;
        }
        if (!ClientMetricsListener.class.isAssignableFrom(clazz)) {
            problems.add(prefix + ": @WithMetrics class " + clazz.getSimpleName() + " must implement ClientMetricsListener");
            return;
        }
        try {
            clazz.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            problems.add(prefix + ": @WithMetrics class " + clazz.getSimpleName() + " must declare a no-arg constructor");
        }
    }

    static RequestDefaultParam[] resolveVerbDefaultParams(Method method) {
        if (method.isAnnotationPresent(Get.class)) {
            return method.getAnnotation(Get.class).params();
        }
        if (method.isAnnotationPresent(Post.class)) {
            return method.getAnnotation(Post.class).params();
        }
        if (method.isAnnotationPresent(Put.class)) {
            return method.getAnnotation(Put.class).params();
        }
        if (method.isAnnotationPresent(Patch.class)) {
            return method.getAnnotation(Patch.class).params();
        }
        if (method.isAnnotationPresent(Delete.class)) {
            return method.getAnnotation(Delete.class).params();
        }
        return new RequestDefaultParam[0];
    }

    static RequestType resolveRequestType(Method method) {
        if (method.isAnnotationPresent(Get.class)) {
            return RequestType.APPLICATION_JSON;
        }
        if (method.isAnnotationPresent(Post.class)) {
            return method.getAnnotation(Post.class).requestType();
        }
        if (method.isAnnotationPresent(Put.class)) {
            return method.getAnnotation(Put.class).requestType();
        }
        if (method.isAnnotationPresent(Patch.class)) {
            return method.getAnnotation(Patch.class).requestType();
        }
        return method.getAnnotation(Delete.class).requestType();
    }

    static void validateCompress(String prefix, Compress compress, List<String> problems) {
        if (compress.algorithm() == null) {
            problems.add(prefix + ": @Compress algorithm must not be null");
        }
        if (compress.minSizeBytes() < 0) {
            problems.add(prefix + ": @Compress minSizeBytes must not be negative, was " + compress.minSizeBytes());
        }
    }

    static void validateCircuitBreaker(String prefix, ru.jrestly.annotation.CircuitBreaker cb, List<String> problems) {
        if (cb.name().isBlank()) {
            problems.add(prefix + ": @CircuitBreaker name must not be blank");
        }
        if (cb.failureRateThreshold() <= 0.0f || cb.failureRateThreshold() > 100.0f) {
            problems.add(prefix + ": @CircuitBreaker failureRateThreshold must be in (0.0, 100.0], was " + cb.failureRateThreshold());
        }
        if (cb.minimumNumberOfCalls() <= 0) {
            problems.add(prefix + ": @CircuitBreaker minimumNumberOfCalls must be strictly positive, was " + cb.minimumNumberOfCalls());
        }
        if (cb.slidingWindowSize() < cb.minimumNumberOfCalls()) {
            problems.add(prefix + ": @CircuitBreaker slidingWindowSize (" + cb.slidingWindowSize()
                    + ") must be >= minimumNumberOfCalls (" + cb.minimumNumberOfCalls() + ")");
        }
        if (cb.waitDurationMs() <= 0) {
            problems.add(prefix + ": @CircuitBreaker waitDurationMs must be strictly positive, was " + cb.waitDurationMs());
        }
        if (cb.permittedNumberOfCallsInHalfOpenState() <= 0) {
            problems.add(prefix + ": @CircuitBreaker permittedNumberOfCallsInHalfOpenState must be strictly positive, was "
                    + cb.permittedNumberOfCallsInHalfOpenState());
        }
    }

    static void validateRateLimiter(String prefix, ru.jrestly.annotation.RateLimiter rl, List<String> problems) {
        if (rl.name().isBlank()) {
            problems.add(prefix + ": @RateLimiter name must not be blank");
        }
        if (rl.limitForPeriod() <= 0) {
            problems.add(prefix + ": @RateLimiter limitForPeriod must be strictly positive, was " + rl.limitForPeriod());
        }
        if (rl.limitRefreshPeriodMs() <= 0) {
            problems.add(prefix + ": @RateLimiter limitRefreshPeriodMs must be strictly positive, was " + rl.limitRefreshPeriodMs());
        }
        if (rl.timeoutDurationMs() < 0) {
            problems.add(prefix + ": @RateLimiter timeoutDurationMs must not be negative, was " + rl.timeoutDurationMs());
        }
    }

    // ADR-0025: a path template and its @PathVariable parameters must agree.
    // An unresolved {name} placeholder never reaches the wire (URI.create throws
    // a cryptic "Illegal character in path" at send time), and a @PathVariable
    // with no matching placeholder is silently dropped from the URL. Both are
    // caught here, at proxy creation, through the aggregated channel.
    static void validatePathVariableMismatch(Method method, List<String> problems) {
        String path = resolvePath(method);
        if (path == null) {
            return;
        }
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = PATH_PLACEHOLDER.matcher(path);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }

        Set<String> pathVariables = new LinkedHashSet<>();
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(PathVariable.class)) {
                PathVariable pv = parameter.getAnnotation(PathVariable.class);
                if (pv.name().isEmpty() && !parameter.isNamePresent()) {
                    // the fallback name is the synthetic arg0; reported by
                    // validateParameterNames with an actionable message, so it is
                    // left out of the mismatch set to avoid a confusing bare-arg0 note
                    continue;
                }
                pathVariables.add(pv.name().isEmpty() ? parameter.getName() : pv.name());
            }
        }

        for (String placeholder : placeholders) {
            if (placeholder.isBlank()) {
                problems.add(method.getName() + "(): path placeholder '{}' must not be blank");
            } else if (!pathVariables.contains(placeholder)) {
                problems.add(method.getName() + "(): path placeholder '{" + placeholder
                    + "}' has no matching @PathVariable parameter");
            }
        }
        for (String pathVariable : pathVariables) {
            if (!placeholders.contains(pathVariable)) {
                problems.add(method.getName() + "(): @PathVariable '" + pathVariable
                        + "' does not match any {...} placeholder in path '" + path + "'");
            }
        }
    }

    // ADR-0025: a verb's path is the other half of the path/@PathVariable pair.
    // A path (or absolute URL) carrying a character that is illegal in a URI --
    // a space, a CR/LF, or an unmatched or unterminated brace -- is only rejected
    // at send time by URI.create ("Illegal character in path at index N" /
    // "Malformed escape pair"), naming neither the method nor the path. It is
    // caught here, at proxy creation. {name} placeholders are masked with a
    // same-length legal run first, so a valid template does not trip the check
    // and reported indices stay aligned with the declared path.
    static void validatePath(String prefix, String path, List<String> problems) {
        if (path == null) {
            return;
        }
        String masked = PATH_PLACEHOLDER.matcher(path)
                .replaceAll(match -> "x".repeat(match.group().length()));
        try {
            URI.create(masked);
        } catch (IllegalArgumentException e) {
            problems.add(prefix + ": path must be a valid URI reference: " + e.getMessage());
        }
    }

    // A binding with a blank name() falls back to the Java parameter name, which
    // the compiler only retains under -parameters. Without the flag the fallback
    // silently becomes "arg0": a query/header/cookie goes out under the wrong name
    // (the server ignores it), and a @PathVariable never matches its {...}
    // placeholder. Both are silent until the wire, so they are surfaced here, at
    // proxy creation. A Map parameter (and an HttpCookie) takes its names from the
    // entries/instance rather than the parameter name and is legitimately blank.
    static void validateParameterNames(Method method, List<String> problems) {
        for (Parameter parameter : method.getParameters()) {
            String annotation = fallbackNameAnnotation(parameter);
            if (annotation != null && !parameter.isNamePresent()) {
                problems.add(method.getName() + "(): @" + annotation
                        + " requires an explicit name() when the controller is compiled without '-parameters'"
                        + " (parameter names are not retained at runtime)");
            }
        }
    }

    static String fallbackNameAnnotation(Parameter parameter) {
        Class<?> type = parameter.getType();
        if (parameter.isAnnotationPresent(PathVariable.class)
                && parameter.getAnnotation(PathVariable.class).name().isEmpty()) {
            return "PathVariable";
        }
        if (parameter.isAnnotationPresent(RequestParam.class)
                && parameter.getAnnotation(RequestParam.class).name().isEmpty()
                && !Map.class.isAssignableFrom(type)) {
            return "RequestParam";
        }
        if (parameter.isAnnotationPresent(RequestHeader.class)
                && parameter.getAnnotation(RequestHeader.class).name().isEmpty()
                && !Map.class.isAssignableFrom(type)) {
            return "RequestHeader";
        }
        if (parameter.isAnnotationPresent(Cookie.class)
                && parameter.getAnnotation(Cookie.class).name().isEmpty()
                && !Map.class.isAssignableFrom(type)
                && !HttpCookie.class.isAssignableFrom(type)) {
            return "Cookie";
        }
        return null;
    }

    static String resolvePath(Method method) {
        if (method.isAnnotationPresent(Get.class)) {
            return method.getAnnotation(Get.class).value();
        }
        if (method.isAnnotationPresent(Post.class)) {
            return method.getAnnotation(Post.class).value();
        }
        if (method.isAnnotationPresent(Put.class)) {
            return method.getAnnotation(Put.class).value();
        }
        if (method.isAnnotationPresent(Patch.class)) {
            return method.getAnnotation(Patch.class).value();
        }
        if (method.isAnnotationPresent(Delete.class)) {
            return method.getAnnotation(Delete.class).value();
        }
        return null;
    }

    // Mirrors RetryPolicy.Builder.build() so a misconfigured @Retry surfaces
    // through the eager-validation channel (aggregated, actionable) instead of
    // a bare IllegalStateException at the first invocation (MethodMetadata.parse).
    static void validateRetry(String prefix, Retry retry, List<String> problems) {
        if (retry.times() < 1) {
            problems.add(prefix + ": @Retry times must be >= 1, was " + retry.times());
        }
        if (retry.initialBackoffMs() < 0) {
            problems.add(prefix + ": @Retry initialBackoffMs must not be negative, was " + retry.initialBackoffMs());
        }
        if (retry.maxBackoffMs() < 0) {
            problems.add(prefix + ": @Retry maxBackoffMs must not be negative, was " + retry.maxBackoffMs());
        }
        if (retry.initialBackoffMs() > retry.maxBackoffMs()) {
            problems.add(prefix + ": @Retry initialBackoffMs must not exceed maxBackoffMs, was "
                    + retry.initialBackoffMs() + " > " + retry.maxBackoffMs());
        }
        if (Double.isNaN(retry.multiplier()) || retry.multiplier() < 1.0) {
            problems.add(prefix + ": @Retry multiplier must be >= 1.0, was " + retry.multiplier());
        }
        for (int status : retry.statuses()) {
            if (status < 100 || status > 599) {
                problems.add(prefix + ": @Retry status must be in [100, 599], was " + status);
            }
        }
    }

    static void validateExpectStatus(String prefix, ExpectStatus expectStatus, List<String> problems) {
        int[] statuses = expectStatus.statuses();
        if (statuses.length == 0) {
            problems.add(prefix + ": @ExpectStatus statuses must not be empty");
        }
        for (int status : statuses) {
            if (status < 100 || status > 599) {
                problems.add(prefix + ": @ExpectStatus status must be in [100, 599], was " + status);
            }
        }
    }

    static void validateFollowRedirects(String prefix, FollowRedirects followRedirects, List<String> problems) {
        if (followRedirects.count() < 0) {
            problems.add(prefix + ": @FollowRedirects count must not be negative, was " + followRedirects.count());
        }
    }
}
