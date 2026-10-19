package ru.jrestly.codec.protobuf;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.codec.CodecRegistry;
import ru.jrestly.http.MediaType;
import ru.jrestly.json.JsonCodecResolver;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end for Protocol Buffers payload binding through the {@code PayloadCodec}
 * SPI (ADR-0074): generated {@code Message} DTOs serialize to protobuf wire bytes
 * and typed responses bind by {@code Content-Type}, while the JSON default stays
 * untouched.
 */
@WireMockTest
@DisplayName("ADR-0074: ProtobufCodec binds request/response protobuf end-to-end")
class ProtobufWireMockTest {

    private JRestlyClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    record Person(String name, int age) {
    }

    interface ProtoApi {
        @Post("/stamp")
        @ContentType(MediaType.APPLICATION_X_PROTOBUF)
        String send(@RequestBody Timestamp stamp);

        @Get("/stamp")
        @Accept(MediaType.APPLICATION_PROTOBUF)
        Timestamp fetch();

        @Post("/json-person")
        String sendJson(@RequestBody Person person);
    }

    private static Timestamp fixtureStamp() {
        return Timestamp.newBuilder()
                .setSeconds(1_700_000_000L)
                .setNanos(123_456_789)
                .build();
    }

    /** The exact wire bytes the codec contractually produces for the fixture message. */
    private static byte[] stampWire() {
        return new ProtobufCodec().serialize(fixtureStamp(), "application/x-protobuf");
    }

    @Test
    @DisplayName("a Message with @ContentType(application/x-protobuf) goes on the wire as protobuf, not JSON")
    void dtoRequestIsSentAsProtobuf(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/stamp"))
                .withHeader("Content-Type", equalTo("application/x-protobuf"))
                .withRequestBody(binaryEqualTo(stampWire()))
                .willReturn(ok("OK")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(new ProtobufCodec())
                .build();

        assertEquals("OK", client.get(ProtoApi.class).send(fixtureStamp()));
        verify(postRequestedFor(urlEqualTo("/stamp"))
                .withHeader("Content-Type", equalTo("application/x-protobuf"))
                .withRequestBody(binaryEqualTo(stampWire())));
    }

    @Test
    @DisplayName("a generated Message return type binds from an application/protobuf response body")
    void typedResponseBindsFromProtobuf(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/stamp"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/protobuf")
                        .withBody(stampWire())));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(new ProtobufCodec())
                .build();

        assertEquals(fixtureStamp(), client.get(ProtoApi.class).fetch());
    }

    @Test
    @DisplayName("registering the protobuf codec keeps the JSON default path byte-for-byte intact")
    void jsonDefaultPathUnaffected(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/json-person"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalTo("{\"name\":\"Anton\",\"age\":42}"))
                .willReturn(ok("OK")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(new ProtobufCodec())
                .build();

        assertEquals("OK", client.get(ProtoApi.class).sendJson(new Person("Anton", 42)));
    }

    @Test
    @DisplayName("the protobuf codec is auto-detected when the submodule is on the classpath (no codec(...) call)")
    void protobufCodecAutoDetectedWithoutRegistration(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/stamp"))
                .withHeader("Content-Type", equalTo("application/x-protobuf"))
                .withRequestBody(binaryEqualTo(stampWire()))
                .willReturn(ok("OK")));

        // No .codec(...): the ProtobufCodecProvider service registration lights protobuf up by itself.
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .build();

        assertEquals("OK", client.get(ProtoApi.class).send(fixtureStamp()));
        verify(postRequestedFor(urlEqualTo("/stamp"))
                .withRequestBody(binaryEqualTo(stampWire())));
    }

    @Test
    @DisplayName("replacing the registry with JSON-only opts out, so @ContentType(application/x-protobuf) fails fast")
    void jsonOnlyRegistryDisablesProtobufCodec(WireMockRuntimeInfo wm) {
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codecs(CodecRegistry.of(JsonCodecResolver.resolve()))
                .build();

        ProtoApi api = client.get(ProtoApi.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> api.send(fixtureStamp()));
        assertTrue(ex.getMessage().contains("application/x-protobuf"),
                "the message must name the offending Content-Type: " + ex.getMessage());
    }
}
