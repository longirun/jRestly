package ru.jrestly.http;

import ru.jrestly.tracing.TraceIdExtractor;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Diagnostic logger for the HTTP dispatch pipeline (ADR-0089, ADR-0095, ADR-0096).
 *
 * <p>Handles wire-level formatting, distributed trace/correlation ID correlation,
 * credential sanitization (GDPR, 152-FZ, PCI-DSS), and gated output of outgoing requests,
 * incoming responses, intermediate redirect hops, and retry scheduling attempts.
 */
final class HttpDiagnosticLogger {

    private static final Set<HttpMethod> BODY_LOGGING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.PUT);

    private static final String SANITIZE_PROP = "jrestly.logging.sanitize";

    private HttpDiagnosticLogger() {
        // Utility class
    }

    /**
     * Checks whether diagnostic log sanitization is enabled via system property or default (ADR-0095).
     *
     * @return {@code true} if sanitization is enabled, {@code false} if raw output is permitted
     */
    static boolean isSanitizingEnabled() {
        return Boolean.parseBoolean(System.getProperty(SANITIZE_PROP, "true"));
    }

    /**
     * Logs the outgoing request at INFO level with automatic trace extraction and default sanitization.
     *
     * @param logger  the target system logger
     * @param request the wire request about to be sent
     */
    static void logRequest(System.Logger logger, WireRequest request) {
        logRequest(logger, request, TraceIdExtractor.extract(request), isSanitizingEnabled());
    }

    /**
     * Logs the outgoing request at INFO level: method, URI, headers, and payload.
     *
     * @param logger   the target system logger
     * @param request  the wire request about to be sent
     * @param traceId  the active trace or correlation ID, or null
     * @param sanitize whether to sanitize credentials, PII, and sensitive query parameters
     */
    static void logRequest(System.Logger logger, WireRequest request, String traceId, boolean sanitize) {
        if (logger == null || !logger.isLoggable(System.Logger.Level.INFO) || request == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (traceId != null && !traceId.isBlank()) {
            sb.append('[').append(traceId.trim()).append("] ");
        }

        String uriStr = sanitize
                ? SensitiveDataSanitizer.sanitizeUri(request.uri())
                : (request.uri() != null ? request.uri().toString() : "");
        sb.append(request.method()).append(' ').append(uriStr).append('\n');

        if (request.headers() != null) {
            String headers = request.headers().stream()
                    .map(h -> sanitize ? SensitiveDataSanitizer.sanitizeHeader(h) : h)
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
            sb.append(headers);
        }

        if (BODY_LOGGING_METHODS.contains(request.method())) {
            sb.append('\n');
            appendRequestBody(sb, request, sanitize);
        }

        logger.log(System.Logger.Level.INFO, "Request: " + sb);
    }

    private static void appendRequestBody(StringBuilder sb, WireRequest request, boolean sanitize) {
        String contentType = request.contentType();
        if (contentType != null && contentType.contains("multipart/form-data")) {
            List<MultipartPart> parts = request.parts();
            if (parts != null) {
                String partsSummary = parts.stream()
                        .map(HttpDiagnosticLogger::formatPart)
                        .collect(Collectors.joining(", "));
                sb.append(partsSummary).append('\n');
            }
            sb.append("Sending size: ").append(multipartSendSize(request));
        } else if (request.body() != null) {
            if (sanitize) {
                sb.append(SensitiveDataSanitizer.sanitizeBody(request.body(), SensitiveDataSanitizer.DEFAULT_MAX_LOG_BODY_SIZE));
            } else {
                sb.append(new String(request.body(), StandardCharsets.UTF_8));
            }
        } else if (request.bodyPublisherSupplier() != null) {
            sb.append("<streaming body>");
        } else {
            sb.append("No request body");
        }
    }

    private static String formatPart(MultipartPart part) {
        return "name=" + part.name()
                + (part.filename() == null ? "" : ", filename=" + part.filename());
    }

    /**
     * Computes the total content size of a multipart wire request without
     * allocating publishers.
     *
     * @param request the wire request
     * @return string representation of content size or "unknown (chunked)"
     */
    static String multipartSendSize(WireRequest request) {
        if (request == null || request.parts() == null) {
            return "0";
        }
        long size = MultipartBodyPublisher.contentLength(request.boundary(), request.parts());
        return size >= 0 ? String.valueOf(size) : "unknown (chunked)";
    }

    /**
     * Logs the incoming response line at INFO level with automatic trace extraction and default sanitization.
     *
     * @param logger    the target system logger
     * @param response  the received wire response
     * @param elapsedMs total call duration in milliseconds
     */
    static void logResponse(System.Logger logger, WireResponse response, long elapsedMs) {
        logResponse(logger, response, elapsedMs, TraceIdExtractor.extract(response), isSanitizingEnabled());
    }

    /**
     * Logs the incoming response line at INFO level: protocol version, status code,
     * elapsed duration, and response headers.
     *
     * @param logger    the target system logger
     * @param response  the received wire response
     * @param elapsedMs total call duration in milliseconds
     * @param traceId   the active trace or correlation ID, or null
     * @param sanitize  whether to sanitize sensitive headers (e.g. Set-Cookie)
     */
    static void logResponse(System.Logger logger, WireResponse response, long elapsedMs, String traceId, boolean sanitize) {
        if (logger == null || !logger.isLoggable(System.Logger.Level.INFO) || response == null) {
            return;
        }
        String headers = response.headers().stream()
                .map(h -> sanitize ? SensitiveDataSanitizer.sanitizeHeader(h) : h)
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        String proto = response.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
        String prefix = (traceId != null && !traceId.isBlank()) ? "[" + traceId.trim() + "] " : "";
        logger.log(System.Logger.Level.INFO,
                "Response: " + prefix + proto + " " + response.statusCode() + " in " + elapsedMs + " ms\n" + headers);
    }

    /**
     * Logs an individual intermediate dispatch hop at DEBUG level with default sanitization.
     *
     * @param logger     the target system logger
     * @param request    the wire request sent in this hop
     * @param statusCode status code returned by this hop
     * @param startNanos hop start timestamp in nanoseconds
     */
    static void logPerHop(System.Logger logger, WireRequest request, int statusCode, long startNanos) {
        logPerHop(logger, request, statusCode, startNanos, TraceIdExtractor.extract(request), isSanitizingEnabled());
    }

    /**
     * Logs an individual intermediate dispatch hop at DEBUG level.
     *
     * @param logger     the target system logger
     * @param request    the wire request sent in this hop
     * @param statusCode status code returned by this hop
     * @param startNanos hop start timestamp in nanoseconds
     * @param traceId    the active trace or correlation ID, or null
     * @param sanitize   whether to sanitize query parameters
     */
    static void logPerHop(System.Logger logger, WireRequest request, int statusCode, long startNanos, String traceId, boolean sanitize) {
        if (logger == null || !logger.isLoggable(System.Logger.Level.DEBUG) || request == null) {
            return;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        String uriStr = sanitize
                ? SensitiveDataSanitizer.sanitizeUri(request.uri())
                : (request.uri() != null ? request.uri().toString() : "");
        String prefix = (traceId != null && !traceId.isBlank()) ? "[" + traceId.trim() + "] " : "";
        logger.log(System.Logger.Level.DEBUG,
                prefix + request.method() + " " + uriStr + " -> " + statusCode + " in " + elapsedMs + " ms");
    }

    /**
     * Logs scheduled retry attempt at DEBUG level.
     *
     * @param logger      the target system logger
     * @param attempt     current attempt number (1-based)
     * @param maxAttempts maximum configured attempts
     * @param failure     description of failure or exception
     * @param delayMillis scheduled backoff delay before next attempt
     */
    static void logRetry(System.Logger logger, int attempt, int maxAttempts, String failure, long delayMillis) {
        logRetry(logger, attempt, maxAttempts, failure, delayMillis, null);
    }

    /**
     * Logs scheduled retry attempt at DEBUG level with trace correlation.
     *
     * @param logger      the target system logger
     * @param attempt     current attempt number (1-based)
     * @param maxAttempts maximum configured attempts
     * @param failure     description of failure or exception
     * @param delayMillis scheduled backoff delay before next attempt
     * @param traceId     the active trace or correlation ID, or null
     */
    static void logRetry(System.Logger logger, int attempt, int maxAttempts, String failure, long delayMillis, String traceId) {
        if (logger != null && logger.isLoggable(System.Logger.Level.DEBUG)) {
            String prefix = (traceId != null && !traceId.isBlank()) ? "[" + traceId.trim() + "] " : "";
            logger.log(System.Logger.Level.DEBUG,
                    prefix + "Attempt " + attempt + "/" + maxAttempts + " failed: " + failure + " — retrying in " + delayMillis + " ms");
        }
    }
}
