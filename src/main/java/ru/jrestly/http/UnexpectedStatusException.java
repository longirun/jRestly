package ru.jrestly.http;

import java.io.Serial;
import java.util.List;

/**
 * Thrown when the server returns a status not declared in {@code @OnError},
 * not listed in {@code @ExpectStatus}, and outside the 2xx range
 * (when {@code @ExpectStatus} is absent).
 *
 * <p>Unlike {@link HandledException}, the response body here is untyped —
 * an unexpected status has no body contract by definition, so it is exposed
 * as a raw string via {@link #getRawBody()}.</p>
 */
// Not part of the wire contract: the payload is a diagnostic raw string (ADR-0013)
@SuppressWarnings("serial")
public class UnexpectedStatusException extends HandledException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The response body as a raw string — an unexpected status has no body contract, so it is not decoded. */
    private final String rawBody;

    /**
     * Creates the exception for an unexpected status.
     *
     * @param statusCode the unexpected HTTP status
     * @param rawBody the response body as a string, or {@code null} for an empty body
     * @param responseHeaders the response headers, or {@code null} for none
     */
    public UnexpectedStatusException(int statusCode, String rawBody, List<Header> responseHeaders) {
        super(null, statusCode, responseHeaders);
        this.rawBody = rawBody == null ? "" : rawBody;
    }

    /**
     * The response body as a raw string — an unexpected status has no body
     * contract, so it is not decoded.
     *
     * @return the raw body, empty when the body was absent
     */
    public String getRawBody() {
        return rawBody;
    }

    @Override
    public String getMessage() {
        return "Unexpected HTTP status " + getStatusCode();
    }
}
