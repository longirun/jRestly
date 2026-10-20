package ru.jrestly.http;

import ru.jrestly.ModuleInfo;

import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the request header list for one invocation: ambient auth headers,
 * static {@code @RequestHeader} declarations, dynamic header arguments,
 * cookies, and the decompression {@code Accept-Encoding} default.
 *
 * <p>Stateless and package-private (ADR-0083); the caller applies tracing after
 * the merged list is built, so trace headers stay the last emitted ones. The
 * merge follows the ADR-0075 invariants: a static declaration wins over an
 * ambient header with the same name, a dynamic argument wins over both, and
 * the surviving headers keep their original order.
 */
final class HeaderComposer {

    private HeaderComposer() {
    }

    /**
     * Builds the merged header list for this invocation.
     *
     * @param moduleInfo     module configuration supplying ambient auth headers
     *                       and the decompression switch
     * @param methodMetadata parsed controller method metadata
     * @param args           invocation arguments, or {@code null}
     * @return a mutable list ready for tracing and body-related headers
     */
    static List<Header> compose(ModuleInfo moduleInfo, MethodMetadata methodMetadata, Object[] args) {
        List<Header> authHeaders = moduleInfo.getAuthProvider().getAuthHeaders();
        List<Header> staticHeaders = methodMetadata.staticHeaders();
        List<Header> result;
        if (authHeaders == null || authHeaders.isEmpty()) {
            result = new ArrayList<>(staticHeaders);
        } else if (staticHeaders.isEmpty()) {
            result = new ArrayList<>(authHeaders);
        } else {
            // Candidate #14: merge without an intermediate HashSet and per-name
            // lowercased strings — both header lists are small, so a direct
            // case-insensitive scan is cheaper than building and hashing a name
            // set. Parity: ambient headers not overridden by a static one keep
            // their order, then all static headers (a static declaration wins
            // over the ambient header with the same name).
            result = new ArrayList<>(authHeaders.size() + staticHeaders.size());
            for (int i = 0, authSize = authHeaders.size(); i < authSize; i++) {
                Header ambient = authHeaders.get(i);
                boolean overridden = false;
                for (Header s : staticHeaders) {
                    if (s.name().equalsIgnoreCase(ambient.name())) {
                        overridden = true;
                        break;
                    }
                }
                if (!overridden) {
                    result.add(ambient);
                }
            }
            result.addAll(staticHeaders);
        }

        if (methodMetadata.hasRequestHeaders() && args != null) {
            for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
                if (p.isRequestHeader() && p.index() < args.length) {
                    Object arg = args[p.index()];
                    if (arg == null) {
                        continue;
                    }

                    if (arg instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (entry.getKey() == null || entry.getValue() == null) {
                                continue;
                            }
                            String headerName = String.valueOf(entry.getKey());
                            removeHeaderByName(result, headerName);
                            appendHeaderValues(result, headerName, entry.getValue());
                        }
                        continue;
                    }

                    String name = p.requestHeaderName();
                    removeHeaderByName(result, name);
                    appendHeaderValues(result, name, arg);
                }
            }
        }

        String cookieHeader = resolveCookies(methodMetadata, args);
        if (cookieHeader != null) {
            boolean hasCookieHeader = false;
            for (int i = 0; i < result.size(); i++) {
                Header h = result.get(i);
                if ("Cookie".equalsIgnoreCase(h.name())) {
                    hasCookieHeader = true;
                    // An empty/blank existing value (an empty dynamic Cookie argument or a
                    // null-valued ambient header) has no cookie-pair to join: appending would
                    // emit a leading "; " and the header would be malformed per RFC 6265.
                    String existing = h.value();
                    String merged = (existing == null || existing.isBlank())
                            ? cookieHeader
                            : existing + "; " + cookieHeader;
                    result.set(i, new Header("Cookie", merged));
                    break;
                }
            }
            if (!hasCookieHeader) {
                result.add(new Header("Cookie", cookieHeader));
            }
        }

        if (moduleInfo.isDecompressionEnabled()) {
            boolean hasAcceptEncoding = hasHeaderNamed(result, "Accept-Encoding");
            if (!hasAcceptEncoding) {
                result.add(new Header("Accept-Encoding", "gzip, deflate"));
            }
        }

        return result;
    }

    // Candidate #14: in-place removal without a per-invocation Predicate instance —
    // removeIf(h -> h.name().equalsIgnoreCase(name)) allocated a lambda per dynamic
    // header on every request. Null names are skipped (ambient providers are a
    // public seam) instead of failing inside the predicate.
    static void removeHeaderByName(List<Header> result, String name) {
        for (int i = result.size() - 1; i >= 0; i--) {
            Header h = result.get(i);
            if (h.name() != null && h.name().equalsIgnoreCase(name)) {
                result.remove(i);
            }
        }
    }

    // Candidate #14: plain scan instead of stream().anyMatch(...) — a stream
    // pipeline allocates a Stream, a pipeline stage, and a predicate per check,
    // and the checks run on the per-request hot path.
    static boolean hasHeaderNamed(List<Header> headers, String name) {
        for (int i = 0, size = headers.size(); i < size; i++) {
            Header h = headers.get(i);
            if (h.name() != null && h.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveCookies(MethodMetadata methodMetadata, Object[] args) {
        if (!methodMetadata.hasCookieParams()) {
            return methodMetadata.staticCookieHeader();
        }

        Map<String, String> cookies = methodMetadata.staticCookies().isEmpty()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(methodMetadata.staticCookies());

        if (args != null) {
            for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
                if (p.isCookie() && p.index() < args.length) {
                    Object arg = args[p.index()];
                    if (arg == null) {
                        cookies.remove(p.cookieName());
                        continue;
                    }

                    if (arg instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (entry.getKey() != null) {
                                String key = String.valueOf(entry.getKey());
                                if (entry.getValue() != null) {
                                    cookies.put(key, String.valueOf(entry.getValue()));
                                } else {
                                    cookies.remove(key);
                                }
                            }
                        }
                        continue;
                    }

                    if (arg instanceof HttpCookie httpCookie) {
                        cookies.put(httpCookie.getName(), httpCookie.getValue());
                        continue;
                    }

                    cookies.put(p.cookieName(), String.valueOf(arg));
                }
            }
        }

        if (cookies.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private static void appendHeaderValues(List<Header> result, String name, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object o : collection) {
                if (o != null) {
                    result.add(new Header(name, textValue(o, "header")));
                }
            }
        } else if (value.getClass().isArray() && !byte[].class.equals(value.getClass())) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object o = java.lang.reflect.Array.get(value, i);
                if (o != null) {
                    result.add(new Header(name, textValue(o, "header")));
                }
            }
        } else {
            result.add(new Header(name, textValue(value, "header")));
        }
    }

    // Header and parameter positions are text on the wire; a byte[] has no defined
    // text rendering, so it fails fast instead of silently sending "[B@...'" garbage.
    static String textValue(Object value, String position) {
        return switch (value) {
            case null -> "null";
            case byte[] ignored -> throw new IllegalArgumentException(
                    "A byte[] cannot be a " + position + " value: header and parameter values are text on the wire. "
                            + "Send raw bytes as @RequestBody, or encode the bytes to text explicitly "
                            + "(e.g. Base64 or new String(bytes, StandardCharsets.ISO_8859_1)) before passing.");
            default -> value.toString();
        };
    }
}
