package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Static default parameter attached to an HTTP method annotation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestDefaultParam {
    /**
     * The name of the parameter.
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
