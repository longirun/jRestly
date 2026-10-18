package ru.jrestly.fixtures;

import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.http.RequestType;

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
