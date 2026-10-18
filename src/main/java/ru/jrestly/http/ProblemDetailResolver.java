package ru.jrestly.http;

import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.problem.ProblemDetail;
import ru.jrestly.problem.ProblemDetailException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a {@link ProblemDetail} (RFC 9457) from an unexpected-status
 * response body (ADR-0064).
 *
 * <p>Two sources are tried in order: the media-type-aware response codec
 * (unless its document is rejected by the "likely problem" shape heuristic on
 * an unrecognized media type) and, when that fails, the built-in
 * zero-dependency parser. The wire status is filled in when the document
 * carries none.
 *
 * <p>Extracted from {@code HttpTemplate} (ADR-0066) — a cohesive concern fed
 * by the response headers, the already-selected response codec and its media
 * type, so the resolution rules are pinned by unit tests without a wire
 * round-trip. The throwable instantiation helper lives here too, since it
 * shares the Problem Details fallback.
 */
final class ProblemDetailResolver {

    private ProblemDetailResolver() {
    }

    /**
     * The problem detail carried by the response body, or {@code null} when the
     * body is absent, is not a JSON object, or is not recognizable as one.
     *
     * @param statusCode      the wire status, used to fill a missing document status
     * @param rawBody         the decoded response body, may be {@code null}
     * @param responseHeaders the response headers (media-type override + heuristic)
     * @param contentType     the response {@code Content-Type}, may be {@code null}
     * @param responseCodec   the response codec selected for {@code contentType}, may be
     *                        {@code null} (the caller resolves it best-effort, so a
     *                        missing codec never aborts the resolution)
     * @return the resolved problem detail, or {@code null}
     */
    static ProblemDetail resolve(int statusCode, String rawBody, List<Header> responseHeaders,
                                 String contentType, PayloadCodec responseCodec) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        String trimmed = rawBody.trim();
        if (trimmed.charAt(0) == '\uFEFF') {
            trimmed = trimmed.substring(1).trim(); // mirror ProblemDetail.fromJson: RFC 8259 §8.1 BOM prefix
        }
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        boolean isProblemType = isProblemJsonContentType(responseHeaders);
        try {
            if (responseCodec != null) {
                byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
                ProblemDetail pd = responseCodec.deserialize(bytes, ProblemDetail.class, contentType);
                if (pd != null && (isProblemType || isLikelyProblemDetail(pd))) {
                    if (pd.getStatus() <= 0) {
                        pd.setStatus(statusCode);
                    }
                    return pd;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            ProblemDetail pd = ProblemDetail.fromJson(trimmed);
            if (pd != null && (isProblemType || isLikelyProblemDetail(pd))) {
                if (pd.getStatus() <= 0) {
                    pd.setStatus(statusCode);
                }
                return pd;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // Package-private seam: unit tests pin the media-type override.
    static boolean isProblemJsonContentType(List<Header> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        for (Header h : headers) {
            if ("Content-Type".equalsIgnoreCase(h.name())) {
                String val = h.value();
                if (val != null) {
                    String lower = val.toLowerCase(Locale.ROOT);
                    return lower.contains("application/problem+json") || lower.contains("application/problem+xml");
                }
            }
        }
        return false;
    }

    // Package-private seam: unit tests pin the "likely problem" shape heuristic.
    static boolean isLikelyProblemDetail(ProblemDetail pd) {
        if (pd == null) {
            return false;
        }
        boolean hasNonDefaultType = pd.getType() != null && !"about:blank".equals(pd.getType().toString());
        return pd.getStatus() > 0
                || hasNonDefaultType
                || (pd.getTitle() != null && !pd.getTitle().isBlank())
                || (pd.getDetail() != null && !pd.getDetail().isBlank())
                || pd.getInstance() != null;
    }

    // Instantiate a declared @OnError throwable from the wire status, probing
    // the constructor shapes it may expose (ADR-0036).
    static Throwable instantiateThrowable(Class<?> clazz, int statusCode, List<Header> responseHeaders) {
        if (ProblemDetailException.class.isAssignableFrom(clazz)) {
            return new ProblemDetailException(ProblemDetail.forStatus(statusCode), responseHeaders);
        }
        try {
            var ctor = clazz.getConstructor(Object.class, int.class, List.class);
            return (Throwable) ctor.newInstance(null, statusCode, responseHeaders);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            var ctor = clazz.getConstructor(String.class, int.class);
            return (Throwable) ctor.newInstance("HTTP " + statusCode, statusCode);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            var ctor = clazz.getConstructor(String.class);
            return (Throwable) ctor.newInstance("HTTP " + statusCode);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            var ctor = clazz.getConstructor();
            return (Throwable) ctor.newInstance();
        } catch (ReflectiveOperationException ignored) {
        }
        return new HandledException(null, statusCode, responseHeaders);
    }
}
