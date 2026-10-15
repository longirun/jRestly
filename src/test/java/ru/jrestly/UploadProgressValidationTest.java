package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.RequestType;
import ru.jrestly.http.UploadProgressListener;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UploadProgressValidationTest")
class UploadProgressValidationTest {

    interface AnnotatedRequestParamController {
        @Post(path = "/upload")
        String upload(@RequestParam(name = "p") UploadProgressListener progress);
    }

    interface AnnotatedPathVariableController {
        @Post(path = "/upload/${p}")
        String upload(@PathVariable(name = "p") UploadProgressListener progress);
    }

    interface AnnotatedHeaderController {
        @Post(path = "/upload")
        String upload(@RequestHeader(name = "X-Progress") UploadProgressListener progress);
    }

    interface AnnotatedMultipartController {
        @Post(path = "/upload", requestType = RequestType.MULTIPART_FORM_DATA)
        String upload(@MultipartFormFile(partName = "file") UploadProgressListener progress);
    }

    interface OnlyProgressListenerMultipartController {
        @Post(path = "/upload", requestType = RequestType.MULTIPART_FORM_DATA)
        String upload(UploadProgressListener progress);
    }

    interface ValidController {
        @Post(path = "/upload", requestType = RequestType.MULTIPART_FORM_DATA)
        String upload(@MultipartFormFile(partName = "file") FileContent file, UploadProgressListener progress);
    }

    @Test
    @DisplayName("Validation fails if UploadProgressListener is annotated with @RequestParam")
    void failsOnAnnotatedRequestParam() {
        List<String> problems = ControllerValidator.validate(AnnotatedRequestParamController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("UploadProgressListener parameter must not be annotated")));
    }

    @Test
    @DisplayName("Validation fails if UploadProgressListener is annotated with @PathVariable")
    void failsOnAnnotatedPathVariable() {
        List<String> problems = ControllerValidator.validate(AnnotatedPathVariableController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("UploadProgressListener parameter must not be annotated")));
    }

    @Test
    @DisplayName("Validation fails if UploadProgressListener is annotated with @RequestHeader")
    void failsOnAnnotatedRequestHeader() {
        List<String> problems = ControllerValidator.validate(AnnotatedHeaderController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("UploadProgressListener parameter must not be annotated")));
    }

    @Test
    @DisplayName("Validation fails if UploadProgressListener is annotated with @MultipartFormFile")
    void failsOnAnnotatedMultipartFormFile() {
        List<String> problems = ControllerValidator.validate(AnnotatedMultipartController.class);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("UploadProgressListener parameter must not be annotated")));
    }

    @Test
    @DisplayName("Validation fails if multipart method has only UploadProgressListener and no actual parts")
    void failsIfOnlyProgressListenerInMultipart() {
        List<String> problems = ControllerValidator.validate(OnlyProgressListenerMultipartController.class);
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
    @DisplayName("Proxy creation throws ControllerValidationException when controller has invalid UploadProgressListener parameter")
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
