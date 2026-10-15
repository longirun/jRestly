package ru.jrestly.http;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Internal wire model: everything needed to place a request on the wire,
 * decoupled from both the template layer and the HTTP engine.
 * <p>
 * For multipart requests {@code contentType} carries the boundary parameter
 * and {@code body} is unused; the payload is rendered per send from
 * {@code parts} + {@code boundary} as a streaming publisher, so a redirect
 * re-send gets a fresh body.
 * <p>
 * For streaming requests (e.g. {@code Flow.Publisher}, {@code InputStream},
 * or {@code Path}), {@code bodyPublisherSupplier} supplies a fresh
 * {@link HttpRequest.BodyPublisher} on each transmission attempt.
 *
 * @param method the HTTP method for this hop; a redirect hop may replace it (GET unless the redirect status preserves the method)
 * @param uri the target URI for this hop; a redirect hop resolves the {@code Location} header against it
 * @param headers the headers to send with this hop, or {@code null}; cross-origin redirect hops drop the default auth headers
 * @param contentType the {@code Content-Type} header value, or {@code null}; for multipart requests it carries the boundary parameter; dropped on redirect hops that do not preserve the payload
 * @param body the raw payload bytes, or {@code null} for a body-less or streaming request; unused for multipart requests
 * @param parts the multipart parts, or {@code null} for non-multipart requests; rendered per send as a streaming publisher
 * @param boundary the multipart boundary used to render {@code parts}, or {@code null} for non-multipart requests
 * @param bodyPublisherSupplier the body publisher supplier for streaming payloads, or {@code null}
 */
