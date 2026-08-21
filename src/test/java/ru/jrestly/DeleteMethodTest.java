package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class DeleteMethodTest extends BaseWireMockTest {

    @Test
    @DisplayName("DELETE with @PathVariable")
    void deleteWithPathVariable() {
        stubFor(delete(urlEqualTo("/api/items/99"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("")));

        controller.deleteItem(99L);

        verify(deleteRequestedFor(urlEqualTo("/api/items/99")));
    }

    @Test
    @DisplayName("DELETE with @RequestBody sends JSON in body")
    void deleteWithBody() {
        stubFor(delete(urlEqualTo("/api/items"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("")));

        TestDto criteria = new TestDto(1L, "test", null);
        controller.deleteItems(criteria);

        verify(deleteRequestedFor(urlEqualTo("/api/items"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("test")));
    }

    @Test
    @DisplayName("DELETE with form-urlencoded sends parameters in body")
    void deleteFormUrlEncoded() {
        stubFor(delete(urlEqualTo("/api/items"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("")));

        controller.deleteItemsForm("obsolete");

        verify(deleteRequestedFor(urlEqualTo("/api/items"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("reason=obsolete")));
    }
}
