package ru.jrestly.auth;

import ru.jrestly.annotation.Authorization;
import ru.jrestly.annotation.Order;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Request interceptor attaching an RFC 6750 Bearer authentication header:
 * {@code Authorization: Bearer <token>}.
 * <p>
 * Backed by a {@link TokenManager} for automatic caching, single-flight refresh,
 * and cache invalidation. Requests to controller methods annotated with
 * {@link Authorization} bypass this interceptor.
 */
@Order(-1000)
public class BearerAuth implements RequestInterceptor {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenManager tokenManager;

    /**
     * Constructs a Bearer authentication interceptor with the given token manager.
     *
     * @param tokenManager the token manager, must not be {@code null}
     * @throws NullPointerException if {@code tokenManager} is {@code null}
     */
    public BearerAuth(TokenManager tokenManager) {
        this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
    }

    /**
     * Creates a Bearer authentication interceptor backed by the given token manager.
     *
     * @param tokenManager the token manager, must not be {@code null}
     * @return a new Bearer authentication interceptor
     * @throws NullPointerException if {@code tokenManager} is {@code null}
     */
    public static BearerAuth of(TokenManager tokenManager) {
        return new BearerAuth(tokenManager);
    }

    /**
     * Creates a Bearer authentication interceptor using a fixed static token.
     *
     * @param token the static token string, must not be {@code null}
     * @return a new Bearer authentication interceptor
     * @throws NullPointerException if {@code token} is {@code null}
     */
    public static BearerAuth of(String token) {
        Objects.requireNonNull(token, "token");
        return new BearerAuth(TokenManager.ofPerpetual(() -> token));
    }

    /**
     * Creates a Bearer authentication interceptor using a dynamic token string supplier.
     *
     * @param tokenSupplier the token supplier, must not be {@code null}
     * @return a new Bearer authentication interceptor
     * @throws NullPointerException if {@code tokenSupplier} is {@code null}
     */
    public static BearerAuth of(Supplier<String> tokenSupplier) {
        Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        return new BearerAuth(TokenManager.ofPerpetual(tokenSupplier));
    }

    /**
     * Returns the underlying {@link TokenManager}.
     *
     * @return the token manager, never {@code null}
     */
    public TokenManager tokenManager() {
        return tokenManager;
    }

    @Override
    public WireRequest intercept(WireRequest request, RequestContext context) {
        if (context != null && context.method() != null && context.method().isAnnotationPresent(Authorization.class)) {
            return request;
        }

        String token = tokenManager.getToken();
        return request.withoutHeader(AUTHORIZATION)
                .withHeader(AUTHORIZATION, BEARER_PREFIX + token);
    }
}
