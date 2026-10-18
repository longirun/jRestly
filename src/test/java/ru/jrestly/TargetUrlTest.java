package ru.jrestly;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.TargetUrl;

import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end and validation test suite for dynamic endpoint / target URL overrides via {@link TargetUrl} (ADR-0062).
 */
public class TargetUrlTest extends BaseWireMockTest {

    interface InvalidTypeController {
        @Get
        String fetch(@TargetUrl int invalidType);
    }

    interface MultipleTargetUrlController {
        @Get
        String fetch(@TargetUrl URI first, @TargetUrl String second);
    }

    interface CombinedAnnotationsController {
        @Get
        String fetch(@TargetUrl @RequestParam(name = "u") String url);
    }

    interface ValidTargetUrlTypesController {
        @Get
        String withUri(@TargetUrl URI uri);

        @Get
        String withUrl(@TargetUrl URL url);

        @Get
        String withString(@TargetUrl String str);

        @Get
        String withCharSequence(@TargetUrl CharSequence cs);
    }

    interface DynamicApiController {
        @Get
        String downloadWithUri(@TargetUrl URI uri);

        @Get
        String downloadWithString(@TargetUrl String url);

        @Get
        String downloadWithUrl(@TargetUrl URL url);

        @Get("/subpath")
        String fetchSubpath(@TargetUrl String baseUrl);

        @Get("/items/{id}")
        String fetchItemWithVar(@TargetUrl URI baseUri, @PathVariable String id);

        @Get
        String queryParams(@TargetUrl String baseUrl, @RequestParam int limit);

        @Get
        CompletableFuture<String> fetchAsync(@TargetUrl URI uri);

        @Get("/items?size=10")
        String fetchSubpathWithStaticQuery(@TargetUrl String baseUrl);

        @Get("/items?size=10")
        String fetchSubpathWithStaticQueryAndTargetQuery(@TargetUrl String baseUrl);

        @Get("/items?size=10")
        String fetchSubpathWithStaticQueryAndDynamicParam(@TargetUrl String baseUrl, @RequestParam int limit);
    }

    @Test
    void rejectsInvalidTargetUrlParameterTypeEagerly() {
        List<String> problems = ControllerValidator.validate(InvalidTypeController.class);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("fetch(): @TargetUrl parameter must be of type java.net.URI, java.net.URL, or CharSequence/String"));

