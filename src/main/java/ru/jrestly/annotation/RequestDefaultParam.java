package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Static default parameter attached to an HTTP method or controller interface.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RequestDefaultParams.class)
public @interface RequestDefaultParam {
    /**
     * The name of the parameter. Must be non-blank.
     *
     * @return the name of the parameter
     */
    String name();

    /**
     * The value the parameter takes on every call of the annotated method.
     *
     * @return the value the parameter takes on every call of the annotated method
     */
    String value();
}
