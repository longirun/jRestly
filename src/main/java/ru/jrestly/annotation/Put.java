package ru.jrestly.annotation;

import ru.jrestly.http.RequestType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Put {

    String path();

    RequestType requestType() default RequestType.APPLICATION_JSON;

    RequestDefaultParam[] params() default {};
}
