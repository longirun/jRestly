package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestDto;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class GetMethodTest extends BaseWireMockTest {

    @Test
    @DisplayName("GET с @PathVariable подставляет значение в путь")
    void getWithPathVariable() {
        stubFor(get(urlEqualTo("/api/items/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"name\":\"Test Item\",\"description\":\"desc\"}")));

        TestDto result = controller.getItem(42L);

        assertNotNull(result);
        assertEquals(42L, result.id());
        assertEquals("Test Item", result.name());
        assertEquals("desc", result.description());
    }

    @Test
    @DisplayName("GET с @RequestParam добавляет параметр в URL")
    void getWithRequestParam() {
        stubFor(get(urlEqualTo("/api/items?page=5"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        List<TestDto> result = controller.getItems(5);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("GET с несколькими @RequestParam")
    void getWithMultipleParams() {
        stubFor(get(urlEqualTo("/api/search?q=test&limit=10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":1,\"name\":\"test1\",\"description\":\"d1\"}]")));

        List<TestDto> result = controller.searchItems("test", 10);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("test1", result.get(0).name());
    }

    @Test
    @DisplayName("GET с @RequestDefaultParam добавляет дефолтные параметры")
    void getWithDefaultParams() {
        stubFor(get(urlEqualTo("/api/items?size=10&page=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        List<TestDto> result = controller.getItemsWithDefaults(1);

        assertNotNull(result);
    }

    @Test
    @DisplayName("GET возвращает String как есть")
    void getReturnsString() {
        stubFor(get(urlEqualTo("/api/raw"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("raw text response")));

        String result = controller.getRaw();

        assertEquals("raw text response", result);
    }

    @Test
    @DisplayName("GET с void возвратом не падает")
    void getReturnsVoid() {
        stubFor(get(urlEqualTo("/api/nothing"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("")));

        controller.getNothing();
    }

    @Test
    @DisplayName("GET с коллекцией в @RequestParam создаёт несколько одноимённых параметров")
    void getWithCollectionParam() {
        stubFor(get(urlEqualTo("/api/collection?ids=1&ids=2&ids=3"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        List<TestDto> result = controller.getWithCollectionParam(List.of(1L, 2L, 3L));

        assertNotNull(result);
    }

    @Test
    @DisplayName("GET с @PathVariable кодирует спецсимволы в URL")
    void getWithEncodedPathVariable() {
        stubFor(get(urlEqualTo("/api/items/hello%20world"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"name\":\"encoded\",\"description\":\"desc\"}")));

        TestDto result = controller.getItemBySlug("hello world");

        assertNotNull(result);
        assertEquals("encoded", result.name());
    }
}
