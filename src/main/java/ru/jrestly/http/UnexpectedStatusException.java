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

    /** The parsed Problem Detail payload, or {@code null} if absent or not a Problem Detail. */
    private final ru.jrestly.problem.ProblemDetail problemDetail;

    /**
     * Creates the exception for an unexpected status.
     *
     * @param statusCode the unexpected HTTP status
     * @param rawBody the response body as a string, or {@code null} for an empty body
     * @param responseHeaders the response headers, or {@code null} for none
     */
    public UnexpectedStatusException(int statusCode, String rawBody, List<Header> responseHeaders) {
        this(statusCode, rawBody, responseHeaders, null);
    }

    /**
     * Creates the exception for an unexpected status carrying an optional Problem Detail payload.
     *
     * @param statusCode the unexpected HTTP status
     * @param rawBody the response body as a string, or {@code null} for an empty body
     * @param responseHeaders the response headers, or {@code null} for none
     * @param problemDetail the parsed Problem Detail, or {@code null}
     */
    public UnexpectedStatusException(int statusCode, String rawBody, List<Header> responseHeaders, ru.jrestly.problem.ProblemDetail problemDetail) {
        super(null, statusCode, responseHeaders);
        this.rawBody = rawBody == null ? "" : rawBody;
        this.problemDetail = problemDetail;
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
    public ru.jrestly.problem.ProblemDetail getProblemDetailPayload() {
        if (problemDetail != null) {
            return problemDetail;
        }
        return super.getProblemDetailPayload();
    }

    @Override
    public String getMessage() {
        if (problemDetail == null) {
            return "Unexpected HTTP status " + getStatusCode();
        }
        StringBuilder sb = new StringBuilder("Unexpected HTTP status ").append(getStatusCode());
        if (problemDetail.getTitle() != null && !problemDetail.getTitle().isEmpty()) {
            sb.append(" (").append(problemDetail.getTitle()).append(")");
        }
        if (problemDetail.getDetail() != null && !problemDetail.getDetail().isEmpty()) {
            sb.append(": ").append(problemDetail.getDetail());
        }
        return sb.toString();
    }
}
