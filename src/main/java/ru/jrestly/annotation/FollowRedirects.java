package ru.jrestly.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maximum number of HTTP redirects to follow for this method.
 *
 * <p>When present, jRestly chases the redirect chain itself (301/302/303
 * downgrade to GET and drop the body, 307/308 re-send the method and the
 * body) for up to the declared number of hops; when the budget runs out,
 * the chain stops and the last 3xx response is returned as is. Without
 * the annotation no redirect is followed at all. On a hop to a different
 * origin the ambient client auth headers are dropped (ADR-0002).
 *
 * <p>Validated eagerly at proxy creation (ADR-0004, Amendment #235):
 * {@link #count() count} must be non-negative — a negative budget would
 * never follow a hop yet log "Too many redirects" for every 3xx response.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FollowRedirects {
    /**
     * Maximum number of redirect hops to follow for the annotated method.
     * Must be non-negative; {@code 0} means no hop is followed (the 3xx
     * response is returned as the final one) (checked at proxy creation,
     * ADR-0004).
     *
     * @return maximum number of redirect hops
     */
    int count();
}
