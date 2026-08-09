package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PatchMethodTest extends BaseWireMockTest {

    @Test
    @DisplayName("PATCH with @RequestBody sends JSON and deserializes response")
    void patchWithJsonBody() {
        stubFor(patch(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"name\":\"patched\",\"description\":\"updated\"}")));

        TestDto body = new TestDto(null, "patched", "updated");
        TestDto result = controller.patchItem(42L, body);

        assertNotNull(result);
        assertEquals(42L, result.id());
        assertEquals("patched", result.name());

        verify(patchRequestedFor(urlEqualTo("/api/items/42"))
                .withHeader("Content-Type", containing("application/json")));
    }

    @Test
    @DisplayName("PATCH with form-urlencoded sends parameters in body")
    void patchFormUrlEncoded() {
        stubFor(patch(urlEqualTo("/api/items/99"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        String result = controller.patchItemForm(99L, "new-name");

        assertEquals("ok", result);

        verify(patchRequestedFor(urlEqualTo("/api/items/99"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("name=new-name")));
    }
}
