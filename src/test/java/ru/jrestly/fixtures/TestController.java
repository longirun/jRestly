package ru.jrestly.fixtures;

import ru.jrestly.annotation.*;
import ru.jrestly.http.RequestType;

import java.io.File;
import java.util.List;

public interface TestController {

    @Get(path = "/api/items/${id}")
    TestDto getItem(@PathVariable(name = "id") Long id);

    @Get(path = "/api/items/${slug}")
    TestDto getItemBySlug(@PathVariable(name = "slug") String slug);

    @Get(path = "/api/items")
    List<TestDto> getItems(@RequestParam(name = "page") Integer page);

    @Get(path = "/api/search")
    List<TestDto> searchItems(@RequestParam(name = "q") String query, @RequestParam(name = "limit") Integer limit);

    @Get(path = "/api/items", params = {@RequestDefaultParam(name = "size", value = "10")})
    List<TestDto> getItemsWithDefaults(@RequestParam(name = "page") Integer page);

    @Get(path = "/api/raw")
    String getRaw();

    @Get(path = "/api/nothing")
    void getNothing();

    @Post(path = "/api/items")
    TestDto createItem(@RequestBody TestDto item);

    @Post(path = "/api/form", requestType = RequestType.APPLICATION_FORM_URLENCODED)
    String submitForm(@RequestParam(name = "field1") String field1, @RequestParam(name = "field2") String field2);

    @Delete(path = "/api/items/${id}")
    void deleteItem(@PathVariable(name = "id") Long id);

    @Delete(path = "/api/items")
    void deleteItems(@RequestBody TestDto criteria);

    @Delete(path = "/api/items", requestType = RequestType.APPLICATION_FORM_URLENCODED)
    void deleteItemsForm(@RequestParam(name = "reason") String reason);

    @Get(path = "/api/headers")
    String getWithHeaders(@RequestHeader(name = "X-Custom") String customHeader);

    @Get(path = "/api/error")
    @OnError(statuses = {400, 404}, errorObject = TestErrorDto.class)
    TestDto getWithError();

    @Get(path = "/api/auth-check")
    TestDto getAuthorized();

    @Anonymous
    @Get(path = "/api/public")
    TestDto getAnonymous();

    @SetAuthDetails(headerName = "access-token")
    @Post(path = "/api/login")
    String login(@RequestBody Object credentials);

    @FollowRedirects(count = 3)
    @Get(path = "/api/redirect")
    String getWithRedirect();

    @Authorization
    @SetAuthDetails(headerName = "access-token")
    @Post(path = "/api/auth/login")
    String authLogin(@RequestBody Object credentials);

    @Get(path = "/api/collection")
    List<TestDto> getWithCollectionParam(@RequestParam(name = "ids") List<Long> ids);

    @Patch(path = "/api/items/${id}")
    TestDto patchItem(@PathVariable(name = "id") Long id, @RequestBody TestDto item);

    @Patch(path = "/api/items/${id}", requestType = RequestType.APPLICATION_FORM_URLENCODED)
    String patchItemForm(@PathVariable(name = "id") Long id, @RequestParam(name = "name") String name);

    @Put(path = "/api/items/${id}")
    TestDto putItem(@PathVariable(name = "id") Long id, @RequestBody TestDto item);

    @Post(path = "/api/async")
    @ExpectStatus(statuses = {202})
    void createAsync(@RequestBody TestDto item);

    @Get(path = "/api/server-error")
    TestDto getServerError();

    @Delete(path = "/api/items/${id}/silent")
    @ExpectStatus(statuses = {204})
    void deleteSilent(@PathVariable(name = "id") Long id);

    @Post(path = "/api/upload", requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFile(@MultipartFormFile(partName = "file") String filePath);

    @Post(path = "/api/upload", requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileObject(@MultipartFormFile(partName = "file") File file);
}
