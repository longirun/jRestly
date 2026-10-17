package ru.jrestly;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.fixtures.TestErrorDto;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.ServerSentEvent;
import ru.jrestly.http.UnexpectedStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming text responses (ADR-0016): {@code Stream<ServerSentEvent>} (LLM-style SSE,
 * {@code [DONE]} recognition) and NDJSON {@code Stream<String>} line streaming, sync
 * and async, with {@code Content-Type} charset decoding and error-body consumption for
 * {@code @OnError} and unexpected statuses. WireMock e2e.
 */
class ServerSentEventsAndLineStreamingTest extends BaseWireMockTest {

    interface LlmChatApi {
        @Post(path = "/v1/chat/completions")
        Stream<ServerSentEvent> streamChat(@RequestBody Map<String, Object> request);

        @Post(path = "/v1/chat/completions")
        CompletableFuture<Stream<ServerSentEvent>> streamChatAsync(@RequestBody Map<String, Object> request);

        @Get(path = "/v1/chat/error")
        @OnError(statuses = {429}, errorObject = TestErrorDto.class)
        Stream<ServerSentEvent> rateLimitedStream();

        @Get(path = "/v1/lines/error")
        @OnError(statuses = {400}, errorObject = TestErrorDto.class)
        Stream<String> badRequestLines();
    }

