package ru.jrestly;

import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Patch;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Put;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.http.RequestType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ControllerValidator {

    private ControllerValidator() {
    }

    public static List<String> validate(Class<?> controller) {
        Map<String, Method> methods = new LinkedHashMap<>();
        collectMethods(controller, methods);

        List<String> problems = new ArrayList<>();
        problems.addAll(validateStaticDeclarations(controller));
        for (Method method : methods.values()) {
            problems.addAll(validateMethod(method));
        }
        return problems;
    }

    private static List<String> validateStaticDeclarations(Class<?> controller) {
        List<String> problems = new ArrayList<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        collectInterfaces(controller, hierarchy);

        for (Class<?> iface : hierarchy) {
            for (RequestHeader header : iface.getAnnotationsByType(RequestHeader.class)) {
                if (header.name().isBlank()) {
                    problems.add(iface.getSimpleName() + ": @RequestHeader name must not be blank");
                }
            }
            for (RequestDefaultParam param : iface.getAnnotationsByType(RequestDefaultParam.class)) {
                if (param.name().isBlank()) {
                    problems.add(iface.getSimpleName() + ": @RequestDefaultParam name must not be blank");
                }
            }
        }
        return problems;
    }

    private static void collectInterfaces(Class<?> current, List<Class<?>> result) {
        if (current == null || result.contains(current)) {
            return;
        }
        for (Class<?> parent : current.getInterfaces()) {
            collectInterfaces(parent, result);
        }
        result.add(current);
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
        validateParameterNames(method, problems);

        if (method.getParameters().length == 0) {
            RequestType requestType = resolveRequestType(method);
            if (requestType == RequestType.MULTIPART_FORM_DATA) {
                problems.add(method.getName() + "(): MULTIPART_FORM_DATA method must have at least one parameter");
            } else if (requestType == RequestType.APPLICATION_FORM_URLENCODED) {
                problems.add(method.getName() + "(): APPLICATION_FORM_URLENCODED method must have at least one parameter");
            }
        }
        return problems;
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

    // A blank name() falls back to the Java parameter name, which the compiler only
    // retains under -parameters; without the flag the request silently goes out as
    // "arg0". A Map parameter takes its names from the entries and is legitimately blank.
    private static void validateParameterNames(Method method, List<String> problems) {
        for (Parameter parameter : method.getParameters()) {
            String annotation = fallbackNameAnnotation(parameter);
            if (annotation != null && !parameter.isNamePresent()) {
                problems.add(method.getName() + "(): @" + annotation
                        + " requires an explicit name() when the controller is compiled without '-parameters'"
                        + " (parameter names are not retained at runtime)");
            }
        }
    }

    private static String fallbackNameAnnotation(Parameter parameter) {
        if (parameter.isAnnotationPresent(PathVariable.class)
                && parameter.getAnnotation(PathVariable.class).name().isEmpty()) {
            return "PathVariable";
        }
        if (parameter.isAnnotationPresent(RequestParam.class)
                && parameter.getAnnotation(RequestParam.class).name().isEmpty()
                && !Map.class.isAssignableFrom(parameter.getType())) {
            return "RequestParam";
        }
        if (parameter.isAnnotationPresent(RequestHeader.class)
                && parameter.getAnnotation(RequestHeader.class).name().isEmpty()
                && !Map.class.isAssignableFrom(parameter.getType())) {
            return "RequestHeader";
        }
        return null;
    }
}
