package ru.jrestly.http;

import java.io.Serial;
import java.util.List;

/**
 * Thrown when the server returns an unexpected HTTP 4xx client error status code
 * (status in range [400..499]) not declared in {@code @OnError} and not listed
 * in {@code @ExpectStatus}.
 *
 * <p>Extends {@link UnexpectedStatusException} to allow callers to catch all client
 * errors specifically (e.g. 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found)
 * without manual status code checks, while retaining full backward compatibility with
 * existing {@code catch (UnexpectedStatusException)} and {@code catch (HandledException)} blocks.</p>
 */
@SuppressWarnings("serial")
public class ClientErrorException extends UnexpectedStatusException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for an unexpected HTTP 4xx client error status.
     *
     * @param statusCode the unexpected HTTP 4xx status
     * @param rawBody the response body as a string, or {@code null} for an empty body
     * @param responseHeaders the response headers, or {@code null} for none
     */
    public ClientErrorException(int statusCode, String rawBody, List<Header> responseHeaders) {
        super(statusCode, rawBody, responseHeaders);
    }
}
