package ru.jrestly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.fixtures.TestErrorDto;
import ru.jrestly.http.CapturedResponse;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.http.UnexpectedStatusException;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end WireMock integration suite for ADR-0022: Response headers and metadata access.
 * Validates real HTTP network calls for RFC 8288 pagination, Location & ETag headers,
 * rate limit metrics, capture pattern, async CompletableFuture twin, streaming,
 * and error paths (@OnError and UnexpectedStatusException).
 */
class ResponseHeadersWireMockTest extends BaseWireMockTest {

    private final List<AbstractHttpClient> createdClients = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDownCreatedClients() {
        for (AbstractHttpClient clientToClose : createdClients) {
            clientToClose.close();
        }
        createdClients.clear();
    }

    private JRestlyClient createClient() {
        JRestlyClient newClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .build();
        createdClients.add(newClient);
        return newClient;
    }

    public record UserDto(String name, String role) {}

    interface MetadataTestClient {

        @Get(path = "/items")
        List<String> getItemsWithHeaders(@RequestParam(name = "page") int page, ResponseHeaders headers);

        @Get(path = "/items-clean")
        List<String> getItemsClean(@RequestParam(name = "page") int page);

        @Post(path = "/users")
        UserDto createUser(@RequestBody UserDto user, ResponseHeaders headers);

        @Get(path = "/rate-limited")
        String getRateLimited(ResponseHeaders headers);

        @Get(path = "/async-items")
        CompletableFuture<List<String>> getAsyncItems(ResponseHeaders headers);

        @Get(path = "/download")
        InputStream download(ResponseHeaders headers);

        @Get(path = "/error-handled")
        @OnError(statuses = {400}, errorObject = TestErrorDto.class)
        String getHandledError(ResponseHeaders headers);

        @Get(path = "/error-unexpected")
        String getUnexpectedError(ResponseHeaders headers);
    }

    @Test
    @DisplayName("Pagination via RFC 8288 Link header using method parameter ResponseHeaders")
    void paginationViaMethodParameter() {
        stubFor(get(urlEqualTo("/items?page=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link", "<" + baseUrl() + "/items?page=2>; rel=\"next\", <" + baseUrl() + "/items?page=5>; rel=\"last\"")
                        .withHeader("X-Total-Count", "50")
                        .withBody("[\"item1\",\"item2\"]")));

        MetadataTestClient client = createClient().get(MetadataTestClient.class);
        ResponseHeaders headers = new ResponseHeaders();

        List<String> items = client.getItemsWithHeaders(1, headers);

        assertEquals(List.of("item1", "item2"), items);
        assertTrue(headers.isPopulated());
        assertEquals(200, headers.statusCode());
        assertTrue(headers.isSuccess());
        assertTrue(headers.duration().toMillis() >= 0);

        assertEquals(Optional.of(baseUrl() + "/items?page=2"), headers.link("next"));
        assertEquals(Optional.of(baseUrl() + "/items?page=5"), headers.link("last"));
        assertEquals(OptionalLong.of(50L), headers.firstLongValue("X-Total-Count"));
    }

    @Test
    @DisplayName("Pagination via RFC 8288 Link header using ResponseHeaders.capture pattern")
    void paginationViaCapturePattern() {
        stubFor(get(urlEqualTo("/items-clean?page=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link", "<" + baseUrl() + "/items-clean?page=2>; rel=\"next\"")
                        .withBody("[\"itemA\",\"itemB\"]")));

        MetadataTestClient client = createClient().get(MetadataTestClient.class);

        CapturedResponse<List<String>> captured = ResponseHeaders.capture(() -> client.getItemsClean(1));

        assertEquals(List.of("itemA", "itemB"), captured.result());
        assertTrue(captured.headers().isPopulated());
        assertEquals(200, captured.headers().statusCode());
        assertEquals(Optional.of(baseUrl() + "/items-clean?page=2"), captured.headers().link("next"));
    }

