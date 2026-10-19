package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Whitelist of HTTP status codes considered successful (defaults to 2xx).
 *
 * <p>When present, the whitelist is strict: a response is successful only
 * if its status is one of the declared codes — the default 2xx allowance
 * (and the 304/404 allowances of conditional/optional calls) no longer
 * apply. Any other status fails the call with
 * {@code UnexpectedStatusException} carrying the raw body.
 *
 * <p>Validated eagerly at proxy creation (ADR-0004, Amendment #235): the
 * whitelist must be non-empty, and every code must fall in the valid HTTP
 * range {@code [100, 599]} — an empty whitelist would turn every response
 * (including plain 200 OK) into a failure, and an out-of-range code can
 * never match a real status.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExpectStatus {
    /**
     * Expected successful HTTP status codes for the annotated method.
     * Must be non-empty, and every code must fall in the valid HTTP range
     * {@code [100, 599]} (checked at proxy creation, ADR-0004).
     *
     * @return expected successful HTTP status codes
     */
    int[] statuses();
}
