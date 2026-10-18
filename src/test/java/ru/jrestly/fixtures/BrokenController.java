package ru.jrestly.fixtures;

import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.http.RequestType;

/**
 * Intentionally invalid declarations for the eager
 * {@code ControllerValidator} tests: a method without an HTTP verb, two
 * verbs on one method, and multipart/form methods without body
 * parameters (ADR-0024).
 */
public interface BrokenController {

    String noHttpAnnotation();

    @Get("/duplicate")
    @Post("/duplicate")
    String multipleHttpAnnotations();

    @Post(value = "/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String multipartWithoutParams();

    @Post(value = "/form",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
    String formWithoutParams();
}
