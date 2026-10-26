package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Static default parameter attached to an HTTP method annotation, a method, or a
 * controller interface. When placed on an interface, applies to every method in that
 * interface and sub-interfaces; overridden when a method parameter specifies the same
 * parameter name.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RequestDefaultParams.class)
public @interface RequestDefaultParam {
    /** Parameter name; must be non-blank. */
    String name();

    /** Value the parameter takes on every call of the annotated method. */
    String value();
}
