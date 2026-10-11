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
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface MultipartFormFile {
    String partName();

    /** Part Content-Type; if empty, derived from the file extension. */
    String contentType() default "";
}
