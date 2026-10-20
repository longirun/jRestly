package ru.jrestly.http;

import java.net.URI;
import java.util.List;

/**
 * Redirect policy for a single call (ADR-0002, ADR-0066).
 *
 * <p>Backward compatibility facade delegating to {@link RedirectExecutor} (ADR-0092).
 */
final class Redirects {

    private final RedirectExecutor delegate;

    Redirects(Integer followRedirectsNumber, List<String> authHeaderNames, System.Logger logger) {
        this.delegate = new RedirectExecutor(followRedirectsNumber, authHeaderNames, logger);
    }

    WireRequest nextHop(WireRequest request, WireResponse response) {
        return delegate.nextHop(request, response);
    }

    void logExhausted(WireResponse response) {
        delegate.logExhausted(response);
    }

    static String location(WireResponse response) {
        return RedirectExecutor.location(response);
    }

    static boolean isCrossOrigin(URI from, URI to) {
        return RedirectExecutor.isCrossOrigin(from, to);
    }

    static int effectivePort(URI uri) {
        return RedirectExecutor.effectivePort(uri);
    }
}
