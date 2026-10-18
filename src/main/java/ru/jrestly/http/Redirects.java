package ru.jrestly.http;

import java.net.URI;
import java.util.List;

/**
 * Redirect policy for a single call (ADR-0002).
 *
 * <p>Decides the next hop of a redirect chain — 301/302/303 downgrade to GET
 * and drop the body, 307/308 re-send the method and body — and applies the
 * cross-origin credential hygiene: on a hop to a different origin the ambient
 * client auth headers are dropped to prevent credential leakage, while an
 * explicit {@code @RequestHeader} value the controller declares is the
 * caller's intentional choice and survives the redirect.
 *
 * <p>Extracted from {@code HttpTemplate} (ADR-0066) — a self-contained concern
 * fed by three immutable settings (the hop budget, the ambient auth header
 * names, and a logger); the unit tests exercise the origin comparison
 * directly.
 */
final class Redirects {

    private final Integer followRedirectsNumber;
    private final List<String> authHeaderNames;
    private final System.Logger logger;

    /**
     * Creates the policy for one call.
     *
     * @param followRedirectsNumber the hop budget, or {@code null} when
     *                              redirects are not followed
     * @param authHeaderNames       the ambient client auth header names to drop
     *                              on a cross-origin hop (may be null/empty)
     * @param logger                the call logger
     */
    Redirects(Integer followRedirectsNumber, List<String> authHeaderNames, System.Logger logger) {
        this.followRedirectsNumber = followRedirectsNumber;
        this.authHeaderNames = authHeaderNames;
        this.logger = logger;
    }

    // The next redirect hop, or null if the chain stops here
    // (no @FollowRedirects, a non-redirect status, or a missing Location)
    WireRequest nextHop(WireRequest request, WireResponse response) {
        if (followRedirectsNumber == null) {
            return null;
        }

        String location = location(response);
        if (location == null) {
            return null;
        }

        boolean preserveMethod = response.statusCode() == 307 || response.statusCode() == 308;
        HttpMethod nextMethod = preserveMethod ? request.method() : HttpMethod.GET;

        URI nextUri = request.uri().resolve(location);
        List<Header> nextHeaders = request.headers();
        if (isCrossOrigin(request.uri(), nextUri)) {
            // Drop the ambient client auth headers (the AuthProvider names the
            // controller does not explicitly declare) to prevent credential
            // leakage; an explicit @RequestHeader value is the caller's
            // intentional choice and survives the redirect (ADR-0002).
            nextHeaders = dropAuthHeaders(nextHeaders);
            logger.log(System.Logger.Level.INFO,
                    "Cross-origin redirect to " + nextUri + ", auth headers dropped");
        }

        logger.log(System.Logger.Level.INFO,
                "Following redirect (" + response.statusCode() + ") to: " + location);

        return new WireRequest(
                nextMethod,
                nextUri,
                nextHeaders,
                preserveMethod ? request.contentType() : null,
                preserveMethod ? request.body() : null,
                preserveMethod ? request.parts() : null,
                preserveMethod ? request.boundary() : null,
                preserveMethod ? request.bodyPublisherSupplier() : null);
    }

    // The hop budget ran out while the server still answered with a redirect
    void logExhausted(WireResponse response) {
        if (followRedirectsNumber != null && location(response) != null) {
            logger.log(System.Logger.Level.INFO, "Too many redirects, returning the last response");
        }
    }

    // The Location header of a redirect response, or null for any other status
    static String location(WireResponse response) {
        int statusCode = response.statusCode();
        if (statusCode != 301 && statusCode != 302 && statusCode != 303 && statusCode != 307 && statusCode != 308) {
            return null;
        }

        return response.headers().stream()
                .filter(h -> h.name() != null && h.name().equalsIgnoreCase("Location"))
                .map(Header::value)
                .findFirst()
                .orElse(null);
    }

    // Package-private seam: unit tests verify all cross-origin comparison branches
    static boolean isCrossOrigin(URI from, URI to) {
        if (from.getScheme() == null || to.getScheme() == null
                || !from.getScheme().equalsIgnoreCase(to.getScheme())) {
            return true;
        }
        if (from.getHost() == null || to.getHost() == null
                || !from.getHost().equalsIgnoreCase(to.getHost())) {
            return true;
        }
        return effectivePort(from) != effectivePort(to);
    }

    // Package-private seam: unit tests verify default-port resolution
    static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private List<Header> dropAuthHeaders(List<Header> headers) {
        if (authHeaderNames == null || authHeaderNames.isEmpty()) {
            return headers;
        }

        return headers.stream()
                .filter(header -> authHeaderNames.stream()
                        .noneMatch(name -> name.equalsIgnoreCase(header.name())))
                .toList();
    }
}
