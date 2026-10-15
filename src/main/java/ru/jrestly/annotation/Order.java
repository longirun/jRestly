package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the execution priority of a declarative interceptor class.
 * <p>
 * When multiple interceptors are registered at the same scope level (global, interface, or
 * method), those annotated with {@code @Order} are sorted in ascending order of their value:
 * a lower value means higher priority (runs earlier).
 * Interceptors without {@code @Order} receive the implicit value {@link #DEFAULT} ({@code 0}).
 * <p>
 * Ties (equal values) preserve the original declaration order within {@link WithInterceptor}.
 * <p>
 * Example — auth runs before logging:
 * <pre>{@code
 * @Order(-10)
 * public class AuthInterceptor implements RequestInterceptor { ... }
 *
 * @Order(10)
 * public class LoggingInterceptor implements RequestInterceptor { ... }
 *
 * @WithInterceptor({AuthInterceptor.class, LoggingInterceptor.class})
 * public interface UserApi { ... }
 * }</pre>
 *
 * @see WithInterceptor
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Order {

    /**
     * Implicit order value for interceptors that do not declare {@code @Order}.
     */
    int DEFAULT = 0;

    /**
     * Priority value. Lower value = higher priority (runs earlier).
     * May be negative. Interceptors with the same value preserve declaration order.
     *
     * @return the priority value
     */
    int value() default DEFAULT;
}
