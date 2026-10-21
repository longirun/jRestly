package ru.jrestly.problem;

import ru.jrestly.http.HandledException;
import ru.jrestly.http.Header;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Exception thrown or caught when an HTTP response contains an RFC 7807 / RFC 9457 Problem Detail.
 *
 * <p>Extends {@link HandledException} so that existing code catching {@code HandledException}
 * continues to work seamlessly, while giving callers direct access to {@link #getProblemDetail()}.</p>
 */
@SuppressWarnings("serial")
public class ProblemDetailException extends HandledException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The wrapped {@link ProblemDetail} payload.
     * @serial
     */
    private final ProblemDetail problemDetail;

    /**
     * Creates a {@link ProblemDetailException} wrapping the given {@link ProblemDetail}.
     *
     * @param problemDetail the problem detail payload; never {@code null}
     * @param responseHeaders the response headers, or {@code null} for none
     */
    public ProblemDetailException(ProblemDetail problemDetail, List<Header> responseHeaders) {
        super(problemDetail, Objects.requireNonNull(problemDetail, "problemDetail must not be null").getStatus(), responseHeaders);
        this.problemDetail = problemDetail;
    }

    /**
     * Creates a {@link ProblemDetailException} with details, status code, and headers.
     *
     * @param details the decoded object or {@link ProblemDetail}
     * @param statusCode the HTTP status code
     * @param responseHeaders the response headers
     */
    public ProblemDetailException(Object details, int statusCode, List<Header> responseHeaders) {
        super(details instanceof ProblemDetail pd ? pd : (details != null ? ProblemDetail.forStatusAndDetail(statusCode, details.toString()) : ProblemDetail.forStatus(statusCode)), statusCode, responseHeaders);
        if (details instanceof ProblemDetail pd) {
            this.problemDetail = pd;
        } else {
            this.problemDetail = ProblemDetail.forStatus(statusCode);
            if (details != null) {
                this.problemDetail.setDetail(details.toString());
            }
        }
    }

    /**
     * Returns the structured {@link ProblemDetail} payload directly.
     *
     * @return the problem detail payload; never {@code null}
     */
    public ProblemDetail problemDetail() {
        return problemDetail;
    }

    /**
     * Returns the structured {@link ProblemDetail} payload.
     *
     * @return the problem detail payload; never {@code null}
     */
    @Override
    public ProblemDetail getProblemDetailPayload() {
        return problemDetail;
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder("HTTP ").append(problemDetail.getStatus());
        if (problemDetail.getTitle() != null && !problemDetail.getTitle().isEmpty()) {
            sb.append(" [").append(problemDetail.getTitle()).append("]");
        }
        if (problemDetail.getDetail() != null && !problemDetail.getDetail().isEmpty()) {
            sb.append(": ").append(problemDetail.getDetail());
        }
        return sb.toString();
    }
}
