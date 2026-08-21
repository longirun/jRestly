package ru.jrestly;

import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Patch;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Put;
import ru.jrestly.http.RequestType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        for (Method method : methods.values()) {
            problems.addAll(validateMethod(method));
        }
        return problems;
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
}
