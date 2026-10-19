package ru.jrestly.codec.yaml;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
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
 * End-to-end for YAML payload binding through the {@code PayloadCodec} SPI (ADR-0073): DTOs
 * serialize to YAML and typed responses bind by {@code Content-Type}, while the JSON default
 * stays untouched.
 */
@WireMockTest
@DisplayName("ADR-0073: YamlCodec binds request/response YAML end-to-end")
class YamlWireMockTest {

    private JRestlyClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    record Person(String name, int age) {
    }

    interface YamlApi {
        @Post("/person")
        @ContentType(MediaType.APPLICATION_YAML)
        String send(@RequestBody Person person);

        @Get("/person")
        @Accept(MediaType.APPLICATION_YAML)
        Person fetch();

        @Post("/json-person")
        String sendJson(@RequestBody Person person);
    }

    /** The exact wire bytes the codec contractually produces for the fixture DTO. */
    private static String personYaml() {
        return new String(YamlCodec.defaults().serialize(new Person("Anton", 42), "application/yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a DTO with @ContentType(application/yaml) goes on the wire as YAML, not JSON")
    void dtoRequestIsSentAsYaml(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/person"))
                .withHeader("Content-Type", equalTo("application/yaml"))
                .withRequestBody(equalTo(personYaml()))
                .willReturn(ok("OK")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(YamlCodec.defaults())
                .build();

        assertEquals("OK", client.get(YamlApi.class).send(new Person("Anton", 42)));
        verify(postRequestedFor(urlEqualTo("/person"))
                .withHeader("Content-Type", equalTo("application/yaml"))
                .withRequestBody(equalTo(personYaml())));
    }

    @Test
    @DisplayName("a typed return binds from an application/yaml response body")
    void typedResponseBindsFromYaml(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/person"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/yaml")
                        .withBody("name: Anton\nage: 42")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(YamlCodec.defaults())
                .build();

        assertEquals(new Person("Anton", 42), client.get(YamlApi.class).fetch());
    }

    @Test
    @DisplayName("registering the YAML codec keeps the JSON default path byte-for-byte intact")
    void jsonDefaultPathUnaffected(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/json-person"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalTo("{\"name\":\"Anton\",\"age\":42}"))
                .willReturn(ok("OK")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(YamlCodec.defaults())
                .build();

        assertEquals("OK", client.get(YamlApi.class).sendJson(new Person("Anton", 42)));
    }

    @Test
    @DisplayName("the YAML codec is auto-detected when the submodule is on the classpath (no codec(...) call)")
    void yamlCodecAutoDetectedWithoutRegistration(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/person"))
                .withHeader("Content-Type", equalTo("application/yaml"))
                .withRequestBody(equalTo(personYaml()))
                .willReturn(ok("OK")));

        // No .codec(...): the YamlCodecProvider service registration lights YAML up by itself.
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .build();

        assertEquals("OK", client.get(YamlApi.class).send(new Person("Anton", 42)));
        verify(postRequestedFor(urlEqualTo("/person"))
                .withRequestBody(equalTo(personYaml())));
    }

    @Test
    @DisplayName("replacing the registry with JSON-only opts out, so @ContentType(application/yaml) fails fast")
    void jsonOnlyRegistryDisablesYamlCodec(WireMockRuntimeInfo wm) {
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codecs(CodecRegistry.of(JsonCodecResolver.resolve()))
                .build();

        YamlApi api = client.get(YamlApi.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> api.send(new Person("Anton", 42)));
        assertTrue(ex.getMessage().contains("application/yaml"),
                "the message must name the offending Content-Type: " + ex.getMessage());
    }
}
