package ru.jrestly;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.http.ClientErrorException;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.Header;
import ru.jrestly.http.ServerErrorException;
import ru.jrestly.http.UnexpectedStatusException;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end for {@code @OnError} handling: mapping statuses to deserialized error
 * objects, custom exception factories, interface-hierarchy fallbacks and async errors.
 */
class OnErrorWireMockTest {

    private WireMockServer wireMock;
    private JRestlyClient httpClient;

    public record NotFoundDto(String entity, long id) {}
    public record ValidationDto(String field, String issue) {}
    public record SharedAuthErrorDto(String reason) {}
    public record MethodSpecificErrorDto(String detail) {}

    @SuppressWarnings("serial")
    public static class ItemNotFoundException extends RuntimeException {
        private String reason;

        public ItemNotFoundException() {
            super("Item not found");
        }

        public ItemNotFoundException(String message) {
            super(message);
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    @SuppressWarnings("serial")
    public static class ItemConflictException extends HandledException {
        public ItemConflictException(Object details, int statusCode, List<Header> responseHeaders) {
            super(details, statusCode, responseHeaders);
        }
    }

    @SuppressWarnings("serial")
    public static class CustomMessageAndStatusException extends RuntimeException {
        private final int code;
        public CustomMessageAndStatusException(String message, int code) {
            super(message);
            this.code = code;
        }
        public int getCode() {
            return code;
        }
    }

    @SuppressWarnings("serial")
    public static class FallbackException extends RuntimeException {
        public FallbackException(double val1, double val2) {
            super("fallback");
        }
    }


    @OnError(statuses = {401}, errorObject = SharedAuthErrorDto.class)
    @OnError(statuses = {400}, errorObject = SharedAuthErrorDto.class)
    public interface ErrorTestClient {

        @Get("/api/items/${id}")
        @OnError(statuses = {404}, errorObject = NotFoundDto.class)
        @OnError(statuses = {422}, errorObject = ValidationDto.class)
        String getItem(@PathVariable long id);

        @Get("/api/unannotated-method")
        String callUnannotated();

        @Get("/api/override-method")
        @OnError(statuses = {400}, errorObject = MethodSpecificErrorDto.class)
        String callOverride();

        @Get("/api/typed-exception/${id}")
        @OnError(statuses = {404}, errorObject = ItemNotFoundException.class)
        String getWithTypedException(@PathVariable long id);

        @Get("/api/handled-subclass/${id}")
        @OnError(statuses = {409}, errorObject = ItemConflictException.class)
        String getWithHandledSubclass(@PathVariable long id);

        @Get("/api/typed-async/${id}")
        @OnError(statuses = {404}, errorObject = ItemNotFoundException.class)
        CompletableFuture<String> getAsyncWithTypedException(@PathVariable long id);

        @Get("/api/typed-stream/${id}")
        @OnError(statuses = {404}, errorObject = ItemNotFoundException.class)
        InputStream getStreamWithTypedException(@PathVariable long id);

        @Get("/api/unexpected")
        String callUnexpected();

        @Get("/api/custom-status-ex")
        @OnError(statuses = {422}, errorObject = CustomMessageAndStatusException.class)
        String callCustomStatusEx();

        @Get("/api/fallback-ex")
        @OnError(statuses = {423}, errorObject = FallbackException.class)
        String callFallbackEx();
    }


    private ErrorTestClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        httpClient = new JRestlyClient.Builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .build();
        client = httpClient.get(ErrorTestClient.class);
    }