        JRestlyClient dummyClient = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> dummyClient.get(InvalidTypeController.class));
    }

    @Test
    void rejectsMultipleTargetUrlParametersEagerly() {
        List<String> problems = ControllerValidator.validate(MultipleTargetUrlController.class);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("fetch(): multiple parameters annotated with @TargetUrl, at most one is allowed"));

        JRestlyClient dummyClient = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> dummyClient.get(MultipleTargetUrlController.class));
    }

    @Test
    void rejectsTargetUrlCombinedWithHttpAnnotationsEagerly() {
        List<String> problems = ControllerValidator.validate(CombinedAnnotationsController.class);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("fetch(): parameter must not combine @TargetUrl with other HTTP parameter annotations"));

        JRestlyClient dummyClient = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> dummyClient.get(CombinedAnnotationsController.class));
    }

    @Test
    void acceptsValidTargetUrlTypesWithoutValidationProblems() {
        List<String> problems = ControllerValidator.validate(ValidTargetUrlTypesController.class);
        assertTrue(problems.isEmpty(), "Valid target url types should have 0 problems, but got: " + problems);
    }

    @Test
    void dynamicEndpointUriResolvesCorrectly() {
        stubFor(get(urlEqualTo("/s3/presigned-file.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"downloaded\"}")));

        DynamicApiController api = client.get(DynamicApiController.class);
        URI uri = URI.create(baseUrl() + "/s3/presigned-file.json");
        String result = api.downloadWithUri(uri);

        assertEquals("{\"status\":\"downloaded\"}", result);
        verify(getRequestedFor(urlEqualTo("/s3/presigned-file.json")));
    }

    @Test
    void dynamicEndpointStringResolvesCorrectly() {
        stubFor(get(urlEqualTo("/webhook/callback"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ack")));

        DynamicApiController api = client.get(DynamicApiController.class);
        String result = api.downloadWithString(baseUrl() + "/webhook/callback");

        assertEquals("ack", result);
        verify(getRequestedFor(urlEqualTo("/webhook/callback")));
    }

    @Test
    void dynamicEndpointUrlResolvesCorrectly() throws Exception {
        stubFor(get(urlEqualTo("/url/test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("url-ok")));

        DynamicApiController api = client.get(DynamicApiController.class);
        URL url = URI.create(baseUrl() + "/url/test").toURL();
        String result = api.downloadWithUrl(url);

        assertEquals("url-ok", result);
        verify(getRequestedFor(urlEqualTo("/url/test")));
    }

    @Test
    void targetUrlOverridesBaseUrlWithSubpath() {
        stubFor(get(urlEqualTo("/custom-tenant/subpath"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("tenant-data")));

        DynamicApiController api = client.get(DynamicApiController.class);
        String result = api.fetchSubpath(baseUrl() + "/custom-tenant");

        assertEquals("tenant-data", result);
        verify(getRequestedFor(urlEqualTo("/custom-tenant/subpath")));
    }

    @Test
    void targetUrlWithPathVariableSubstitutesProperly() {
        stubFor(get(urlEqualTo("/api-v2/items/item-123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("item-content")));

        DynamicApiController api = client.get(DynamicApiController.class);
        String result = api.fetchItemWithVar(URI.create(baseUrl() + "/api-v2"), "item-123");

        assertEquals("item-content", result);
        verify(getRequestedFor(urlEqualTo("/api-v2/items/item-123")));
    }

    @Test
    void targetUrlWithQueryParamsAppendsQueryString() {
        stubFor(get(urlEqualTo("/search?limit=50"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("results")));

        DynamicApiController api = client.get(DynamicApiController.class);
        String result = api.queryParams(baseUrl() + "/search", 50);

        assertEquals("results", result);
        verify(getRequestedFor(urlEqualTo("/search?limit=50")));
    }

    @Test
    void nullTargetUrlThrowsIllegalArgumentException() {
        DynamicApiController api = client.get(DynamicApiController.class);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> api.downloadWithUri(null));
        assertTrue(ex.getMessage().contains("@TargetUrl parameter value cannot be null"));
    }

    @Test
    void asyncDynamicEndpointResolvesCorrectly() throws Exception {
        stubFor(get(urlEqualTo("/async/job"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("async-ok")));

        DynamicApiController api = client.get(DynamicApiController.class);
        CompletableFuture<String> future = api.fetchAsync(URI.create(baseUrl() + "/async/job"));

        assertNotNull(future);
        assertEquals("async-ok", future.get());
        verify(getRequestedFor(urlEqualTo("/async/job")));
    }

    @Test
    void targetUrlWithoutQueryKeepsStaticPathQuery() {
        stubFor(get(urlEqualTo("/static-query/items?size=10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("page")));

        DynamicApiController api = client.get(DynamicApiController.class);
        String result = api.fetchSubpathWithStaticQuery(baseUrl() + "/static-query");

        assertEquals("page", result);
        verify(getRequestedFor(urlEqualTo("/static-query/items?size=10")));
    }

    @Test
    void targetUrlWithQueryMergesStaticPathQuery() {
        stubFor(get(urlEqualTo("/static-query/search/items?size=10&a=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("merged")));

        DynamicApiController api = client.get(DynamicApiController.class);
        String result = api.fetchSubpathWithStaticQueryAndTargetQuery(baseUrl() + "/static-query/search?a=1");

        assertEquals("merged", result);
        verify(getRequestedFor(urlEqualTo("/static-query/search/items?size=10&a=1")));
    }

    @Test
    void targetUrlStaticPathQueryPlusDynamicParamMergesAll() {
        stubFor(get(urlEqualTo("/static-query/search/items?size=10&a=1&limit=5"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("all")));

        DynamicApiController api = client.get(DynamicApiController.class);
        String result = api.fetchSubpathWithStaticQueryAndDynamicParam(baseUrl() + "/static-query/search?a=1", 5);

        assertEquals("all", result);
        verify(getRequestedFor(urlEqualTo("/static-query/search/items?size=10&a=1&limit=5")));
    }
}
