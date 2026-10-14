package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.http.RequestType;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ArrayParameterWireMockTest extends BaseWireMockTest {

    interface ArrayQueryController {
        @Get(path = "/api/items")
        String getByTags(@RequestParam(name = "tag") String[] tags);

        @Get(path = "/api/items/by-ids")
        String getByIds(@RequestParam(name = "id") int[] ids);

        @Get(path = "/api/items/filter")
        String getByFilterMap(@RequestParam Map<String, Object> filter);

        @Get(path = "/api/items#top")
        String getWithFragmentAndParam(@RequestParam(name = "page") int page);
    }

    interface ArrayHeaderController {
        @Get(path = "/api/headers/array")
        String getWithHeaderArray(@RequestHeader(name = "X-Role") String[] roles);

        @Get(path = "/api/headers/dynamic-array")
        String getWithHeaderMap(@RequestHeader Map<String, Object> headers);
    }

    interface ArrayFormController {
        @Post(path = "/api/form/array", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String postForm(
                @RequestParam(name = "category") String[] categories,
                @RequestParam(name = "flag") int[] flags
        );
    }

    private ArrayQueryController queryController() {
        return client.get(ArrayQueryController.class);
    }

    private ArrayHeaderController headerController() {
        return client.get(ArrayHeaderController.class);
    }

    private ArrayFormController formController() {
        return client.get(ArrayFormController.class);
    }

    @Test
    @DisplayName("String[] query parameter unpacks into repeated query parameters")
    void stringArrayQueryParameter() {
        stubFor(get(urlPathEqualTo("/api/items"))
                .withQueryParam("tag", equalTo("electronics"))
                .withQueryParam("tag", equalTo("gadgets"))
                .willReturn(aResponse().withStatus(200).withBody("tags-ok")));

        String result = queryController().getByTags(new String[]{"electronics", "gadgets"});

        assertEquals("tags-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/items"))
                .withQueryParam("tag", equalTo("electronics"))
                .withQueryParam("tag", equalTo("gadgets")));
    }

    @Test
    @DisplayName("primitive int[] query parameter unpacks into repeated query parameters")
    void primitiveIntArrayQueryParameter() {
        stubFor(get(urlPathEqualTo("/api/items/by-ids"))
                .withQueryParam("id", equalTo("101"))
                .withQueryParam("id", equalTo("202"))
                .willReturn(aResponse().withStatus(200).withBody("ids-ok")));

        String result = queryController().getByIds(new int[]{101, 202});

        assertEquals("ids-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/items/by-ids"))
                .withQueryParam("id", equalTo("101"))
                .withQueryParam("id", equalTo("202")));
    }

    @Test
    @DisplayName("empty array query parameter is omitted entirely from the query string")
    void emptyArrayQueryParameterIsOmitted() {
        stubFor(get(urlEqualTo("/api/items"))
                .willReturn(aResponse().withStatus(200).withBody("empty-array-ok")));

        String result = queryController().getByTags(new String[0]);

        assertEquals("empty-array-ok", result);
        verify(getRequestedFor(urlEqualTo("/api/items"))
                .withoutQueryParam("tag"));
    }

    @Test
    @DisplayName("array query parameter with null elements skips nulls and sends non-null elements")
    void arrayQueryParameterSkipsNullElements() {
        stubFor(get(urlPathEqualTo("/api/items"))
                .withQueryParam("tag", equalTo("books"))
                .withQueryParam("tag", equalTo("music"))
                .willReturn(aResponse().withStatus(200).withBody("skip-nulls-ok")));

        String result = queryController().getByTags(new String[]{"books", null, "music"});

        assertEquals("skip-nulls-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/items"))
                .withQueryParam("tag", equalTo("books"))
                .withQueryParam("tag", equalTo("music")));
    }

    @Test
    @DisplayName("Map query parameter with array value unpacks into repeated query parameters")
    void mapQueryParameterWithArrayValue() {
        stubFor(get(urlPathEqualTo("/api/items/filter"))
                .withQueryParam("status", equalTo("ACTIVE"))
                .withQueryParam("status", equalTo("PENDING"))
                .willReturn(aResponse().withStatus(200).withBody("map-array-ok")));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", new String[]{"ACTIVE", "PENDING"});

        String result = queryController().getByFilterMap(map);

        assertEquals("map-array-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/items/filter"))
                .withQueryParam("status", equalTo("ACTIVE"))
                .withQueryParam("status", equalTo("PENDING")));
    }

    @Test
    @DisplayName("String[] header unpacks into multiple headers with the same name")
    void stringArrayHeader() {
        stubFor(get(urlEqualTo("/api/headers/array"))
                .withHeader("X-Role", equalTo("admin"))
                .withHeader("X-Role", equalTo("editor"))
                .willReturn(aResponse().withStatus(200).withBody("header-array-ok")));

        String result = headerController().getWithHeaderArray(new String[]{"admin", "editor"});

        assertEquals("header-array-ok", result);
        verify(getRequestedFor(urlEqualTo("/api/headers/array"))
                .withHeader("X-Role", equalTo("admin"))
                .withHeader("X-Role", equalTo("editor")));
    }

    @Test
    @DisplayName("empty array header is omitted entirely from request headers")
    void emptyArrayHeaderIsOmitted() {
        stubFor(get(urlEqualTo("/api/headers/array"))
                .willReturn(aResponse().withStatus(200).withBody("empty-header-array-ok")));

        String result = headerController().getWithHeaderArray(new String[0]);

        assertEquals("empty-header-array-ok", result);
        verify(getRequestedFor(urlEqualTo("/api/headers/array"))
                .withoutHeader("X-Role"));
    }

    @Test
    @DisplayName("Map header with array value unpacks into multiple request headers")
    void mapHeaderWithArrayValue() {
        stubFor(get(urlEqualTo("/api/headers/dynamic-array"))
                .withHeader("X-Custom", equalTo("one"))
                .withHeader("X-Custom", equalTo("two"))
                .willReturn(aResponse().withStatus(200).withBody("map-header-ok")));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("X-Custom", new String[]{"one", "two"});

        String result = headerController().getWithHeaderMap(map);

        assertEquals("map-header-ok", result);
        verify(getRequestedFor(urlEqualTo("/api/headers/dynamic-array"))
                .withHeader("X-Custom", equalTo("one"))
                .withHeader("X-Custom", equalTo("two")));
    }

    @Test
    @DisplayName("Form urlencoded request serializes String[] and primitive arrays into body")
    void formUrlencodedWithArrays() {
        stubFor(post(urlEqualTo("/api/form/array"))
                .withRequestBody(equalTo("category=tech&category=news&flag=1&flag=2"))
                .willReturn(aResponse().withStatus(200).withBody("form-array-ok")));

        String result = formController().postForm(
                new String[]{"tech", "news"},
                new int[]{1, 2}
        );

        assertEquals("form-array-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/form/array"))
                .withRequestBody(equalTo("category=tech&category=news&flag=1&flag=2")));
    }

    @Test
    @DisplayName("Endpoint path with #fragment correctly appends query parameter before fragment")
    void endpointPathWithFragmentAndQuery() {
        // WireMock receives the request on /api/items?page=1 (fragment is not sent over the wire by HTTP client)
        stubFor(get(urlEqualTo("/api/items?page=1"))
                .willReturn(aResponse().withStatus(200).withBody("fragment-query-ok")));

        String result = queryController().getWithFragmentAndParam(1);

        assertEquals("fragment-query-ok", result);
        verify(getRequestedFor(urlEqualTo("/api/items?page=1")));
    }
}
