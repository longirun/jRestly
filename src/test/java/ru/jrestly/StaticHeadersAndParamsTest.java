package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.http.RequestType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class StaticHeadersAndParamsTest extends BaseWireMockTest {

    @RequestHeader(name = "X-Api-Key", value = "secret")
    interface CatalogApi {
        @Get(path = "/api/catalog")
        String catalog();

        @Get(path = "/api/echo")
        String echoToken(@RequestHeader(name = "X-Api-Key") String token);
    }

    @RequestHeader(name = "X-A", value = "a")
    @RequestHeader(name = "X-B", value = "b")
    interface MultiHeaderApi {
        @Get(path = "/api/multi")
        String call();
    }

    @RequestHeader(name = "Accept", value = "application/json")
    @RequestDefaultParam(name = "page", value = "1")
    @RequestDefaultParam(name = "locale", value = "en")
    interface ParentApi {
        @Get(path = "/api/parent")
        String call();
    }

    @RequestHeader(name = "accept", value = "text/plain")
    @RequestDefaultParam(name = "page", value = "2")
    interface ChildApi extends ParentApi {
        @Get(path = "/api/child")
        String call();
    }

    @RequestDefaultParam(name = "size", value = "10")
    interface PagedApi {
        @Get(path = "/api/paged")
        String list(@RequestParam(name = "size") Integer size);

        @Get(path = "/api/paged/fixed")
        @RequestDefaultParam(name = "size", value = "25")
        String fixed();
    }

    @RequestDefaultParam(name = "size", value = "10")
    interface SearchApi {
        @Get(path = "/api/search")
        String search(@RequestParam Map<String, Object> params);

        @Get(path = "/api/search/named")
        String searchNamed(@RequestParam(name = "f") Map<String, Object> params);
    }

    @RequestDefaultParam(name = "grant_type", value = "client_credentials")
    interface TokenApi {
        @Post(path = "/api/token", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String token(@RequestParam(name = "scope") String scope);
    }

    interface MergeApi {
        @Get(path = "/api/merge")
        @RequestHeader(name = "X-Static", value = "static")
        String call();
    }

    @RequestHeader(name = "X-Auth", value = "static-wins")
    interface AuthOverrideApi {
        @Get(path = "/api/auth-override")
        String call();
    }

    private List<String> sentHeaderValues(String name) {
        return wireMockServer.getAllServeEvents().get(0).getRequest().header(name).values();
    }

    private List<String> sentQueryParamValues(String name) {
        return wireMockServer.getAllServeEvents().get(0).getRequest()
                .queryParameter(name).values();
    }

    @Test
    @DisplayName("Class-level @RequestHeader is sent with every request of the interface")
    void classLevelStaticHeaderIsSent() {
        stubFor(get(urlEqualTo("/api/catalog"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String result = client.get(CatalogApi.class).catalog();

        assertEquals("ok", result);
        verify(getRequestedFor(urlEqualTo("/api/catalog"))
                .withHeader("X-Api-Key", equalTo("secret")));
    }

    @Test
    @DisplayName("Repeatable class-level @RequestHeader declarations are all sent")
    void repeatableClassLevelStaticHeadersAreSent() {
        stubFor(get(urlEqualTo("/api/multi"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        client.get(MultiHeaderApi.class).call();

        verify(getRequestedFor(urlEqualTo("/api/multi"))
                .withHeader("X-A", equalTo("a"))
                .withHeader("X-B", equalTo("b")));
    }

    @Test
    @DisplayName("Inheritance preserves parent defaults and lets the child override case-insensitively")
    void interfaceInheritanceHeadersAndParams() {
        stubFor(get(urlPathEqualTo("/api/child"))
                .willReturn(aResponse().withStatus(200).withBody("child-ok")));

        String result = client.get(ChildApi.class).call();

        assertEquals("child-ok", result);
        // overridden by the child, case-insensitively, without duplicates
        assertEquals(List.of("text/plain"), sentHeaderValues("Accept"));
        // overridden by the child
        assertEquals(List.of("2"), sentQueryParamValues("page"));
        // preserved from the parent
        verify(getRequestedFor(urlPathEqualTo("/api/child"))
                .withQueryParam("locale", equalTo("en")));
    }

    @Test
    @DisplayName("A non-null @RequestHeader argument replaces the static header; null falls back to it")
    void parameterHeaderOverridesStaticAndFallsBackOnNull() {
        stubFor(get(urlEqualTo("/api/echo"))
                .willReturn(aResponse().withStatus(200).withBody("echo-ok")));

        client.get(CatalogApi.class).echoToken("dynamic");

        assertEquals(List.of("dynamic"), sentHeaderValues("X-Api-Key"));

        wireMockServer.resetRequests();
        client.get(CatalogApi.class).echoToken(null);

        assertEquals(List.of("secret"), sentHeaderValues("X-Api-Key"));
    }

    @Test
    @DisplayName("Method-level @RequestDefaultParam overrides the class-level default")
    void methodLevelDefaultParamOverridesClassLevel() {
        stubFor(get(urlPathEqualTo("/api/paged/fixed"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        client.get(PagedApi.class).fixed();

        assertEquals(List.of("25"), sentQueryParamValues("size"));
    }

    @Test
    @DisplayName("A non-null @RequestParam argument replaces the default without duplicates; null falls back to it")
    void parameterOverridesDefaultParamWithoutDuplicate() {
        stubFor(get(urlPathEqualTo("/api/paged"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        client.get(PagedApi.class).list(50);

        assertEquals(List.of("50"), sentQueryParamValues("size"));

        wireMockServer.resetRequests();
        client.get(PagedApi.class).list(null);

        assertEquals(List.of("10"), sentQueryParamValues("size"));
    }

    @Test
    @DisplayName("Bare Map keys replace same-named defaults; prefixed Map keys do not")
    void mapParameterOverridesDefaultParam() {
        stubFor(get(urlPathEqualTo("/api/search"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("size", "50");
        params.put("q", "x");

        client.get(SearchApi.class).search(params);

        assertEquals(List.of("50"), sentQueryParamValues("size"));
        verify(getRequestedFor(urlPathEqualTo("/api/search"))
                .withQueryParam("q", equalTo("x")));

        wireMockServer.resetRequests();
        stubFor(get(urlPathEqualTo("/api/search/named"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        client.get(SearchApi.class).searchNamed(params);

        // f[size] is a different parameter name — the default survives
        assertEquals(List.of("10"), sentQueryParamValues("size"));
        verify(getRequestedFor(urlPathEqualTo("/api/search/named"))
                .withQueryParam("f[size]", equalTo("50")));
    }

    @Test
    @DisplayName("Class-level @RequestDefaultParam lands in the urlencoded body and is overridable")
    void classLevelDefaultParamInFormUrlencoded() {
        stubFor(post(urlEqualTo("/api/token"))
                .willReturn(aResponse().withStatus(200).withBody("token")));

        String result = client.get(TokenApi.class).token("read");

        assertEquals("token", result);
        verify(postRequestedFor(urlEqualTo("/api/token"))
                .withRequestBody(equalTo("grant_type=client_credentials&scope=read")));
    }

    @Test
    @DisplayName("Static headers merge with ambient auth headers")
    void staticHeadersMergeWithAuthHeaders() {
        stubFor(get(urlEqualTo("/api/merge"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        moduleInfo.getTestAuthProvider().updateAuthHeader("X-Auth", "ambient");

        client.get(MergeApi.class).call();

        verify(getRequestedFor(urlEqualTo("/api/merge"))
                .withHeader("X-Auth", equalTo("ambient"))
                .withHeader("X-Static", equalTo("static")));
    }

    @Test
    @DisplayName("A static header replaces the ambient auth header with the same name")
    void staticHeaderWinsOverAmbientAuthHeader() {
        stubFor(get(urlEqualTo("/api/auth-override"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        moduleInfo.getTestAuthProvider().updateAuthHeader("X-Auth", "ambient");

        client.get(AuthOverrideApi.class).call();

        assertEquals(List.of("static-wins"), sentHeaderValues("X-Auth"));
    }
}
