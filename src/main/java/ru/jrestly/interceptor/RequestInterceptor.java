package ru.jrestly.interceptor;

import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.jrestly.http.WireRequest;

/**
 * Interceptor invoked before an HTTP request is placed on the wire.
 * <p>
 * Implementations may inspect the outgoing {@link WireRequest} and its invocation
 * {@link RequestContext}, and return a modified or decorated request (for example,
 * adding authentication headers, correlation IDs, or altering query parameters).
 */
@FunctionalInterface
public interface RequestInterceptor {

    /**
     * Intercepts and potentially transforms the outgoing HTTP request.
     *
     * @param request the wire request to be sent, never {@code null}
     * @param context the invocation context containing method metadata and request attributes
     * @return the effective wire request to send, must not be {@code null}
     */
    WireRequest intercept(WireRequest request, RequestContext context);

    /**
     * Adapts a simple {@link UnaryOperator} transformation into a {@link RequestInterceptor}.
     *
     * @param operator the request transformation operator, must not be {@code null}
     * @return a {@link RequestInterceptor} delegating to the operator
     * @throws NullPointerException if {@code operator} is {@code null}
     */
    static RequestInterceptor of(UnaryOperator<WireRequest> operator) {
        Objects.requireNonNull(operator, "operator");
        return (request, context) -> operator.apply(request);
    }
}
