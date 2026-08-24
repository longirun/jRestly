package ru.jrestly.http;

import ru.jrestly.ModuleInfo;
import ru.jrestly.json.JsonCodec;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class HttpTemplate {
    private System.Logger logger;

    private HttpClient httpClient;
    private ModuleInfo moduleInfo;
    private JsonCodec jsonCodec;

    private String url;
    private HttpMethod httpMethod;
    private String contentType;
    private List<Header> headers;
    private List<String> authHeaderNames;
    private Consumer<List<Header>> authDetailsConsumer;
    private List<Param> requestParams;
    private byte[] body;
    private List<MultipartPart> multipartFormParts;
    private Type returnType;
    private OnErrorParser onErrorParser;
    private List<Integer> expectStatuses;
    private Integer followRedirectsNumber;

    @SuppressWarnings("unchecked")
    public <T> T exchange() {
        WireRequest request = new WireRequest(httpMethod, createUri(), headers, contentType, body, multipartFormParts);

        logRequest(request);

        String payload;

        try {
            long start = System.nanoTime();
            WireResponse response = executeWithRedirects(request);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            List<Header> responseHeaders = response.headers();
            if (authDetailsConsumer != null) {
                authDetailsConsumer.accept(responseHeaders);
            }

            logResponse(response, elapsedMs);

            byte[] bytes = response.body();
            int statusCode = response.statusCode();
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

            return jsonCodec.deserialize(payload, returnType);

        } catch (IOException e) {
            logger.log(System.Logger.Level.ERROR,
                    "Request failed: " + request.method() + " " + request.uri(), e);

            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Manual redirect loop (the JDK client's built-in handling is disabled):
     * 301/302/303 downgrade to GET and drop the body, 307/308 re-send the
     * method and body unchanged. The final response is returned to the caller.
     */
    private WireResponse executeWithRedirects(WireRequest request) throws IOException, InterruptedException {
        int hopsLeft = followRedirectsNumber == null ? 0 : followRedirectsNumber;

        WireResponse response = send(request);

        while (followRedirectsNumber != null && hopsLeft > 0) {
            String location = redirectLocation(response);
            if (location == null) {
                break;
            }

            boolean preserveMethod = response.statusCode() == 307 || response.statusCode() == 308;
            HttpMethod nextMethod = preserveMethod ? request.method() : HttpMethod.GET;

            URI nextUri = request.uri().resolve(location);
            List<Header> nextHeaders = request.headers();
            if (isCrossOrigin(request.uri(), nextUri)) {
                // ambient credentials must not leak to another origin (curl CVE-2018-1000120);
                // explicit @RequestHeader values stay — the caller placed them deliberately
                nextHeaders = dropAuthHeaders(nextHeaders);
                logger.log(System.Logger.Level.INFO,
                        "Cross-origin redirect to " + nextUri + ", auth headers dropped");
            }

            logger.log(System.Logger.Level.INFO,
                    "Following redirect (" + response.statusCode() + ") to: " + location);

            request = new WireRequest(
                    nextMethod,
                    nextUri,
                    nextHeaders,
                    preserveMethod ? request.contentType() : null,
                    preserveMethod ? request.body() : null,
                    preserveMethod ? request.parts() : null);

            hopsLeft--;

            response = send(request);
        }

        if (followRedirectsNumber != null && redirectLocation(response) != null) {
            logger.log(System.Logger.Level.INFO, "Too many redirects, returning the last response");
        }

        return response;
    }

    private WireResponse send(WireRequest request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(Duration.ofMillis(moduleInfo.getSocketTimeout()));

        if (request.headers() != null) {
            for (Header header : request.headers()) {
                builder.header(header.name(), header.value());
            }
        }
        if (request.contentType() != null) {
            builder.header("Content-Type", request.contentType());
        }

        if (request.method() == HttpMethod.GET && request.body() == null) {
            builder.GET();
        } else {
            builder.method(request.method().name(),
                    request.body() == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        }

        long start = System.nanoTime();
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // per-hop timing; the total (redirect chain included) is logged in the response INFO line
        logger.log(System.Logger.Level.DEBUG,
                request.method() + " " + request.uri() + " -> " + response.statusCode() + " in " + elapsedMs + " ms");

        List<Header> headers = response.headers().map().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(value -> new Header(entry.getKey(), value)))
                .toList();

        return new WireResponse(response.statusCode(), headers, response.body());
    }

    private String redirectLocation(WireResponse response) {
        int statusCode = response.statusCode();
        if (statusCode != 301 && statusCode != 302 && statusCode != 303 && statusCode != 307 && statusCode != 308) {
            return null;
        }

        return response.headers().stream()
                .filter(h -> h.name() != null && h.name().equalsIgnoreCase("Location"))
                .map(Header::value)
                .findFirst()
                .orElse(null);
    }

    private static boolean isCrossOrigin(URI from, URI to) {
        if (from.getScheme() == null || to.getScheme() == null
                || !from.getScheme().equalsIgnoreCase(to.getScheme())) {
            return true;
        }
        if (from.getHost() == null || to.getHost() == null
                || !from.getHost().equalsIgnoreCase(to.getHost())) {
            return true;
        }
        return effectivePort(from) != effectivePort(to);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private List<Header> dropAuthHeaders(List<Header> headers) {
        if (authHeaderNames == null || authHeaderNames.isEmpty()) {
            return headers;
        }

        return headers.stream()
                .filter(header -> authHeaderNames.stream()
                        .noneMatch(name -> name.equalsIgnoreCase(header.name())))
                .toList();
    }

    private URI createUri() {
        if (requestParams == null || requestParams.isEmpty()) {
            return URI.create(url);
        }

        String query = requestParams.stream()
                .map(param -> encode(param.name()) + "=" + encode(param.value()))
                .collect(Collectors.joining("&"));

        return URI.create(url + (url.contains("?") ? "&" : "?") + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    protected void logRequest(WireRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.method()).append(" ").append(request.uri());
        sb.append("\n");

        if (request.headers() != null) {
            String headers = request.headers().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
            sb.append(headers);
        }

        if (Arrays.asList(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE).contains(httpMethod)) {
            sb.append("\n");

            if (contentType != null && contentType.contains("multipart/form-data")) {
                if (multipartFormParts != null) {
                    sb.append(multipartFormParts.stream()
                            .map(part -> "name=" + part.name()
                                    + (part.filename() == null ? "" : ", filename=" + part.filename()))
                            .collect(Collectors.joining(", ")));
                    sb.append("\n");
                }
                sb.append("Sending size: ").append(body == null ? 0 : body.length);
            } else if (body != null) {
                sb.append(new String(body, StandardCharsets.UTF_8));
            } else {
                sb.append("No request body");
            }
        }

        logger.log(System.Logger.Level.INFO, "Request: " + sb);
    }

    private void logResponse(WireResponse response, long elapsedMs) {
        String headers = response.headers().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        logger.log(System.Logger.Level.INFO,
                "Response: HTTP " + response.statusCode() + " in " + elapsedMs + " ms\n" + headers);
    }

    private boolean isExpectedStatus(int statusCode) {
        if (expectStatuses != null) {
            return expectStatuses.contains(statusCode);
        }
        return statusCode >= 200 && statusCode < 300;
    }

    public void setLogger(System.Logger logger) {
        this.logger = logger;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void setHeaders(List<Header> headers) {
        this.headers = headers;
    }

    public void setAuthHeaderNames(List<String> authHeaderNames) {
        this.authHeaderNames = authHeaderNames;
    }

    public void setAuthDetailsConsumer(Consumer<List<Header>> authDetailsConsumer) {
        this.authDetailsConsumer = authDetailsConsumer;
    }

    public void setRequestParams(List<Param> requestParams) {
        this.requestParams = requestParams;
    }

    public void setMultipartFormParts(List<MultipartPart> multipartFormParts) {
        this.multipartFormParts = multipartFormParts;
    }

    public void setBody(byte[] body) {
        this.body = body;
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

    public void setFollowRedirectsNumber(Integer followRedirectsNumber) {
        this.followRedirectsNumber = followRedirectsNumber;
    }

    public void setModuleInfo(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;
    }

    public void setJsonCodec(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }
}
