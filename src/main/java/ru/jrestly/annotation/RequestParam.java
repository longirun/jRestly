package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to a query parameter or form field.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestParam {
    /**
     * Parameter name. If empty, derived from the Java parameter name.
     *
     * @return the parameter name,
     * or the empty string (the default) to use the Java parameter name
     */
    String name() default "";
}
