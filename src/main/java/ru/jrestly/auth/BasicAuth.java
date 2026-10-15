package ru.jrestly.auth;

import ru.jrestly.annotation.Authorization;
import ru.jrestly.annotation.Order;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Request interceptor attaching an RFC 7617 Basic authentication header:
 * {@code Authorization: Basic <base64(username:password)>}.
 * <p>
 * Requests to controller methods annotated with {@link Authorization} bypass this interceptor.
 */
@Order(-1000)
public class BasicAuth implements RequestInterceptor {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BASIC_PREFIX = "Basic ";

    private final Supplier<String> credentialsSupplier;

    /**
     * Constructs a Basic authentication interceptor with fixed credentials.
     *
     * @param username the username, must not be {@code null}
     * @param password the password, must not be {@code null}
     * @throws NullPointerException if {@code username} or {@code password} is {@code null}
     */
    public BasicAuth(String username, String password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        String encoded = encode(username, password);
        this.credentialsSupplier = () -> encoded;
    }

    /**
     * Constructs a Basic authentication interceptor with dynamic credential suppliers.
     *
     * @param usernameSupplier the username supplier, must not be {@code null}
     * @param passwordSupplier the password supplier, must not be {@code null}
     * @throws NullPointerException if {@code usernameSupplier} or {@code passwordSupplier} is {@code null}
     */
    public BasicAuth(Supplier<String> usernameSupplier, Supplier<String> passwordSupplier) {
        Objects.requireNonNull(usernameSupplier, "usernameSupplier");
        Objects.requireNonNull(passwordSupplier, "passwordSupplier");
        this.credentialsSupplier = () -> encode(usernameSupplier.get(), passwordSupplier.get());
    }

    /**
     * Creates a Basic authentication interceptor with fixed credentials.
     *
     * @param username the username, must not be {@code null}
     * @param password the password, must not be {@code null}
     * @return a new Basic authentication interceptor
     * @throws NullPointerException if {@code username} or {@code password} is {@code null}
     */
    public static BasicAuth of(String username, String password) {
        return new BasicAuth(username, password);
    }

    /**
     * Creates a Basic authentication interceptor with dynamic credential suppliers.
     *
     * @param usernameSupplier the username supplier, must not be {@code null}
     * @param passwordSupplier the password supplier, must not be {@code null}
     * @return a new Basic authentication interceptor
     * @throws NullPointerException if {@code usernameSupplier} or {@code passwordSupplier} is {@code null}
     */
    public static BasicAuth of(Supplier<String> usernameSupplier, Supplier<String> passwordSupplier) {
        return new BasicAuth(usernameSupplier, passwordSupplier);
    }

    private static String encode(String username, String password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        String raw = username + ":" + password;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public WireRequest intercept(WireRequest request, RequestContext context) {
        if (context != null && context.method() != null && context.method().isAnnotationPresent(Authorization.class)) {
            return request;
        }

        String encoded = credentialsSupplier.get();
        return request.withoutHeader(AUTHORIZATION)
                .withHeader(AUTHORIZATION, BASIC_PREFIX + encoded);
    }
}
