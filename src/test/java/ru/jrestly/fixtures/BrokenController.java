package ru.jrestly.fixtures;

import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.http.RequestType;

public interface BrokenController {

    String noHttpAnnotation();

    @Get(path = "/duplicate")
    @Post(path = "/duplicate")
    String multipleHttpAnnotations();

    @Post(path = "/upload", requestType = RequestType.MULTIPART_FORM_DATA)
    String multipartWithoutParams();

    @Post(path = "/form", requestType = RequestType.APPLICATION_FORM_URLENCODED)
    String formWithoutParams();
}
