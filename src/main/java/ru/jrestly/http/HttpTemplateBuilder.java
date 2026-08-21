package ru.jrestly.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class HttpTemplateBuilder {
    protected final Logger logger = LogManager.getLogger(getClass());

    private final ModuleInfo moduleInfo;
    private final CloseableHttpClient httpClient;
    private final Method method;
    private final Object[] args;
    private final Class<?> controller;

    public HttpTemplateBuilder(ModuleInfo moduleInfo, CloseableHttpClient httpClient, Method method, Object[] args, Class<?> controller) {
        this.moduleInfo = moduleInfo;
        this.httpClient = httpClient;
        this.method = method;
        this.args = args;
        this.controller = controller;
    }

    public HttpTemplate build() throws JsonProcessingException {
        HttpTemplate result = new HttpTemplate();
        ContentType contentType = getContentType();

        result.setLogger(LogManager.getLogger(controller));
        result.setModuleInfo(moduleInfo);
        result.setObjectMapper(moduleInfo.getObjectMapper());

        result.setHttpClient(httpClient);
        result.setUrl(createUrl());
        result.setHttpMethod(getRequestMethod());
        result.setContentType(contentType);
        result.setHeaders(createHeaders());
        result.setHeadersConsumer(getCookiesConsumer());
        if (!ContentType.MULTIPART_FORM_DATA.equals(contentType) && !ContentType.APPLICATION_FORM_URLENCODED.equals(contentType)) {
            result.setRequestParams(createRequestParams());
        }

        HttpEntity entity = null;
        if (ContentType.APPLICATION_JSON.equals(contentType)) {
            entity = createJsonEntity(contentType);
        } else if (ContentType.APPLICATION_FORM_URLENCODED.equals(contentType)) {
            entity = createUrlEncodedEntity();
        } else if (ContentType.MULTIPART_FORM_DATA.equals(contentType)) {
            MultipartEntityBuilder multipartFormBuilder = createMultipartFormBuilder(contentType);
            entity = multipartFormBuilder.build();

            result.setMultipartFormParts(getMultipartFormParts(multipartFormBuilder));
        }

        result.setEntity(entity);
        result.setReturnType(createTypeReference(method.getGenericReturnType()));
        result.setOnErrorParser(createOnErrorParser());
        result.setExpectStatuses(createExpectStatuses());
        result.setFollowRedirectsNumber(getFollowRedirectsNumber());

        return result;
    }

    public HttpTemplate buildFromRedirect(HttpTemplate oldTemplate, String redirectUri) {
        HttpTemplate result = new HttpTemplate();

        result.setLogger(oldTemplate.getLogger());
        result.setHttpClient(oldTemplate.getHttpClient());
        result.setModuleInfo(moduleInfo);
        result.setObjectMapper(moduleInfo.getObjectMapper());
        result.setUrl(redirectUri);
        result.setHttpMethod(HttpMethod.GET);
        result.setContentType(ContentType.WILDCARD);
        result.setHeaders(createHeaders());
        result.setHeadersConsumer(oldTemplate.getHeadersConsumer());
        result.setRequestParams(null);
        result.setEntity(null);
        result.setReturnType(oldTemplate.getTypeReference());
        result.setOnErrorParser(oldTemplate.getOnErrorParser());
        result.setExpectStatuses(oldTemplate.getExpectStatuses());
        result.setFollowRedirectsNumber(oldTemplate.getFollowRedirectsNumber() - 1);

        return result;
    }

    private List<FormBodyPart> getMultipartFormParts(MultipartEntityBuilder builder) {
        try {
            Field bodyParts = builder.getClass().getDeclaredField("bodyParts");
            bodyParts.setAccessible(true);

            //noinspection unchecked
            return (List<FormBodyPart>) bodyParts.get(builder);

        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.error("cannot get multiparts", e);

            return null;
        }
    }

    private String createUrl() {
        String path = getPath();

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(PathVariable.class)) {
                PathVariable pathAnnotation = parameters[i].getAnnotation(PathVariable.class);

                String name = StringUtils.isEmpty(pathAnnotation.name())
                        ? parameters[i].getName()
                        : pathAnnotation.name();

                String encodedValue = URLEncoder.encode(args[i].toString(), StandardCharsets.UTF_8).replace("+", "%20");
                path = StrSubstitutor.replace(path, Map.of(name, encodedValue));
            }
        }

        return !path.startsWith("http")
                ? moduleInfo.getEnvironment().getUrl() + path
                : path;
    }

    private List<Pair<String, String>> createHeaders() {
        List<Pair<String, String>> result = new ArrayList<>();

        Pair<String, String> authHeader = moduleInfo.getAuthProvider().getAuthHeader();
        if (authHeader != null) {
            result.add(authHeader);
        }

        if (method != null) {
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].isAnnotationPresent(RequestHeader.class)) {
                    RequestHeader headerAnnotation = parameters[i].getAnnotation(RequestHeader.class);
                    result.add(new ImmutablePair<>(headerAnnotation.name(), args[i].toString()));
                }
            }
        }

        return result;
    }

    private String getPath() {
        if (method.isAnnotationPresent(Get.class)) {
            return method.getAnnotation(Get.class).path();
        } else if (method.isAnnotationPresent(Post.class)) {
            return method.getAnnotation(Post.class).path();
        } else if (method.isAnnotationPresent(Put.class)) {
            return method.getAnnotation(Put.class).path();
        } else if (method.isAnnotationPresent(Patch.class)) {
            return method.getAnnotation(Patch.class).path();
        } else if (method.isAnnotationPresent(Delete.class)) {
            return method.getAnnotation(Delete.class).path();
        }

        throw new UnsupportedOperationException("Cannot define http path");
    }

    private HttpMethod getRequestMethod() {
        if (method.isAnnotationPresent(Get.class)) {
            return HttpMethod.GET;
        }
        if (method.isAnnotationPresent(Post.class)) {
            return HttpMethod.POST;
        }
        if (method.isAnnotationPresent(Put.class)) {
            return HttpMethod.PUT;
        }
        if (method.isAnnotationPresent(Patch.class)) {
            return HttpMethod.PATCH;
        }
        if (method.isAnnotationPresent(Delete.class)) {
            return HttpMethod.DELETE;
        }

        throw new UnsupportedOperationException("Cannot define http method");
    }

    private ContentType getContentType() {
        if (method.isAnnotationPresent(Get.class)) {
            return ContentType.APPLICATION_JSON;
        }

        if (method.isAnnotationPresent(Post.class)) {
            return resolveContentType(method.getAnnotation(Post.class).requestType());
        }

        if (method.isAnnotationPresent(Put.class)) {
            return resolveContentType(method.getAnnotation(Put.class).requestType());
        }

        if (method.isAnnotationPresent(Patch.class)) {
            return resolveContentType(method.getAnnotation(Patch.class).requestType());
        }

        if (method.isAnnotationPresent(Delete.class)) {
            return resolveContentType(method.getAnnotation(Delete.class).requestType());
        }

        throw new UnsupportedOperationException("Cannot define content type");
    }

    private ContentType resolveContentType(RequestType type) {
        switch (type) {
            case APPLICATION_JSON: return ContentType.APPLICATION_JSON;
            case APPLICATION_FORM_URLENCODED: return ContentType.APPLICATION_FORM_URLENCODED;
            case MULTIPART_FORM_DATA: return ContentType.MULTIPART_FORM_DATA;
            default: throw new UnsupportedOperationException("Unknown request type: " + type);
        }
    }

    private Consumer<List<Pair<String, String>>> getCookiesConsumer() {
        if (method.isAnnotationPresent(SetAuthDetails.class)) {
            AuthProvider authProvider = moduleInfo.getAuthProvider();
            authProvider.setHeaderName(method.getAnnotation(SetAuthDetails.class).headerName());
            return authProvider::setHeaders;
        } else {
            return null;
        }
    }

    private List<NameValuePair> createRequestParams() {
        List<NameValuePair> result = new ArrayList<>();

        if (method.isAnnotationPresent(Get.class)) {
            result.addAll(createRequestDefaultParams(method.getAnnotation(Get.class).params()));
        } else if (method.isAnnotationPresent(Post.class)) {
            result.addAll(createRequestDefaultParams(method.getAnnotation(Post.class).params()));
        } else if (method.isAnnotationPresent(Put.class)) {
            result.addAll(createRequestDefaultParams(method.getAnnotation(Put.class).params()));
        } else if (method.isAnnotationPresent(Patch.class)) {
            result.addAll(createRequestDefaultParams(method.getAnnotation(Patch.class).params()));
        } else if (method.isAnnotationPresent(Delete.class)) {
            result.addAll(createRequestDefaultParams(method.getAnnotation(Delete.class).params()));
        }

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(RequestParam.class)) {
                if (args[i] == null) {
                    continue;
                }

                Parameter parameter = parameters[i];
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);

                String name = StringUtils.isEmpty(annotation.name())
                        ? parameter.getName()
                        : annotation.name();

                if (args[i] instanceof Collection) {
                    for (Object o : (Collection) args[i]) {
                        result.add(new BasicNameValuePair(name, o.toString()));
                    }
                } else {
                    result.add(new BasicNameValuePair(name, args[i].toString()));
                }
            }
        }

        return result;
    }

    private List<NameValuePair> createRequestDefaultParams(RequestDefaultParam[] params) {
        List<NameValuePair> result = new ArrayList<>();

        for (RequestDefaultParam param : params) {
            NameValuePair item = new BasicNameValuePair(param.name(), param.value());
            result.add(item);
        }

        return result;
    }

    private StringEntity createJsonEntity(ContentType contentType) throws JsonProcessingException {
        Object body = createBody(method, args);
        if (body == null) {
            return null;
        }

        String stringifiedBody = body instanceof String
                ? (String) body
                : moduleInfo.getObjectMapper().writeValueAsString(body);

        StringEntity entity = new StringEntity(stringifiedBody, StandardCharsets.UTF_8);
        entity.setContentType(contentType.getMimeType());

        return entity;
    }

    private Object createBody(Method method, Object[] args){
        if (method.isAnnotationPresent(Get.class)) {
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

    private MultipartEntityBuilder createMultipartFormBuilder(ContentType contentType) {
        RequestType requestType = null;
        if (method.isAnnotationPresent(Post.class)) {
            requestType = method.getAnnotation(Post.class).requestType();
        } else if (method.isAnnotationPresent(Put.class)) {
            requestType = method.getAnnotation(Put.class).requestType();
        } else if (method.isAnnotationPresent(Patch.class)) {
            requestType = method.getAnnotation(Patch.class).requestType();
        }

        if (requestType == null || !RequestType.MULTIPART_FORM_DATA.equals(requestType)) {
            throw new UnsupportedOperationException("Cannot create multipart entity");
        }

        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            throw new UnsupportedOperationException("Multipart entity has no parts");
        }

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setContentType(contentType);
        builder.setCharset(StandardCharsets.UTF_8);

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(MultipartFormFile.class)) {
                MultipartFormFile multipartAnnotation = parameters[i].getAnnotation(MultipartFormFile.class);
                builder.addBinaryBody(multipartAnnotation.partName(), new File("data/" + args[i].toString()));
            }
        }

        return builder;
    }

    private UrlEncodedFormEntity createUrlEncodedEntity() {
        RequestType requestType = null;
        if (method.isAnnotationPresent(Post.class)) {
            requestType = method.getAnnotation(Post.class).requestType();
        } else if (method.isAnnotationPresent(Put.class)) {
            requestType = method.getAnnotation(Put.class).requestType();
        } else if (method.isAnnotationPresent(Patch.class)) {
            requestType = method.getAnnotation(Patch.class).requestType();
        }

        if (!RequestType.APPLICATION_FORM_URLENCODED.equals(requestType)) {
            throw new UnsupportedOperationException("Cannot create urlencoded entity");
        }

        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            throw new UnsupportedOperationException("Urlencoded method has no parameters");
        }

        List<NameValuePair> params = createRequestParams();

        return new UrlEncodedFormEntity(params, StandardCharsets.UTF_8);
    }

    private TypeReference<?> createTypeReference(Type returnType) {
        return new TypeReference<>() {
            @Override
            public Type getType() {
                return returnType;
            }
        };
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
