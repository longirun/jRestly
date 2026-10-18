package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.fixtures.TestDto;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GET semantics: {@code @PathVariable} substitution and encoding, {@code @RequestParam}
 * (single, collection, dynamic {@code Map}, prefixed maps) with null/empty omission,
 * {@code @RequestDefaultParam}, and the String/void/Void return shapes (ADR-0004, ADR-0025).
 * WireMock e2e.
 */
class GetMethodTest extends BaseWireMockTest {

    interface DynamicQueryController {
        @Get("/api/search/dynamic")
        String searchWithMap(@RequestParam Map<String, ?> params);

        @Get("/api/search/prefixed")
        String searchWithPrefixedMap(@RequestParam(name = "filter") Map<String, ?> filter);
    }

    private DynamicQueryController dynamicQueryController() {
        return client.get(DynamicQueryController.class);
    }

    @Test
    @DisplayName("GET with @PathVariable substitutes value into path")
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
    @DisplayName("GET with @RequestParam adds parameter to URL")
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
    @DisplayName("GET with multiple @RequestParam")
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
    @DisplayName("GET with @RequestDefaultParam adds default parameters")
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
    @DisplayName("GET returns String as-is")
    void getReturnsString() {
        stubFor(get(urlEqualTo("/api/raw"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("raw text response")));

        String result = controller.getRaw();

        assertEquals("raw text response", result);
    }

    @Test
    @DisplayName("GET with void return does not throw")
    void getReturnsVoid() {
        stubFor(get(urlEqualTo("/api/nothing"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("")));

        controller.getNothing();
    }

    @Test
    @DisplayName("GET with void return and a non-empty body completes (body discarded, ADR-0009)")
    void getReturnsVoidWithNonEmptyBody() {
        stubFor(get(urlEqualTo("/api/nothing"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"a body a void method never decodes\"}")));

        controller.getNothing();
    }

    @Test
    @DisplayName("GET with Void return and a non-empty body returns null (body discarded)")
    void getReturnsVoidBoxedWithNonEmptyBody() {
        stubFor(get(urlEqualTo("/api/nothing"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"a body a Void method never decodes\"}")));

        assertNull(controller.getNothingBoxed());
    }

    @Test
    @DisplayName("GET with collection in @RequestParam creates multiple parameters with the same name")
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
    @DisplayName("GET with @PathVariable URL-encodes special characters")
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

    @Test
    @DisplayName("GET with collection @RequestParam skips null elements")
    void getWithCollectionParamContainingNullsSkipsNullElements() {
        stubFor(get(urlEqualTo("/api/collection?ids=1&ids=2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        List<TestDto> result = controller.getWithCollectionParam(Arrays.asList(1L, null, 2L));

        assertNotNull(result);
    }

    @Test
    @DisplayName("GET with collection @RequestParam containing only nulls omits parameter")
    void getWithCollectionParamOnlyNullsOmitsParam() {
        stubFor(get(urlEqualTo("/api/collection"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        List<TestDto> result = controller.getWithCollectionParam(Collections.singletonList(null));

        assertNotNull(result);
    }

    @Test
    @DisplayName("GET with null collection @RequestParam omits parameter")
    void getWithNullCollectionParamOmitsParam() {
        stubFor(get(urlEqualTo("/api/collection"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        List<TestDto> result = controller.getWithCollectionParam(null);

        assertNotNull(result);
    }

    @Test
    @DisplayName("GET with @RequestParam Map expands entries into individual query parameters")
    void getWithMapRequestParamExpandsIndividualParams() {
        stubFor(get(urlEqualTo("/api/search/dynamic?q=java&page=2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("found")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", "java");
        params.put("page", 2);

        String result = dynamicQueryController().searchWithMap(params);

        assertEquals("found", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/dynamic"))
                .withQueryParam("q", equalTo("java"))
                .withQueryParam("page", equalTo("2")));
    }

    @Test
    @DisplayName("GET with @RequestParam(name) Map prefixes parameters with name[key]")
    void getWithPrefixedMapRequestParam() {
        stubFor(get(urlPathEqualTo("/api/search/prefixed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("prefixed-ok")));

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("status", "active");
        filter.put("role", "admin");

        String result = dynamicQueryController().searchWithPrefixedMap(filter);

        assertEquals("prefixed-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/prefixed"))
                .withQueryParam("filter[status]", equalTo("active"))
                .withQueryParam("filter[role]", equalTo("admin")));
    }

    @Test
    @DisplayName("GET with @RequestParam Map containing Collection expands into repeated parameters")
    void getWithMapContainingCollectionExpandsRepeatedParams() {
        stubFor(get(urlPathEqualTo("/api/search/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("collection-ok")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tags", List.of("jvm", "http"));

        String result = dynamicQueryController().searchWithMap(params);

        assertEquals("collection-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/dynamic"))
                .withQueryParam("tags", equalTo("jvm"))
                .withQueryParam("tags", equalTo("http")));
    }

    @Test
    @DisplayName("GET with @RequestParam(name) Map containing Collection prefixes repeated parameters")
    void getWithPrefixedMapContainingCollectionExpandsRepeatedParams() {
        stubFor(get(urlPathEqualTo("/api/search/prefixed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("prefixed-collection-ok")));

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("tags", List.of("jvm", "http"));

        String result = dynamicQueryController().searchWithPrefixedMap(filter);

        assertEquals("prefixed-collection-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/prefixed"))
                .withQueryParam("filter[tags]", equalTo("jvm"))
                .withQueryParam("filter[tags]", equalTo("http")));
    }

    @Test
    @DisplayName("GET with @RequestParam Map skips null keys, null values, and null collection elements")
    void getWithMapSkipsNullKeysAndValues() {
        stubFor(get(urlPathEqualTo("/api/search/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("clean-ok")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put(null, "skipped");
        params.put("ignored", null);
        params.put("valid", "ok");
        params.put("tags", Arrays.asList("one", null, "two"));

        String result = dynamicQueryController().searchWithMap(params);

        assertEquals("clean-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/dynamic"))
                .withoutQueryParam("null")
                .withoutQueryParam("ignored")
                .withQueryParam("valid", equalTo("ok"))
                .withQueryParam("tags", equalTo("one"))
                .withQueryParam("tags", equalTo("two")));
    }

    @Test
    @DisplayName("GET with null or empty @RequestParam Map omits query string")
    void getWithNullOrEmptyMapOmitsQueryString() {
        stubFor(get(urlEqualTo("/api/search/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("empty-ok")));

        dynamicQueryController().searchWithMap(Map.of());
        dynamicQueryController().searchWithMap(null);

        verify(2, getRequestedFor(urlEqualTo("/api/search/dynamic")));
    }
}
