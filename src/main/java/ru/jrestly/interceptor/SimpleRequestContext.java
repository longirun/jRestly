package ru.jrestly.interceptor;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import ru.jrestly.http.WireResponse;

/**
 * Standard implementation of {@link RequestContext}.
 * <p>
 * Stores invocation metadata and thread-safe attributes for communication
 * across request and response interceptors.
 */
public class SimpleRequestContext implements RequestContext {

    private final String moduleName;
    private final Class<?> controllerClass;
    private final Method method;
    private final Object[] args;
    private final ConcurrentMap<String, Object> attributes;
    private volatile WireResponse shortCircuitResponse;

    /**
     * Creates a new invocation request context.
     *
     * @param moduleName the module name, must not be {@code null}
     * @param controllerClass the controller interface class, must not be {@code null}
     * @param method the controller method, must not be {@code null}
     * @param args the call arguments array, or {@code null} for an empty arguments list
     * @throws NullPointerException if {@code moduleName}, {@code controllerClass}, or {@code method} is {@code null}
     */
    public SimpleRequestContext(String moduleName, Class<?> controllerClass, Method method, Object[] args) {
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName");
        this.controllerClass = Objects.requireNonNull(controllerClass, "controllerClass");
        this.method = Objects.requireNonNull(method, "method");
        this.args = args != null ? args.clone() : new Object[0];
        this.attributes = new ConcurrentHashMap<>();
    }

    @Override
    public String moduleName() {
        return moduleName;
    }

    @Override
    public Class<?> controllerClass() {
        return controllerClass;
    }

    @Override
    public Method method() {
        return method;
    }

    @Override
    public Object[] args() {
        return args.clone();
    }

    @Override
    public void setAttribute(String name, Object value) {
        Objects.requireNonNull(name, "name");
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public Object getAttribute(String name) {
        Objects.requireNonNull(name, "name");
        return attributes.get(name);
    }

    @Override
    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public void shortCircuit(WireResponse response) {
        this.shortCircuitResponse = Objects.requireNonNull(response, "response");
    }

    @Override
    public Optional<WireResponse> shortCircuitResponse() {
        return Optional.ofNullable(shortCircuitResponse);
    }
}
