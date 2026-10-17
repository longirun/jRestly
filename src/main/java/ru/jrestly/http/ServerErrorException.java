package ru.jrestly.http;

import java.io.Serial;
import java.util.List;

/**
 * Thrown when the server returns an unexpected HTTP 5xx server error status code
 * (status in range [500..599]) not declared in {@code @OnError} and not listed
 * in {@code @ExpectStatus}.
 *
 * <p>Extends {@link UnexpectedStatusException} to allow callers to catch all server
 * errors specifically (e.g. 500 Internal Server Error, 502 Bad Gateway, 503 Service Unavailable,
 * 504 Gateway Timeout) without manual status code checks, while retaining full backward
 * compatibility with existing {@code catch (UnexpectedStatusException)} and
 * {@code catch (HandledException)} blocks.</p>
 */
@SuppressWarnings("serial")
public class ServerErrorException extends UnexpectedStatusException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for an unexpected HTTP 5xx server error status.
     *
     * @param statusCode the unexpected HTTP 5xx status
     * @param rawBody the response body as a string, or {@code null} for an empty body
     * @param responseHeaders the response headers, or {@code null} for none
     */
    public ServerErrorException(int statusCode, String rawBody, List<Header> responseHeaders) {
        super(statusCode, rawBody, responseHeaders);
    }

    /**
     * Creates the exception for an unexpected HTTP 5xx server error status carrying an optional Problem Detail payload.
     *
     * @param statusCode the unexpected HTTP 5xx status
     * @param rawBody the response body as a string, or {@code null} for an empty body
     * @param responseHeaders the response headers, or {@code null} for none
     * @param problemDetail the parsed Problem Detail, or {@code null}
     */
    public ServerErrorException(int statusCode, String rawBody, List<Header> responseHeaders, ru.jrestly.problem.ProblemDetail problemDetail) {
        super(statusCode, rawBody, responseHeaders, problemDetail);
    }
}
