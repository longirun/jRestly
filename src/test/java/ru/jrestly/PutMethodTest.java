package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PutMethodTest extends BaseWireMockTest {

    @Test
    @DisplayName("PUT с @RequestBody отправляет JSON и десериализует ответ")
    void putWithJsonBody() {
        stubFor(put(urlEqualTo("/api/items/7"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"name\":\"replaced\",\"description\":\"full update\"}")));

        TestDto body = new TestDto(7L, "replaced", "full update");
        TestDto result = controller.putItem(7L, body);

        assertNotNull(result);
        assertEquals(7L, result.id());
        assertEquals("replaced", result.name());

        verify(putRequestedFor(urlEqualTo("/api/items/7"))
                .withHeader("Content-Type", containing("application/json")));
    }
}