public record WireRequest(
        HttpMethod method,
        URI uri,
        List<Header> headers,
        String contentType,
        byte[] body,
        List<MultipartPart> parts,
        String boundary,
        Supplier<? extends HttpRequest.BodyPublisher> bodyPublisherSupplier
) {

    /**
     * Backward-compatible canonical constructor without streaming body publisher supplier.
     *
     * @param method the HTTP method
     * @param uri the target URI
     * @param headers the headers
     * @param contentType the Content-Type header
     * @param body the raw payload bytes
     * @param parts the multipart parts
     * @param boundary the multipart boundary
     */
    public WireRequest(
            HttpMethod method,
            URI uri,
            List<Header> headers,
            String contentType,
            byte[] body,
            List<MultipartPart> parts,
            String boundary
    ) {
        this(method, uri, headers, contentType, body, parts, boundary, null);
    }

    /**
     * Returns a copy of this request with the specified header appended.
     *
     * @param name the header name, must not be {@code null}
     * @param value the header value, must not be {@code null}
     * @return a new {@link WireRequest} with the header appended
     * @throws NullPointerException if {@code name} or {@code value} is {@code null}
     */
    public WireRequest withHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return withHeader(new Header(name, value));
    }

    /**
     * Returns a copy of this request with the specified header appended.
     *
     * @param header the header to append, must not be {@code null}
     * @return a new {@link WireRequest} with the header appended
     * @throws NullPointerException if {@code header} is {@code null}
     */
    public WireRequest withHeader(Header header) {
        Objects.requireNonNull(header, "header");
        List<Header> updated = new ArrayList<>(this.headers != null ? this.headers : List.of());
        updated.add(header);
        return new WireRequest(method, uri, Collections.unmodifiableList(updated), contentType, body, parts, boundary, bodyPublisherSupplier);
    }

    /**
     * Returns a copy of this request with the specified headers appended.
     *
     * @param newHeaders the list of headers to append, must not be {@code null}
     * @return a new {@link WireRequest} with headers appended
     * @throws NullPointerException if {@code newHeaders} is {@code null}
     */
    public WireRequest withHeaders(List<Header> newHeaders) {
        Objects.requireNonNull(newHeaders, "newHeaders");
        List<Header> updated = new ArrayList<>(this.headers != null ? this.headers : List.of());
        updated.addAll(newHeaders);
        return new WireRequest(method, uri, Collections.unmodifiableList(updated), contentType, body, parts, boundary, bodyPublisherSupplier);
    }

    /**
     * Returns a copy of this request with all headers matching the specified name (case-insensitively) removed.
     *
     * @param name the header name to remove, must not be {@code null}
     * @return a new {@link WireRequest} without the matching headers, or {@code this} if no headers matched
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public WireRequest withoutHeader(String name) {
        Objects.requireNonNull(name, "name");
        if (headers == null || headers.isEmpty()) {
            return this;
        }
        List<Header> filtered = headers.stream()
                .filter(h -> !h.name().equalsIgnoreCase(name))
                .toList();
        if (filtered.size() == headers.size()) {
            return this;
        }
        return new WireRequest(method, uri, filtered, contentType, body, parts, boundary, bodyPublisherSupplier);
    }

    /**
     * Returns a copy of this request with the specified URI.
     *
     * @param newUri the new URI, must not be {@code null}
     * @return a new {@link WireRequest} with the new URI
     * @throws NullPointerException if {@code newUri} is {@code null}
     */
    public WireRequest withUri(URI newUri) {
        Objects.requireNonNull(newUri, "newUri");
        return new WireRequest(method, newUri, headers, contentType, body, parts, boundary, bodyPublisherSupplier);
    }

    /**
     * Returns a copy of this request with the specified HTTP method.
     *
     * @param newMethod the new HTTP method, must not be {@code null}
     * @return a new {@link WireRequest} with the new method
     * @throws NullPointerException if {@code newMethod} is {@code null}
     */
    public WireRequest withMethod(HttpMethod newMethod) {
        Objects.requireNonNull(newMethod, "newMethod");
        return new WireRequest(newMethod, uri, headers, contentType, body, parts, boundary, bodyPublisherSupplier);
    }

    /**
     * Returns a copy of this request with the specified payload body and Content-Type.
     *
     * @param newBody the new raw payload bytes, or {@code null}
     * @param newContentType the new Content-Type header value, or {@code null}
     * @return a new {@link WireRequest} with the body and content type updated
     */
    public WireRequest withBody(byte[] newBody, String newContentType) {
        return new WireRequest(method, uri, headers, newContentType, newBody, parts, boundary, null);
    }

    /**
     * Returns a copy of this request with the specified payload body, preserving existing Content-Type.
     *
     * @param newBody the new raw payload bytes, or {@code null}
     * @return a new {@link WireRequest} with the body updated
     */
    public WireRequest withBody(byte[] newBody) {
        return new WireRequest(method, uri, headers, contentType, newBody, parts, boundary, null);
    }

    /**
     * Returns a copy of this request with the specified streaming body publisher.
     *
     * @param publisher the body publisher, or {@code null}
     * @return a new {@link WireRequest} with the streaming body publisher set and raw body cleared
     */
    public WireRequest withBodyPublisher(HttpRequest.BodyPublisher publisher) {
        Supplier<? extends HttpRequest.BodyPublisher> supplier = publisher != null ? () -> publisher : null;
        return new WireRequest(method, uri, headers, contentType, null, parts, boundary, supplier);
    }

    /**
     * Returns a copy of this request with the specified streaming body publisher supplier.
     *
     * @param supplier the body publisher supplier, or {@code null}
     * @return a new {@link WireRequest} with the streaming body supplier set and raw body cleared
     */
    public WireRequest withBodyPublisherSupplier(Supplier<? extends HttpRequest.BodyPublisher> supplier) {
        return new WireRequest(method, uri, headers, contentType, null, parts, boundary, supplier);
    }

    /**
     * Returns a copy of this request with the specified Content-Type.
     *
     * @param newContentType the new Content-Type header value, or {@code null}
     * @return a new {@link WireRequest} with the content type updated
     */
    public WireRequest withContentType(String newContentType) {
        return new WireRequest(method, uri, headers, newContentType, body, parts, boundary, bodyPublisherSupplier);
    }

    /**
     * Returns a copy of this request with the specified multipart parts and boundary.
     *
     * @param newParts the multipart parts, or {@code null}
     * @param newBoundary the multipart boundary, or {@code null}
     * @return a new {@link WireRequest} with parts and boundary updated
     */
    public WireRequest withParts(List<MultipartPart> newParts, String newBoundary) {
        return new WireRequest(method, uri, headers, contentType, body, newParts, newBoundary, bodyPublisherSupplier);
    }

    /**
     * Creates a new {@link Builder} pre-populated with this request's current state.
     *
     * @return a populated {@link Builder}
     */
    public Builder toBuilder() {
        Builder builder = builder()
                .method(method)
                .uri(uri)
                .contentType(contentType)
                .body(body)
                .parts(parts)
                .boundary(boundary)
                .bodyPublisherSupplier(bodyPublisherSupplier);
        if (headers != null) {
            builder.headers(headers);
        }
        return builder;
    }

    /**
     * Creates a fresh, empty {@link Builder}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link WireRequest} instances.
     */
    public static final class Builder {
        private HttpMethod method;
        private URI uri;
        private List<Header> headers;
        private String contentType;
        private byte[] body;
        private List<MultipartPart> parts;
        private String boundary;
        private Supplier<? extends HttpRequest.BodyPublisher> bodyPublisherSupplier;

        private Builder() {
        }

        /**
         * Sets the HTTP method.
         *
         * @param method the HTTP method
         * @return this builder, for chaining
         */
        public Builder method(HttpMethod method) {
            this.method = method;
            return this;
        }

        /**
         * Sets the request URI.
         *
         * @param uri the URI
         * @return this builder, for chaining
         */
        public Builder uri(URI uri) {
            this.uri = uri;
            return this;
        }

        /**
         * Sets the request headers list.
         *
         * @param headers the list of headers, or {@code null}
         * @return this builder, for chaining
         */
        public Builder headers(List<Header> headers) {
            this.headers = headers != null ? new ArrayList<>(headers) : null;
            return this;
        }

        /**
         * Appends a header.
         *
         * @param name the header name, must not be {@code null}
         * @param value the header value, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code name} or {@code value} is {@code null}
         */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            return header(new Header(name, value));
        }

        /**
         * Appends a header.
         *
         * @param header the header, must not be {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException if {@code header} is {@code null}
         */
        public Builder header(Header header) {
            Objects.requireNonNull(header, "header");
            if (this.headers == null) {
                this.headers = new ArrayList<>();
            }
            this.headers.add(header);
            return this;
        }

        /**
         * Sets the Content-Type header value.
         *
         * @param contentType the content type, or {@code null}
         * @return this builder, for chaining
         */
        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        /**
         * Sets the raw payload bytes.
         *
         * @param body the payload bytes, or {@code null}
         * @return this builder, for chaining
         */
        public Builder body(byte[] body) {
            this.body = body;
            return this;
        }

        /**
         * Sets the streaming body publisher.
         *
         * @param publisher the body publisher, or {@code null}
         * @return this builder, for chaining
         */
        public Builder bodyPublisher(HttpRequest.BodyPublisher publisher) {
            this.bodyPublisherSupplier = publisher != null ? () -> publisher : null;
            return this;
        }

        /**
         * Sets the streaming body publisher supplier.
         *
         * @param supplier the body publisher supplier, or {@code null}
         * @return this builder, for chaining
         */
        public Builder bodyPublisherSupplier(Supplier<? extends HttpRequest.BodyPublisher> supplier) {
            this.bodyPublisherSupplier = supplier;
            return this;
        }

        /**
         * Sets the multipart parts.
         *
         * @param parts the multipart parts, or {@code null}
         * @return this builder, for chaining
         */
        public Builder parts(List<MultipartPart> parts) {
            this.parts = parts;
            return this;
        }

        /**
         * Sets the multipart boundary string.
         *
         * @param boundary the boundary string, or {@code null}
         * @return this builder, for chaining
         */
        public Builder boundary(String boundary) {
            this.boundary = boundary;
            return this;
        }

        /**
         * Builds an immutable {@link WireRequest} instance.
         *
         * @return the constructed {@link WireRequest}
         */
        public WireRequest build() {
            List<Header> finalHeaders = headers != null ? Collections.unmodifiableList(new ArrayList<>(headers)) : null;
            return new WireRequest(method, uri, finalHeaders, contentType, body, parts, boundary, bodyPublisherSupplier);
        }
    }
}
