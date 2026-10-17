package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static validation of {@code @RequestBody} usage: rejecting it on {@code @Get}
 * methods, multiple body parameters, or a parameter combined with another binding
 * annotation, while all supported streaming body types ({@code Flow.Publisher},
 * {@code InputStream}, {@code Supplier}, {@code Path}, {@code BodyPublisher}) pass
 * and can be proxied (ADR-0035). Pure unit tests.
 */
@DisplayName("RequestBodyValidationTest")
class RequestBodyValidationTest {

    interface InvalidGetWithBodyApi {
        @Get(path = "/items")
        String getItems(@RequestBody String filter);
    }

    interface MultipleRequestBodyApi {
        @Post(path = "/upload")
        void upload(@RequestBody String first, @RequestBody String second);
    }

    interface CombinedParamApi {
        @Post(path = "/data")
        void sendData(@RequestBody @RequestParam(name = "q") String payload);
    }

    interface CombinedPathApi {
        @Put(path = "/users/${id}")
        void updateUser(@RequestBody @PathVariable(name = "id") String payload);
    }

    interface CombinedHeaderApi {
        @Post(path = "/data")
        void sendWithHeader(@RequestBody @RequestHeader(name = "X-Tag") String payload);
    }

    interface CombinedCookieApi {
        @Post(path = "/data")
        void sendWithCookie(@RequestBody @Cookie(name = "session") String payload);
    }

    interface ValidStreamingApi {
        @Post(path = "/stream/bytebuffer")
        void streamByteBuffer(@RequestBody Flow.Publisher<ByteBuffer> publisher);

        @Post(path = "/stream/string")
        void streamString(@RequestBody Flow.Publisher<String> publisher);

        @Post(path = "/stream/inputstream")
        void streamInputStream(@RequestBody InputStream stream);

        @Post(path = "/stream/supplier")
        void streamSupplier(@RequestBody Supplier<InputStream> streamSupplier);

        @Post(path = "/stream/path")
        void streamPath(@RequestBody Path path);

        @Post(path = "/stream/publisher")
        void streamBodyPublisher(@RequestBody HttpRequest.BodyPublisher publisher);
    }

    @Test
    @DisplayName("Reject @Get method with @RequestBody parameter")
    void testRejectGetWithRequestBody() {
        List<String> problems = ControllerValidator.validate(InvalidGetWithBodyApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("@Get method must not declare @RequestBody parameter"),
                "Expected rejection of @RequestBody on @Get: " + problems);
    }

    @Test
    @DisplayName("Reject method declaring multiple @RequestBody parameters")
    void testRejectMultipleRequestBodies() {
        List<String> problems = ControllerValidator.validate(MultipleRequestBodyApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("multiple parameters annotated with @RequestBody, at most one is allowed"),
                "Expected rejection of multiple @RequestBody: " + problems);
    }

    @Test
    @DisplayName("Reject parameter combining @RequestBody with @RequestParam")
    void testRejectCombinedParam() {
        List<String> problems = ControllerValidator.validate(CombinedParamApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("parameter must not combine @RequestBody with other HTTP parameter annotations"),
                "Expected rejection of combined @RequestParam: " + problems);
    }

    @Test
    @DisplayName("Reject parameter combining @RequestBody with @PathVariable")
    void testRejectCombinedPath() {
        List<String> problems = ControllerValidator.validate(CombinedPathApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("parameter must not combine @RequestBody with other HTTP parameter annotations"),
                "Expected rejection of combined @PathVariable: " + problems);
    }

    @Test
    @DisplayName("Reject parameter combining @RequestBody with @RequestHeader")
    void testRejectCombinedHeader() {
        List<String> problems = ControllerValidator.validate(CombinedHeaderApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("parameter must not combine @RequestBody with other HTTP parameter annotations"),
                "Expected rejection of combined @RequestHeader: " + problems);
    }

    @Test
    @DisplayName("Reject parameter combining @RequestBody with @Cookie")
    void testRejectCombinedCookie() {
        List<String> problems = ControllerValidator.validate(CombinedCookieApi.class);
        // Might trigger both Cookie validation check and RequestBody check
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("parameter must not combine @RequestBody with other HTTP parameter annotations")
                        || p.contains("parameter must not combine @Cookie with other HTTP parameter annotations")),
                "Expected rejection of combined @Cookie: " + problems);
    }

    @Test
    @DisplayName("Valid streaming API passes validation cleanly")
    void testValidStreamingApiPasses() {
        List<String> problems = ControllerValidator.validate(ValidStreamingApi.class);
        assertTrue(problems.isEmpty(), "Expected no validation problems, but found: " + problems);
    }

    @Test
    @DisplayName("Client successfully builds proxy for valid streaming interface")
    void testProxyCreationForStreamingApi() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .build();
        try {
            ValidStreamingApi api = client.get(ValidStreamingApi.class);
            assertNotNull(api);
        } finally {
            client.close();
        }
    }
}
