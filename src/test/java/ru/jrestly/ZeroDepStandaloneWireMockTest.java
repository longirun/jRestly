package ru.jrestly;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.json.NoOpJsonCodec;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest
class ZeroDepStandaloneWireMockTest {

    private JRestlyClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    interface PureZeroDepApi {
        @Get(path = "/user/${id}")
        String getUser(@PathVariable(name = "id") UUID id);

        @Post(path = "/echo")
        String echo(@RequestBody String text);

        @Get(path = "/download")
        byte[] download();

        @Get(path = "/stream")
        InputStream stream();

        @Get(path = "/optional")
        Optional<String> optionalValue();

        @Get(path = "/async-user/${id}")
        CompletableFuture<String> getUserAsync(@PathVariable(name = "id") UUID id);

        @Delete(path = "/item/${id}")
        void deleteItem(@PathVariable(name = "id") long id);

        @Get(path = "/user-dto")
        TestDto getUserDto();

        @Post(path = "/user-dto")
        String sendUserDto(@RequestBody TestDto dto);

        @OnError(statuses = {500}, errorObject = TestDto.class)
        @Get(path = "/error-dto")
        String getWithCustomErrorDto();
    }

    @Test
    @DisplayName("Pure zero-dependency client with NoOpJsonCodec handles String responses and UUID path variables")
    void stringResponseWithUuidPathVariable(WireMockRuntimeInfo wm) {
        UUID id = UUID.randomUUID();
        stubFor(get(urlEqualTo("/user/" + id))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + id + "\",\"name\":\"Anton\"}")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .jsonCodec(JsonCodec.noop())
                .build();

        PureZeroDepApi api = client.get(PureZeroDepApi.class);
        String response = api.getUser(id);

        assertEquals("{\"id\":\"" + id + "\",\"name\":\"Anton\"}", response);
    }

    @Test
    @DisplayName("Pure zero-dependency client handles String request body and response")
    void stringRequestBodyAndResponse(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/echo"))
                .withRequestBody(equalTo("hello zero-dependency"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("echo: hello zero-dependency")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .jsonCodec(NoOpJsonCodec.instance())
                .build();

        PureZeroDepApi api = client.get(PureZeroDepApi.class);
        String result = api.echo("hello zero-dependency");

        assertEquals("echo: hello zero-dependency", result);
    }

    @Test
    @DisplayName("Pure zero-dependency client handles byte[] and InputStream responses")
    void binaryResponses(WireMockRuntimeInfo wm) throws Exception {
        byte[] expected = "binary payload 12345".getBytes(StandardCharsets.UTF_8);
        stubFor(get(urlEqualTo("/download"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBody(expected)));

        stubFor(get(urlEqualTo("/stream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBody(expected)));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .jsonCodec(JsonCodec.noop())
                .build();

        PureZeroDepApi api = client.get(PureZeroDepApi.class);

        byte[] downloaded = api.download();
        assertArrayEquals(expected, downloaded);

        try (InputStream in = api.stream()) {
            assertNotNull(in);
            assertArrayEquals(expected, in.readAllBytes());
        }
    }

    @Test
    @DisplayName("Pure zero-dependency client handles Optional<String> for 200 OK and 404 Not Found")
    void optionalHandling(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/optional"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("found text")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .jsonCodec(JsonCodec.noop())
                .build();

        PureZeroDepApi api = client.get(PureZeroDepApi.class);
        Optional<String> result = api.optionalValue();
        assertTrue(result.isPresent());
        assertEquals("found text", result.get());

        stubFor(get(urlEqualTo("/optional"))
                .willReturn(aResponse()
                        .withStatus(404)));

        Optional<String> missing = api.optionalValue();
        assertTrue(missing.isEmpty());
    }

    @Test
    @DisplayName("Pure zero-dependency client handles CompletableFuture<String> and void returns")
    void asyncAndVoidOperations(WireMockRuntimeInfo wm) throws Exception {
        UUID id = UUID.randomUUID();
        stubFor(get(urlEqualTo("/async-user/" + id))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("async-" + id)));

        stubFor(delete(urlEqualTo("/item/42"))
                .willReturn(aResponse()
                        .withStatus(204)));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .jsonCodec(JsonCodec.noop())
                .build();

        PureZeroDepApi api = client.get(PureZeroDepApi.class);

        CompletableFuture<String> future = api.getUserAsync(id);
        assertEquals("async-" + id, future.get());

        api.deleteItem(42L);
    }

    @Test
    @DisplayName("DTO deserialization without codec fails with actionable IllegalStateException")
    void dtoDeserializationFailsActionably(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/user-dto"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"Alice\",\"description\":\"desc\"}")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .jsonCodec(JsonCodec.noop())
                .build();

        PureZeroDepApi api = client.get(PureZeroDepApi.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class, api::getUserDto);
        assertTrue(ex.getMessage().contains("jackson-databind"));
        assertTrue(ex.getMessage().contains("gson"));
        assertTrue(ex.getMessage().contains("moshi"));
        assertTrue(ex.getMessage().contains("jsonCodec"));
    }

    @Test
    @DisplayName("DTO serialization without codec fails with actionable IllegalStateException")
    void dtoSerializationFailsActionably(WireMockRuntimeInfo wm) {
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .jsonCodec(JsonCodec.noop())
                .build();

        PureZeroDepApi api = client.get(PureZeroDepApi.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> api.sendUserDto(new TestDto(1L, "Alice", "test")));
        assertTrue(ex.getMessage().contains("jackson-databind"));
    }

    @Test
    @DisplayName("Custom @OnError DTO without codec fails with actionable IllegalStateException on error status")
    void onErrorDtoFailsActionably(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/error-dto"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"id\":999,\"name\":\"Error\",\"description\":\"Failed\"}")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .jsonCodec(JsonCodec.noop())
                .build();

        PureZeroDepApi api = client.get(PureZeroDepApi.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class, api::getWithCustomErrorDto);
        assertTrue(ex.getMessage().contains("jackson-databind"));
    }
}
