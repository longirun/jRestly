package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to an HTTP request header, or declares a static HTTP header on a method or type.
 * When placed on a controller interface, applies to all methods in that interface and sub-interfaces.
 * Static headers are overridable (case-insensitively) by method-level headers and runtime arguments.
 * Passing {@code null} as an argument omits the header.
 * <p>
 * Header values are text on the wire: a {@code byte[]} argument (scalar or as a list/array/map element)
 * throws {@link IllegalArgumentException} instead of writing class-string garbage —
 * encode bytes explicitly (e.g. Base64) or send them raw as a {@code @RequestBody}.
 */
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RequestHeaders.class)
public @interface RequestHeader {
    /**
     * Name of the HTTP request header to bind the parameter to, or static header name on method/type.
     * If empty on parameters, derived from the Java parameter name (for scalar/collection parameters)
     * or uses map keys (for {@link java.util.Map} parameters).
     * Required and must be non-blank on methods and types.
     *
     * @return the header name, or empty string (the default)
     */
    String name() default "";

    /**
     * Static value of the HTTP request header when placed on a method or type.
     * Ignored when placed on a method parameter.
     *
     * @return the static header value, or empty string (the default)
     */
    String value() default "";
}
