package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.RequestType;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * POST semantics: JSON {@code @RequestBody} request/response round-trip and
 * form-urlencoded bodies built from {@code @RequestParam} (single, dynamic Map,
 * prefixed and collection maps) with UTF-8 percent-encoding and null-element
 * omission. WireMock e2e.
 */
class PostMethodTest extends BaseWireMockTest {

    interface DynamicFormController {
        @Post(value = "/api/form/dynamic",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String postFormWithMap(@RequestParam Map<String, ?> fields);

        @Post(value = "/api/form/prefixed",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String postFormWithPrefixedMap(@RequestParam(name = "data") Map<String, ?> fields);
    }

    private DynamicFormController dynamicFormController() {
        return client.get(DynamicFormController.class);
    }

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

    @Test
    @DisplayName("POST form-urlencoded percent-encodes non-ASCII parameters as UTF-8, not ISO-8859-1")
    void postFormUrlEncodedNonAsciiParams() {
        stubFor(post(urlEqualTo("/api/form"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        String result = controller.submitForm("пароль123", "значение");

        assertEquals("ok", result);

        verify(postRequestedFor(urlEqualTo("/api/form"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("field1=%D0%BF%D0%B0%D1%80%D0%BE%D0%BB%D1%8C123"))
                .withRequestBody(containing("field2=%D0%B7%D0%BD%D0%B0%D1%87%D0%B5%D0%BD%D0%B8%D0%B5")));
    }

    @Test
    @DisplayName("POST form-urlencoded with @RequestParam Map sends entries as form fields")
    void postFormUrlEncodedWithMap() {
        stubFor(post(urlEqualTo("/api/form/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("form-map-ok")));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("username", "alice");
        fields.put("age", 30);

        String result = dynamicFormController().postFormWithMap(fields);

        assertEquals("form-map-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/form/dynamic"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("username=alice"))
                .withRequestBody(containing("age=30")));
    }

    @Test
    @DisplayName("POST form-urlencoded with @RequestParam(name) Map sends prefixed fields name[key]")
    void postFormUrlEncodedWithPrefixedMap() {
        stubFor(post(urlEqualTo("/api/form/prefixed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("prefixed-form-ok")));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("first_name", "Bob");

        String result = dynamicFormController().postFormWithPrefixedMap(fields);

        assertEquals("prefixed-form-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/form/prefixed"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("data%5Bfirst_name%5D=Bob")));
    }

    @Test
    @DisplayName("POST form-urlencoded with @RequestParam Map containing Collection sends repeated fields")
    void postFormUrlEncodedWithMapContainingCollection() {
        stubFor(post(urlEqualTo("/api/form/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("multi-form-ok")));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("roles", List.of("admin", "manager"));

        String result = dynamicFormController().postFormWithMap(fields);

        assertEquals("multi-form-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/form/dynamic"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("roles=admin"))
                .withRequestBody(containing("roles=manager")));
    }

    @Test
    @DisplayName("POST form-urlencoded with @RequestParam Map skips null keys, null values, and null collection elements")
    void postFormUrlEncodedWithMapSkipsNullKeysAndValues() {
        stubFor(post(urlEqualTo("/api/form/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("clean-form-ok")));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(null, "skipped");
        fields.put("ignored", null);
        fields.put("valid", "present");
        fields.put("roles", Arrays.asList("admin", null, "user"));

        String result = dynamicFormController().postFormWithMap(fields);

        assertEquals("clean-form-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/form/dynamic"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(notContaining("null"))
                .withRequestBody(notContaining("ignored"))
                .withRequestBody(containing("valid=present"))
                .withRequestBody(containing("roles=admin"))
                .withRequestBody(containing("roles=user")));
    }
}
