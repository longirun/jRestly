package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a method to an HTTP GET request.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Get {

    /** Target path or absolute URL. May contain ${var} placeholders. */
    String path();

    /** Default query parameters appended to the request. */
    RequestDefaultParam[] params() default {};
}
