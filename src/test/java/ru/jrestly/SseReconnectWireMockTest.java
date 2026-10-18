package ru.jrestly;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.http.ReconnectInfo;
import ru.jrestly.http.ReconnectPolicy;
import ru.jrestly.http.ServerSentEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end test for {@link ServerSentEvent#reconnecting} over real HTTP
 * (ADR-0059, phase 1): a cleanly closed SSE connection is resumed with
 * {@code Last-Event-ID}, and the server {@code retry:} field drives the wait.
 */
class SseReconnectWireMockTest extends BaseWireMockTest {

    interface ReconnectChatApi {
        @Post("/v1/reconnect/chat")
        Stream<ServerSentEvent> chat(
                @RequestHeader(name = "Last-Event-ID") String lastEventId,
                @RequestBody Map<String, Object> request);
    }

    @Test
    @DisplayName("e2e: dropped SSE stream resumes with Last-Event-ID and honors the server retry field")
    void resumesStreamWithLastEventId() {
        String droppedConnection = """
                retry: 50

                id: 1
                data: {"token":"A"}

                id: 2
                data: {"token":"B"}

                """;

        stubFor(post(urlEqualTo("/v1/reconnect/chat"))
                .inScenario("sse-reconnect")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(droppedConnection))
                .willSetStateTo("resumed"));

        stubFor(post(urlEqualTo("/v1/reconnect/chat"))
                .inScenario("sse-reconnect")
                .whenScenarioStateIs("resumed")
                .withHeader("Last-Event-ID", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: [DONE]\n\n")));

        ReconnectChatApi api = client.get(ReconnectChatApi.class);
        List<String> openedWith = new ArrayList<>();
        List<ReconnectInfo> infos = new ArrayList<>();

        Function<String, Stream<ServerSentEvent>> opener = lastEventId -> {
            openedWith.add(lastEventId);
            return api.chat(lastEventId, Map.of("stream", true));
        };
        ReconnectPolicy policy = ReconnectPolicy.builder().maxAttempts(2).onReconnect(infos::add).build();

        List<ServerSentEvent> events;
        try (Stream<ServerSentEvent> stream = ServerSentEvent.reconnecting(opener, policy)) {
            events = stream.toList();
        }

        assertEquals(3, events.size());
        assertEquals("1", events.get(0).id());
        assertEquals("{\"token\":\"A\"}", events.get(0).data());
        assertEquals("2", events.get(1).id());
        assertEquals("{\"token\":\"B\"}", events.get(1).data());
        assertEquals("[DONE]", events.get(2).data());

        // The reopened request carried the id of the last event of the dropped stream.
        assertEquals(Arrays.asList(null, "2"), openedWith);

        assertEquals(1, infos.size());
        ReconnectInfo info = infos.getFirst();
        assertEquals(1, info.attempt());
        assertEquals("2", info.lastEventId());
        assertEquals(Duration.ofMillis(50), info.delay());
        assertNull(info.cause());

        List<LoggedRequest> requests = wireMockServer.findAll(postRequestedFor(urlEqualTo("/v1/reconnect/chat")));
        assertEquals(2, requests.size());
        assertFalse(requests.get(0).containsHeader("Last-Event-ID"));
        assertEquals("2", requests.get(1).getHeader("Last-Event-ID"));
    }
}
