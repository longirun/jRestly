package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import ru.jrestly.metrics.ClientMetricsListener;

/**
 * Binds one or more {@link ClientMetricsListener} classes to a controller interface or method.
 * <p>
 * Declared metrics listener classes must implement {@link ClientMetricsListener} and provide
 * an accessible no-argument constructor.
 * <p>
 * When placed on an interface, the listener applies to all methods of that interface and its sub-interfaces.
 * When placed on a method, the listener applies specifically to that method.
 * <p>
 * Listeners declared via {@link WithMetrics} are combined with any client-level listener configured
 * on the client builder via {@link ClientMetricsListener#composite(ClientMetricsListener...)}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(WithMetrics.List.class)
public @interface WithMetrics {

    /**
     * One or more listener classes to bind.
     * Each class must implement {@link ClientMetricsListener} and have an accessible no-argument constructor.
     *
     * @return the array of listener classes
     */
    Class<?>[] value();

    /**
     * Container annotation for repeatable {@link WithMetrics}.
     */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface List {
        /**
         * The contained {@link WithMetrics} annotations.
         *
         * @return the array of annotations
         */
        WithMetrics[] value();
    }
}
