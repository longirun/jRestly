package ru.jrestly.http;

import java.io.InputStream;
import java.util.List;

/**
 * Internal wire model: the engine's view of a response — status, headers
 * and the payload, either the raw bytes or a live body stream (ADR-0015).
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
 */
public record WireResponse(int statusCode, List<Header> headers, byte[] body, InputStream stream) {

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
        this(statusCode, headers, body, null);
    }
}
