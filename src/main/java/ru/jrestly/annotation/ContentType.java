package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the request body payload media type for an HTTP request (the {@code Content-Type} header).
 *
 * <p>When placed on a controller interface, applies to all methods in that interface and
 * its sub-interfaces. A method-level {@code @ContentType} overrides any interface-level declaration.
 *
 * <p>Overrides the default MIME type determined by the verb's {@code requestType} attribute
 * (e.g. overriding default {@code application/json} with {@code application/xml} or
 * {@code application/octet-stream}).
 *
 * <p>A runtime parameter annotated with {@code @RequestHeader(name = "Content-Type")} overrides
 * this static declaration.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ContentType {

    /**
     * The MIME type of the request body (e.g. {@code "application/json"}, {@code "application/xml"},
     * {@code "text/plain; charset=utf-8"}, or {@link ru.jrestly.http.MediaType#APPLICATION_OCTET_STREAM}).
     *
     * @return the content type string; must not be blank
     */
    String value();
}
