package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures the default collection and array formatting style for query parameters
 * and form fields at the controller interface or method level.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface QueryCollectionFormat {
    /**
     * The collection formatting style to apply.
     *
     * @return the collection format
     */
    CollectionFormat value();
}
