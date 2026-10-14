package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps HTTP error status codes to a typed exception body.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
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
}
