package ru.jrestly.annotation;

import ru.jrestly.http.RequestType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a method to an HTTP PUT request.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Put {

    /** Target path or absolute URL. May contain ${var} placeholders. */
    String path();

    /** Content type and payload format. Defaults to {@link RequestType#APPLICATION_JSON}. */
    RequestType requestType() default RequestType.APPLICATION_JSON;

    /** Default query parameters appended to the request. */
    RequestDefaultParam[] params() default {};
}
