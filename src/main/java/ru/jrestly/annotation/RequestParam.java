package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to a query parameter, form field, or multipart part.
 * Supports scalar values, {@link java.util.Collection} (repeated parameters),
 * and {@link java.util.Map} (dynamic parameters, prefixed with {@code name[key]} when name is non-empty).
 * Null argument values, null collection elements, and null map entries are omitted.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestParam {
    /**
     * Parameter name. If empty, derived from the Java parameter name (for scalar/collection parameters)
     * or uses map keys directly (for {@link java.util.Map} parameters).
     *
     * @return the parameter name, or empty string (the default)
     */
    String name() default "";
}
