package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the request body media type (the {@code Content-Type} header), overriding
 * the default MIME type of the verb's {@code requestType}. When placed on a controller
 * interface, applies to every method of the interface and sub-interfaces; a
 * method-level declaration overrides the interface one, and a static or runtime
 * {@code @RequestHeader(name = "Content-Type")} replaces both.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ContentType {

    /** The MIME type, e.g. {@code text/plain; charset=utf-8} — the {@code charset=}
     * parameter encodes {@code String} bodies. A declared {@code multipart/*} type
     * keeps its subtype and gains the boundary parameter. Must not be blank.
     */
    String value();
}
