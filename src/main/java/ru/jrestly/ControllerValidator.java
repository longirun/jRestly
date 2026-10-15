package ru.jrestly;

import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Patch;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Put;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.http.RequestType;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a controller interface before its proxy is created.
 *
 * <p>Collects every non-synthetic, non-static method of the interface
 * and its parent interfaces (a sub-interface method wins over a parent
 * declaration with the same signature) and checks each one: it carries
 * exactly one HTTP verb annotation — {@code @Get}, {@code @Post},
 * {@code @Put}, {@code @Patch} or {@code @Delete} — and a method without
 * parameters is not declared form- or multipart-typed (those request
 * types need at least one parameter to build a body from).
 *
 * <p>Also validates that static {@code @RequestHeader} and
 * {@code @RequestDefaultParam} declarations on controller interfaces
 * and methods have non-blank {@code name()} attributes.
 *
 * <p>Runs at proxy creation ({@link AbstractHttpClient#createController}):
 * with the module's controller validation enabled, a non-empty result
 * fails the creation with a {@link ControllerValidationException}; with
 * it disabled, the problems are only logged.
 */
public final class ControllerValidator {

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

    private static void collectClassProblems(Class<?> current, List<String> problems, Set<Class<?>> visited) {
        if (current == null || !current.isInterface() || !visited.add(current)) {
            return;
        }
        for (RequestHeader header : current.getAnnotationsByType(RequestHeader.class)) {
            if (header.name().isBlank()) {
                problems.add(current.getSimpleName() + ": @RequestHeader name must not be blank");
            }
        }
        for (RequestDefaultParam param : current.getAnnotationsByType(RequestDefaultParam.class)) {
            if (param.name().isBlank()) {
                problems.add(current.getSimpleName() + ": @RequestDefaultParam name must not be blank");
            }
        }
        for (Timeout timeout : current.getAnnotationsByType(Timeout.class)) {
            if (timeout.value() <= 0) {
                problems.add(current.getSimpleName() + ": @Timeout value must be strictly positive, was " + timeout.value());
            }
        }
        Map<Integer, Class<?>> classSeenStatuses = new LinkedHashMap<>();
        for (OnError onError : current.getAnnotationsByType(OnError.class)) {
            for (int status : onError.statuses()) {
                if (status < 100 || status > 599) {
                    problems.add(current.getSimpleName() + ": @OnError status must be a valid HTTP status (100..599), was " + status);
                } else {
                    Class<?> prev = classSeenStatuses.put(status, onError.errorObject());
                    if (prev != null && !prev.equals(onError.errorObject())) {
                        problems.add(current.getSimpleName() + ": @OnError duplicate status " + status
                                + " mapped to conflicting classes: " + prev.getSimpleName() + " and " + onError.errorObject().getSimpleName());
                    }
                }
            }
        }
        for (WithInterceptor withInterceptor : current.getAnnotationsByType(WithInterceptor.class)) {
            for (Class<?> interceptorClass : withInterceptor.value()) {
                validateInterceptorClass(interceptorClass, current.getSimpleName(), problems);
            }
        }
        for (Class<?> parent : current.getInterfaces()) {
            collectClassProblems(parent, problems, visited);
        }
    }

    private static void collectMethods(Class<?> current, Map<String, Method> methods) {
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

    private static List<String> validateMethod(Method method) {
        List<String> problems = new ArrayList<>();

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

        if (httpAnnotations.size() != 1) {
            String problem = httpAnnotations.isEmpty()
                    ? "missing HTTP method annotation, expected one of @Get/@Post/@Put/@Patch/@Delete"
                    : "multiple HTTP method annotations " + httpAnnotations + ", exactly one is allowed";
            problems.add(method.getName() + "(): " + problem);
            return problems;
        }

        for (RequestHeader header : method.getAnnotationsByType(RequestHeader.class)) {
            if (header.name().isBlank()) {
                problems.add(method.getName() + "(): @RequestHeader name must not be blank");
            }
        }

        for (RequestDefaultParam param : method.getAnnotationsByType(RequestDefaultParam.class)) {
            if (param.name().isBlank()) {
                problems.add(method.getName() + "(): @RequestDefaultParam name must not be blank");
            }
        }

        for (RequestDefaultParam param : resolveVerbDefaultParams(method)) {
            if (param.name().isBlank()) {
                problems.add(method.getName() + "(): @RequestDefaultParam name must not be blank");
            }
        }

        if (method.isAnnotationPresent(Timeout.class)) {
            Timeout timeout = method.getAnnotation(Timeout.class);
            if (timeout.value() <= 0) {
                problems.add(method.getName() + "(): @Timeout value must be strictly positive, was " + timeout.value());
            }
        }

        Map<Integer, Class<?>> methodSeenStatuses = new LinkedHashMap<>();
        for (OnError onError : method.getAnnotationsByType(OnError.class)) {
            for (int status : onError.statuses()) {
                if (status < 100 || status > 599) {
                    problems.add(method.getName() + "(): @OnError status must be a valid HTTP status (100..599), was " + status);
                } else {
                    Class<?> prev = methodSeenStatuses.put(status, onError.errorObject());
                    if (prev != null && !prev.equals(onError.errorObject())) {
                        problems.add(method.getName() + "(): @OnError duplicate status " + status
                                + " mapped to conflicting classes: " + prev.getSimpleName() + " and " + onError.errorObject().getSimpleName());
                    }
                }
            }
        }

        long payloadParamCount = Arrays.stream(method.getParameters())
                .filter(p -> !ResponseHeaders.class.equals(p.getType()))
                .count();
        if (payloadParamCount == 0) {
            RequestType requestType = resolveRequestType(method);
            if (requestType == RequestType.MULTIPART_FORM_DATA) {
                problems.add(method.getName() + "(): MULTIPART_FORM_DATA method must have at least one parameter");
            } else if (requestType == RequestType.APPLICATION_FORM_URLENCODED) {
                problems.add(method.getName() + "(): APPLICATION_FORM_URLENCODED method must have at least one parameter");
            }
        }

        for (WithInterceptor withInterceptor : method.getAnnotationsByType(WithInterceptor.class)) {
            for (Class<?> interceptorClass : withInterceptor.value()) {
                validateInterceptorClass(interceptorClass, method.getName() + "()", problems);
            }
        }
        return problems;
    }

    private static void validateInterceptorClass(Class<?> clazz, String prefix, List<String> problems) {
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

    private static RequestDefaultParam[] resolveVerbDefaultParams(Method method) {
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

    private static RequestType resolveRequestType(Method method) {
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
}
