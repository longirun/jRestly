package ru.jrestly.auth;

import ru.jrestly.annotation.Authorization;
import ru.jrestly.annotation.Order;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Request interceptor attaching an API Key to outgoing requests via an HTTP header
 * or a URL query parameter.
 * <p>
 * Requests to controller methods annotated with {@link Authorization} bypass this interceptor.
 */
@Order(-1000)
public class ApiKeyAuth implements RequestInterceptor {

    /**
     * Target location for conveying the API Key.
     */
    public enum Location {
        /**
         * Convey API key as an HTTP header.
         */
        HEADER,

        /**
         * Convey API key as a URL query parameter.
         */
        QUERY
    }

    private final Location location;
    private final String name;
    private final Supplier<String> keySupplier;

    /**
     * Constructs an API Key authentication interceptor.
     *
     * @param location the location (header or query), must not be {@code null}
     * @param name the header or query parameter name, must not be {@code null}
     * @param keySupplier the API key supplier, must not be {@code null}
     * @throws NullPointerException if any parameter is {@code null}
     */
    public ApiKeyAuth(Location location, String name, Supplier<String> keySupplier) {
        this.location = Objects.requireNonNull(location, "location");
        this.name = Objects.requireNonNull(name, "name");
        this.keySupplier = Objects.requireNonNull(keySupplier, "keySupplier");
    }

    /**
     * Creates an API Key authentication interceptor that attaches the key as an HTTP header.
     *
     * @param headerName the HTTP header name, must not be {@code null}
     * @param apiKey the API key value, must not be {@code null}
     * @return a new API Key authentication interceptor
     * @throws NullPointerException if {@code headerName} or {@code apiKey} is {@code null}
     */
    public static ApiKeyAuth header(String headerName, String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey");
        return new ApiKeyAuth(Location.HEADER, headerName, () -> apiKey);
    }

    /**
     * Creates an API Key authentication interceptor that attaches the key as an HTTP header using a supplier.
     *
     * @param headerName the HTTP header name, must not be {@code null}
     * @param apiKeySupplier the API key supplier, must not be {@code null}
     * @return a new API Key authentication interceptor
     * @throws NullPointerException if {@code headerName} or {@code apiKeySupplier} is {@code null}
     */
    public static ApiKeyAuth header(String headerName, Supplier<String> apiKeySupplier) {
        return new ApiKeyAuth(Location.HEADER, headerName, apiKeySupplier);
    }

    /**
     * Creates an API Key authentication interceptor that attaches the key as a URL query parameter.
     *
     * @param paramName the query parameter name, must not be {@code null}
     * @param apiKey the API key value, must not be {@code null}
     * @return a new API Key authentication interceptor
     * @throws NullPointerException if {@code paramName} or {@code apiKey} is {@code null}
     */
    public static ApiKeyAuth query(String paramName, String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey");
        return new ApiKeyAuth(Location.QUERY, paramName, () -> apiKey);
    }

    /**
     * Creates an API Key authentication interceptor that attaches the key as a URL query parameter using a supplier.
     *
     * @param paramName the query parameter name, must not be {@code null}
     * @param apiKeySupplier the API key supplier, must not be {@code null}
     * @return a new API Key authentication interceptor
     * @throws NullPointerException if {@code paramName} or {@code apiKeySupplier} is {@code null}
     */
    public static ApiKeyAuth query(String paramName, Supplier<String> apiKeySupplier) {
        return new ApiKeyAuth(Location.QUERY, paramName, apiKeySupplier);
    }

    @Override
    public WireRequest intercept(WireRequest request, RequestContext context) {
        if (context != null && context.method() != null && context.method().isAnnotationPresent(Authorization.class)) {
            return request;
        }

        String key = keySupplier.get();
        if (location == Location.HEADER) {
            return request.withoutHeader(name).withHeader(name, key);
        } else {
            return appendQueryParam(request, name, key);
        }
    }

    private static WireRequest appendQueryParam(WireRequest request, String paramName, String paramValue) {
        URI originalUri = request.uri();
        String uriStr = originalUri.toString();
        String encodedParam = URLEncoder.encode(paramName, StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(paramValue, StandardCharsets.UTF_8);

        String fragment = originalUri.getRawFragment();
        String base = (fragment != null && uriStr.contains("#"))
                ? uriStr.substring(0, uriStr.indexOf('#'))
                : uriStr;

        String separator = base.contains("?") ? "&" : "?";
        String newUriStr = base + separator + encodedParam + (fragment != null ? "#" + fragment : "");
        return request.withUri(URI.create(newUriStr));
    }
}
