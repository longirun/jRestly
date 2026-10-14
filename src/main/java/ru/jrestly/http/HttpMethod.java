package ru.jrestly.http;

/**
 * The HTTP methods supported by jRestly.
 *
 * <p>Controller annotations map one-to-one to these constants —
 * {@code @Get} to {@link #GET}, {@code @Post} to {@link #POST},
 * {@code @Put} to {@link #PUT}, {@code @Patch} to {@link #PATCH},
 * {@code @Delete} to {@link #DELETE} — when {@code HttpTemplateBuilder}
 * resolves the method's {@code RequestMeta}.
 *
 * <p>The request-log body section covers every method except
 * {@link #GET}; on redirects, 307/308 preserve the method and the rest
 * become {@link #GET}.
 */
public enum HttpMethod {
    /** Safe read; the request never carries a body. */
    GET,

    /** Non-idempotent (RFC 9110); the body is logged. */
    POST,

    /** Idempotent; the body is logged. */
    PUT,

    /** Non-idempotent (RFC 9110); the body is logged. */
    PATCH,

    /** Idempotent; the body is logged. */
    DELETE
}
