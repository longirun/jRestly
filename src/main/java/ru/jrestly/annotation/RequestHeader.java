package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to an HTTP request header, or declares a static header on a
 * controller interface or method. Static declarations apply to every method of the
 * interface (and sub-interfaces) and are replaced case-insensitively when a later
 * declaration names the same header: a sub-interface, a method, or a non-null
 * runtime argument wins.
 *
 * <p>Passing {@code null} as a parameter argument omits the header; a static
 * declaration with the same name stays in place.
 *
 * <p>Header values are text on the wire: a {@code byte[]} argument fails fast instead
 * of writing class-string garbage — encode the bytes explicitly (e.g. Base64) before
 * passing.
 */
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RequestHeaders.class)
public @interface RequestHeader {
    /** Header name. If empty on a parameter, derived from the Java parameter name
     * (Map parameters read names from their keys); required and non-blank on methods and types.
     */
    String name() default "";

    /** Static header value when placed on a method or type; ignored on parameters. */
    String value() default "";
}
