package ru.jrestly.http;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Internal wire model: the engine's view of a response — status, headers,
 * negotiated protocol version, optional trailers, and the payload (either
 * the raw bytes or a live body stream, ADR-0015, ADR-0038, ADR-0059).
 *
 * <p>Exactly one of {@code body} and {@code stream} is non-null: the
 * {@code ofByteArray} send path carries {@code body}, the
 * {@code ofInputStream} one carries {@code stream} (the {@link WireRequest}
 * nullable-component style).
 *
 * @param statusCode the HTTP status code; classified against the {@code @OnError} statuses, the {@code @ExpectStatus} list, and the redirect statuses (301/302/303/307/308)
 * @param headers the response headers, one {@link Header} per entry of the JDK header map; consumed for the redirect {@code Location} lookup, the {@code @SetAuthDetails} capture, and as the {@link HandledException} details
 * @param body the raw payload bytes, or {@code null} for a body-less or a streaming response; decoded into the method's return type by the module's codec, or into the {@code @OnError} error class
 * @param stream the live body stream of a streaming response (the JDK's {@code ofInputStream} handler), or {@code null} for a byte-payload response
 * @param version the negotiated HTTP protocol version (ADR-0038)
 * @param trailers the trailing headers, or an empty list if none (ADR-0038)
 * @param trailersSupplier lazy supplier for response trailers populated after stream completion (ADR-0059)
 */
public record WireResponse(
        int statusCode,
        List<Header> headers,
        byte[] body,
        InputStream stream,
        HttpClient.Version version,
        List<Header> trailers,
        Supplier<List<Header>> trailersSupplier
) {

    /**
     * Compact constructor normalizing collections, defaults, and the trailer supplier.
     */
    public WireResponse {
        headers = headers != null ? List.copyOf(headers) : List.of();
        version = version != null ? version : HttpClient.Version.HTTP_1_1;
        List<Header> effectiveTrailers = trailers != null ? List.copyOf(trailers) : List.of();
        trailers = effectiveTrailers;
        if (trailersSupplier == null) {
            trailersSupplier = !effectiveTrailers.isEmpty() ? () -> effectiveTrailers : List::of;
        }
    }

    /**
     * The 6-component form with negotiated protocol version and trailers (source-compatible with ADR-0038).
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param body the raw payload bytes, or {@code null}
     * @param stream the live body stream
     * @param version the negotiated HTTP protocol version
     * @param trailers the trailing headers
     */
    public WireResponse(int statusCode, List<Header> headers, byte[] body, InputStream stream, HttpClient.Version version, List<Header> trailers) {
        this(statusCode, headers, body, stream, version, trailers, trailers != null && !trailers.isEmpty() ? () -> List.copyOf(trailers) : List::of);
    }

    /**
     * The byte-payload form: no stream component — the pre-streaming call
     * sites and the {@code ofByteArray} send path keep this shape
     * (source-compatible with the 0.8.0 wire model).
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param body the raw payload bytes, or {@code null} for a body-less response
     */
    public WireResponse(int statusCode, List<Header> headers, byte[] body) {
        this(statusCode, headers, body, null, HttpClient.Version.HTTP_1_1, List.of());
    }

    /**
     * The streaming-response form (source-compatible with the 0.9.0 wire model).
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param body the raw payload bytes, or {@code null}
     * @param stream the live body stream
     */
    public WireResponse(int statusCode, List<Header> headers, byte[] body, InputStream stream) {
        this(statusCode, headers, body, stream, HttpClient.Version.HTTP_1_1, List.of());
    }

    /**
     * Response form with negotiated protocol version (ADR-0038).
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param body the raw payload bytes, or {@code null}
     * @param stream the live body stream
     * @param version the negotiated HTTP protocol version
     */
    public WireResponse(int statusCode, List<Header> headers, byte[] body, InputStream stream, HttpClient.Version version) {
        this(statusCode, headers, body, stream, version, List.of());
    }

    /**
     * Creates a minimal body-less synthetic wire response.
     *
     * @param statusCode the HTTP status code
     * @return a synthetic wire response
     */
    public static WireResponse of(int statusCode) {
        return new WireResponse(statusCode, List.of(), null, null, HttpClient.Version.HTTP_1_1, List.of());
    }

    /**
     * Creates a synthetic wire response with a UTF-8 string payload.
     *
     * @param statusCode the HTTP status code
     * @param body the response body string, or {@code null}
     * @return a synthetic wire response
     */
    public static WireResponse of(int statusCode, String body) {
        byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        return new WireResponse(statusCode, List.of(), bytes, null, HttpClient.Version.HTTP_1_1, List.of());
    }

    /**
     * Creates a synthetic wire response with raw byte payload.
     *
     * @param statusCode the HTTP status code
     * @param body the raw response body bytes, or {@code null}
     * @return a synthetic wire response
     */
    public static WireResponse of(int statusCode, byte[] body) {
        return new WireResponse(statusCode, List.of(), body, null, HttpClient.Version.HTTP_1_1, List.of());
    }

    /**
     * Creates a synthetic wire response with headers and a UTF-8 string payload.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param body the response body string, or {@code null}
     * @return a synthetic wire response
     */
    public static WireResponse of(int statusCode, List<Header> headers, String body) {
        byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        return new WireResponse(statusCode, headers, bytes, null, HttpClient.Version.HTTP_1_1, List.of());
    }

    /**
     * Creates a synthetic wire response with headers and raw byte payload.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param body the raw response body bytes, or {@code null}
     * @return a synthetic wire response
     */
    public static WireResponse of(int statusCode, List<Header> headers, byte[] body) {
        return new WireResponse(statusCode, headers, body, null, HttpClient.Version.HTTP_1_1, List.of());
    }

    /**
     * Finds the first value of the specified header (case-insensitive).
     *
     * @param name the header name to find, must not be {@code null}
     * @return an {@link Optional} containing the header value, or {@link Optional#empty()}
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Optional<String> header(String name) {
        Objects.requireNonNull(name, "name");
        for (Header h : headers) {
            if (h.name().equalsIgnoreCase(name)) {
                return Optional.ofNullable(h.value());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether this response carries a non-empty byte body.
     *
     * @return {@code true} if body bytes are present and non-empty
     */
    public boolean hasBody() {
        return body != null && body.length > 0;
    }

    /**
     * Creates a new fluent {@link Builder} initialized with status code 200.
     *
     * @return a new response builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for synthetic {@link WireResponse} instances.
     */
    public static final class Builder {

        private int statusCode = 200;
        private final List<Header> headers = new ArrayList<>();
        private byte[] body;
        private InputStream stream;
        private HttpClient.Version version = HttpClient.Version.HTTP_1_1;
        private final List<Header> trailers = new ArrayList<>();
        private Supplier<List<Header>> trailersSupplier;

        private Builder() {
        }

        /**
         * Sets the HTTP status code.
         *
         * @param statusCode the HTTP status code
         * @return this builder
         */
        public Builder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        /**
         * Appends a header.
         *
         * @param name the header name, must not be {@code null}
         * @param value the header value
         * @return this builder
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            this.headers.add(new Header(name, value));
            return this;
        }

        /**
         * Appends a header.
         *
         * @param header the header to append, must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code header} is {@code null}
         */
        public Builder header(Header header) {
            Objects.requireNonNull(header, "header");
            this.headers.add(header);
            return this;
        }

        /**
         * Appends multiple headers.
         *
         * @param headers the list of headers to append
         * @return this builder
         */
        public Builder headers(List<Header> headers) {
            if (headers != null) {
                this.headers.addAll(headers);
            }
            return this;
        }

        /**
         * Sets the body bytes.
         *
         * @param body the body bytes, or {@code null}
         * @return this builder
         */
        public Builder body(byte[] body) {
            this.body = body;
            this.stream = null;
            return this;
        }

        /**
         * Sets the body as a UTF-8 string.
         *
         * @param body the body string, or {@code null}
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
            this.stream = null;
            return this;
        }

        /**
         * Sets the body string with the specified character set.
         *
         * @param body the body string, or {@code null}
         * @param charset the character set to encode with, must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public Builder body(String body, Charset charset) {
            Objects.requireNonNull(charset, "charset");
            this.body = body != null ? body.getBytes(charset) : null;
            this.stream = null;
            return this;
        }

        /**
         * Sets the body stream.
         *
         * @param stream the body stream, or {@code null}
         * @return this builder
         */
        public Builder stream(InputStream stream) {
            this.stream = stream;
            this.body = null;
            return this;
        }

        /**
         * Sets the HTTP version.
         *
         * @param version the HTTP protocol version, or {@code null} for default HTTP/1.1
         * @return this builder
         */
        public Builder version(HttpClient.Version version) {
            this.version = version != null ? version : HttpClient.Version.HTTP_1_1;
            return this;
        }

        /**
         * Appends a trailing header.
         *
         * @param name the trailer name, must not be {@code null}
         * @param value the trailer value
         * @return this builder
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public Builder trailer(String name, String value) {
            Objects.requireNonNull(name, "name");
            this.trailers.add(new Header(name, value));
            return this;
        }

        /**
         * Appends a trailing header.
         *
         * @param header the trailer header to append, must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code header} is {@code null}
         */
        public Builder trailer(Header header) {
            Objects.requireNonNull(header, "header");
            this.trailers.add(header);
            return this;
        }

        /**
         * Appends multiple trailing headers.
         *
         * @param trailers the trailing headers to append
         * @return this builder
         */
        public Builder trailers(List<Header> trailers) {
            if (trailers != null) {
                this.trailers.addAll(trailers);
            }
            return this;
        }

        /**
         * Sets the lazy trailer supplier.
         *
         * @param trailersSupplier the supplier of trailers, or {@code null}
         * @return this builder
         */
        public Builder trailersSupplier(Supplier<List<Header>> trailersSupplier) {
            this.trailersSupplier = trailersSupplier;
            return this;
        }

        /**
         * Builds the {@link WireResponse} instance.
         *
         * @return the newly constructed wire response
         */
        public WireResponse build() {
            return new WireResponse(statusCode, headers, body, stream, version, trailers, trailersSupplier);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof WireResponse other)) return false;
        return statusCode == other.statusCode
                && Objects.equals(headers, other.headers)
                && Arrays.equals(body, other.body)
                && Objects.equals(stream, other.stream)
                && version == other.version
                && Objects.equals(trailers, other.trailers);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(statusCode, headers, stream, version, trailers);
        result = 31 * result + Arrays.hashCode(body);
        return result;
    }

    @Override
    public String toString() {
        return "WireResponse[statusCode=" + statusCode
                + ", headers=" + headers
                + ", body=" + (body != null ? "[" + body.length + " bytes]" : "null")
                + ", stream=" + stream
                + ", version=" + version
                + ", trailers=" + trailers + "]";
    }
}
