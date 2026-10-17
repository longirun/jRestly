package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the target endpoint URL dynamically at runtime for a single request (ADR-0062).
 *
 * <p>When present on a method parameter, the parameter value supplies the full URL
 * or base URL for the request, overriding the environment's configured base URL.
 * Designed for dynamic multi-tenant routing, webhooks, S3 presigned URLs, and
 * HATEOAS / Link pagination next-page traversal.
 *
 * <p>Supported parameter types:
 * <ul>
 *   <li>{@link java.net.URI}</li>
 *   <li>{@link java.net.URL}</li>
 *   <li>{@link String} (or {@link CharSequence})</li>
 * </ul>
 *
 * <p>At most one {@code @TargetUrl} parameter may be declared per method.
 * The parameter must not be annotated with other HTTP parameter annotations
 * ({@code @RequestParam}, {@code @PathVariable}, {@code @RequestHeader},
 * {@code @Cookie}, {@code @RequestBody}, or {@code @MultipartFormFile}).
 *
 * <p>At invocation time, the {@code @TargetUrl} parameter argument must not be {@code null}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TargetUrl {
}
