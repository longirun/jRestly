package ru.jrestly.annotation;

import ru.jrestly.http.RequestType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a method to an HTTP PATCH request.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Patch {

    /**
     * Target path or absolute URL. May contain {var} placeholders.
     * Defaults to empty string when the full URL is supplied dynamically via {@link TargetUrl}.
     *
     * @return the target path, or an absolute URL
     */
    String value() default "";

    /**
     * Content type and payload format. Defaults to {@link RequestType#APPLICATION_JSON}.
     *
     * @return the request type (content type and payload format)
     */
    RequestType requestType() default RequestType.APPLICATION_JSON;

    /**
     * Default query parameters appended to the request.
     *
     * @return the default query parameters
     */
    RequestDefaultParam[] params() default {};
}
