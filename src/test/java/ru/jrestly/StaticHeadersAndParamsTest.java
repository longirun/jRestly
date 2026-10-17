package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.http.RequestType;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Static, class-level {@code @RequestHeader} and {@code @RequestDefaultParam}
 * declarations: repeatable annotations, interface inheritance and the
 * method/parameter/Map override precedence (case-insensitive header names) in
 * both query strings and form bodies. WireMock e2e (ADR-0017).
 */
class StaticHeadersAndParamsTest extends BaseWireMockTest {

    @RequestHeader(name = "X-Api-Key", value = "secret-123")
    interface SingleClassHeaderController {
        @Get(path = "/api/static/single-header")
        String call();
    }

    @RequestHeader(name = "X-Header-One", value = "val1")
    @RequestHeader(name = "X-Header-Two", value = "val2")
    interface MultiClassHeaderController {
        @Get(path = "/api/static/multi-header")
        String call();
    }

    @RequestHeader(name = "X-Common", value = "base")
    @RequestHeader(name = "X-Overridden", value = "base-val")
    interface BaseHeaderController {
        @Get(path = "/api/static/inherited-header")
        String call();
    }

    @RequestHeader(name = "X-Overridden", value = "child-val")
    @RequestHeader(name = "X-Child-Only", value = "child")
    interface ChildHeaderController extends BaseHeaderController {
    }

    @RequestHeader(name = "Accept", value = "application/json")
    @RequestHeader(name = "X-Tag", value = "class-tag")
    interface MethodOverrideHeaderController {
        @Get(path = "/api/static/method-header-override")
        @RequestHeader(name = "Accept", value = "text/plain")
        @RequestHeader(name = "X-Method", value = "method-val")
        String call();
    }

    @RequestHeader(name = "X-Version", value = "v1")
    interface ParamOverrideHeaderController {
        @Get(path = "/api/static/param-header-override")
        String call(@RequestHeader(name = "X-Version") String version);
    }

    @RequestHeader(name = "X-Trace", value = "trace-default")
    interface MapOverrideHeaderController {
        @Get(path = "/api/static/map-header-override")
        String call(@RequestHeader Map<String, ?> headers);
    }

    @RequestHeader(name = "x-custom-key", value = "lowercased")
    interface CaseInsensitiveHeaderController {
        @Get(path = "/api/static/case-header")
        @RequestHeader(name = "X-Custom-Key", value = "uppercased")
        String call();
    }

    @RequestDefaultParam(name = "api_version", value = "1.0")
    interface SingleClassParamController {
        @Get(path = "/api/static/single-param")
        String call();
    }

    @RequestDefaultParam(name = "p1", value = "v1")
    @RequestDefaultParam(name = "p2", value = "v2")
    interface MultiClassParamController {
        @Get(path = "/api/static/multi-param")
        String call();
    }

    @RequestDefaultParam(name = "common", value = "base")
    @RequestDefaultParam(name = "override_me", value = "base")
    interface BaseParamController {
        @Get(path = "/api/static/inherited-param")
        String call();
    }

    @RequestDefaultParam(name = "override_me", value = "child")
    @RequestDefaultParam(name = "child_param", value = "yes")
    interface ChildParamController extends BaseParamController {
    }

    @RequestDefaultParam(name = "limit", value = "10")
    interface MethodOverrideParamController {
        @Get(path = "/api/static/method-param-override")
        @RequestDefaultParam(name = "limit", value = "50")
        String call();
    }

    @RequestDefaultParam(name = "limit", value = "10")
    interface RuntimeOverrideParamController {
        @Get(path = "/api/static/runtime-param-override")
        String call(@RequestParam(name = "limit") Integer limit);
    }

    @RequestDefaultParam(name = "sort", value = "asc")
    interface MapOverrideParamController {
        @Get(path = "/api/static/map-param-override")
        String call(@RequestParam Map<String, ?> params);
    }

