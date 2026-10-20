package ru.jrestly.http;

import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.problem.ProblemDetail;
import ru.jrestly.problem.ProblemDetailException;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Evaluates HTTP status acceptability, constructs unexpected-status exception
 * hierarchies (client/server/unexpected), and orchestrates {@code @OnError}
 * response decoding and dispatch (ADR-0088).
 *
 * <p>Extracted from {@link HttpTemplate} as part of the ADR-0081 modularization
 * wave. Package-private and stateless.
 */
final class HttpErrorHandler {

    private HttpErrorHandler() {
    }

    /**
     * Determines whether the given HTTP status code is accepted as a successful response.
     *
     * @param expectStatuses the explicitly accepted statuses, or {@code null} for the default (any 2xx)
     * @param conditional    {@code true} if 304 Not Modified is accepted
     * @param optional       {@code true} if 304 and 404 Not Found are accepted
     * @param statusCode     the HTTP response status code
     * @return {@code true} if the status code is expected
     */
    static boolean isExpectedStatus(List<Integer> expectStatuses, boolean conditional, boolean optional, int statusCode) {
        if (expectStatuses != null) {
            return expectStatuses.contains(statusCode);
        }
        if (statusCode >= 200 && statusCode < 300) {
            return true;
        }
        if (conditional && statusCode == 304) {
            return true;
        }
        if (optional && (statusCode == 304 || statusCode == 404)) {
            return true;
        }
        return false;
    }

    /**
     * Creates an appropriate {@link UnexpectedStatusException} (or subclass {@link ClientErrorException}
     * or {@link ServerErrorException}) for an unexpected HTTP response status.
     *
     * @param statusCode      the HTTP response status code
     * @param rawBody         the raw response body string, or {@code null}
     * @param responseHeaders the HTTP response headers
     * @param codec           the resolved response codec for Problem Details parsing, or {@code null}
     * @return the constructed exception
     */
    static UnexpectedStatusException createUnexpectedStatusException(
            int statusCode, String rawBody, List<Header> responseHeaders, PayloadCodec codec) {
        ProblemDetail problemDetail = ProblemDetailResolver.resolve(
                statusCode, rawBody, responseHeaders, ResponseBody.contentTypeOf(responseHeaders), codec);
        if (statusCode >= 400 && statusCode < 500) {
            return new ClientErrorException(statusCode, rawBody, responseHeaders, problemDetail);
        }
        if (statusCode >= 500 && statusCode < 600) {
            return new ServerErrorException(statusCode, rawBody, responseHeaders, problemDetail);
        }
        return new UnexpectedStatusException(statusCode, rawBody, responseHeaders, problemDetail);
    }

    /**
     * Convenience overload for {@code @OnError} dispatch when a {@link PayloadCodec} is already resolved.
     *
     * @param onErrorParser   the error parser for looking up the target error class
     * @param codec           the payload codec, or {@code null}
     * @param statusCode      the HTTP response status code
     * @param payload         the raw response body string, or {@code null}
     * @param responseHeaders the HTTP response headers
     * @param payloadCharset  the charset used to encode the payload bytes for the codec
     */
    static void handleOnError(
            OnErrorParser onErrorParser,
            PayloadCodec codec,
            int statusCode,
            String payload,
            List<Header> responseHeaders,
            Charset payloadCharset) {
        handleOnError(onErrorParser, codec != null ? () -> codec : null, statusCode, payload, responseHeaders, payloadCharset);
    }

    /**
     * Dispatches an error response according to the configured {@link OnErrorParser}.
     * Deserializes the body into the declared error type, handles {@link ProblemDetail}
     * fallbacks, instantiates throwables when bodyless, and throws either a typed
     * {@link Throwable} or a {@link HandledException}.
     *
     * @param onErrorParser   the error parser for looking up the target error class
     * @param codecSupplier   lazy supplier for the payload codec, invoked only when payload decoding is needed
     * @param statusCode      the HTTP response status code
     * @param payload         the raw response body string, or {@code null}
     * @param responseHeaders the HTTP response headers
     * @param payloadCharset  the charset used to encode the payload bytes for the codec
     */
    static void handleOnError(
            OnErrorParser onErrorParser,
            Supplier<PayloadCodec> codecSupplier,
            int statusCode,
            String payload,
            List<Header> responseHeaders,
            Charset payloadCharset) {
        Objects.requireNonNull(onErrorParser, "onErrorParser");
        Class<?> targetErrorClass = onErrorParser.getErrorClass(statusCode);
        boolean isProblemException = ProblemDetailException.class.isAssignableFrom(targetErrorClass);
        Class<?> deserializeClass = isProblemException ? ProblemDetail.class : targetErrorClass;

        Object errorObject = null;
        if (payload != null && !payload.isEmpty() && deserializeClass != Void.class) {
            PayloadCodec codec = codecSupplier != null ? codecSupplier.get() : null;
            if (codec != null) {
                if (deserializeClass == ProblemDetail.class) {
                    try {
                        errorObject = codec.deserialize(
                                payload.getBytes(payloadCharset), deserializeClass, ResponseBody.contentTypeOf(responseHeaders));
                    } catch (Throwable ignored) {
                    }
                } else {
                    errorObject = codec.deserialize(
                            payload.getBytes(payloadCharset), deserializeClass, ResponseBody.contentTypeOf(responseHeaders));
                }
            }
        }

        if (errorObject == null && (targetErrorClass == ProblemDetail.class || isProblemException)
                && payload != null && !payload.isBlank()) {
            try {
                errorObject = ProblemDetail.fromJson(payload);
            } catch (Throwable ignored) {
            }
        }
        if (errorObject instanceof ProblemDetail pd && pd.getStatus() <= 0) {
            pd.setStatus(statusCode);
        }
        if (isProblemException) {
            ProblemDetail pd = (errorObject instanceof ProblemDetail p)
                    ? p
                    : ProblemDetail.forStatus(statusCode);
            errorObject = new ProblemDetailException(pd, responseHeaders);
        }

        if (Throwable.class.isAssignableFrom(targetErrorClass)) {
            switch (errorObject) {
                case RuntimeException re -> throw re;
                case Error err -> throw err;
                case Throwable t -> throw new RuntimeException(t);
                case null, default -> {
                    Throwable instance = ProblemDetailResolver.instantiateThrowable(targetErrorClass, statusCode, responseHeaders);
                    switch (instance) {
                        case RuntimeException re -> throw re;
                        case Error err -> throw err;
                        default -> throw new RuntimeException(instance);
                    }
                }
            }
        }

        throw new HandledException(errorObject, statusCode, responseHeaders);
    }
}
