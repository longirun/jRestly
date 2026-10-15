package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.http.HttpClient;

/**
 * Overrides the HTTP protocol version for this method or controller interface.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpVersion {
    /**
     * HTTP protocol version to use for the annotated method or controller interface.
     *
     * @return the HTTP protocol version to use
     */
    HttpClient.Version value();
}
