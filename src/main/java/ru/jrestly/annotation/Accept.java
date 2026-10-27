package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the media types accepted in the response (the {@code Accept} header). When
 * placed on a controller interface, applies to every method of the interface and
 * sub-interfaces; a method-level declaration overrides the interface one, and a static
 * or runtime {@code @RequestHeader(name = "Accept")} replaces both.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Accept {

    /** Media types to accept; joined with {@code ", "} into a single header value.
     * Must not be empty and must not contain blank entries.
     */
    String[] value();
}
