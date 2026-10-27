package ru.jrestly.http;

import ru.jrestly.AuthProvider;
import ru.jrestly.ModuleInfo;
import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.ContentType;
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
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.SetAuthDetails;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class HttpTemplateBuilder {

    private final ModuleInfo moduleInfo;
    private final HttpClient httpClient;
    private final Method method;
    private final Object[] args;
    private final Class<?> controller;

    private RequestMeta requestMeta;
    private List<Class<?>> controllerHierarchy;

    public HttpTemplateBuilder(ModuleInfo moduleInfo, HttpClient httpClient, Method method, Object[] args, Class<?> controller) {
        this.moduleInfo = moduleInfo;
        this.httpClient = httpClient;
        this.method = method;
        this.args = args;
        this.controller = controller;
    }

    public HttpTemplate build() {
        requestMeta = resolveRequestMeta(method);
        controllerHierarchy = collectInterfaceHierarchy(controller);

        HttpTemplate result = new HttpTemplate();
        RequestType requestType = requestMeta.requestType();

        result.setLogger(System.getLogger(controller.getName()));
        result.setModuleInfo(moduleInfo);
        result.setJsonCodec(moduleInfo.getJsonCodec());

        result.setHttpClient(httpClient);
        result.setUrl(createUrl());
        result.setHttpMethod(requestMeta.httpMethod());
        List<Header> headers = createHeaders();
        String effectiveContentType = resolveContentType(headers, requestType);
        result.setContentType(effectiveContentType);
        result.setHeaders(headers);
        // Header names to drop on cross-origin redirects
        result.setAuthHeaderNames(moduleInfo.getAuthProvider().getAuthHeaders().stream()
                .map(Header::name)
                .toList());
        result.setAuthDetailsConsumer(getAuthDetailsConsumer());
        if (!RequestType.MULTIPART_FORM_DATA.equals(requestType) && !RequestType.APPLICATION_FORM_URLENCODED.equals(requestType)) {
            result.setRequestParams(createRequestParams());
        }

        byte[] body = null;
        List<MultipartPart> parts = null;
        if (RequestType.APPLICATION_JSON.equals(requestType)) {
            body = createJsonBody(effectiveContentType);
        } else if (RequestType.APPLICATION_FORM_URLENCODED.equals(requestType)) {
            body = createUrlEncodedBody();
        } else if (RequestType.MULTIPART_FORM_DATA.equals(requestType)) {
            parts = createMultipartParts();

            String boundary = "jrestly-" + UUID.randomUUID().toString().replace("-", "");
            // A declared multipart/* subtype keeps its name and gains the boundary.
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

        result.setBody(body);
        result.setMultipartFormParts(parts);
        result.setReturnType(method.getGenericReturnType());
        result.setOnErrorParser(createOnErrorParser());
        result.setExpectStatuses(createExpectStatuses());
        result.setFollowRedirectsNumber(getFollowRedirectsNumber());
        result.setHttpVersion(getHttpVersion());

        return result;
    }

    private String createUrl() {
        String path = requestMeta.path();

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(PathVariable.class)) {
                PathVariable pathAnnotation = parameters[i].getAnnotation(PathVariable.class);

                String name = pathAnnotation.name().isEmpty()
                        ? parameters[i].getName()
                        : pathAnnotation.name();

                if (args[i] == null) {
                    throw new IllegalArgumentException("Path variable '" + name + "' value cannot be null");
                }

                String encodedValue = URLEncoder.encode(args[i].toString(), StandardCharsets.UTF_8).replace("+", "%20");
                path = path.replace("${" + name + "}", encodedValue);
            }
        }

        return !path.startsWith("http")
                ? moduleInfo.getEnvironment().getUrl() + path
                : path;
    }

    private List<Header> createHeaders() {
        List<Header> result = new ArrayList<>();
        for (Header h : moduleInfo.getDefaultHeaders()) {
            if (!"Content-Type".equalsIgnoreCase(h.name())) {
                result.add(h);
            }
        }
        for (Header authHeader : moduleInfo.getAuthProvider().getAuthHeaders()) {
            removeHeaderByName(result, authHeader.name());
            result.add(authHeader);
        }

        String accept = resolveAccept();
        if (accept != null) {
            removeHeaderByName(result, "Accept");
            result.add(new Header("Accept", accept));
        }

        Map<String, Header> staticHeaders = new LinkedHashMap<>();
        for (Class<?> iface : controllerHierarchy) {
            for (RequestHeader header : iface.getAnnotationsByType(RequestHeader.class)) {
                staticHeaders.keySet().removeIf(k -> k.equalsIgnoreCase(header.name()));
                staticHeaders.put(header.name(), new Header(header.name(), header.value()));
            }
        }
        for (RequestHeader header : method.getAnnotationsByType(RequestHeader.class)) {
            staticHeaders.keySet().removeIf(k -> k.equalsIgnoreCase(header.name()));
            staticHeaders.put(header.name(), new Header(header.name(), header.value()));
        }
        for (Header staticHeader : staticHeaders.values()) {
            removeHeaderByName(result, staticHeader.name());
            result.add(staticHeader);
        }

        if (method != null) {
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].isAnnotationPresent(RequestHeader.class)) {
                    Object arg = args[i];
                    if (arg == null) {
                        continue;
                    }

                    RequestHeader headerAnnotation = parameters[i].getAnnotation(RequestHeader.class);
                    String name = headerAnnotation.name().isEmpty()
                            ? parameters[i].getName()
                            : headerAnnotation.name();

                    if (arg instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (entry.getKey() == null || entry.getValue() == null) {
                                continue;
                            }
                            String headerName = String.valueOf(entry.getKey());
                            removeHeaderByName(result, headerName);
                            appendHeaderValues(result, headerName, entry.getValue());
                        }
                    } else {
                        removeHeaderByName(result, name);
                        appendHeaderValues(result, name, arg);
                    }
                }
            }
        }

        return result;
    }

    private static void removeHeaderByName(List<Header> headers, String name) {
        headers.removeIf(h -> h.name().equalsIgnoreCase(name));
    }

    private static void appendHeaderValues(List<Header> result, String name, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object o : collection) {
                if (o != null) {
                    result.add(new Header(name, textValue(o, "header")));
                }
            }
        } else if (value.getClass().isArray() && !byte[].class.equals(value.getClass())) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object o = Array.get(value, i);
                if (o != null) {
                    result.add(new Header(name, textValue(o, "header")));
                }
            }
        } else {
            result.add(new Header(name, textValue(value, "header")));
        }
    }

    // Resolution order: method @Accept, interface hierarchy, client defaultHeader.
    // The result is the base layer of the header merge: a static/runtime @RequestHeader("Accept") replaces it by name.
    private String resolveAccept() {
        Accept accept = method.getAnnotation(Accept.class);
        if (accept == null) {
            for (Class<?> iface : controllerHierarchy) {
                if (iface.isAnnotationPresent(Accept.class)) {
                    accept = iface.getAnnotation(Accept.class);
                }
            }
        }
        return accept != null ? String.join(", ", accept.value()) : null;
    }

    // Precedence: static/runtime @RequestHeader, then @ContentType (method, interface),
    // then the client defaultHeader, then the requestType default. The client layer is
    // JSON-only: it must not corrupt the body semantics of form and multipart requests.
    // The header entry is removed — Content-Type travels in its own template slot and would duplicate on the wire.
    private String resolveContentType(List<Header> headers, RequestType requestType) {
        String fromHeader = extractContentTypeHeader(headers);
        if (fromHeader != null) {
            return fromHeader;
        }

        ContentType contentType = method.getAnnotation(ContentType.class);
        if (contentType == null) {
            for (Class<?> iface : controllerHierarchy) {
                if (iface.isAnnotationPresent(ContentType.class)) {
                    contentType = iface.getAnnotation(ContentType.class);
                }
            }
        }
        if (contentType != null) {
            return contentType.value();
        }
        if (RequestType.APPLICATION_JSON.equals(requestType)) {
            for (Header h : moduleInfo.getDefaultHeaders()) {
                if ("Content-Type".equalsIgnoreCase(h.name())) {
                    return h.value();
                }
            }
        }
        return requestType.mime();
    }

    private static String extractContentTypeHeader(List<Header> headers) {
        String value = null;
        Iterator<Header> iterator = headers.iterator();
        while (iterator.hasNext()) {
            Header header = iterator.next();
            if ("Content-Type".equalsIgnoreCase(header.name())) {
                if (value == null) {
                    value = header.value();
                }
                iterator.remove();
            }
        }
        return value;
    }

    private RequestMeta resolveRequestMeta(Method method) {
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

    private Consumer<List<Header>> getAuthDetailsConsumer() {
        if (method.isAnnotationPresent(SetAuthDetails.class)) {
            String headerName = method.getAnnotation(SetAuthDetails.class).headerName();
            AuthProvider authProvider = moduleInfo.getAuthProvider();

            return responseHeaders -> authProvider.captureAuthDetails(headerName, responseHeaders);
        }

        return null;
    }

    private List<Param> createRequestParams() {
        List<Param> result = new ArrayList<>(staticDefaultParams());

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(RequestParam.class)) {
                if (args[i] == null) {
                    continue;
                }

                Parameter parameter = parameters[i];
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);

                if (args[i] instanceof Map<?, ?> map) {
                    // Raw annotation name, deliberately not the parameter-name fallback:
                    // for a Map, name() is an optional namespace prefix — blank sends keys as-is.
                    String prefix = annotation.name();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null) {
                            continue;
                        }
                        String name = prefix.isEmpty()
                                ? String.valueOf(entry.getKey())
                                : prefix + "[" + entry.getKey() + "]";
                        result.removeIf(existing -> existing.name().equals(name));
                        appendParamValues(result, name, entry.getValue());
                    }
                } else {
                    String name = annotation.name().isEmpty()
                            ? parameter.getName()
                            : annotation.name();
                    result.removeIf(existing -> existing.name().equals(name));
                    appendParamValues(result, name, args[i]);
                }
            }
        }

        return result;
    }

    private static void appendParamValues(List<Param> result, String name, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object o : collection) {
                if (o != null) {
                    result.add(new Param(name, textValue(o, "parameter")));
                }
            }
        } else if (value.getClass().isArray() && !byte[].class.equals(value.getClass())) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object o = Array.get(value, i);
                if (o != null) {
                    result.add(new Param(name, textValue(o, "parameter")));
                }
            }
        } else {
            result.add(new Param(name, textValue(value, "parameter")));
        }
    }

    private List<Param> staticDefaultParams() {
        Map<String, Param> defaults = new LinkedHashMap<>();
        for (Class<?> iface : controllerHierarchy) {
            for (RequestDefaultParam param : iface.getAnnotationsByType(RequestDefaultParam.class)) {
                defaults.put(param.name(), new Param(param.name(), param.value()));
            }
        }
        for (RequestDefaultParam param : requestMeta.defaultParams()) {
            defaults.put(param.name(), new Param(param.name(), param.value()));
        }
        for (RequestDefaultParam param : method.getAnnotationsByType(RequestDefaultParam.class)) {
            defaults.put(param.name(), new Param(param.name(), param.value()));
        }
        return new ArrayList<>(defaults.values());
    }

    // Parents first, the controller interface itself last: a sub-interface
    // declaration overrides the parent one for the same header/parameter name.
    private static List<Class<?>> collectInterfaceHierarchy(Class<?> controller) {
        List<Class<?>> result = new ArrayList<>();
        collectInterfaces(controller, result);
        return result;
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

    private byte[] createJsonBody(String contentType) {
        Object body = createBody(method, args);
        if (body == null) {
            return null;
        }
        if (body instanceof byte[] rawBytes) {
            // raw passthrough: the bytes are the payload even under the JSON request type
            return rawBytes;
        }
        if (body instanceof String stringBody) {
            return stringBody.getBytes(parseCharset(contentType, StandardCharsets.UTF_8));
        }

        return moduleInfo.getJsonCodec().serialize(body).getBytes(StandardCharsets.UTF_8);
    }

    // An absent or unknown charset name falls back to the default instead of failing the request.
    private static Charset parseCharset(String contentType, Charset defaultCharset) {
        if (contentType == null || contentType.isEmpty()) {
            return defaultCharset;
        }
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx < 0) {
            return defaultCharset;
        }
        String charsetName = contentType.substring(idx + 8).trim();
        int semi = charsetName.indexOf(';');
        if (semi >= 0) {
            charsetName = charsetName.substring(0, semi).trim();
        }
        if (charsetName.length() >= 2 && charsetName.startsWith("\"") && charsetName.endsWith("\"")) {
            charsetName = charsetName.substring(1, charsetName.length() - 1);
        }
        try {
            return Charset.forName(charsetName);
        } catch (Exception ignored) {
            return defaultCharset;
        }
    }

    private Object createBody(Method method, Object[] args){
        if (requestMeta.httpMethod() == HttpMethod.GET) {
            return null;
        }

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i ++) {
            if (parameters[i].isAnnotationPresent(RequestBody.class)) {
                return args[i];
            }
        }

        return null;
    }

    private List<MultipartPart> createMultipartParts() {
        if (!RequestType.MULTIPART_FORM_DATA.equals(requestMeta.requestType())) {
            throw new UnsupportedOperationException("Cannot create multipart entity");
        }

        List<MultipartPart> parts = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(MultipartFormFile.class)) {
                MultipartFormFile multipartAnnotation = parameters[i].getAnnotation(MultipartFormFile.class);
                String partName = multipartAnnotation.partName();
                String explicitContentType = multipartAnnotation.contentType().isEmpty()
                        ? null
                        : multipartAnnotation.contentType();

                for (Object value : multipartFileValues(args[i])) {
                    parts.add(createFilePart(partName, value, explicitContentType));
                }
            } else if (parameters[i].isAnnotationPresent(RequestParam.class)) {
                if (args[i] == null) {
                    continue;
                }

                Parameter parameter = parameters[i];
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);

                if (args[i] instanceof Map<?, ?> map) {
                    // Raw annotation name, deliberately not the parameter-name fallback:
                    // for a Map, name() is an optional namespace prefix — blank sends keys as-is.
                    String prefix = annotation.name();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null) {
                            continue;
                        }
                        String name = prefix.isEmpty()
                                ? String.valueOf(entry.getKey())
                                : prefix + "[" + entry.getKey() + "]";
                        appendTextParts(parts, name, entry.getValue());
                    }
                } else {
                    String name = annotation.name().isEmpty()
                            ? parameter.getName()
                            : annotation.name();
                    appendTextParts(parts, name, args[i]);
                }
            }
        }

        return parts;
    }

    private static void appendTextParts(List<MultipartPart> parts, String name, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object o : collection) {
                if (o != null) {
                    parts.add(textPart(name, o));
                }
            }
        } else if (value.getClass().isArray() && !byte[].class.equals(value.getClass())) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object o = Array.get(value, i);
                if (o != null) {
                    parts.add(textPart(name, o));
                }
            }
        } else {
            parts.add(textPart(name, value));
        }
    }

    private static MultipartPart textPart(String name, Object value) {
        if (value instanceof byte[]) {
            throw new IllegalArgumentException(
                    "A byte[] cannot be a multipart text part: send binary data as @MultipartFormFile "
                            + "with FileContent.of(fileName, bytes), or encode the bytes to text explicitly (e.g. Base64).");
        }
        return MultipartPart.text(name, value.toString());
    }

    // Header and parameter values are text on the wire; a byte[] has no text rendering,
    // so it fails fast instead of silently sending "[B@..." garbage.
    private static String textValue(Object value, String position) {
        return switch (value) {
            case null -> "null";
            case byte[] ignored -> throw new IllegalArgumentException(
                    "A byte[] cannot be a " + position + " value: header and parameter values are text on the wire. "
                            + "Send raw bytes as @RequestBody, or encode the bytes to text explicitly "
                            + "(e.g. Base64) before passing.");
            default -> value.toString();
        };
    }

    private MultipartPart createFilePart(String partName, Object arg, String explicitContentType) {
        if (arg instanceof FileContent content) {
            return MultipartPart.file(partName, content.fileName(), explicitContentType,
                    content.content(), content.contentLength());
        }
        if (arg instanceof byte[]) {
            throw new IllegalArgumentException(
                    "A bare byte[] cannot be a multipart file: the wire file name cannot be derived from it. "
                            + "Wrap in-memory bytes in FileContent.of(fileName, bytes)");
        }
        Path path = arg instanceof File file
                ? file.toPath()
                : arg instanceof Path p
                        ? p
                        : arg instanceof String str
                                ? Path.of(str)
                                : null;
        if (path == null) {
            throw new IllegalArgumentException(
                    "Unsupported multipart file argument of type " + arg.getClass().getName()
                            + ". Expected Path, File, String, or FileContent");
        }
        if (path.getFileName() == null) {
            throw new IllegalArgumentException("Path must have a file name: " + path);
        }
        return MultipartPart.file(partName, path.getFileName().toString(), explicitContentType, path, null);
    }

    // Unpacks collections, arrays, and varargs into individual part values
    private static List<?> multipartFileValues(Object arg) {
        if (arg == null) {
            return List.of();
        }
        if (arg instanceof Collection<?> collection) {
            return collection.stream().filter(Objects::nonNull).toList();
        }
        if (arg instanceof Object[] array) {
            return Arrays.stream(array).filter(Objects::nonNull).toList();
        }
        return List.of(arg);
    }

    private byte[] createUrlEncodedBody() {
        if (!RequestType.APPLICATION_FORM_URLENCODED.equals(requestMeta.requestType())) {
            throw new UnsupportedOperationException("Cannot create urlencoded entity");
        }

        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            throw new UnsupportedOperationException("Urlencoded method has no parameters");
        }

        return createRequestParams().stream()
                .map(param -> URLEncoder.encode(param.name(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(param.value(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"))
                .getBytes(StandardCharsets.UTF_8);
    }

    private OnErrorParser createOnErrorParser() {
        if (method.isAnnotationPresent(OnError.class)) {
            OnError onError = method.getAnnotation(OnError.class);
            int[] statuses = onError.statuses();
            Class<?> errorObject = onError.errorObject();

            OnErrorParser parser = new OnErrorParser();
            parser.setStatuses(Arrays.stream(statuses).boxed().toList());
            parser.setErrorClass(errorObject);

            return parser;
        } else {
            return null;
        }
    }

    private List<Integer> createExpectStatuses() {
        if (method.isAnnotationPresent(ExpectStatus.class)) {
            int[] statuses = method.getAnnotation(ExpectStatus.class).statuses();
            return Arrays.stream(statuses).boxed().toList();
        }
        return null;
    }

    private Integer getFollowRedirectsNumber() {
        if (method.isAnnotationPresent(FollowRedirects.class)) {
            return method.getAnnotation(FollowRedirects.class).count();
        }

        return null;
    }

    private HttpClient.Version getHttpVersion() {
        if (method.isAnnotationPresent(HttpVersion.class)) {
            return method.getAnnotation(HttpVersion.class).value();
        }

        return null;
    }
}
