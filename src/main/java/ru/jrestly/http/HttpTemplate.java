package ru.jrestly.http;

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
import ru.jrestly.ModuleInfo;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.util.IO;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class HttpTemplate {
    private System.Logger logger;

    private CloseableHttpClient httpClient;
    private ModuleInfo moduleInfo;
    private JsonCodec jsonCodec;

    private String url;
    private HttpMethod httpMethod;
    private ContentType contentType;
    private List<Header> headers;
    private Consumer<List<Header>> authDetailsConsumer;
    private List<NameValuePair> requestParams;
    private HttpEntity entity;
    private List<FormBodyPart> multipartFormParts;
    private Type returnType;
    private OnErrorParser onErrorParser;
    private List<Integer> expectStatuses;
    private Integer followRedirectsNumber;

    public <T> T exchange() {
        URI uri = createUri();

        HttpRequestBase httpRequest = createRequest(uri);

        if (headers != null) {
            headers.forEach(header -> httpRequest.addHeader(header.name(), header.value()));
        }

        if (entity != null) {
            ((HttpEntityEnclosingRequestBase) httpRequest).setEntity(entity);
            httpRequest.addHeader(entity.getContentType());
        } else {
            httpRequest.addHeader(new BasicHeader(HTTP.CONTENT_TYPE, contentType.getMimeType()));
        }

        try {
            logRequest(httpRequest);
        } catch (IOException e) {
            logger.log(System.Logger.Level.ERROR, "Cannot log request", e);
        }

        String payload = null;
        byte[] bytes = null;

        try (CloseableHttpResponse response = httpClient.execute(httpRequest)) {

            List<Header> responseHeaders = null;
            if (response.getAllHeaders() != null) {
                responseHeaders = Arrays.stream(response.getAllHeaders())
                        .map(header -> new Header(header.getName(), header.getValue()))
                        .collect(Collectors.toList());

                if (authDetailsConsumer != null) {
                    authDetailsConsumer.accept(responseHeaders);
                }
            }

            logResponseHeaders(response);

            if (followRedirectsNumber != null) {
                if (followRedirectsNumber <= 0) {
                    logger.log(System.Logger.Level.INFO, "Too many redirects");
                } else {
                    String redirectUri = getRedirectUri(response);

                    if (redirectUri != null) {
                        logger.log(System.Logger.Level.INFO, "Following redirect to: " + redirectUri);

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

            int statusCode = response.getStatusLine().getStatusCode();
            payload = (bytes != null && bytes.length > 0) ? new String(bytes, StandardCharsets.UTF_8) : null;

            if (onErrorParser != null && onErrorParser.canParse(statusCode)) {
                Object errorObject = (payload != null)
                        ? jsonCodec.deserialize(payload, onErrorParser.getErrorClass())
                        : null;
                throw new HandledException(errorObject, statusCode, responseHeaders);
            }

            if (!isExpectedStatus(statusCode)) {
                throw new UnexpectedStatusException(statusCode, payload == null ? "" : payload, responseHeaders);
            }

            if (payload == null) {
                logger.log(System.Logger.Level.INFO, "No response.");
                return null;
            }

            if (String.class.equals(returnType)) {
                logger.log(System.Logger.Level.INFO, "Response size: " + payload.length() + " bytes\n" + payload);
                return (T) payload;
            }

            logger.log(System.Logger.Level.INFO, "Response size: " + payload.length() + " bytes\n" + jsonCodec.prettyPrint(payload));
            if (Void.class.equals(returnType)) {
                return null;
            }

            //noinspection unchecked
            return (T) jsonCodec.deserialize(payload, returnType);

        } catch (IOException e) {
            String message = null;
            if (payload != null) {
                message = jsonCodec.prettyPrint(payload);
            } else if (bytes != null) {
                message = new String(bytes, StandardCharsets.UTF_8);
            }
            logger.log(System.Logger.Level.ERROR, "Failed to parse payload\n" + (message == null ? "" : message));

            throw new RuntimeException(e);
        }
    }

    public void setLogger(System.Logger logger) {
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

    public void setHeaders(List<Header> headers) {
        this.headers = headers;
    }

    public void setAuthDetailsConsumer(Consumer<List<Header>> authDetailsConsumer) {
        this.authDetailsConsumer = authDetailsConsumer;
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

    public void setReturnType(Type returnType) {
        this.returnType = returnType;
    }

    public void setOnErrorParser(OnErrorParser onErrorParser) {
        this.onErrorParser = onErrorParser;
    }

    public void setExpectStatuses(List<Integer> expectStatuses) {
        this.expectStatuses = expectStatuses;
    }

    private boolean isExpectedStatus(int statusCode) {
        if (expectStatuses != null) {
            return expectStatuses.contains(statusCode);
        }
        return statusCode >= 200 && statusCode < 300;
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
                    sb.append(IO.readString(stringEntity.getContent(), StandardCharsets.UTF_8));
                }
            } else {
                sb.append("No request body");
            }
        }

        logger.log(System.Logger.Level.INFO, "Request: " + sb);
    }

    private void logResponseHeaders(CloseableHttpResponse response) {
        String headers = Arrays.stream(response.getAllHeaders())
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        logger.log(System.Logger.Level.INFO, "Response: " + response.getStatusLine() + "\n" + headers);
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

        logger.log(System.Logger.Level.INFO, "Redirect uri not found");
        return null;
    }

    public System.Logger getLogger() {
        return logger;
    }

    public CloseableHttpClient getHttpClient() {
        return httpClient;
    }

    public void setModuleInfo(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;
    }

    public void setJsonCodec(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
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

    public List<Header> getHeaders() {
        return headers;
    }

    public Consumer<List<Header>> getAuthDetailsConsumer() {
        return authDetailsConsumer;
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

    public Type getReturnType() {
        return returnType;
    }

    public OnErrorParser getOnErrorParser() {
        return onErrorParser;
    }

    public List<Integer> getExpectStatuses() {
        return expectStatuses;
    }

    public Integer getFollowRedirectsNumber() {
        return followRedirectsNumber;
    }
}
