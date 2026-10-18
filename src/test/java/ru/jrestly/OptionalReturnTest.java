package ru.jrestly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.fixtures.TestErrorDto;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.UnexpectedStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for ADR-0023: Native {@link Optional} return type ergonomics.
 */
class OptionalReturnTest extends BaseWireMockTest {

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

    public record UserDto(long id, String name) {}

    interface OptionalClient {

        @Get("/users/${id}")
        Optional<UserDto> findUser(@PathVariable(name = "id") long id);

        @Get("/users/${id}/async")
        CompletableFuture<Optional<UserDto>> findUserAsync(@PathVariable(name = "id") long id);

        @Get("/users/${id}/strict")
        @ExpectStatus(statuses = {200})
        Optional<UserDto> findUserStrict(@PathVariable(name = "id") long id);

        @Get("/users/${id}/error-override")
        @OnError(errorObject = TestErrorDto.class, statuses = {404})
        Optional<UserDto> findUserWithErrorOverride(@PathVariable(name = "id") long id);

        @Get("/raw-text")
        Optional<String> getRawText();

        @Get("/void-check")
        Optional<Void> getVoidCheck();

        @Get("/user-list")
        Optional<List<UserDto>> listUsers();
    }

    @Test
    @DisplayName("200 OK with JSON payload returns Optional.of(DTO)")
    void optionalOfDtoOn200() {
        stubFor(get(urlEqualTo("/users/1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"Alice\"}")));

        OptionalClient client = createClient().get(OptionalClient.class);
        Optional<UserDto> result = client.findUser(1L);

        assertTrue(result.isPresent());
        assertEquals(new UserDto(1L, "Alice"), result.get());
    }

    @Test
    @DisplayName("404 Not Found returns Optional.empty() without throwing exception")
    void optionalEmptyOn404() {
        stubFor(get(urlEqualTo("/users/999"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"User not found\"}")));

        OptionalClient client = createClient().get(OptionalClient.class);
        Optional<UserDto> result = client.findUser(999L);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("204 No Content returns Optional.empty()")
    void optionalEmptyOn204() {
        stubFor(get(urlEqualTo("/users/2"))
                .willReturn(aResponse()
                        .withStatus(204)));

        OptionalClient client = createClient().get(OptionalClient.class);
        Optional<UserDto> result = client.findUser(2L);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("304 Not Modified returns Optional.empty()")
    void optionalEmptyOn304() {
        stubFor(get(urlEqualTo("/users/3"))
                .willReturn(aResponse()
                        .withStatus(304)));

        OptionalClient client = createClient().get(OptionalClient.class);
        Optional<UserDto> result = client.findUser(3L);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("200 OK with empty body returns Optional.empty()")
    void optionalEmptyOnEmpty200() {
        stubFor(get(urlEqualTo("/users/4"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("")));

        OptionalClient client = createClient().get(OptionalClient.class);
        Optional<UserDto> result = client.findUser(4L);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Optional<String> returns Optional.of(text) on 200 and Optional.empty() on 404")
    void optionalStringHandling() {
        stubFor(get(urlEqualTo("/raw-text"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Hello, World!")));

        OptionalClient client = createClient().get(OptionalClient.class);
        Optional<String> success = client.getRawText();
        assertTrue(success.isPresent());
        assertEquals("Hello, World!", success.get());

        stubFor(get(urlEqualTo("/raw-text"))
                .willReturn(aResponse()
                        .withStatus(404)));

        Optional<String> notFound = client.getRawText();
        assertTrue(notFound.isEmpty());
    }

    @Test
    @DisplayName("Optional<Void> always returns Optional.empty()")
    void optionalVoidHandling() {
        stubFor(get(urlEqualTo("/void-check"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ignored")));

        OptionalClient client = createClient().get(OptionalClient.class);
        Optional<Void> result = client.getVoidCheck();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Optional<List<DTO>> deserializes generic collection into Optional.of(list)")
    void optionalGenericCollection() {
        stubFor(get(urlEqualTo("/user-list"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]")));

        OptionalClient client = createClient().get(OptionalClient.class);
        Optional<List<UserDto>> result = client.listUsers();

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertEquals(new UserDto(1L, "Alice"), result.get().get(0));
        assertEquals(new UserDto(2L, "Bob"), result.get().get(1));
    }

    @Test
    @DisplayName("CompletableFuture<Optional<T>> resolves asynchronously on 200 and 404")
    void optionalAsyncTwin() {
        stubFor(get(urlEqualTo("/users/42/async"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"name\":\"Arthur\"}")));

        OptionalClient client = createClient().get(OptionalClient.class);
        Optional<UserDto> present = client.findUserAsync(42L).join();
        assertTrue(present.isPresent());
        assertEquals(new UserDto(42L, "Arthur"), present.get());

        stubFor(get(urlEqualTo("/users/404/async"))
                .willReturn(aResponse()
                        .withStatus(404)));

        Optional<UserDto> absent = client.findUserAsync(404L).join();
        assertTrue(absent.isEmpty());
    }

    @Test
    @DisplayName("@OnError takes precedence over Optional default 404 handling")
    void onErrorTakesPrecedenceOverOptional() {
        stubFor(get(urlEqualTo("/users/5/error-override"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"ERR_404\",\"message\":\"Explicit error mapping\"}")));

        OptionalClient client = createClient().get(OptionalClient.class);
        HandledException ex = assertThrows(HandledException.class, () -> client.findUserWithErrorOverride(5L));
        assertEquals(404, ex.getStatusCode());
        assertInstanceOf(TestErrorDto.class, ex.getDetails());
        TestErrorDto errorDto = ex.getDetails();
        assertEquals("ERR_404", errorDto.code());
        assertEquals("Explicit error mapping", errorDto.message());
    }

    @Test
    @DisplayName("Explicit @ExpectStatus({200}) overrides Optional default 404 acceptance")
    void explicitExpectStatusOverridesOptionalDefault() {
        stubFor(get(urlEqualTo("/users/6/strict"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withBody("Not Found")));

        OptionalClient client = createClient().get(OptionalClient.class);
        UnexpectedStatusException ex = assertThrows(UnexpectedStatusException.class, () -> client.findUserStrict(6L));
        assertEquals(404, ex.getStatusCode());
    }
}
