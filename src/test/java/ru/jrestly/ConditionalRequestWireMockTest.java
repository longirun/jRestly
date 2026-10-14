package ru.jrestly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.http.CapturedResponse;
import ru.jrestly.http.ResponseHeaders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end WireMock integration test for ADR-0023: Conditional HTTP requests and 304 Not Modified.
 */
class ConditionalRequestWireMockTest extends BaseWireMockTest {

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

    public record ItemDto(long id, String title) {}

    interface ConditionalClient {

        @Get(path = "/items/${id}")
        ItemDto getItem(
                @PathVariable(name = "id") long id,
                @RequestHeader(name = "If-None-Match") String ifNoneMatch,
                ResponseHeaders headers
        );

        @Get(path = "/items/${id}")
        ItemDto getItemNoHeaders(
                @PathVariable(name = "id") long id,
                @RequestHeader(name = "If-None-Match") String ifNoneMatch
        );

        @Get(path = "/items/${id}")
        Optional<ItemDto> findItem(
                @PathVariable(name = "id") long id,
                @RequestHeader(name = "If-None-Match") String ifNoneMatch
        );

        @Get(path = "/items/${id}")
        ItemDto getItemSince(
                @PathVariable(name = "id") long id,
                @RequestHeader(name = "If-Modified-Since") String ifModifiedSince,
                ResponseHeaders headers
        );

        @Get(path = "/items/${id}/async")
        CompletableFuture<ItemDto> getItemAsync(
                @PathVariable(name = "id") long id,
                @RequestHeader(name = "If-None-Match") String ifNoneMatch,
                ResponseHeaders headers
        );
    }

    @Test
    @DisplayName("Conditional GET with If-None-Match: 200 OK returns domain object and new ETag")
    void conditionalGetReturnsNewDataOn200() {
        stubFor(get(urlEqualTo("/items/100"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"v2\"")
                        .withBody("{\"id\":100,\"title\":\"Updated Item\"}")));

        ConditionalClient client = createClient().get(ConditionalClient.class);
        ResponseHeaders headers = new ResponseHeaders();
        ItemDto item = client.getItem(100L, "\"v1\"", headers);

        assertNotNull(item);
        assertEquals(new ItemDto(100L, "Updated Item"), item);
        assertTrue(headers.isPopulated());
        assertEquals(200, headers.statusCode());
        assertTrue(headers.isSuccess());
        assertTrue(headers.etag().isPresent());
        assertTrue(headers.etag().get().contains("v2"));
    }

    @Test
    @DisplayName("Conditional GET with If-None-Match: 304 Not Modified succeeds, returns null, marks headers.isNotModified()")
    void conditionalGetHandles304NotModified() {
        stubFor(get(urlEqualTo("/items/100"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse()
                        .withStatus(304)
                        .withHeader("ETag", "\"v1\"")
                        .withHeader("Cache-Control", "max-age=3600")));

        ConditionalClient client = createClient().get(ConditionalClient.class);
        ResponseHeaders headers = new ResponseHeaders();
        ItemDto item = client.getItem(100L, "\"v1\"", headers);

        assertNull(item, "304 response has no body; domain return should be null");
        assertTrue(headers.isPopulated());
        assertEquals(304, headers.statusCode());
        assertTrue(headers.isNotModified());
        assertFalse(headers.isSuccess());
        assertEquals(Optional.of("\"v1\""), headers.etag());
        assertEquals(Optional.of("max-age=3600"), headers.cacheControl());
    }

    @Test
    @DisplayName("Conditional GET with If-Modified-Since: 304 Not Modified populates Last-Modified date")
    void conditionalGetWithIfModifiedSince() {
        stubFor(get(urlEqualTo("/items/200"))
                .withHeader("If-Modified-Since", equalTo("Wed, 21 Oct 2015 07:28:00 GMT"))
                .willReturn(aResponse()
                        .withStatus(304)
                        .withHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                        .withHeader("ETag", "\"tag200\"")));

        ConditionalClient client = createClient().get(ConditionalClient.class);
        ResponseHeaders headers = new ResponseHeaders();
        ItemDto item = client.getItemSince(200L, "Wed, 21 Oct 2015 07:28:00 GMT", headers);

        assertNull(item);
        assertTrue(headers.isNotModified());
        assertEquals(Optional.of("Wed, 21 Oct 2015 07:28:00 GMT"), headers.lastModified());
        assertEquals(Optional.of(Instant.parse("2015-10-21T07:28:00Z")), headers.lastModifiedInstant());
        assertEquals(Optional.of("\"tag200\""), headers.etag());
    }

    @Test
    @DisplayName("Conditional GET with Optional<T>: 304 Not Modified returns Optional.empty()")
    void conditionalGetWithOptionalReturn() {
        stubFor(get(urlEqualTo("/items/300"))
                .withHeader("If-None-Match", equalTo("\"current-etag\""))
                .willReturn(aResponse()
                        .withStatus(304)
                        .withHeader("ETag", "\"current-etag\"")));

        ConditionalClient client = createClient().get(ConditionalClient.class);
        Optional<ItemDto> result = client.findItem(300L, "\"current-etag\"");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Conditional GET with ResponseHeaders.capture: captures 304 metadata without interface parameter")
    void conditionalGetWithCapturePattern() {
        stubFor(get(urlEqualTo("/items/400"))
                .withHeader("If-None-Match", equalTo("\"v9\""))
                .willReturn(aResponse()
                        .withStatus(304)
                        .withHeader("ETag", "\"v9\"")
                        .withHeader("Cache-Control", "public, max-age=60")));

        ConditionalClient client = createClient().get(ConditionalClient.class);
        CapturedResponse<ItemDto> captured = ResponseHeaders.capture(() -> client.getItemNoHeaders(400L, "\"v9\""));

        assertNull(captured.result());
        assertTrue(captured.headers().isNotModified());
        assertEquals(304, captured.headers().statusCode());
        assertEquals(Optional.of("\"v9\""), captured.headers().etag());
        assertEquals(Optional.of("public, max-age=60"), captured.headers().cacheControl());
    }

    @Test
    @DisplayName("Async CompletableFuture twin: resolves 304 successfully without exceptional completion")
    void conditionalGetAsyncCompletableFuture() {
        stubFor(get(urlEqualTo("/items/500/async"))
                .withHeader("If-None-Match", equalTo("\"async-v1\""))
                .willReturn(aResponse()
                        .withStatus(304)
                        .withHeader("ETag", "\"async-v1\"")));

        ConditionalClient client = createClient().get(ConditionalClient.class);
        ResponseHeaders headers = new ResponseHeaders();
        ItemDto item = client.getItemAsync(500L, "\"async-v1\"", headers).join();

        assertNull(item);
        assertTrue(headers.isNotModified());
        assertEquals(304, headers.statusCode());
        assertEquals(Optional.of("\"async-v1\""), headers.etag());
    }
}
