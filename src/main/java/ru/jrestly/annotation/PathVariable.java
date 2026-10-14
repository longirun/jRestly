package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to a URI path placeholder.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PathVariable {
    /**
     * Variable name matching the path placeholder. If empty, derived from the Java parameter name.
     *
     * @return the variable name matching a {@code ${name}} path placeholder,
     * or the empty string (the default) to use the Java parameter name
     */
    String name() default "";
}
