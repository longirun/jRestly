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

    /**
     * Optional Content-Type for this parameter when used in multipart/form-data requests.
     * <p>
     * If specified, the part will include a {@code Content-Type} header with this value.
     * For JSON content types (e.g. {@code application/json}), complex DTO arguments
     * will be serialized to JSON via the client's {@link ru.jrestly.json.JsonCodec}.
     * When not specified, multipart text parts default to {@code text/plain; charset=UTF-8}.
     *
     * @return the part's Content-Type, or empty string (the default)
     */
    String contentType() default "";
}
