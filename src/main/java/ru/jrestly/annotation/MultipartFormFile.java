package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method parameter as a binary file part of a multipart request.
 * <p>
 * The parameter value is a path to the file: {@code String}, {@link java.io.File}
 * or {@link java.nio.file.Path}. The path is used as-is, no directory prefix is applied.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface MultipartFormFile {
    String partName();
}
