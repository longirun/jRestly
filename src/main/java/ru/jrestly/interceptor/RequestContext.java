package ru.jrestly.interceptor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Invocation context passed to request and response interceptors, carrying call metadata
 * and mutable attributes for cross-cutting state propagation.
 */
public interface RequestContext {

    /**
     * The name of the client module originating the call.
     *
     * @return the module name, never {@code null}
     */
    String moduleName();

    /**
     * The controller interface class being executed.
     *
     * @return the controller interface class, never {@code null}
     */
    Class<?> controllerClass();

    /**
     * The controller method being executed.
     *
     * @return the invoked method, never {@code null}
     */
    Method method();

    /**
     * The arguments passed to the controller method invocation.
     *
     * @return an array of runtime arguments, never {@code null}
     */
    Object[] args();

    /**
     * Stores a context attribute for sharing state between interceptors.
     *
     * @param name the attribute name, must not be {@code null}
     * @param value the attribute value, or {@code null} to remove the attribute
     * @throws NullPointerException if {@code name} is {@code null}
     */
    void setAttribute(String name, Object value);

    /**
     * Retrieves an attribute by name.
     *
     * @param name the attribute name, must not be {@code null}
     * @return the attribute value, or {@code null} if not found
     * @throws NullPointerException if {@code name} is {@code null}
     */
    Object getAttribute(String name);

    /**
     * Retrieves an attribute by name and casts it to the expected type if present and matching.
     *
     * @param <T> the expected value type
     * @param name the attribute name, must not be {@code null}
     * @param type the expected value class, must not be {@code null}
     * @return an {@link Optional} containing the cast value, or {@link Optional#empty()}
     * @throws NullPointerException if {@code name} or {@code type} is {@code null}
     */
    default <T> Optional<T> getAttribute(String name, Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object val = getAttribute(name);
        return type.isInstance(val) ? Optional.of(type.cast(val)) : Optional.empty();
    }

    /**
     * Returns an unmodifiable snapshot view of all stored attributes.
     *
     * @return an unmodifiable map of attributes, never {@code null}
     */
    Map<String, Object> attributes();
}
