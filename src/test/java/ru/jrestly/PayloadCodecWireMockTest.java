package ru.jrestly;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.codec.PayloadCodec;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end for the media-type-aware codec SPI (ADR-0060): a custom zero-dependency
 * {@code PayloadCodec} binds request and response payloads by {@code Content-Type}, with
 * the JSON default preserved for untouched media types.
 */
@WireMockTest
@DisplayName("ADR-0060: a custom PayloadCodec binds request/response by Content-Type (zero-dep SPI)")
class PayloadCodecWireMockTest {

    private JRestlyClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    record Person(String name, int age) {
    }

    /** Zero-dependency codec for {@code application/x-pipe} ("name|age"). */
    static final class PipeCodec implements PayloadCodec {
        static final String MEDIA = "application/x-pipe";

        @Override
        public boolean supports(String mediaType) {
            return MEDIA.equals(mediaType);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(byte[] payload, Type type, String mediaType) {
            if (payload == null || payload.length == 0) {
                return null;
            }
            if (type == Person.class) {
                String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|", 2);
                return (T) new Person(parts[0], Integer.parseInt(parts[1]));
            }
            throw new IllegalStateException("PipeCodec cannot deserialize " + type);
        }

        @Override
        public byte[] serialize(Object value, String mediaType) {
            if (value instanceof Person person) {
                return (person.name() + "|" + person.age()).getBytes(StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("PipeCodec cannot serialize " + value);
        }
    }

    interface PipeApi {
        @Post("/person")
        @ContentType(PipeCodec.MEDIA)
        String send(@RequestBody Person person);

        @Get("/person")
        @Accept(PipeCodec.MEDIA)
        Person fetch();

        @Post("/unregistered")
        @ContentType("application/x-unregistered")
        String sendUnregistered(@RequestBody Person person);

        @Post("/json-person")
        String sendJson(@RequestBody Person person);
    }

    @Test
    @DisplayName("DTO request is serialized by the codec matching the effective Content-Type")
    void customCodecSerializesRequestByContentType(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/person"))
                .withHeader("Content-Type", equalTo(PipeCodec.MEDIA))
                .withRequestBody(equalTo("Anton|42"))
                .willReturn(ok("OK")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(new PipeCodec())
                .build();

        assertEquals("OK", client.get(PipeApi.class).send(new Person("Anton", 42)));
        verify(postRequestedFor(urlEqualTo("/person"))
                .withHeader("Content-Type", equalTo(PipeCodec.MEDIA))
                .withRequestBody(equalTo("Anton|42")));
    }

    @Test
    @DisplayName("Typed response is bound by the codec matching the response Content-Type")
    void customCodecDeserializesResponseByContentType(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/person"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", PipeCodec.MEDIA)
                        .withBody("Anton|42")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(new PipeCodec())
                .build();

        assertEquals(new Person("Anton", 42), client.get(PipeApi.class).fetch());
    }

    @Test
    @DisplayName("An unregistered request Content-Type fails fast instead of silently sending JSON under a foreign header")
    void unregisteredMediaTypeFailsFast(WireMockRuntimeInfo wm) {
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(new PipeCodec())
                .build();

        PipeApi api = client.get(PipeApi.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> api.sendUnregistered(new Person("Anton", 42)));
        assertTrue(ex.getMessage().contains("application/x-unregistered"),
                "the message must name the offending Content-Type: " + ex.getMessage());
    }

    @Test
    @DisplayName("Registering a custom codec does not disturb the JSON default path")
    void jsonPathUnaffectedByCustomCodec(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/json-person"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalTo("{\"name\":\"Anton\",\"age\":42}"))
                .willReturn(ok("OK")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(new PipeCodec())
                .build();

        assertEquals("OK", client.get(PipeApi.class).sendJson(new Person("Anton", 42)));
    }
}
