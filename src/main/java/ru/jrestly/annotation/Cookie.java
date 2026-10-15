package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to an HTTP Cookie, or declares a static Cookie on a method or type (ADR-0032).
 *
 * <p>When placed on a controller interface, applies to all methods in that interface and sub-interfaces.
 * Static cookies are overridable by method-level cookies and runtime method arguments.
 * Passing {@code null} as an argument omits or removes that cookie.
 *
 * <p>Cookies are serialized into the standard RFC 6265 {@code Cookie: name=value; name2=value2} request header.
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Cookies.class)
public @interface Cookie {

    /**
     * Name of the cookie.
     * When placed on a method parameter, if empty, the name defaults to the Java parameter name.
     * When placed on a method or type, must not be blank.
     *
     * @return the cookie name, or empty string (the default)
     */
    String name() default "";

    /**
     * Static value of the cookie when placed on a method or type.
     * Ignored when placed on a method parameter.
     *
     * @return the static cookie value, or empty string (the default)
     */
    String value() default "";
}
