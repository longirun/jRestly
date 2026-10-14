package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to an HTTP request header.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestHeader {
    /**
     * Name of the HTTP request header to bind the parameter to.
     * If empty, derived from the Java parameter name (for scalar/collection parameters)
     * or uses map keys (for {@link java.util.Map} parameters).
     *
     * @return the header name, or empty string (the default)
     */
    String name() default "";
}
