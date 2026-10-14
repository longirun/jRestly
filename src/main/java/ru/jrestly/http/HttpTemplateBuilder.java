package ru.jrestly.http;

import ru.jrestly.AuthProvider;
import ru.jrestly.ModuleInfo;
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
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.SetAuthDetails;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Builds the {@link HttpTemplate} for a single controller-method
 * invocation.
 *
 * <p>Instantiated on every proxy dispatch (see
 * {@link ru.jrestly.AbstractHttpClient#createController}) from the
 * module's configuration, the client's {@link HttpClient}, the invoked
 * method, its arguments, and the controller interface; discarded after
 * {@link #build()} — the stateful engine lives in the produced template.
 */
public class HttpTemplateBuilder {

    private final ModuleInfo moduleInfo;
    private final HttpClient httpClient;
    private final Method method;
    private final Object[] args;
    private final Class<?> controller;

    private RequestMeta requestMeta;

    /**
     * Creates a builder for the given invocation.
     *
     * @param moduleInfo the module configuration (environment, codec, auth provider, retry policy)
     * @param httpClient the client's preconfigured {@link HttpClient}
     * @param method the invoked controller method; its verb annotation drives the request
     * @param args the invocation arguments
     * @param controller the proxied controller interface; names the request logger
     */
    public HttpTemplateBuilder(ModuleInfo moduleInfo, HttpClient httpClient, Method method, Object[] args, Class<?> controller) {
        this.moduleInfo = moduleInfo;
        this.httpClient = httpClient;
        this.method = method;
        this.args = args;
        this.controller = controller;
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
     * @return the assembled template, ready for {@link HttpTemplate#exchange()}
     */
    public HttpTemplate build() {
        requestMeta = resolveRequestMeta(method);

        HttpTemplate result = new HttpTemplate();
        RequestType requestType = requestMeta.requestType();

        result.setLogger(System.getLogger(controller.getName()));
        result.setModuleInfo(moduleInfo);
        result.setJsonCodec(moduleInfo.getJsonCodec());

        result.setHttpClient(httpClient);
        result.setUrl(createUrl());
        result.setHttpMethod(requestMeta.httpMethod());
        result.setContentType(requestType.mime());
        result.setHeaders(createHeaders());
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
            body = createJsonBody();
        } else if (RequestType.APPLICATION_FORM_URLENCODED.equals(requestType)) {
            body = createUrlEncodedBody();
        } else if (RequestType.MULTIPART_FORM_DATA.equals(requestType)) {
            parts = createMultipartParts();

            String boundary = "jrestly-" + UUID.randomUUID().toString().replace("-", "");
            result.setContentType("multipart/form-data; boundary=" + boundary);
            result.setMultipartBoundary(boundary);
        }

        result.setBody(body);
        result.setMultipartFormParts(parts);
        resolveReturnType(result, method.getGenericReturnType());
        result.setOnErrorParser(createOnErrorParser());
        result.setExpectStatuses(createExpectStatuses());
        result.setFollowRedirectsNumber(getFollowRedirectsNumber());
        result.setHttpVersion(getHttpVersion());
        result.setRetryPolicy(resolveRetryPolicy());

        return result;
    }

    // CompletableFuture<T> methods run non-blockingly on the client executor;
    // T itself (a class or parameterized type, including Void) is the wire
    // contract — the effective return type. An InputStream, Stream<String>, or
    // Stream<ServerSentEvent> effective return type declares streaming binding
    // (ADR-0015, ADR-0016): the body arrives as a live stream via
    // BodyHandlers.ofInputStream, codec-free
    private static void resolveReturnType(HttpTemplate result, Type genericReturnType) {
        Type effective;
        if (genericReturnType instanceof ParameterizedType parameterized
                && CompletableFuture.class.equals(parameterized.getRawType())) {
            result.setAsync(true);
            effective = parameterized.getActualTypeArguments()[0];
        } else {
            effective = genericReturnType;
        }
        result.setReturnType(effective);
        if (InputStream.class.equals(effective)
                || HttpTemplate.isStreamType(effective, String.class)
                || HttpTemplate.isStreamType(effective, ServerSentEvent.class)) {
            result.setStreamBody(true);
        }
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
        List<Header> result = new ArrayList<>(moduleInfo.getAuthProvider().getAuthHeaders());

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

                    if (arg instanceof Collection<?> collection) {
                        for (Object o : collection) {
                            if (o != null) {
                                result.add(new Header(name, o.toString()));
                            }
                        }
                    } else {
                        result.add(new Header(name, arg.toString()));
                    }
                }
            }
        }

        return result;
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
        List<Param> result = new ArrayList<>(createRequestDefaultParams(requestMeta.defaultParams()));

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(RequestParam.class)) {
                if (args[i] == null) {
                    continue;
                }

                Parameter parameter = parameters[i];
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);

                String name = annotation.name().isEmpty()
                        ? parameter.getName()
                        : annotation.name();

                if (args[i] instanceof Collection<?> collection) {
                    for (Object o : collection) {
                        if (o != null) {
                            result.add(new Param(name, o.toString()));
                        }
                    }
                } else {
                    result.add(new Param(name, args[i].toString()));
                }
            }
        }

        return result;
    }

    private List<Param> createRequestDefaultParams(RequestDefaultParam[] params) {
        List<Param> result = new ArrayList<>();

        for (RequestDefaultParam param : params) {
            result.add(new Param(param.name(), param.value()));
        }

        return result;
    }

    private byte[] createJsonBody() {
        Object body = createBody(method, args);
        if (body == null) {
            return null;
        }

        String stringifiedBody = body instanceof String
                ? (String) body
                : moduleInfo.getJsonCodec().serialize(body);

        return stringifiedBody.getBytes(StandardCharsets.UTF_8);
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

                String name = annotation.name().isEmpty()
                        ? parameter.getName()
                        : annotation.name();

                if (args[i] instanceof Collection<?> collection) {
                    for (Object o : collection) {
                        if (o != null) {
                            parts.add(MultipartPart.text(name, o.toString()));
                        }
                    }
                } else {
                    parts.add(MultipartPart.text(name, args[i].toString()));
                }
            }
        }

        return parts;
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

    // The per-method @Retry is a self-contained policy: its presence
    // replaces the client-level one entirely, no field-by-field merge
    // (ADR-0008, Aspect 2). times = 1 — the default — is the explicit
    // off-switch on a client that has retry enabled.
    private RetryPolicy resolveRetryPolicy() {
        if (method.isAnnotationPresent(Retry.class)) {
            Retry retry = method.getAnnotation(Retry.class);
            return RetryPolicy.builder()
                    .maxAttempts(retry.times())
                    .initialBackoff(Duration.ofMillis(retry.initialBackoffMs()))
                    .maxBackoff(Duration.ofMillis(retry.maxBackoffMs()))
                    .retryableStatuses(Arrays.stream(retry.statuses()).boxed().toList())
                    .retryTransport(retry.transport())
                    .retryOnErrorStatuses(retry.onErrorStatuses())
                    .retryNonIdempotent(retry.nonIdempotent())
                    .retryDecodeFailures(retry.retryDecodeFailures())
                    .build();
        }

        return moduleInfo.getRetryPolicy();
    }
}
