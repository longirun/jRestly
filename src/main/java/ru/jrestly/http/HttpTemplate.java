package ru.jrestly.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;
import org.apache.logging.log4j.Logger;
import ru.jrestly.ModuleInfo;
import ru.jrestly.util.IO;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HttpTemplate {
    private Logger logger;

    private CloseableHttpClient httpClient;
    private ModuleInfo moduleInfo;
    private ObjectMapper objectMapper;

    private String url;
    private HttpMethod httpMethod;
    private ContentType contentType;
    private List<Pair<String, String>> headers;
    private Consumer<List<Pair<String, String>>> headersConsumer;
    private List<NameValuePair> requestParams;
    private HttpEntity entity;
    private List<FormBodyPart> multipartFormParts;
    private TypeReference<?> typeReference;
    private OnErrorParser onErrorParser;
    private Integer followRedirectsNumber;

    public <T> T exchange() {
        URI uri = createUri();

        HttpRequestBase httpRequest = createRequest(uri);

        List<Header> headers = createHeaders();

        headers.forEach(httpRequest::addHeader);

        if (entity != null) {
            ((HttpEntityEnclosingRequestBase) httpRequest).setEntity(entity);
            httpRequest.addHeader(entity.getContentType());
        } else {
            httpRequest.addHeader(new BasicHeader(HTTP.CONTENT_TYPE, contentType.getMimeType()));
        }

        try {
            logRequest(httpRequest);
        } catch (IOException e) {
            logger.error("Cannot log request", e);
        }

        String payload = null;
        byte[] bytes = null;

        try (CloseableHttpResponse response = httpClient.execute(httpRequest)) {

            List<Pair<String, String>> responseHeaders = null;
            if (response.getAllHeaders() != null) {
                responseHeaders = Arrays.stream(response.getAllHeaders())
                        .map(header -> new ImmutablePair<>(header.getName(), header.getValue()))
                        .collect(Collectors.toList());

                if (headersConsumer != null) {
                    headersConsumer.accept(responseHeaders);
                }
            }

            logResponseHeaders(response);

            if (followRedirectsNumber != null) {
                if (followRedirectsNumber <= 0) {
                    logger.info("Too many redirects");
                } else {
                    String redirectUri = getRedirectUri(response);

                    if (redirectUri != null) {
                        logger.info("Following redirect to: " + redirectUri);

                        HttpTemplate httpTemplate = new HttpTemplateBuilder(moduleInfo, httpClient, null, null, null)
                                .buildFromRedirect(this, redirectUri);
                        httpTemplate.exchange();
                        return null;
                    }
                }
            }

            if (response.getEntity() != null) {
                bytes = IO.readBytes(response.getEntity().getContent());
            }

            if (bytes == null || bytes.length == 0) {
                logger.info("No response.");
                return null;
            }

            payload = new String(bytes, StandardCharsets.UTF_8);

            if (String.class.equals(typeReference.getType())) {
                logger.info("Response size: {} bytes \n" + payload, payload.length());
                return (T) payload;
            }

            logger.info("Response size: {} bytes \n" + readPrettyPayload(payload), payload.length());
            if (Void.class.equals(typeReference.getType())) {
                return null;
            }

            if (onErrorParser != null && onErrorParser.canParse(response.getStatusLine().getStatusCode())) {
                Object errorObject = objectMapper.readValue(payload, onErrorParser.getErrorClass());
                throw new HandledException(errorObject,
                        response.getStatusLine().getStatusCode(),
                        responseHeaders);
            }

            //noinspection unchecked
            return (T) objectMapper.readValue(payload, typeReference);

        } catch (IOException e) {
            String message = null;
            if (payload != null) {
                message = readPrettyPayload(payload);
            } else if (bytes != null) {
                message = new String(bytes, StandardCharsets.UTF_8);
            }
            logger.error("Failed to parse payload\n" + (message == null ? "" : message));

            throw new RuntimeException(e);
        }
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    public void setHttpClient(CloseableHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    public void setContentType(ContentType contentType) {
        this.contentType = contentType;
    }

    public void setHeaders(List<Pair<String, String>> headers) {
        this.headers = headers;
    }

    public void setHeadersConsumer(Consumer<List<Pair<String, String>>> headersConsumer) {
        this.headersConsumer = headersConsumer;
    }

    public void setRequestParams(List<NameValuePair> requestParams) {
        this.requestParams = requestParams;
    }

    public void setMultipartFormParts(List<FormBodyPart> multipartFormParts) {
        this.multipartFormParts = multipartFormParts;
    }

    public void setEntity(HttpEntity entity) {
        this.entity = entity;
    }

    public void setReturnType(TypeReference<?> typeReference) {
        this.typeReference = typeReference;
    }

    public void setOnErrorParser(OnErrorParser onErrorParser) {
        this.onErrorParser = onErrorParser;
    }

    public void setFollowRedirectsNumber(Integer followRedirectsNumber) {
        this.followRedirectsNumber = followRedirectsNumber;
    }

    private URI createUri() {
        try {
            return new URIBuilder(url)
                    .addParameters(requestParams != null ? requestParams : Collections.emptyList())
                    .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to build uri for " + url, e);
        }
    }

    private HttpRequestBase createRequest(URI uri) {
        switch (httpMethod) {
            case GET: return new HttpGet(uri);
            case POST: return new HttpPost(uri);
            case PATCH: return new HttpPatch(uri);
            case PUT: return new HttpPut(uri);
            case DELETE: return new HttpDelete(uri);

            default: throw new UnsupportedOperationException();
        }
    }

    private List<Header> createHeaders() {
        List<Header> result = new ArrayList<>();

        if (headers != null) {
            for (Map.Entry<String, String> cookie : headers) {
                result.add(new BasicHeader(cookie.getKey(), cookie.getValue()));
            }
        }

        return result;
    }

    protected void logRequest(HttpRequestBase httpRequest) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(httpRequest.toString());
        sb.append("\n");
        String headers = Arrays.stream(httpRequest.getAllHeaders())
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        sb.append(headers);

        if (Arrays.asList(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE).contains(httpMethod)) {
            sb.append("\n");

            HttpEntity entity = ((HttpEntityEnclosingRequestBase) httpRequest).getEntity();
            if (entity != null) {
                String contentType = entity.getContentType().getValue();

                if (contentType.contains(ContentType.MULTIPART_FORM_DATA.getMimeType())) {
                    if (multipartFormParts != null) {
                        sb.append(multipartFormParts.stream()
                                .map(part -> part.getHeader().toString())
                                .collect(Collectors.joining(", "))
                        );
                        sb.append("\n");
                    }
                    sb.append("Sending size: ").append(entity.getContentLength());
                } else {
                    StringEntity stringEntity = (StringEntity) entity;
                    sb.append(IOUtils.toString(stringEntity.getContent(), StandardCharsets.UTF_8));
                }
            } else {
                sb.append("No request body");
            }
        }

        logger.info("Request: " + sb);
    }

    private String readPrettyPayload(String payload) {
        try {
            Object value = objectMapper.readValue(payload, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            return payload;
        }
    }

    private void logResponseHeaders(CloseableHttpResponse response) {
        String headers = Arrays.stream(response.getAllHeaders())
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        logger.info("Response: {}\n{}", response.getStatusLine().toString(), headers);
    }

    private String getRedirectUri(CloseableHttpResponse response) {
        if (response.getStatusLine().getStatusCode() == 302) {
            String location = Arrays.stream(response.getAllHeaders())
                    .filter(h -> h.getValue() != null && h.getName() != null)
                    .filter(h -> h.getName().equalsIgnoreCase("Location"))
                    .findFirst()
                    .map(NameValuePair::getValue)
                    .orElse(null);
            if (location != null) {
                return location;
            }
        }

        logger.info("Redirect uri not found");
        return null;
    }

    public Logger getLogger() {
        return logger;
    }

    public CloseableHttpClient getHttpClient() {
        return httpClient;
    }

    public void setModuleInfo(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String getUrl() {
        return url;
    }

    public HttpMethod getHttpMethod() {
        return httpMethod;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public List<Pair<String, String>> getHeaders() {
        return headers;
    }

    public Consumer<List<Pair<String, String>>> getHeadersConsumer() {
        return headersConsumer;
    }

    public List<NameValuePair> getRequestParams() {
        return requestParams;
    }

    public HttpEntity getEntity() {
        return entity;
    }

    public List<FormBodyPart> getMultipartFormParts() {
        return multipartFormParts;
    }

    public TypeReference<?> getTypeReference() {
        return typeReference;
    }

    public OnErrorParser getOnErrorParser() {
        return onErrorParser;
    }

    public Integer getFollowRedirectsNumber() {
        return followRedirectsNumber;
    }
}
