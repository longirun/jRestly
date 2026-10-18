package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.RequestType;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.http.UploadProgressListener;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation of {@link ResponseHeaders} and listener cardinality parameters (ADR-0022):
 * {@code ResponseHeaders} must not carry request-binding annotations ({@code @RequestParam},
 * {@code @PathVariable}, {@code @RequestHeader}, {@code @Cookie}, {@code @RequestBody},
 * {@code @MultipartFormFile}), and each side-channel parameter type ({@code ResponseHeaders},
 * {@code UploadProgressListener}, {@code DownloadProgressListener}) may appear at most once per method.
 * Unit.
 */
@DisplayName("ResponseHeadersValidationTest")
class ResponseHeadersValidationTest {

    interface AnnotatedRequestParamController {
        @Get("/headers")
        String getHeaders(@RequestParam(name = "p") ResponseHeaders headers);
    }

    interface AnnotatedPathVariableController {
        @Get("/headers/{p}")
        String getHeaders(@PathVariable(name = "p") ResponseHeaders headers);
    }

    interface AnnotatedHeaderController {
        @Get("/headers")
        String getHeaders(@RequestHeader(name = "X-Headers") ResponseHeaders headers);
    }

    interface AnnotatedCookieController {
        @Get("/headers")
        String getHeaders(@Cookie(name = "c") ResponseHeaders headers);
    }

    interface AnnotatedRequestBodyController {
        @Post("/headers")
        String postHeaders(@RequestBody ResponseHeaders headers);
    }

    interface AnnotatedMultipartController {
        @Post(value = "/headers",  requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadHeaders(@MultipartFormFile(partName = "file") ResponseHeaders headers);
    }

    interface MultipleResponseHeadersController {
        @Get("/headers")
        String getHeaders(ResponseHeaders first, ResponseHeaders second);
    }

    interface MultipleUploadProgressController {
        @Post(value = "/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
        String upload(@MultipartFormFile(partName = "f") java.io.File file,
                      UploadProgressListener l1,
                      UploadProgressListener l2);
    }

    interface MultipleDownloadProgressController {
        @Get("/download")
        InputStream download(DownloadProgressListener l1, DownloadProgressListener l2);
    }

    interface ValidController {
        @Get("/items/{id}")
        String getItem(@PathVariable(name = "id") String id,
                       @RequestParam(name = "filter") String filter,
                       ResponseHeaders headers);
    }

    @Test
    @DisplayName("Validation fails if ResponseHeaders is annotated with @RequestParam")
    void failsOnAnnotatedRequestParam() {
        List<String> problems = ControllerValidator.validate(AnnotatedRequestParamController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("ResponseHeaders parameter must not be annotated with HTTP parameter annotations")));
    }

    @Test
    @DisplayName("Validation fails if ResponseHeaders is annotated with @PathVariable")
    void failsOnAnnotatedPathVariable() {
        List<String> problems = ControllerValidator.validate(AnnotatedPathVariableController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("ResponseHeaders parameter must not be annotated with HTTP parameter annotations")));
    }

    @Test
    @DisplayName("Validation fails if ResponseHeaders is annotated with @RequestHeader")
    void failsOnAnnotatedRequestHeader() {
        List<String> problems = ControllerValidator.validate(AnnotatedHeaderController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("ResponseHeaders parameter must not be annotated with HTTP parameter annotations")));
    }

    @Test
    @DisplayName("Validation fails if ResponseHeaders is annotated with @Cookie")
    void failsOnAnnotatedCookie() {
        List<String> problems = ControllerValidator.validate(AnnotatedCookieController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("ResponseHeaders parameter must not be annotated with HTTP parameter annotations")));
    }

    @Test
    @DisplayName("Validation fails if ResponseHeaders is annotated with @RequestBody")
    void failsOnAnnotatedRequestBody() {
        List<String> problems = ControllerValidator.validate(AnnotatedRequestBodyController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("ResponseHeaders parameter must not be annotated with HTTP parameter annotations")));
    }

    @Test
    @DisplayName("Validation fails if ResponseHeaders is annotated with @MultipartFormFile")
    void failsOnAnnotatedMultipartFormFile() {
        List<String> problems = ControllerValidator.validate(AnnotatedMultipartController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("ResponseHeaders parameter must not be annotated with HTTP parameter annotations")));
    }

    @Test
    @DisplayName("Validation fails if multiple ResponseHeaders parameters are declared on the same method")
    void failsOnMultipleResponseHeaders() {
        List<String> problems = ControllerValidator.validate(MultipleResponseHeadersController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("multiple parameters of type ResponseHeaders, at most one is allowed")));
    }

    @Test
    @DisplayName("Validation fails if multiple UploadProgressListener parameters are declared on the same method")
    void failsOnMultipleUploadProgressListeners() {
        List<String> problems = ControllerValidator.validate(MultipleUploadProgressController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("multiple parameters of type UploadProgressListener, at most one is allowed")));
    }

    @Test
    @DisplayName("Validation fails if multiple DownloadProgressListener parameters are declared on the same method")
    void failsOnMultipleDownloadProgressListeners() {
        List<String> problems = ControllerValidator.validate(MultipleDownloadProgressController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("multiple parameters of type DownloadProgressListener, at most one is allowed")));
    }

    @Test
    @DisplayName("Validation succeeds for valid ResponseHeaders declaration")
    void succeedsForValidResponseHeaders() {
        List<String> problems = ControllerValidator.validate(ValidController.class);
        assertTrue(problems.isEmpty(), () -> "Unexpected problems: " + problems);
    }

    @Test
    @DisplayName("Proxy creation throws ControllerValidationException when controller has invalid ResponseHeaders parameter")
    void clientThrowsOnInvalidResponseHeadersController() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .build();
        try {
            assertThrows(ControllerValidationException.class, () -> client.get(AnnotatedRequestParamController.class));
            assertThrows(ControllerValidationException.class, () -> client.get(MultipleResponseHeadersController.class));
            assertThrows(ControllerValidationException.class, () -> client.get(MultipleUploadProgressController.class));
            assertThrows(ControllerValidationException.class, () -> client.get(MultipleDownloadProgressController.class));
        } finally {
            client.close();
        }
    }
}
