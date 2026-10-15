package ru.jrestly.interceptor;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

/**
 * Specialized {@link RequestInterceptor} capable of short-circuiting HTTP execution
 * by supplying a synthetic {@link WireResponse} (such as a mock response, cache hit,
 * or fallback) without performing a physical network call.
 * <p>
 * When a synthetic response is supplied, further request interceptors and network calls
 * are bypassed, and the response is immediately routed to the client response pipeline
 * (decoding, {@code @OnError}, response headers capture, and {@link ResponseInterceptor}).
 */
@FunctionalInterface
public interface ShortCircuitInterceptor extends RequestInterceptor {

    /**
     * Attempts to short-circuit the given request with a synthetic response.
     *
     * @param request the current wire request, never {@code null}
     * @param context the invocation context, never {@code null}
     * @return an {@link Optional} containing the synthetic response, or {@link Optional#empty()} to proceed
     */
    Optional<WireResponse> shortCircuit(WireRequest request, RequestContext context);

    @Override
    default WireRequest intercept(WireRequest request, RequestContext context) {
        Optional<WireResponse> synthetic = shortCircuit(request, context);
        if (synthetic != null && synthetic.isPresent()) {
            context.shortCircuit(synthetic.get());
        }
        return request;
    }

    /**
     * Creates an interceptor that unconditionally returns the specified synthetic response.
     *
     * @param response the synthetic wire response to return, must not be {@code null}
     * @return a short-circuit interceptor
     * @throws NullPointerException if {@code response} is {@code null}
     */
    static ShortCircuitInterceptor of(WireResponse response) {
        Objects.requireNonNull(response, "response");
        return (request, context) -> Optional.of(response);
    }

    /**
     * Creates an interceptor that returns a synthetic response if the request matches the predicate.
     *
     * @param predicate the predicate matching requests to mock, must not be {@code null}
     * @param responseSupplier the factory producing synthetic responses for matching requests, must not be {@code null}
     * @return a conditional short-circuit interceptor
     * @throws NullPointerException if {@code predicate} or {@code responseSupplier} is {@code null}
     */
    static ShortCircuitInterceptor matching(Predicate<WireRequest> predicate, Function<WireRequest, WireResponse> responseSupplier) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(responseSupplier, "responseSupplier");
        return (request, context) -> predicate.test(request)
                ? Optional.ofNullable(responseSupplier.apply(request))
                : Optional.empty();
    }
}
