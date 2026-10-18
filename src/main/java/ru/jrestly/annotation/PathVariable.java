package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to a URI path placeholder {@code {name}}.
 * Passing {@code null} throws an {@link IllegalArgumentException} because path placeholders cannot be omitted.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PathVariable {
    /**
     * Variable name matching the path placeholder. If empty, derived from the Java parameter name.
     *
     * @return the variable name matching a {@code {name}} path placeholder,
     * or the empty string (the default) to use the Java parameter name
     */
    String name() default "";

    /**
     * Whether the value is already encoded or should be inserted raw without percent-encoding.
     *
     * <p>When {@code false} (the default), values are percent-encoded via UTF-8, encoding reserved
     * characters such as {@code /} into {@code %2F} and spaces into {@code %20}.
     * When {@code true}, values are substituted directly as-is into the path placeholder, allowing
     * multi-segment paths (e.g. {@code files/2026/spec.pdf}) and pre-encoded strings.
     *
     * @return {@code true} if the value should be substituted without percent-encoding, {@code false} otherwise
     */
    boolean encoded() default false;
}
