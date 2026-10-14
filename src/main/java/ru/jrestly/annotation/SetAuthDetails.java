package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Captures a response header into the client's AuthProvider.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SetAuthDetails {
    /**
     * Name of the response header to capture into the client's AuthProvider.
     *
     * @return the name of the response header to capture
     */
    String headerName();
}
