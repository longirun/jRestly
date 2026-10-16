package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declaratively configures distributed tracing or correlation ID propagation
 * for a controller method or an entire controller interface (ADR-0042).
 *
 * <p>When placed on an interface or method:
 * <ul>
 *   <li>By default, injects an {@code X-Request-ID} header containing a generated UUID v4 string.</li>
 *   <li>When {@link #w3c()} is {@code true}, injects a W3C {@code traceparent} header (e.g. {@code 00-${traceId}-${spanId}-01}).</li>
 *   <li>If the header was already provided by the caller (e.g. via {@link RequestHeader}), the user-provided
 *       value is preserved unless {@link #forceNew()} is {@code true}.</li>
 *   <li>Can be selectively disabled on individual methods of an annotated interface via
 *       {@code @Trace(enabled = false)}; on a disabled method the client-level
 *       {@code TracePropagator} (if configured) applies instead (ADR-0042 §6.4).</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Trace {

    /**
     * The header name to inject the correlation ID into.
     * Defaults to {@code "X-Request-ID"}. Must not be blank.
     * Ignored when {@link #w3c()} is {@code true}.
     *
     * @return header name
     */
    String header() default "X-Request-ID";

    /**
     * When {@code true}, generates and injects standard W3C {@code traceparent} headers
     * (RFC 8414 / W3C TraceContext Level 1).
     *
     * @return {@code true} for W3C traceparent, {@code false} for custom correlation ID
     */
    boolean w3c() default false;

    /**
     * Controls whether tracing is active. Can be set to {@code false} on a method
     * to opt out of an interface-level {@code @Trace}. On a disabled method the
     * client-level {@code TracePropagator} (if configured) applies instead.
     *
     * @return {@code true} if enabled, {@code false} to disable
     */
    boolean enabled() default true;

    /**
     * When {@code true}, forces the generation of a fresh correlation ID or traceparent
     * even if the request already contains the header.
     * Defaults to {@code false} to preserve explicitly passed correlation IDs.
     *
     * @return {@code true} to force overwrite, {@code false} to preserve existing headers
     */
    boolean forceNew() default false;
}
