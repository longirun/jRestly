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
     *
     * @return the header name
     */
    String name();
}