    @Test
    @DisplayName("sync: LLM chat streaming via Stream<ServerSentEvent> parses events and recognizes [DONE]")
    void syncLlmChatStreaming() {
        String ssePayload = """
                event: message
                id: 1
                data: {"content": "Hello"}

                event: message
                id: 2
                data: {"content": " world!"}

                data: [DONE]

                """;

        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream; charset=utf-8")
                        .withBody(ssePayload)));

        LlmChatApi api = client.get(LlmChatApi.class);
        try (Stream<ServerSentEvent> eventStream = api.streamChat(Map.of("model", "gpt-4", "stream", true))) {
            List<ServerSentEvent> events = eventStream.toList();
            assertEquals(3, events.size());

            ServerSentEvent e1 = events.get(0);
            assertEquals("1", e1.id());
            assertEquals("message", e1.event());
            assertEquals("{\"content\": \"Hello\"}", e1.data());
            assertFalse(e1.isDone());

            ServerSentEvent e2 = events.get(1);
            assertEquals("2", e2.id());
            assertEquals("message", e2.event());
            assertEquals("{\"content\": \" world!\"}", e2.data());
            assertFalse(e2.isDone());

            ServerSentEvent e3 = events.get(2);
            assertEquals("2", e3.id());
            assertNull(e3.event());
            assertEquals("[DONE]", e3.data());
            assertTrue(e3.isDone());
        }
    }

    @Test
    @DisplayName("async: LLM chat streaming via CompletableFuture<Stream<ServerSentEvent>>")
    void asyncLlmChatStreaming() {
        String ssePayload = """
                data: {"token": "A"}

                data: {"token": "B"}

                data: [DONE]

                """;

        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(ssePayload)));

        LlmChatApi api = client.get(LlmChatApi.class);
        CompletableFuture<Stream<ServerSentEvent>> future = api.streamChatAsync(Map.of("stream", true));
        try (Stream<ServerSentEvent> eventStream = future.join()) {
            List<ServerSentEvent> events = eventStream.toList();
            assertEquals(3, events.size());
            assertEquals("{\"token\": \"A\"}", events.get(0).data());
            assertEquals("{\"token\": \"B\"}", events.get(1).data());
            assertTrue(events.get(2).isDone());
        }
    }

    @Test
    @DisplayName("sync: NDJSON line streaming via Stream<String>")
    void syncNdjsonLineStreaming() {
        String ndjsonPayload = "{\"id\":1}\n{\"id\":2}\n{\"id\":3}\n";

        stubFor(get(urlEqualTo("/api/stream/lines"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjsonPayload)));

        try (Stream<String> lines = controller.getLines()) {
            List<String> list = lines.toList();
            assertEquals(List.of("{\"id\":1}", "{\"id\":2}", "{\"id\":3}"), list);
        }
    }

    @Test
    @DisplayName("async: NDJSON line streaming via CompletableFuture<Stream<String>>")
    void asyncNdjsonLineStreaming() {
        String ndjsonPayload = "line-1\nline-2\nline-3\n";

        stubFor(get(urlEqualTo("/api/stream/lines"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(ndjsonPayload)));

        CompletableFuture<Stream<String>> future = controller.getLinesAsync();
        try (Stream<String> lines = future.join()) {
            assertEquals(List.of("line-1", "line-2", "line-3"), lines.toList());
        }
    }

    @Test
    @DisplayName("sync: line streaming decodes charset from Content-Type")
    void lineStreamingDecodesCustomCharset() {
        Charset windows1251 = Charset.forName("windows-1251");
        byte[] payload = "Привет мир\nВторая строка\n".getBytes(windows1251);

        stubFor(get(urlEqualTo("/api/stream/lines"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain; charset=windows-1251")
                        .withBody(payload)));

        try (Stream<String> lines = controller.getLines()) {
            List<String> list = lines.toList();
            assertEquals(List.of("Привет мир", "Вторая строка"), list);
        }
    }

    @Test
    @DisplayName("@OnError status for SSE stream consumes body and throws HandledException")
    void sseOnErrorConsumesBodyAndThrowsHandledException() {
        stubFor(get(urlEqualTo("/v1/chat/error"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"RATE_LIMIT\",\"message\":\"Quota exceeded\"}")));

        LlmChatApi api = client.get(LlmChatApi.class);
        HandledException exception = assertThrows(HandledException.class, api::rateLimitedStream);

        assertEquals(429, exception.getStatusCode());
        TestErrorDto details = exception.getDetails();
        assertNotNull(details);
        assertEquals("RATE_LIMIT", details.code());
        assertEquals("Quota exceeded", details.message());
    }

    @Test
    @DisplayName("@OnError status for line stream consumes body and throws HandledException")
    void lineStreamOnErrorConsumesBodyAndThrowsHandledException() {
        stubFor(get(urlEqualTo("/v1/lines/error"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"BAD_REQUEST\",\"message\":\"Invalid filter\"}")));

        LlmChatApi api = client.get(LlmChatApi.class);
        HandledException exception = assertThrows(HandledException.class, api::badRequestLines);

        assertEquals(400, exception.getStatusCode());
        TestErrorDto details = exception.getDetails();
        assertNotNull(details);
        assertEquals("BAD_REQUEST", details.code());
        assertEquals("Invalid filter", details.message());
    }

    @Test
    @DisplayName("unexpected status for SSE stream throws UnexpectedStatusException with raw payload")
    void sseUnexpectedStatusThrowsException() {
        stubFor(get(urlEqualTo("/api/stream/events"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Bad Gateway: LLM upstream unavailable")));

        UnexpectedStatusException exception = assertThrows(UnexpectedStatusException.class,
                () -> controller.getEvents());

        assertEquals(502, exception.getStatusCode());
        assertEquals("Bad Gateway: LLM upstream unavailable", exception.getRawBody());
    }

    @Test
    @DisplayName("async unexpected status throws CompletionException wrapping UnexpectedStatusException")
    void asyncUnexpectedStatusThrowsException() {
        stubFor(get(urlEqualTo("/api/stream/lines"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Storage offline")));

        CompletableFuture<Stream<String>> future = controller.getLinesAsync();
        CompletionException exception = assertThrows(CompletionException.class, future::join);

        assertTrue(exception.getCause() instanceof UnexpectedStatusException);
        UnexpectedStatusException statusException = (UnexpectedStatusException) exception.getCause();
        assertEquals(500, statusException.getStatusCode());
        assertEquals("Storage offline", statusException.getRawBody());
    }

    @Test
    @DisplayName("server fault results in exception (dispatch failure or unchecked IO during streaming)")
    void serverFaultDuringStream() {
        stubFor(get(urlEqualTo("/api/stream/events"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        try {
            try (Stream<ServerSentEvent> events = controller.getEvents()) {
                assertThrows(UncheckedIOException.class, events::toList);
            }
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof IOException,
                    "Expected IOException in cause, got: " + e.getCause());
        }
    }
}
