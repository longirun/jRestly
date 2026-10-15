package ru.jrestly.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds one or more interceptor classes to a controller interface or method.
 * <p>
 * Declared interceptors may implement {@link ru.jrestly.interceptor.RequestInterceptor},
 * {@link ru.jrestly.interceptor.ResponseInterceptor}, or both.
 * <p>
 * When placed on an interface (or parent interface), the interceptors apply to all
 * methods of that interface. When placed on a method, the interceptors apply only
 * to that specific method.
 * <p>
 * Interceptor execution order:
 * <ul>
 *   <li><b>RequestInterceptors:</b> Global client interceptors &rarr; Super-interface &rarr; Sub-interface &rarr; Method.</li>
 *   <li><b>ResponseInterceptors:</b> Global client interceptors &rarr; Super-interface &rarr; Sub-interface &rarr; Method.</li>
 * </ul>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(WithInterceptor.List.class)
public @interface WithInterceptor {

    /**
     * Interceptor class or classes to bind.
     * Each class must implement either {@link ru.jrestly.interceptor.RequestInterceptor}
     * or {@link ru.jrestly.interceptor.ResponseInterceptor} (or both), and provide an accessible
     * no-argument constructor.
     *
     * @return the interceptor class or array of classes
     */
    Class<?>[] value();

    /**
     * Container annotation for repeatable {@link WithInterceptor}.
     */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface List {
        /**
         * The contained {@link WithInterceptor} annotations.
         *
         * @return the array of annotations
         */
        WithInterceptor[] value();
    }
}
