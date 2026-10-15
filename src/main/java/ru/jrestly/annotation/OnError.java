package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps HTTP error status codes to a typed error payload or custom exception.
 *
 * <p>Can be declared on controller methods and controller interfaces. Multiple
 * {@code @OnError} annotations may be declared on the same element to map different
 * HTTP status codes to different target error types.</p>
 *
 * <p>When {@link #errorObject()} is assignable to {@link Throwable}, the decoded
 * exception is thrown directly (if {@link RuntimeException}) or wrapped. When it is
 * a standard data transfer object, it is wrapped in {@link ru.jrestly.http.HandledException}.</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(OnError.List.class)
public @interface OnError {
    /**
     * HTTP status codes that trigger error handling.
     *
     * @return the status codes that trigger error handling (empty by default, so no status triggers it)
     */
    int[] statuses() default {};

    /**
     * Target class for deserializing the error response body.
     *
     * @return the class the error response body is decoded into ({@code Void.class} by default)
     */
    Class<?> errorObject() default Void.class;

    /**
     * Container annotation for repeatable {@link OnError} declarations.
     */
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        /**
         * The contained {@link OnError} annotations.
         *
         * @return the {@link OnError} annotations
         */
        OnError[] value();
    }
}
