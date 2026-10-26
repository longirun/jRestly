package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.http.RequestType;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestParameterFlexibilityTest extends BaseWireMockTest {

    interface DynamicQueryApi {
        @Get(path = "/api/search/dynamic")
        String searchWithMap(@RequestParam Map<String, Object> params);

        @Get(path = "/api/search/prefixed")
        String searchWithPrefixedMap(@RequestParam(name = "filter") Map<String, Object> filter);

        @Get(path = "/api/search/numeric")
        String searchWithNumericKeys(@RequestParam Map<Integer, String> params);

        @Get(path = "/api/search/array")
        String searchWithArray(@RequestParam(name = "tag") String[] tags);

        @Get(path = "/api/search/dynamic")
        String searchWithByteArray(@RequestParam(name = "blob") byte[] blob);
    }

    interface DynamicHeaderApi {
        @Get(path = "/api/headers/dynamic")
        String headersWithMap(@RequestHeader(name = "X-Ignored") Map<String, Object> headers);

        @Get(path = "/api/headers/dynamic")
        String headerWithByteArray(@RequestHeader(name = "X-Blob") byte[] blob);
    }

    interface DynamicFormApi {
        @Post(path = "/api/token", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String tokenWithMap(@RequestParam Map<String, Object> params);
    }

    interface DynamicMultipartApi {
        @Post(path = "/api/upload/meta", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadMeta(@RequestParam Map<String, Object> meta);

        @Post(path = "/api/upload/meta", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadNamedMeta(@RequestParam(name = "meta") Map<String, Object> meta);

        @Post(path = "/api/upload/meta", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadBlob(@RequestParam(name = "blob") byte[] blob);
    }

    private DynamicQueryApi queryApi() {
        return client.get(DynamicQueryApi.class);
    }

    private DynamicHeaderApi headerApi() {
        return client.get(DynamicHeaderApi.class);
    }

    private DynamicFormApi formApi() {
        return client.get(DynamicFormApi.class);
    }

    private DynamicMultipartApi multipartApi() {
        return client.get(DynamicMultipartApi.class);
    }

    @Test
    @DisplayName("@RequestParam Map expands entries into individual query parameters with flat keys")
    void mapExpandsIntoFlatQueryParams() {
        stubFor(get(urlPathEqualTo("/api/search/dynamic"))
                .willReturn(aResponse().withStatus(200).withBody("found")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", "java");
        params.put("page", 2);

        String result = queryApi().searchWithMap(params);

        assertEquals("found", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/dynamic"))
                .withQueryParam("q", equalTo("java"))
                .withQueryParam("page", equalTo("2")));
    }

    @Test
    @DisplayName("@RequestParam(name) Map prefixes each key with name[key]")
    void namedMapPrefixesKeys() {
        stubFor(get(urlPathEqualTo("/api/search/prefixed"))
                .willReturn(aResponse().withStatus(200).withBody("prefixed-ok")));

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("status", "active");
        filter.put("role", "admin");

        String result = queryApi().searchWithPrefixedMap(filter);

        assertEquals("prefixed-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/prefixed"))
                .withQueryParam("filter[status]", equalTo("active"))
                .withQueryParam("filter[role]", equalTo("admin")));
    }

    @Test
    @DisplayName("@RequestParam Map with Collection value expands into repeated query parameters")
    void mapWithCollectionValueExpandsRepeatedParams() {
        stubFor(get(urlPathEqualTo("/api/search/prefixed"))
                .willReturn(aResponse().withStatus(200).withBody("collection-ok")));

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("tags", List.of("jvm", "http"));

        String result = queryApi().searchWithPrefixedMap(filter);

        assertEquals("collection-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/prefixed"))
                .withQueryParam("filter[tags]", equalTo("jvm"))
                .withQueryParam("filter[tags]", equalTo("http")));
    }

    @Test
    @DisplayName("@RequestParam Map skips null keys, null values, and null collection elements")
    void mapSkipsNullKeysValuesAndElements() {
        stubFor(get(urlPathEqualTo("/api/search/dynamic"))
                .willReturn(aResponse().withStatus(200).withBody("clean-ok")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put(null, "skipped");
        params.put("ignored", null);
        params.put("valid", "ok");
        params.put("tags", Arrays.asList("one", null, "two"));

        String result = queryApi().searchWithMap(params);

        assertEquals("clean-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/dynamic"))
                .withQueryParam("valid", equalTo("ok"))
                .withQueryParam("tags", equalTo("one"))
                .withQueryParam("tags", equalTo("two")));
    }

    @Test
    @DisplayName("@RequestParam Map with non-String keys renders keys via String.valueOf")
    void mapWithNonStringKeysRendersViaStringvalueOf() {
        stubFor(get(urlPathEqualTo("/api/search/numeric"))
                .willReturn(aResponse().withStatus(200).withBody("numeric-ok")));

        Map<Integer, String> params = new LinkedHashMap<>();
        params.put(1, "one");
        params.put(2, "two");

        String result = queryApi().searchWithNumericKeys(params);

        assertEquals("numeric-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/numeric"))
                .withQueryParam("1", equalTo("one"))
                .withQueryParam("2", equalTo("two")));
    }

    @Test
    @DisplayName("@RequestParam array expands into repeated query parameters")
    void arrayParamExpandsIntoRepeatedParams() {
        stubFor(get(urlPathEqualTo("/api/search/array"))
                .willReturn(aResponse().withStatus(200).withBody("array-ok")));

        String result = queryApi().searchWithArray(new String[]{"a", "b"});

        assertEquals("array-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/search/array"))
                .withQueryParam("tag", equalTo("a"))
                .withQueryParam("tag", equalTo("b")));
    }

    @Test
    @DisplayName("@RequestHeader Map expands entries into individual headers")
    void headerMapExpandsIntoHeaders() {
        stubFor(get(urlPathEqualTo("/api/headers/dynamic"))
                .willReturn(aResponse().withStatus(200).withBody("headers-ok")));

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("X-Trace-Id", "abc-123");
        headers.put("X-Tenant", List.of("one", "two"));
        headers.put(null, "skipped");
        headers.put("X-Ignored", null);

        String result = headerApi().headersWithMap(headers);

        assertEquals("headers-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/headers/dynamic"))
                .withHeader("X-Trace-Id", equalTo("abc-123"))
                .withHeader("X-Tenant", equalTo("one"))
                .withHeader("X-Tenant", equalTo("two"))
                .withoutHeader("X-Ignored"));
    }

    @Test
    @DisplayName("Urlencoded body: @RequestParam Map expands into form fields")
    void formUrlencodedMapExpandsIntoFields() {
        stubFor(post(urlEqualTo("/api/token"))
                .willReturn(aResponse().withStatus(200).withBody("token-ok")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("grant_type", "client_credentials");
        params.put("scope", "read");

        String result = formApi().tokenWithMap(params);

        assertEquals("token-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/token"))
                .withRequestBody(equalTo("grant_type=client_credentials&scope=read")));
    }

    @Test
    @DisplayName("Multipart: bare @RequestParam Map sends flat text parts named by keys")
    void multipartMapSendsFlatTextParts() {
        stubFor(post(urlEqualTo("/api/upload/meta"))
                .willReturn(aResponse().withStatus(200).withBody("meta-ok")));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("category", "books");
        meta.put("limit", 10);

        String result = multipartApi().uploadMeta(meta);

        assertEquals("meta-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/upload/meta"))
                .withRequestBodyPart(aMultipart()
                        .withName("category")
                        .withBody(equalTo("books"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("limit")
                        .withBody(equalTo("10"))
                        .build()));
    }

    @Test
    @DisplayName("Multipart: @RequestParam(name) Map sends bracketed meta[key] parts")
    void multipartNamedMapSendsBracketedParts() {
        stubFor(post(urlEqualTo("/api/upload/meta"))
                .willReturn(aResponse().withStatus(200).withBody("bracket-ok")));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("category", "books");

        String result = multipartApi().uploadNamedMeta(meta);

        assertEquals("bracket-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/upload/meta"))
                .withRequestBodyPart(aMultipart()
                        .withName("meta[category]")
                        .withBody(equalTo("books"))
                        .build()));
    }

    @Test
    @DisplayName("byte[] as query parameter fails fast instead of sending [B@ garbage")
    void byteArrayQueryParamFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> queryApi().searchWithByteArray(new byte[]{1, 2, 3}));

        assertTrue(ex.getMessage().contains("byte[] cannot be a parameter value"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("byte[] as header value fails fast instead of sending [B@ garbage")
    void byteArrayHeaderValueFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> headerApi().headerWithByteArray(new byte[]{1, 2, 3}));

        assertTrue(ex.getMessage().contains("byte[] cannot be a header value"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("byte[] as a Map value fails fast")
    void byteArrayMapValueFailsFast() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("blob", new byte[]{1, 2, 3});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> queryApi().searchWithMap(params));

        assertTrue(ex.getMessage().contains("byte[] cannot be a parameter value"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("byte[] as a multipart text part fails fast with FileContent guidance")
    void byteArrayMultipartTextPartFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> multipartApi().uploadBlob(new byte[]{1, 2, 3}));

        assertTrue(ex.getMessage().contains("byte[] cannot be a multipart text part"),
                "unexpected message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("FileContent"),
                "expected FileContent guidance in: " + ex.getMessage());
    }
}
