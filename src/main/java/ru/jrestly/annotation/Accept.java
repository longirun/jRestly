package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the accepted response media types for an HTTP request (the {@code Accept} header).
 *
 * <p>When placed on a controller interface, applies to all methods in that interface and
 * its sub-interfaces. A method-level {@code @Accept} overrides any interface-level declaration.
 *
 * <p>Multiple media types (e.g. {@code @Accept({"application/json", "text/plain"})}) are
 * concatenated with commas into a single {@code Accept} header as specified by RFC 9110 §12.5.1.
 *
 * <p>A runtime parameter annotated with {@code @RequestHeader(name = "Accept")} overrides
 * this static declaration.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Accept {

    /**
     * One or more media types to accept in the response (e.g. {@code "application/json"},
     * {@code "text/plain"}, or {@link ru.jrestly.http.MediaType#APPLICATION_JSON}).
     *
     * @return the array of accepted media types; must not be empty and must not contain blank entries
     */
    String[] value();
}
