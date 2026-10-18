package ru.jrestly.fixtures;

import ru.jrestly.annotation.*;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.RequestType;
import ru.jrestly.http.ServerSentEvent;

import java.io.File;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public interface TestController {

    @Get("/api/items/{id}")
    TestDto getItem(@PathVariable Long id);

    @Get("/api/items/{slug}")
    TestDto getItemBySlug(@PathVariable String slug);

    @Get("/api/items/{id}")
    CompletableFuture<TestDto> getItemAsync(@PathVariable Long id);

    @Get("/api/items")
    List<TestDto> getItems(@RequestParam Integer page);

    @Get("/api/items")
    CompletableFuture<List<TestDto>> getItemsAsync(@RequestParam Integer page);

    @Get("/api/search")
    List<TestDto> searchItems(@RequestParam(name = "q") String query, @RequestParam Integer limit);

    @Get(value = "/api/items",  params = {@RequestDefaultParam(name = "size", value = "10")})
    List<TestDto> getItemsWithDefaults(@RequestParam Integer page);

    @Get("/api/raw")
    String getRaw();

    @Get("/api/raw")
    CompletableFuture<String> getRawAsync();

    @Get("/api/stream")
    InputStream getStream();

    @Get("/api/stream")
    CompletableFuture<InputStream> getStreamAsync();

    @Get("/api/stream/lines")
    Stream<String> getLines();

    @Get("/api/stream/lines")
    CompletableFuture<Stream<String>> getLinesAsync();

    @Get("/api/stream/events")
    Stream<ServerSentEvent> getEvents();

    @Get("/api/stream/events")
    CompletableFuture<Stream<ServerSentEvent>> getEventsAsync();

    @Get("/api/nothing")
    void getNothing();

    @Get("/api/nothing")
    CompletableFuture<Void> getNothingAsync();

    @Get("/api/nothing")
    Void getNothingBoxed();

    @Post("/api/items")
    TestDto createItem(@RequestBody TestDto item);

    @Post("/api/items")
    CompletableFuture<TestDto> createItemAsync(@RequestBody TestDto item);

    @Post(value = "/api/form",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
    String submitForm(@RequestParam String field1, @RequestParam String field2);

    @Delete("/api/items/{id}")
    void deleteItem(@PathVariable Long id);

    @Delete("/api/items")
    void deleteItems(@RequestBody TestDto criteria);

    @Delete(value = "/api/items",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
    void deleteItemsForm(@RequestParam String reason);

    @Get("/api/headers")
    String getWithHeaders(@RequestHeader(name = "X-Custom") String customHeader);

    @Get("/api/error")
    @OnError(statuses = {400, 404}, errorObject = TestErrorDto.class)
    TestDto getWithError();

    @Get("/api/error")
    @OnError(statuses = {400, 404}, errorObject = TestErrorDto.class)
    CompletableFuture<TestDto> getWithErrorAsync();

    @Get("/api/rate-limited")
    @OnError(statuses = {429}, errorObject = TestErrorDto.class)
    TestDto getRateLimited();

    @Get("/api/auth-check")
    TestDto getAuthorized();

    @Anonymous
    @Get("/api/public")
    TestDto getAnonymous();

    @SetAuthDetails(headerName = "access-token")
    @Post("/api/login")
    String login(@RequestBody Object credentials);

    @FollowRedirects(count = 3)
    @Get("/api/redirect")
    String getWithRedirect();

    @FollowRedirects(count = 3)
    @Get("/api/redirect")
    CompletableFuture<String> getWithRedirectAsync();

    @FollowRedirects(count = 3)
    @Post("/api/redirect-post")
    String postWithRedirect(@RequestBody TestDto item);

    @FollowRedirects(count = 3)
    @Post("/api/redirect-post-preserve")
    TestDto postWithRedirectPreservingBody(@RequestBody TestDto item);

    @Authorization
    @SetAuthDetails(headerName = "access-token")
    @Post("/api/auth/login")
    String authLogin(@RequestBody Object credentials);

    @Get("/api/collection")
    List<TestDto> getWithCollectionParam(@RequestParam List<Long> ids);

    @Patch("/api/items/{id}")
    TestDto patchItem(@PathVariable Long id, @RequestBody TestDto item);

    @Patch(value = "/api/items/{id}",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
    String patchItemForm(@PathVariable Long id, @RequestParam String name);

    @Put("/api/items/{id}")
    TestDto putItem(@PathVariable Long id, @RequestBody TestDto item);

    @Post("/api/async")
    @ExpectStatus(statuses = {202})
    void createAsync(@RequestBody TestDto item);

    @Get("/api/server-error")
    TestDto getServerError();

    @Get("/api/server-error")
    CompletableFuture<TestDto> getServerErrorAsync();

    @Get("/api/server-error")
    CompletableFuture<TestDto> getServerErrorFaultAsync();

    @Delete("/api/items/{id}/silent")
    @ExpectStatus(statuses = {204})
    void deleteSilent(@PathVariable Long id);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFile(@MultipartFormFile(partName = "file") String filePath);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileObject(@MultipartFormFile(partName = "file") File file);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileWithText(@MultipartFormFile(partName = "file") String filePath,
                              @RequestParam String comment);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFilePath(@MultipartFormFile(partName = "file") Path file);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileWithTextParts(@MultipartFormFile(partName = "file") String filePath,
                                   @RequestParam List<String> tags);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileContent(@MultipartFormFile(partName = "file") FileContent file);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    @HttpVersion(HttpClient.Version.HTTP_1_1) // chunked transfer encoding is HTTP/1.1 semantics
    String uploadFileContentFromStream(@MultipartFormFile(partName = "file") FileContent file);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileContentFromPath(@MultipartFormFile(partName = "file") FileContent file);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    @FollowRedirects(count = 1)
    String uploadFileContentFollowRedirect(@MultipartFormFile(partName = "file") FileContent file);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFilePaths(@MultipartFormFile(partName = "file") List<Path> files);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileContents(@MultipartFormFile(partName = "file") List<FileContent> files);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadMixedFiles(@MultipartFormFile(partName = "file") List<Object> files);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFilePathVarargs(@MultipartFormFile(partName = "file") Path... files);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFilePathArray(@MultipartFormFile(partName = "file") Path[] files);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileContentTyped(@MultipartFormFile(partName = "file", contentType = "application/xml") FileContent file);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileContentsTyped(@MultipartFormFile(partName = "files", contentType = "text/xml") List<FileContent> files);

    @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
    String uploadFileContentQuoted(@MultipartFormFile(partName = "a\"b") FileContent file);
}