    @AfterEach
    void tearDown() {
        if (httpClient != null) {
            httpClient.close();
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    @DisplayName("Repeatable @OnError: 404 decodes to NotFoundDto, 422 decodes to ValidationDto")
    void repeatableOnErrorMatchesCorrectDto() {
        wireMock.stubFor(get(urlEqualTo("/api/items/101"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"entity\":\"Order\",\"id\":101}")));

        HandledException ex404 = assertThrows(HandledException.class, () -> client.getItem(101));
        assertEquals(404, ex404.getStatusCode());
        NotFoundDto notFound = ex404.getDetails();
        assertEquals("Order", notFound.entity());
        assertEquals(101L, notFound.id());

        wireMock.stubFor(get(urlEqualTo("/api/items/102"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"field\":\"quantity\",\"issue\":\"must be positive\"}")));

        HandledException ex422 = assertThrows(HandledException.class, () -> client.getItem(102));
        assertEquals(422, ex422.getStatusCode());
        ValidationDto validation = ex422.getDetails();
        assertEquals("quantity", validation.field());
        assertEquals("must be positive", validation.issue());
    }

    @Test
    @DisplayName("Interface-level @OnError applies to methods without method-level annotations")
    void interfaceLevelOnErrorInherited() {
        wireMock.stubFor(get(urlEqualTo("/api/unannotated-method"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reason\":\"token_expired\"}")));

        HandledException ex = assertThrows(HandledException.class, () -> client.callUnannotated());
        assertEquals(401, ex.getStatusCode());
        SharedAuthErrorDto dto = ex.getDetails();
        assertEquals("token_expired", dto.reason());
    }

    @Test
    @DisplayName("Method-level @OnError overrides interface-level declaration for the same status code")
    void methodLevelOverridesInterfaceLevel() {
        wireMock.stubFor(get(urlEqualTo("/api/override-method"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"detail\":\"specific_bad_request\"}")));

        HandledException ex = assertThrows(HandledException.class, () -> client.callOverride());
        assertEquals(400, ex.getStatusCode());
        MethodSpecificErrorDto dto = ex.getDetails();
        assertEquals("specific_bad_request", dto.detail());
    }

    @Test
    @DisplayName("Direct exception throwing: Custom RuntimeException thrown directly and catchable by type")
    void directRuntimeExceptionThrowing() {
        wireMock.stubFor(get(urlEqualTo("/api/typed-exception/42"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reason\":\"User 42 does not exist\"}")));

        ItemNotFoundException ex = assertThrows(
                ItemNotFoundException.class,
                () -> client.getWithTypedException(42)
        );
        assertEquals("User 42 does not exist", ex.getReason());
    }

    @Test
    @DisplayName("Direct exception throwing with empty body instantiates exception via reflection fallback")
    void directRuntimeExceptionEmptyBodyFallback() {
        wireMock.stubFor(get(urlEqualTo("/api/typed-exception/999"))
                .willReturn(aResponse().withStatus(404)));

        ItemNotFoundException ex = assertThrows(
                ItemNotFoundException.class,
                () -> client.getWithTypedException(999)
        );
        assertNotNull(ex);
    }

    @Test
    @DisplayName("Direct exception throwing with HandledException subclass")
    void directHandledExceptionSubclassThrowing() {
        wireMock.stubFor(get(urlEqualTo("/api/handled-subclass/77"))
                .willReturn(aResponse().withStatus(409)));

        ItemConflictException ex = assertThrows(
                ItemConflictException.class,
                () -> client.getWithHandledSubclass(77)
        );
        assertInstanceOf(HandledException.class, ex);
        assertEquals(409, ex.getStatusCode());
    }

    @Test
    @DisplayName("Undeclared HTTP 4xx throws ClientErrorException (subclass of UnexpectedStatusException)")
    void undeclared4xxThrowsClientErrorException() {
        wireMock.stubFor(get(urlEqualTo("/api/unexpected"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withBody("Forbidden access")));

        ClientErrorException ex = assertThrows(
                ClientErrorException.class,
                () -> client.callUnexpected()
        );
        assertEquals(403, ex.getStatusCode());
        assertEquals("Forbidden access", ex.getRawBody());
        assertInstanceOf(UnexpectedStatusException.class, ex);
    }

    @Test
    @DisplayName("Undeclared HTTP 5xx throws ServerErrorException (subclass of UnexpectedStatusException)")
    void undeclared5xxThrowsServerErrorException() {
        wireMock.stubFor(get(urlEqualTo("/api/unexpected"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("Gateway Down")));

        ServerErrorException ex = assertThrows(
                ServerErrorException.class,
                () -> client.callUnexpected()
        );
        assertEquals(503, ex.getStatusCode());
        assertEquals("Gateway Down", ex.getRawBody());
        assertInstanceOf(UnexpectedStatusException.class, ex);
    }

    @Test
    @DisplayName("Async call CompletableFuture completes exceptionally with custom typed exception")
    void asyncCallCompletesWithTypedException() {
        wireMock.stubFor(get(urlEqualTo("/api/typed-async/50"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reason\":\"Async missing\"}")));

        ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> client.getAsyncWithTypedException(50).get()
        );
        assertInstanceOf(ItemNotFoundException.class, ex.getCause());
        ItemNotFoundException itemNotFound = (ItemNotFoundException) ex.getCause();
        assertEquals("Async missing", itemNotFound.getReason());
    }

    @Test
    @DisplayName("Streaming InputStream method consumes error body and throws custom typed exception")
    void streamingMethodThrowsTypedException() {
        wireMock.stubFor(get(urlEqualTo("/api/typed-stream/60"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reason\":\"Stream error\"}")));

        ItemNotFoundException ex = assertThrows(
                ItemNotFoundException.class,
                () -> {
                    try (InputStream stream = client.getStreamWithTypedException(60)) {
                        stream.readAllBytes();
                    }
                }
        );
        assertEquals("Stream error", ex.getReason());
    }

    @Test
    @DisplayName("InstantiateThrowable uses (String message, int statusCode) constructor when available")
    void usesStringAndStatusConstructor() {
        wireMock.stubFor(get(urlEqualTo("/api/custom-status-ex"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withBody("")));

        CustomMessageAndStatusException ex = assertThrows(
                CustomMessageAndStatusException.class,
                () -> client.callCustomStatusEx()
        );
        assertEquals(422, ex.getCode());
        assertEquals("HTTP 422", ex.getMessage());
    }

    @Test
    @DisplayName("InstantiateThrowable falls back to HandledException when custom exception lacks standard constructors")
    void fallsBackToHandledExceptionWhenLackingConstructors() {
        wireMock.stubFor(get(urlEqualTo("/api/fallback-ex"))
                .willReturn(aResponse()
                        .withStatus(423)
                        .withBody("")));

        HandledException ex = assertThrows(
                HandledException.class,
                () -> client.callFallbackEx()
        );
        assertEquals(423, ex.getStatusCode());
        assertNull(ex.getDetails());
    }
}


