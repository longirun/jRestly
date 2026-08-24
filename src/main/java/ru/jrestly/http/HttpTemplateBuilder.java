package ru.jrestly.http;

import ru.jrestly.AuthProvider;
import ru.jrestly.ModuleInfo;
import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.FollowRedirects;
import ru.jrestly.annotation.Get;
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
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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

    public HttpTemplateBuilder(ModuleInfo moduleInfo, HttpClient httpClient, Method method, Object[] args, Class<?> controller) {
        this.moduleInfo = moduleInfo;
        this.httpClient = httpClient;
        this.method = method;
        this.args = args;
        this.controller = controller;
    }

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
        // names only: the redirect loop matches them case-insensitively when dropping
        // ambient credentials on a cross-origin hop
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
            body = MultipartWriter.write(boundary, parts);
            result.setContentType("multipart/form-data; boundary=" + boundary);
        }

        result.setBody(body);
        result.setMultipartFormParts(parts);
        result.setReturnType(method.getGenericReturnType());
        result.setOnErrorParser(createOnErrorParser());
        result.setExpectStatuses(createExpectStatuses());
        result.setFollowRedirectsNumber(getFollowRedirectsNumber());

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
                    RequestHeader headerAnnotation = parameters[i].getAnnotation(RequestHeader.class);
                    result.add(new Header(headerAnnotation.name(), args[i].toString()));
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

                if (args[i] instanceof Collection) {
                    for (Object o : (Collection<?>) args[i]) {
                        result.add(new Param(name, o.toString()));
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
                parts.add(createFilePart(multipartAnnotation.partName(), args[i]));
            } else if (parameters[i].isAnnotationPresent(RequestParam.class)) {
                if (args[i] == null) {
                    continue;
                }

                Parameter parameter = parameters[i];
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);

                String name = annotation.name().isEmpty()
                        ? parameter.getName()
                        : annotation.name();

                if (args[i] instanceof Collection) {
                    for (Object o : (Collection<?>) args[i]) {
                        parts.add(MultipartPart.text(name, o.toString()));
                    }
                } else {
                    parts.add(MultipartPart.text(name, args[i].toString()));
                }
            }
        }

        return parts;
    }

    private MultipartPart createFilePart(String partName, Object arg) {
        try {
            Path path = arg instanceof File file
                    ? file.toPath()
                    : arg instanceof Path p
                            ? p
                            : Path.of(arg.toString());

            return MultipartPart.file(partName, path.getFileName().toString(), Files.readAllBytes(path));
        } catch (IOException e) {
            throw new RuntimeException("Cannot read multipart file: " + arg, e);
        }
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
}
