package ru.jrestly.codec.jacksonxml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToXml;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end for XML payload binding through the {@code PayloadCodec} SPI (ADR-0060): DTOs
 * serialize to XML and typed responses bind by {@code Content-Type}, while the JSON default
 * stays untouched.
 */
@WireMockTest
@DisplayName("ADR-0060 phase 2: JacksonXmlCodec binds request/response XML end-to-end")
class JacksonXmlWireMockTest {

    private JRestlyClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @JacksonXmlRootElement(localName = "person")
    record Person(String name, int age) {
    }

    interface XmlApi {
        @Post(path = "/person")
        @ContentType(MediaType.APPLICATION_XML)
        String send(@RequestBody Person person);

        @Get(path = "/person")
        @Accept(MediaType.APPLICATION_XML)
        Person fetch();

        @Post(path = "/json-person")
        String sendJson(@RequestBody Person person);
    }

    @Test
    @DisplayName("a DTO with @ContentType(application/xml) goes on the wire as XML, not JSON")
    void dtoRequestIsSentAsXml(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/person"))
                .withHeader("Content-Type", equalTo("application/xml"))
                .withRequestBody(equalToXml("<person><name>Anton</name><age>42</age></person>"))
                .willReturn(ok("OK")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(JacksonXmlCodec.defaults())
                .build();

        assertEquals("OK", client.get(XmlApi.class).send(new Person("Anton", 42)));
        verify(postRequestedFor(urlEqualTo("/person"))
                .withHeader("Content-Type", equalTo("application/xml"))
                .withRequestBody(equalToXml("<person><name>Anton</name><age>42</age></person>")));
    }

    @Test
    @DisplayName("a typed return binds from an application/xml response body")
    void typedResponseBindsFromXml(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/person"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<person><name>Anton</name><age>42</age></person>")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(JacksonXmlCodec.defaults())
                .build();

        assertEquals(new Person("Anton", 42), client.get(XmlApi.class).fetch());
    }

    @Test
    @DisplayName("registering the XML codec keeps the JSON default path byte-for-byte intact")
    void jsonDefaultPathUnaffected(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/json-person"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalTo("{\"name\":\"Anton\",\"age\":42}"))
                .willReturn(ok("OK")));

        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codec(JacksonXmlCodec.defaults())
                .build();

        assertEquals("OK", client.get(XmlApi.class).sendJson(new Person("Anton", 42)));
    }

    @Test
    @DisplayName("the XML codec is auto-detected when the submodule is on the classpath (no codec(...) call)")
    void xmlCodecAutoDetectedWithoutRegistration(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/person"))
                .withHeader("Content-Type", equalTo("application/xml"))
                .withRequestBody(equalToXml("<person><name>Anton</name><age>42</age></person>"))
                .willReturn(ok("OK")));

        // No .codec(...): the JacksonXmlCodecProvider service registration lights XML up by itself.
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .build();

        assertEquals("OK", client.get(XmlApi.class).send(new Person("Anton", 42)));
        verify(postRequestedFor(urlEqualTo("/person"))
                .withRequestBody(equalToXml("<person><name>Anton</name><age>42</age></person>")));
    }

    @Test
    @DisplayName("replacing the registry with JSON-only opts out, so @ContentType(application/xml) fails fast")
    void jsonOnlyRegistryDisablesXmlCodec(WireMockRuntimeInfo wm) {
        client = JRestlyClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .codecs(CodecRegistry.of(JsonCodecResolver.resolve()))
                .build();

        XmlApi api = client.get(XmlApi.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> api.send(new Person("Anton", 42)));
        assertTrue(ex.getMessage().contains("application/xml"),
                "the message must name the offending Content-Type: " + ex.getMessage());
    }
}
