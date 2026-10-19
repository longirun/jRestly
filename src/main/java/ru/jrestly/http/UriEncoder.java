package ru.jrestly.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * High-performance, zero-allocation RFC 3986 and RFC 9110 URI encoding utility (ADR-0072).
 *
 * <p>Provides fast-path ASCII detection for unreserved characters (letters, digits,
 * {@code -}, {@code _}, {@code .}, and {@code *}) which do not require percent-encoding
 * and are invariant under URL encoding. For unreserved strings, avoids all memory allocations
 * and delegate calls to {@link URLEncoder}.
 */
final class UriEncoder {

    private UriEncoder() {
    }

    /**
     * Checks if the given string consists solely of RFC 3986 / {@link URLEncoder}
     * unreserved characters (ASCII letters, digits, {@code -}, {@code _}, {@code .}, {@code *}).
     *
     * <p>Any string matching this predicate produces the exact same byte sequence when
     * passed to {@code URLEncoder.encode(s, UTF_8).replace("+", "%20")}, with zero
     * allocations.
     *
     * @param s the string to test; may be {@code null}
     * @return {@code true} if no percent-encoding is required
     */
    static boolean isUnreserved(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '*')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Encodes a URI path segment or query component.
     *
     * <p>Returns the original string directly when it consists purely of unreserved characters.
     * Otherwise delegates to {@link URLEncoder} with space converted to {@code %20}.
     *
     * @param value the string to encode; may be {@code null}
     * @return the percent-encoded string, or {@code null} if input was {@code null}
     */
    static String encode(String value) {
        if (value == null || value.isEmpty() || isUnreserved(value)) {
            return value;
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Appends the percent-encoded string directly to a {@link StringBuilder} without
     * creating intermediate string allocations for unreserved inputs.
     *
     * @param value the string to encode; ignored if {@code null} or empty
     * @param target the target {@link StringBuilder}
     */
    static void encodeTo(String value, StringBuilder target) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (isUnreserved(value)) {
            target.append(value);
        } else {
            target.append(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
        }
    }
}
