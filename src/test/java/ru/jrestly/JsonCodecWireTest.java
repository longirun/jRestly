package ru.jrestly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.DateTimeController;
import ru.jrestly.fixtures.EventDto;
import ru.jrestly.fixtures.NullableFieldController;
import ru.jrestly.fixtures.NullableFieldDto;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.json.JacksonCodec;

import java.time.LocalDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonCodecWireTest extends BaseWireMockTest {

    @Test
    @DisplayName("default codec sends compact JSON on the wire (ADR-0001: no pretty-printing)")
    void defaultCodecSendsCompactJsonOnWire() {
        stubFor(post(urlEqualTo("/api/items"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"value\",\"description\":\"compact wire\"}")));

        TestDto result = controller.createItem(new TestDto(1L, "value", "compact wire"));

        assertNotNull(result);
        assertEquals(1L, result.id());

        // Exact string match (not equalToJson which normalizes whitespace):
        // any newline or space added by pretty-printing fails here
        verify(postRequestedFor(urlEqualTo("/api/items"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalTo("{\"id\":1,\"name\":\"value\",\"description\":\"compact wire\"}")));
    }

    @Test
    @DisplayName("null field is serialized on the wire (default JsonInclude.ALWAYS, no config line needed)")
    void nullFieldIsSerializedOnWire() {
        stubFor(post(urlEqualTo("/api/nullable"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":null}")));

        NullableFieldController nullableController = client.get(NullableFieldController.class);
        NullableFieldDto result = nullableController.createNullable(new NullableFieldDto(1L, null));

        assertNotNull(result);
        assertEquals(1L, result.id());

        // Exact string match: the "name":null entry must be physically present in the
        // compact wire body. This is the contract formerly pinned by the (no-op)
        // setSerializationInclusion(ALWAYS) line in JacksonCodec.createDefault():
        // a NON_NULL inclusion policy would silently drop the field and fail here.
        verify(postRequestedFor(urlEqualTo("/api/nullable"))
                .withRequestBody(equalTo("{\"id\":1,\"name\":null}")));
    }

    @Test
    @DisplayName("LocalDateTime round-trip uses yyyy-MM-dd HH:mm:ss pattern on the wire")
    void localDateTimeRoundTripUsesConfiguredPattern() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 24, 12, 34, 56);

        stubFor(post(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"createdAt\":\"2026-08-24 12:34:56\"}")));

        DateTimeController dateTimeController = client.get(DateTimeController.class);
        EventDto result = dateTimeController.createEvent(new EventDto(7L, createdAt));

        // Response direction: the pattern is deserialized back into the original value
        assertNotNull(result);
        assertEquals(7L, result.id());
        assertEquals(createdAt, result.createdAt());

        // Request direction: exact match proves the yyyy-MM-dd HH:mm:ss pattern on the wire
        verify(postRequestedFor(urlEqualTo("/api/events"))
                .withRequestBody(equalTo("{\"id\":7,\"createdAt\":\"2026-08-24 12:34:56\"}")));
    }

    @Test
    @DisplayName("custom JacksonCodec reaches the wire: snake_case mapper renames camelCase fields")
    void customCodecReachesWireWithSnakeCase() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 24, 12, 34, 56);

        stubFor(post(urlEqualTo("/api/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":9,\"created_at\":\"2026-08-24T12:34:56\"}")));

        // Migration path for users of the former objectMapper(...) builder method
        ObjectMapper snakeCaseMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .jsonCodec(new JacksonCodec(snakeCaseMapper))
                .build();
        try {
            DateTimeController dateTimeController = customClient.get(DateTimeController.class);
            EventDto result = dateTimeController.createEvent(new EventDto(9L, createdAt));

            // Custom codec used for deserialization too: snake_case response maps back to the DTO
            assertNotNull(result);
            assertEquals(9L, result.id());
            assertEquals(createdAt, result.createdAt());

            // Custom codec used for serialization: createdAt becomes created_at on the wire
            verify(postRequestedFor(urlEqualTo("/api/events"))
                    .withRequestBody(equalTo("{\"id\":9,\"created_at\":\"2026-08-24T12:34:56\"}")));
        } finally {
            customClient.close();
        }
    }
}
