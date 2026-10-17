package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method parameter as one or more file parts in a multipart/form-data request.
 * <p>
 * Accepts {@link java.io.File}, {@link java.nio.file.Path}, {@link String} (file path),
 * {@link ru.jrestly.http.FileContent}, or their collections and arrays.
 * <p>
 * Must not be combined with {@link RequestParam} on the same parameter: the multipart
 * builder turns a parameter into a single part (the file), so the request-param binding
 * would be silently dropped. Controller validation rejects the combination eagerly.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface MultipartFormFile {
    /**
     * The name of each part produced from the annotated argument.
     *
     * @return the name of each part produced from the annotated argument
     */
    String partName();

    /**
     * Part Content-Type; if empty, derived from the file extension.
     *
     * @return the part's Content-Type, or the empty string (the default) to derive it from the file extension
     */
    String contentType() default "";
}
