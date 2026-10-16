package ru.jrestly.codec;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.json.JsonCodecResolver;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
 * End-to-end guard for ServiceLoader payload-codec auto-detection (ADR-0060,
 * phase 3): a codec provider registered on the classpath must be picked up by
 * a client built without an explicit {@code codec(...)}, and an explicit
 * registry replacement must disable discovery.
 */
@WireMockTest
@DisplayName("SPI payload codec auto-detection end-to-end (ADR-0060 phase 3)")
class PayloadCodecAutoDetectionTest {

    private JRestlyClient client;

    @AfterEach
    void tearDown() {
        TestSpiPayloadCodecProvider.AVAILABLE.set(false);
        if (client != null) {
            client.close();
        }
    }

    record Payload(String value) {
    }

    interface SpiApi {
        @Post(path = "/spi")
        @ContentType(TestSpiPayloadCodecProvider.MEDIA)
        String send(@RequestBody Payload payload);
    }

    @Test
    @DisplayName("A default client uses a ServiceLoader-registered codec without any codec(...) call")
    void defaultClientPicksUpSpiCodec(WireMockRuntimeInfo wm) {
        TestSpiPayloadCodecProvider.AVAILABLE.set(true);
        int before = TestSpiPayloadCodecProvider.CREATE_COUNT.get();
        Payload payload = new Payload("Anton");

        stubFor(post(urlEqualTo("/spi"))
                .withHeader("Content-Type", equalTo(TestSpiPayloadCodecProvider.MEDIA))
                .withRequestBody(equalTo("spi:" + payload))
                .willReturn(ok("OK")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .build();

        assertEquals("OK", client.get(SpiApi.class).send(payload));
        verify(postRequestedFor(urlEqualTo("/spi"))
                .withRequestBody(equalTo("spi:" + payload)));
        assertTrue(TestSpiPayloadCodecProvider.CREATE_COUNT.get() > before,
                "the SPI provider must have been asked to create the codec");
    }

    @Test
    @DisplayName("Without an available provider the unregistered media type still fails fast")
    void withoutProviderFailsFast(WireMockRuntimeInfo wm) {
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .build();

        SpiApi api = client.get(SpiApi.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> api.send(new Payload("Anton")));
        assertTrue(ex.getMessage().contains(TestSpiPayloadCodecProvider.MEDIA),
                "the message must name the offending Content-Type: " + ex.getMessage());
    }

    @Test
    @DisplayName("Replacing the whole registry opts out of auto-discovery")
    void explicitRegistryDisablesDiscovery(WireMockRuntimeInfo wm) {
        TestSpiPayloadCodecProvider.AVAILABLE.set(true);

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codecs(CodecRegistry.of(JsonCodecResolver.resolve()))
                .build();

        SpiApi api = client.get(SpiApi.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> api.send(new Payload("Anton")));
        assertTrue(ex.getMessage().contains(TestSpiPayloadCodecProvider.MEDIA),
                "an explicit registry replacement must not be enriched by discovery: " + ex.getMessage());
    }
}
