package ru.jrestly.http;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Redirect policy and execution pipeline according to HTTP specification (ADR-0002, ADR-0066, ADR-0092).
 *
 * <p>Handles redirect chains for both synchronous and asynchronous calls:
 * <ul>
 *     <li>301/302/303 downgrade to GET and drop the request body;</li>
 *     <li>307/308 preserve the original HTTP method and body;</li>
 *     <li>Cross-origin hops drop ambient client authorization headers to prevent credential leakage (ADR-0002);</li>
 *     <li>Intermediate response streams are safely closed between hops to prevent connection leaks;</li>
 *     <li>Hop budget exhaustion logs an informative diagnostic message and returns the last response.</li>
 * </ul>
 *
 * <p>Extracted from {@link HttpTemplate} as part of god-class decomposition (ADR-0081).
 */
final class RedirectExecutor {

    @FunctionalInterface
    interface SyncSender {
        WireResponse send(WireRequest request) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface AsyncSender {
        CompletableFuture<WireResponse> send(WireRequest request);
    }

    private final Integer followRedirectsNumber;
    private final List<String> authHeaderNames;
    private final System.Logger logger;

    /**
     * Creates the redirect executor for one call contour.
     *
     * @param followRedirectsNumber the hop budget, or {@code null} when redirects are disabled
     * @param authHeaderNames       the ambient client auth header names to drop on cross-origin hops
     * @param logger                the call logger
     */
    RedirectExecutor(Integer followRedirectsNumber, List<String> authHeaderNames, System.Logger logger) {
        this.followRedirectsNumber = followRedirectsNumber;
        this.authHeaderNames = authHeaderNames;
        this.logger = logger;
    }

    /**
     * Executes the request synchronously, following redirects up to the configured hop budget.
     */
    WireResponse execute(WireRequest request, SyncSender sender) throws IOException, InterruptedException {
        if (followRedirectsNumber == null) {
            return sender.send(request);
        }

        int hopsLeft = followRedirectsNumber;
        WireResponse response = sender.send(request);
        while (hopsLeft > 0) {
            WireRequest next = nextHop(request, response);
            if (next == null) {
                break;
            }
            request = next;
            hopsLeft--;
            closeIntermediateStream(response);
            response = sender.send(request);
        }

        logExhausted(response);
        return response;
    }

    /**
     * Executes the request asynchronously, following redirects up to the configured hop budget.
     */
    CompletableFuture<WireResponse> executeAsync(WireRequest request, AsyncSender sender) {
        if (followRedirectsNumber == null) {
            return sender.send(request);
        }
        return executeAsyncHop(request, followRedirectsNumber, sender);
    }

    private CompletableFuture<WireResponse> executeAsyncHop(WireRequest request, int hopsLeft, AsyncSender sender) {
        return sender.send(request).thenCompose(response -> {
            WireRequest next = nextHop(request, response);
            if (next == null || hopsLeft <= 0) {
                return CompletableFuture.completedFuture(response);
            }
            closeIntermediateStream(response);
            return executeAsyncHop(next, hopsLeft - 1, sender);
        }).thenApply(response -> {
            logExhausted(response);
            return response;
        });
    }

    /**
     * Closes the intermediate response stream between hops to release underlying sockets.
     */
    void closeIntermediateStream(WireResponse response) {
        if (response != null) {
            ResponseBody.closeQuietly(response.stream(), logger);
        }
    }

    /**
     * Determines the next redirect hop, or {@code null} if the chain stops.
     */
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
            nextHeaders = dropAuthHeaders(nextHeaders);
            if (logger != null) {
                logger.log(System.Logger.Level.INFO,
                        "Cross-origin redirect to " + nextUri + ", auth headers dropped");
            }
        }

        if (logger != null) {
            logger.log(System.Logger.Level.INFO,
                    "Following redirect (" + response.statusCode() + ") to: " + location);
        }

        return new WireRequest(
                nextMethod,
                nextUri,
                nextHeaders,
                preserveMethod ? request.contentType() : null,
                preserveMethod ? request.body() : null,
                preserveMethod ? request.parts() : null,
                preserveMethod ? request.boundary() : null,
                preserveMethod ? request.bodyPublisherSupplier() : null,
                preserveMethod ? request.knownBodySize() : null);
    }

    /**
     * Logs hop budget exhaustion when the last answer is still a redirect.
     */
    void logExhausted(WireResponse response) {
        if (followRedirectsNumber != null && location(response) != null && logger != null) {
            logger.log(System.Logger.Level.INFO, "Too many redirects, returning the last response");
        }
    }

    /**
     * Returns the {@code Location} header of a redirect response, or {@code null}.
     */
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

    /**
     * Compares origin (scheme, host, effective port) between two URIs.
     */
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

    /**
     * Resolves effective port for URI, applying default ports 443 for HTTPS and 80 for HTTP.
     */
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
