package ru.jrestly.annotation;

import ru.jrestly.compression.CompressionAlgorithm;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures HTTP request body compression for declarative client methods and interfaces (ADR-0041).
 * <p>
 * When placed on a controller interface or method, mutating requests (POST, PUT, PATCH) with a
 * non-empty request body will be compressed using the specified {@link CompressionAlgorithm}
 * and transmitted with the appropriate {@code Content-Encoding} header (e.g. {@code Content-Encoding: gzip}).
 * </p>
 * <p>
 * Method-level annotations override interface-level annotations. Setting {@link #enabled()} to
 * {@code false} allows disabling compression on specific endpoints within a compressed interface.
 * </p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Compress {

    /**
     * The compression algorithm to apply to the request body. Defaults to {@link CompressionAlgorithm#GZIP}.
     *
     * @return the compression algorithm
     */
    CompressionAlgorithm algorithm() default CompressionAlgorithm.GZIP;

    /**
     * Minimum request body size in bytes required to trigger compression.
     * Payloads smaller than this threshold will be sent as uncompressed plaintext.
     * Defaults to {@code 0} (compress all non-empty bodies).
     *
     * @return the minimum payload size in bytes (must be non-negative)
     */
    int minSizeBytes() default 0;

    /**
     * Whether request compression is enabled. Defaults to {@code true}.
     * Can be set to {@code false} at the method level to opt out when an interface-level
     * {@code @Compress} is present.
     *
     * @return {@code true} if compression is enabled, {@code false} otherwise
     */
    boolean enabled() default true;
}
