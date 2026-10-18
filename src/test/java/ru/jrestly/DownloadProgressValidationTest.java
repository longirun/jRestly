package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.RequestType;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation of {@code @DownloadProgressListener} parameters (ADR-0033): the listener
 * must not carry a request-binding annotation ({@code @RequestParam},
 * {@code @PathVariable}, {@code @RequestHeader}, {@code @Cookie},
 * {@code @MultipartFormFile}) and cannot be a multipart method's only parameter.
 * Valid declarations pass. Unit.
 */
@DisplayName("DownloadProgressValidationTest")
class DownloadProgressValidationTest {

    interface AnnotatedRequestParamController {
        @Get("/download")
        InputStream download(@RequestParam(name = "p") DownloadProgressListener progress);
    }

    interface AnnotatedPathVariableController {
        @Get("/download/${p}")
        InputStream download(@PathVariable(name = "p") DownloadProgressListener progress);
    }

    interface AnnotatedHeaderController {
        @Get("/download")
        InputStream download(@RequestHeader(name = "X-Progress") DownloadProgressListener progress);
    }

    interface AnnotatedCookieController {
        @Get("/download")
        InputStream download(@Cookie(name = "c") DownloadProgressListener progress);
    }

    interface AnnotatedMultipartController {
        @Post(value = "/download",  requestType = RequestType.MULTIPART_FORM_DATA)
        InputStream download(@MultipartFormFile(partName = "file") DownloadProgressListener progress);
    }

    interface OnlyDownloadProgressListenerMultipartController {
        @Post(value = "/download",  requestType = RequestType.MULTIPART_FORM_DATA)
        InputStream download(DownloadProgressListener progress);
    }

    interface ValidController {
        @Get("/download/${id}")
        InputStream download(@PathVariable(name = "id") String id, DownloadProgressListener progress);
    }

    @Test
    @DisplayName("Validation fails if DownloadProgressListener is annotated with @RequestParam")
    void failsOnAnnotatedRequestParam() {
        List<String> problems = ControllerValidator.validate(AnnotatedRequestParamController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("DownloadProgressListener parameter must not be annotated")));
    }

    @Test
    @DisplayName("Validation fails if DownloadProgressListener is annotated with @PathVariable")
    void failsOnAnnotatedPathVariable() {
        List<String> problems = ControllerValidator.validate(AnnotatedPathVariableController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("DownloadProgressListener parameter must not be annotated")));
    }

    @Test
    @DisplayName("Validation fails if DownloadProgressListener is annotated with @RequestHeader")
    void failsOnAnnotatedRequestHeader() {
        List<String> problems = ControllerValidator.validate(AnnotatedHeaderController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("DownloadProgressListener parameter must not be annotated")));
    }

    @Test
    @DisplayName("Validation fails if DownloadProgressListener is annotated with @Cookie")
    void failsOnAnnotatedCookie() {
        List<String> problems = ControllerValidator.validate(AnnotatedCookieController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("DownloadProgressListener parameter must not be annotated")));
    }

    @Test
    @DisplayName("Validation fails if DownloadProgressListener is annotated with @MultipartFormFile")
    void failsOnAnnotatedMultipartFormFile() {
        List<String> problems = ControllerValidator.validate(AnnotatedMultipartController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("DownloadProgressListener parameter must not be annotated")));
    }

    @Test
    @DisplayName("Validation fails if multipart method has only DownloadProgressListener and no actual parts")
    void failsIfOnlyProgressListenerInMultipart() {
        List<String> problems = ControllerValidator.validate(OnlyDownloadProgressListenerMultipartController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("MULTIPART_FORM_DATA method must have at least one parameter")));
    }

    @Test
    @DisplayName("Validation succeeds for valid controller declaration")
    void succeedsForValidController() {
        List<String> problems = ControllerValidator.validate(ValidController.class);
        assertTrue(problems.isEmpty(), () -> "Unexpected problems: " + problems);
    }

    @Test
    @DisplayName("Proxy creation throws ControllerValidationException when controller has invalid DownloadProgressListener parameter")
    void clientThrowsOnInvalidController() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .build();
        try {
            assertThrows(ControllerValidationException.class, () -> client.get(AnnotatedRequestParamController.class));
        } finally {
            client.close();
        }
    }
}
