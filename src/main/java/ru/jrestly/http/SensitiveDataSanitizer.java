package ru.jrestly.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enterprise PII, credential, and sensitive data sanitizer (ADR-0095).
 *
 * <p>Protects against credential leakage into diagnostic loggers (CWE-532) in strict compliance
 * with GDPR (Articles 25 &amp; 32), Russian Federal Law 152-FZ (Article 19), and PCI-DSS v4.0.
 */
final class SensitiveDataSanitizer {

    static final int DEFAULT_MAX_LOG_BODY_SIZE = 4096;

    private static final String REDACTED = "[REDACTED]";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final Set<String> STANDARD_SENSITIVE_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "api-key",
            "apikey",
            "secret-token",
            "x-auth-token"
    );

    private static final Set<String> SENSITIVE_HEADER_SUBSTRINGS = Set.of(
            "token",
            "secret",
            "password",
            "credential",
            "signature"
    );

    private static final Set<String> STANDARD_SENSITIVE_PARAMS = Set.of(
            "token",
            "access_token",
            "refresh_token",
            "secret",
            "password",
            "key",
            "apikey",
            "api_key",
            "phone",
            "email",
            "passport",
            "code",
            "client_secret"
    );

    private static final Pattern JSON_CREDENTIAL_PATTERN = Pattern.compile(
            "(\"(?:password|secret|cvv|token|apiKey|cardNumber)\"\\s*:\\s*\")[^\"]*(\")",
            Pattern.CASE_INSENSITIVE
    );

    private SensitiveDataSanitizer() {
        // Utility class
    }

    /**
     * Determines whether the given HTTP header name carries sensitive credentials or PII.
     *
     * @param headerName the header name to inspect, case-insensitive
     * @return {@code true} if the header is sensitive and must be masked
     */
    static boolean isSensitiveHeader(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        String lower = headerName.trim().toLowerCase(Locale.ROOT);
        if (STANDARD_SENSITIVE_HEADERS.contains(lower)) {
            return true;
        }
        for (String sub : SENSITIVE_HEADER_SUBSTRINGS) {
            if (lower.contains(sub)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitizes a single {@link Header}, masking its value if the header name is sensitive.
     *
     * @param header the header to sanitize
     * @return a sanitized header, or the original instance if not sensitive
     */
    static Header sanitizeHeader(Header header) {
        if (header == null || header.name() == null || !isSensitiveHeader(header.name())) {
            return header;
        }
        String val = header.value();
        if (val != null && header.name().equalsIgnoreCase("authorization")
                && val.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return new Header(header.name(), BEARER_PREFIX + REDACTED);
        }
        return new Header(header.name(), REDACTED);
    }

    /**
     * Determines whether the given URI query parameter name carries sensitive information.
     *
     * @param paramName the parameter name to inspect
     * @return {@code true} if sensitive
     */
    static boolean isSensitiveParam(String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return false;
        }
        String lower = paramName.trim().toLowerCase(Locale.ROOT);
        if (STANDARD_SENSITIVE_PARAMS.contains(lower)) {
            return true;
        }
        return lower.contains("token") || lower.contains("secret") || lower.contains("password");
    }

    /**
     * Sanitizes a URI, masking values of sensitive query parameters in compliance with GDPR and 152-FZ.
     *
     * @param uri the target URI
     * @return a sanitized string representation of the URI
     */
    static String sanitizeUri(URI uri) {
        if (uri == null) {
            return "";
        }
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return uri.toString();
        }

        String[] pairs = rawQuery.split("&");
        boolean modified = false;
        StringBuilder newQuery = new StringBuilder();

        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                newQuery.append('&');
            }
            String pair = pairs[i];
            int eqIdx = pair.indexOf('=');
            String key = eqIdx >= 0 ? pair.substring(0, eqIdx) : pair;
            if (isSensitiveParam(key)) {
                modified = true;
                newQuery.append(key).append('=').append(REDACTED);
            } else {
                newQuery.append(pair);
            }
        }

        if (!modified) {
            return uri.toString();
        }

        String uriStr = uri.toString();
        int qIdx = uriStr.indexOf('?');
        int fIdx = uriStr.indexOf('#');
        String base = uriStr.substring(0, qIdx);
        String fragment = fIdx >= 0 ? uriStr.substring(fIdx) : "";

        return base + "?" + newQuery + fragment;
    }

    /**
     * Sanitizes request body bytes for safe diagnostic logging, truncating if over {@code maxLogBytes}
     * and masking known sensitive JSON credential fields.
     *
     * @param body        the raw payload bytes
     * @param maxLogBytes maximum payload bytes to output before truncation
     * @return sanitized string representation
     */
    static String sanitizeBody(byte[] body, int maxLogBytes) {
        if (body == null) {
            return "No request body";
        }
        if (body.length == 0) {
            return "";
        }
        int effectiveLimit = Math.max(0, maxLogBytes);
        boolean truncated = body.length > effectiveLimit;
        byte[] toLog = truncated ? Arrays.copyOf(body, effectiveLimit) : body;

        String text = new String(toLog, StandardCharsets.UTF_8);
        String masked = JSON_CREDENTIAL_PATTERN.matcher(text).replaceAll("$1" + REDACTED + "$2");

        if (truncated) {
            return masked + "\n... [truncated, total " + body.length + " bytes]";
        }
        return masked;
    }
}
