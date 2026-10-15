package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Declares a request (socket) timeout for an HTTP method or controller interface (ADR-0027).
 *
 * <p>When placed on a controller method, overrides the timeout for that specific method.
 * When placed on a controller interface, sets the default timeout for all methods in that
 * interface and sub-interfaces, unless overridden on a method.
 *
 * <p>Precedence order:
 * <ol>
 *   <li>Method-level {@code @Timeout}</li>
 *   <li>Sub-interface {@code @Timeout}</li>
 *   <li>Super-interface {@code @Timeout}</li>
 *   <li>Client-level {@link ru.jrestly.ModuleInfo#getSocketTimeout()}</li>
 * </ol>
 *
 * <p>Example:
 * <pre>{@code
 * @Timeout(value = 10, unit = TimeUnit.SECONDS)
 * public interface HeavyReportApi {
 *
 *     @Get("/reports/daily")
 *     Report getDailyReport();
 *
 *     @Timeout(500) // 500 ms health check
 *     @Get("/health")
 *     Health ping();
 * }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Timeout {

    /**
     * Duration value of the timeout. Must be strictly positive ({@code > 0}).
     *
     * @return timeout duration value
     */
    long value();

    /**
     * The time unit of {@link #value()}.
     * Defaults to {@link TimeUnit#MILLISECONDS}.
     *
     * @return the time unit
     */
    TimeUnit unit() default TimeUnit.MILLISECONDS;
}
