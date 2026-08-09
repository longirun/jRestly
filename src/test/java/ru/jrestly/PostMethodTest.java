package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PostMethodTest extends BaseWireMockTest {

    @Test
    @DisplayName("POST with @RequestBody sends JSON and deserializes response")
    void postWithJsonBody() {
        stubFor(post(urlEqualTo("/api/items"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"created\",\"description\":\"new item\"}")));

        TestDto body = new TestDto(null, "created", "new item");
        TestDto result = controller.createItem(body);

        assertNotNull(result);
        assertEquals(1L, result.id());
        assertEquals("created", result.name());

        verify(postRequestedFor(urlEqualTo("/api/items"))
                .withHeader("Content-Type", containing("application/json")));
    }

    @Test
    @DisplayName("POST with form-urlencoded sends parameters in body")
    void postFormUrlEncoded() {
        stubFor(post(urlEqualTo("/api/form"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        String result = controller.submitForm("value1", "value2");

        assertEquals("ok", result);

        verify(postRequestedFor(urlEqualTo("/api/form"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("field1=value1"))
                .withRequestBody(containing("field2=value2")));
    }
}
