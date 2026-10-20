package ru.jrestly.http;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Diagnostic logger for the HTTP dispatch pipeline (ADR-0089).
 *
 * <p>Handles wire-level formatting and gated output of outgoing requests,
 * incoming responses, intermediate redirect hops, and retry scheduling attempts.
 */
final class HttpDiagnosticLogger {

    private static final Set<HttpMethod> BODY_LOGGING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.PUT);

    private HttpDiagnosticLogger() {
        // Utility class
    }

    /**
     * Logs the outgoing request at INFO level: method, URI, headers, and payload.
     *
     * @param logger  the target system logger
     * @param request the wire request about to be sent
     */
    static void logRequest(System.Logger logger, WireRequest request) {
        if (logger == null || !logger.isLoggable(System.Logger.Level.INFO) || request == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(request.method()).append(' ').append(request.uri()).append('\n');

        if (request.headers() != null) {
            String headers = request.headers().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
            sb.append(headers);
        }

        if (BODY_LOGGING_METHODS.contains(request.method())) {
            sb.append('\n');
            appendRequestBody(sb, request);
        }

        logger.log(System.Logger.Level.INFO, "Request: " + sb);
    }

    private static void appendRequestBody(StringBuilder sb, WireRequest request) {
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
            sb.append(new String(request.body(), StandardCharsets.UTF_8));
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
     * Logs the incoming response line at INFO level: protocol version, status code,
     * elapsed duration, and response headers.
     *
     * @param logger    the target system logger
     * @param response  the received wire response
     * @param elapsedMs total call duration in milliseconds
     */
    static void logResponse(System.Logger logger, WireResponse response, long elapsedMs) {
        if (logger == null || !logger.isLoggable(System.Logger.Level.INFO) || response == null) {
            return;
        }
        String headers = response.headers().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        String proto = response.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
        logger.log(System.Logger.Level.INFO,
                "Response: " + proto + " " + response.statusCode() + " in " + elapsedMs + " ms\n" + headers);
    }

    /**
     * Logs an individual intermediate dispatch hop at DEBUG level.
     *
     * @param logger     the target system logger
     * @param request    the wire request sent in this hop
     * @param statusCode status code returned by this hop
     * @param startNanos hop start timestamp in nanoseconds
     */
    static void logPerHop(System.Logger logger, WireRequest request, int statusCode, long startNanos) {
        if (logger == null || !logger.isLoggable(System.Logger.Level.DEBUG) || request == null) {
            return;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        logger.log(System.Logger.Level.DEBUG,
                request.method() + " " + request.uri() + " -> " + statusCode + " in " + elapsedMs + " ms");
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
        if (logger != null && logger.isLoggable(System.Logger.Level.DEBUG)) {
            logger.log(System.Logger.Level.DEBUG,
                    "Attempt " + attempt + "/" + maxAttempts + " failed: " + failure + " — retrying in " + delayMillis + " ms");
        }
    }
}
