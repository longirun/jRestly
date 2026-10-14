package ru.jrestly.interceptor;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

/**
 * Standard implementation of {@link ResponseContext}, delegating request invocation
 * metadata and attributes to an underlying {@link RequestContext}.
 */
public class SimpleResponseContext implements ResponseContext {

    private final RequestContext requestContext;
    private final WireRequest request;
    private final WireResponse response;
    private final Throwable failure;
    private final Duration duration;

    /**
     * Creates a new response context.
     *
     * @param requestContext the underlying invocation request context, must not be {@code null}
     * @param request the sent wire request, must not be {@code null}
     * @param response the received wire response, or {@code null} if none
     * @param failure the execution failure, or {@code null} if call succeeded
     * @param duration the elapsed duration of the call, must not be {@code null}
     * @throws NullPointerException if {@code requestContext}, {@code request}, or {@code duration} is {@code null}
     */
    public SimpleResponseContext(
            RequestContext requestContext,
            WireRequest request,
            WireResponse response,
            Throwable failure,
            Duration duration
    ) {
        this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
        this.request = Objects.requireNonNull(request, "request");
        this.response = response;
        this.failure = failure;
        this.duration = Objects.requireNonNull(duration, "duration");
    }

    @Override
    public WireRequest request() {
        return request;
    }

    @Override
    public Optional<WireResponse> response() {
        return Optional.ofNullable(response);
    }

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public Duration duration() {
        return duration;
    }

    @Override
    public String moduleName() {
        return requestContext.moduleName();
    }

    @Override
    public Class<?> controllerClass() {
        return requestContext.controllerClass();
    }

    @Override
    public Method method() {
        return requestContext.method();
    }

    @Override
    public Object[] args() {
        return requestContext.args();
    }

    @Override
    public void setAttribute(String name, Object value) {
        requestContext.setAttribute(name, value);
    }

    @Override
    public Object getAttribute(String name) {
        return requestContext.getAttribute(name);
    }

    @Override
    public <T> Optional<T> getAttribute(String name, Class<T> type) {
        return requestContext.getAttribute(name, type);
    }

    @Override
    public Map<String, Object> attributes() {
        return requestContext.attributes();
    }
}