    @RequestDefaultParam(name = "grant_type", value = "client_credentials")
    interface FormDefaultParamController {
        @Post(path = "/api/static/token", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String getToken(@RequestParam(name = "client_id") String clientId);

        @Post(path = "/api/static/token-override", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String getTokenOverride(@RequestParam(name = "grant_type") String grantType,
                                @RequestParam(name = "client_id") String clientId);
    }

    @Test
    @DisplayName("Class-level @RequestHeader is sent with HTTP request")
    void classLevelStaticHeaderIsSent() {
        stubFor(get(urlEqualTo("/api/static/single-header"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = client.get(SingleClassHeaderController.class).call();

        assertEquals("ok", result);
        verify(getRequestedFor(urlEqualTo("/api/static/single-header"))
                .withHeader("X-Api-Key", equalTo("secret-123")));
    }

    @Test
    @DisplayName("Repeatable class-level @RequestHeader declarations are sent")
    void repeatableClassLevelStaticHeadersAreSent() {
        stubFor(get(urlEqualTo("/api/static/multi-header"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = client.get(MultiClassHeaderController.class).call();

        assertEquals("ok", result);
        verify(getRequestedFor(urlEqualTo("/api/static/multi-header"))
                .withHeader("X-Header-One", equalTo("val1"))
                .withHeader("X-Header-Two", equalTo("val2")));
    }

    @Test
    @DisplayName("Interface inheritance preserves base headers and lets child override by name")
    void interfaceInheritanceHeadersAndOverrides() {
        stubFor(get(urlEqualTo("/api/static/inherited-header"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = client.get(ChildHeaderController.class).call();

        assertEquals("ok", result);
        verify(getRequestedFor(urlEqualTo("/api/static/inherited-header"))
                .withHeader("X-Common", equalTo("base"))
                .withHeader("X-Overridden", equalTo("child-val"))
                .withHeader("X-Child-Only", equalTo("child")));
    }

    @Test
    @DisplayName("Method-level @RequestHeader overrides class-level header with same name")
    void methodLevelHeaderOverridesClassLevel() {
        stubFor(get(urlEqualTo("/api/static/method-header-override"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = client.get(MethodOverrideHeaderController.class).call();

        assertEquals("ok", result);
        verify(getRequestedFor(urlEqualTo("/api/static/method-header-override"))
                .withHeader("Accept", equalTo("text/plain"))
                .withHeader("X-Tag", equalTo("class-tag"))
                .withHeader("X-Method", equalTo("method-val")));
    }

    @Test
    @DisplayName("Parameter-level @RequestHeader overrides static header when non-null and falls back when null")
    void parameterHeaderOverridesStaticHeader() {
        stubFor(get(urlEqualTo("/api/static/param-header-override"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        ParamOverrideHeaderController ctrl = client.get(ParamOverrideHeaderController.class);

        ctrl.call("v2");
        verify(getRequestedFor(urlEqualTo("/api/static/param-header-override"))
                .withHeader("X-Version", equalTo("v2")));

        ctrl.call(null);
        verify(getRequestedFor(urlEqualTo("/api/static/param-header-override"))
                .withHeader("X-Version", equalTo("v1")));
    }

    @Test
    @DisplayName("Parameter-level Map @RequestHeader overrides static header for present keys")
    void parameterMapOverridesStaticHeader() {
        stubFor(get(urlEqualTo("/api/static/map-header-override"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        MapOverrideHeaderController ctrl = client.get(MapOverrideHeaderController.class);

        ctrl.call(Map.of("X-Trace", "trace-dynamic", "X-Other", "val"));
        verify(getRequestedFor(urlEqualTo("/api/static/map-header-override"))
                .withHeader("X-Trace", equalTo("trace-dynamic"))
                .withHeader("X-Other", equalTo("val")));
    }

    @Test
    @DisplayName("Case-insensitive header override replaces earlier header with same name")
    void caseInsensitiveHeaderOverride() {
        stubFor(get(urlEqualTo("/api/static/case-header"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        client.get(CaseInsensitiveHeaderController.class).call();

        verify(getRequestedFor(urlEqualTo("/api/static/case-header"))
                .withHeader("X-Custom-Key", equalTo("uppercased")));
    }

    @Test
    @DisplayName("Class-level @RequestDefaultParam is appended to query string")
    void classLevelDefaultParamIsSent() {
        stubFor(get(urlEqualTo("/api/static/single-param?api_version=1.0"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = client.get(SingleClassParamController.class).call();

        assertEquals("ok", result);
    }

    @Test
    @DisplayName("Repeatable class-level @RequestDefaultParam declarations are sent")
    void repeatableClassLevelDefaultParamsAreSent() {
        stubFor(get(urlEqualTo("/api/static/multi-param?p1=v1&p2=v2"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = client.get(MultiClassParamController.class).call();

        assertEquals("ok", result);
    }

    @Test
    @DisplayName("Interface inheritance preserves default params and lets child override by name")
    void interfaceInheritanceDefaultParamsAndOverrides() {
        stubFor(get(urlEqualTo("/api/static/inherited-param?common=base&override_me=child&child_param=yes"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = client.get(ChildParamController.class).call();

        assertEquals("ok", result);
    }

    @Test
    @DisplayName("Method-level @RequestDefaultParam overrides class-level default param with same name")
    void methodLevelDefaultParamOverridesClassLevel() {
        stubFor(get(urlEqualTo("/api/static/method-param-override?limit=50"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = client.get(MethodOverrideParamController.class).call();

        assertEquals("ok", result);
    }

    @Test
    @DisplayName("Parameter-level @RequestParam overrides default param when non-null and falls back when null")
    void parameterOverridesDefaultParamWithoutDuplicate() {
        stubFor(get(urlEqualTo("/api/static/runtime-param-override?limit=100"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        stubFor(get(urlEqualTo("/api/static/runtime-param-override?limit=10"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        RuntimeOverrideParamController ctrl = client.get(RuntimeOverrideParamController.class);

        // non-null: overrides default (no duplicate ?limit=10&limit=100)
        assertEquals("ok", ctrl.call(100));

        // null: falls back to default
        assertEquals("ok", ctrl.call(null));
    }

    @Test
    @DisplayName("Map @RequestParam overrides default param for present keys")
    void mapParameterOverridesDefaultParam() {
        stubFor(get(urlPathEqualTo("/api/static/map-param-override"))
                .withQueryParam("sort", equalTo("desc"))
                .withQueryParam("filter", equalTo("active"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        MapOverrideParamController ctrl = client.get(MapOverrideParamController.class);

        assertEquals("ok", ctrl.call(Map.of("sort", "desc", "filter", "active")));
        verify(getRequestedFor(urlPathEqualTo("/api/static/map-param-override"))
                .withQueryParam("sort", equalTo("desc"))
                .withQueryParam("filter", equalTo("active")));
    }

    @Test
    @DisplayName("Class-level @RequestDefaultParam is sent in form urlencoded body and overridable")
    void classLevelDefaultParamInFormUrlencoded() {
        stubFor(post(urlEqualTo("/api/static/token"))
                .willReturn(aResponse().withStatus(200).withBody("token-ok")));
        stubFor(post(urlEqualTo("/api/static/token-override"))
                .willReturn(aResponse().withStatus(200).withBody("token-ok")));

        FormDefaultParamController ctrl = client.get(FormDefaultParamController.class);

        ctrl.getToken("my-app");
        verify(postRequestedFor(urlEqualTo("/api/static/token"))
                .withRequestBody(equalTo("grant_type=client_credentials&client_id=my-app")));

        ctrl.getTokenOverride("password", "my-app");
        verify(postRequestedFor(urlEqualTo("/api/static/token-override"))
                .withRequestBody(equalTo("grant_type=password&client_id=my-app")));
    }

    @Test
    @DisplayName("Static headers merge cleanly with client-level auth headers")
    void staticHeadersMergeWithAuthHeaders() {
        stubFor(get(urlEqualTo("/api/static/single-header"))
                .willReturn(aResponse().withStatus(200).withBody("auth-merged")));

        JRestlyClient authClient = JRestlyClient.builder()
                .baseUrl(client.getModuleInfo().getEnvironment().getUrl())
                .authHeader("Authorization", "Bearer token-123")
                .build();
        try {
            SingleClassHeaderController controller = authClient.get(SingleClassHeaderController.class);
            String result = controller.call();
            assertEquals("auth-merged", result);

            verify(getRequestedFor(urlEqualTo("/api/static/single-header"))
                    .withHeader("Authorization", equalTo("Bearer token-123"))
                    .withHeader("X-Api-Key", equalTo("secret-123")));
        } finally {
            authClient.close();
        }
    }
}