    @Test
    @DisplayName("Resource creation access to Location and ETag headers")
    void locationAndEtagHeaders() {
        stubFor(post(urlEqualTo("/users"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Location", baseUrl() + "/users/42")
                        .withHeader("ETag", "\"etag-v1\"")
                        .withBody("{\"name\":\"Alice\",\"role\":\"Admin\"}")));

        MetadataTestClient client = createClient().get(MetadataTestClient.class);
        ResponseHeaders headers = new ResponseHeaders();

        UserDto user = client.createUser(new UserDto("Alice", "Admin"), headers);

        assertEquals("Alice", user.name());
        assertEquals("Admin", user.role());
        assertEquals(201, headers.statusCode());
        assertTrue(headers.isSuccess());
        assertEquals(Optional.of(baseUrl() + "/users/42"), headers.location());
        assertEquals(Optional.of(URI.create(baseUrl() + "/users/42")), headers.locationUri());
        assertTrue(headers.etag().isPresent());
        assertTrue(headers.etag().get().contains("etag-v1"));
    }

    @Test
    @DisplayName("Numeric rate limit headers extraction (X-RateLimit-*)")
    void rateLimitHeaders() {
        stubFor(get(urlEqualTo("/rate-limited"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("X-RateLimit-Limit", "1000")
                        .withHeader("X-RateLimit-Remaining", "42")
                        .withHeader("X-RateLimit-Reset", "1700000000")
                        .withBody("OK")));

        MetadataTestClient client = createClient().get(MetadataTestClient.class);
        ResponseHeaders headers = new ResponseHeaders();

        String body = client.getRateLimited(headers);

        assertEquals("OK", body);
        assertEquals(OptionalLong.of(1000L), headers.firstLongValue("X-RateLimit-Limit"));
        assertEquals(OptionalLong.of(42L), headers.firstLongValue("X-RateLimit-Remaining"));
        assertEquals(OptionalLong.of(1700000000L), headers.firstLongValue("X-RateLimit-Reset"));
    }

    @Test
    @DisplayName("Async CompletableFuture twin populates ResponseHeaders upon completion")
    void asyncTwinPopulatesHeaders() {
        stubFor(get(urlEqualTo("/async-items"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Async-Trace", "trace-777")
                        .withBody("[\"async1\",\"async2\"]")));

        MetadataTestClient client = createClient().get(MetadataTestClient.class);
        ResponseHeaders headers = new ResponseHeaders();

        CompletableFuture<List<String>> future = client.getAsyncItems(headers);
        List<String> items = future.join();

        assertEquals(List.of("async1", "async2"), items);
        assertTrue(headers.isPopulated());
        assertEquals(200, headers.statusCode());
        assertEquals(Optional.of("trace-777"), headers.firstValue("X-Async-Trace"));
    }

    @Test
    @DisplayName("Streaming response (InputStream) populates ResponseHeaders")
    void streamingPopulatesHeaders() throws Exception {
        stubFor(get(urlEqualTo("/download"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("Content-Disposition", "attachment; filename=\"data.bin\"")
                        .withBody("binary-payload-stream")));

        MetadataTestClient client = createClient().get(MetadataTestClient.class);
        ResponseHeaders headers = new ResponseHeaders();

        try (InputStream stream = client.download(headers)) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            assertEquals("binary-payload-stream", new String(bytes, StandardCharsets.UTF_8));
        }

        assertTrue(headers.isPopulated());
        assertEquals(200, headers.statusCode());
        assertEquals(Optional.of("application/octet-stream"), headers.contentType());
        assertEquals(Optional.of("attachment; filename=\"data.bin\""), headers.firstValue("Content-Disposition"));
    }

    @Test
    @DisplayName("@OnError handled error populates ResponseHeaders holder")
    void onErrorPopulatesHeaders() {
        stubFor(get(urlEqualTo("/error-handled"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Error-Code", "ERR_INVALID_PARAM")
                        .withBody("{\"error\":\"Bad request\"}")));

        MetadataTestClient client = createClient().get(MetadataTestClient.class);
        ResponseHeaders headers = new ResponseHeaders();

        HandledException ex = assertThrows(HandledException.class, () -> client.getHandledError(headers));
        assertEquals(400, ex.getStatusCode());

        assertTrue(headers.isPopulated());
        assertEquals(400, headers.statusCode());
        assertFalse(headers.isSuccess());
        assertEquals(Optional.of("ERR_INVALID_PARAM"), headers.firstValue("X-Error-Code"));
    }

    @Test
    @DisplayName("UnexpectedStatusException error populates ResponseHeaders holder")
    void unexpectedStatusPopulatesHeaders() {
        stubFor(get(urlEqualTo("/error-unexpected"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("X-Server-Id", "srv-node-9")
                        .withBody("Internal Server Error")));

        MetadataTestClient client = createClient().get(MetadataTestClient.class);
        ResponseHeaders headers = new ResponseHeaders();

        UnexpectedStatusException ex = assertThrows(UnexpectedStatusException.class, () -> client.getUnexpectedError(headers));
        assertEquals(500, ex.getStatusCode());

        assertTrue(headers.isPopulated());
        assertEquals(500, headers.statusCode());
        assertFalse(headers.isSuccess());
        assertEquals(Optional.of("srv-node-9"), headers.firstValue("X-Server-Id"));
    }

    @Test
    @DisplayName("Passing null ResponseHeaders is safely treated as an opt-out")
    void nullResponseHeadersSafe() {
        stubFor(get(urlEqualTo("/items?page=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\"item1\"]")));

        MetadataTestClient client = createClient().get(MetadataTestClient.class);

        List<String> items = client.getItemsWithHeaders(1, null);
        assertEquals(List.of("item1"), items);
    }
}
