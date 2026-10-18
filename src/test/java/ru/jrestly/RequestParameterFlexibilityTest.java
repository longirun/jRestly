package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.http.RequestType;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration suite for ADR-0017: Request parameter &amp; header flexibility.
 * Validates complex real-world combinations of inheritance, nullable parameters,
 * dynamic maps, static class/method declarations, and override precedence.
 */
class RequestParameterFlexibilityTest extends BaseWireMockTest {

    @RequestHeader(name = "User-Agent", value = "jRestly-Client/1.0")
    @RequestHeader(name = "Accept", value = "application/json")
    @RequestDefaultParam(name = "api_version", value = "2026-10-14")
    interface BaseServiceApi {
    }

    @RequestHeader(name = "X-Tenant-Id", value = "tenant-default")
    @RequestDefaultParam(name = "format", value = "full")
    interface CatalogApi extends BaseServiceApi {

        @Get("/api/catalog/items")
        String searchItems(
                @RequestParam(name = "filter") Map<String, Object> filters,
                @RequestParam(name = "tag") List<String> tags,
                @RequestParam Integer offset,
                @RequestHeader(name = "Accept") String acceptOverride,
                @RequestHeader(name = "X-Correlation-Id") String correlationId
        );

        @Get("/api/catalog/items/{id}")
        String getItem(
                @PathVariable String id,
                @RequestHeader Map<String, Object> dynamicHeaders
        );

        @Get("/api/catalog/async-search")
        CompletableFuture<String> asyncSearch(
                @RequestParam(name = "format") String formatOverride,
                @RequestParam Map<String, Object> extraParams
        );
    }

    @RequestDefaultParam(name = "grant_type", value = "client_credentials")
    @RequestDefaultParam(name = "scope", value = "read")
    interface OAuthTokenApi extends BaseServiceApi {

        @Post(value = "/api/oauth/token",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String issueToken(
                @RequestParam(name = "scope") String scopeOverride,
                @RequestParam Map<String, Object> credentials
        );
    }

    @Test
    @DisplayName("Complex search with interface inheritance, dynamic map filters, nullable items, and header override")
    void complexSearchWithInheritanceAndOverrides() {
        stubFor(get(urlPathEqualTo("/api/catalog/items"))
                .withQueryParam("api_version", equalTo("2026-10-14"))
                .withQueryParam("format", equalTo("full"))
                .withQueryParam("filter[status]", equalTo("AVAILABLE"))
                .withQueryParam("filter[min_price]", equalTo("100"))
                .withQueryParam("tag", equalTo("electronics"))
                .withHeader("User-Agent", equalTo("jRestly-Client/1.0"))
                .withHeader("X-Tenant-Id", equalTo("tenant-default"))
                .withHeader("Accept", equalTo("text/csv"))
                .willReturn(aResponse().withStatus(200).withBody("id,name\n1,phone")));

        CatalogApi api = client.get(CatalogApi.class);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("status", "AVAILABLE");
        filters.put("min_price", 100);
        filters.put("null_filter", null);

        List<String> tags = Arrays.asList("electronics", null, "sale");

        String result = api.searchItems(
                filters,
                tags,
                null,
                "text/csv",
                null
        );

        assertEquals("id,name\n1,phone", result);

        verify(getRequestedFor(urlPathEqualTo("/api/catalog/items"))
                .withQueryParam("api_version", equalTo("2026-10-14"))
                .withQueryParam("format", equalTo("full"))
                .withQueryParam("filter[status]", equalTo("AVAILABLE"))
                .withQueryParam("filter[min_price]", equalTo("100"))
                .withQueryParam("tag", equalTo("electronics"))
                .withQueryParam("tag", equalTo("sale"))
                .withoutQueryParam("offset")
                .withoutQueryParam("filter[null_filter]")
                .withHeader("User-Agent", equalTo("jRestly-Client/1.0"))
                .withHeader("X-Tenant-Id", equalTo("tenant-default"))
                .withHeader("Accept", equalTo("text/csv"))
                .withoutHeader("X-Correlation-Id"));
    }

    @Test
    @DisplayName("Dynamic Map headers override class-level headers and skip null values")
    void dynamicHeadersWithOverrideAndOmission() {
        stubFor(get(urlPathEqualTo("/api/catalog/items/42"))
                .withQueryParam("api_version", equalTo("2026-10-14"))
                .withQueryParam("format", equalTo("full"))
                .withHeader("User-Agent", equalTo("jRestly-Client/1.0"))
                .withHeader("X-Tenant-Id", equalTo("tenant-custom"))
                .withHeader("X-Trace-Id", equalTo("trace-999"))
                .willReturn(aResponse().withStatus(200).withBody("item-42")));

        CatalogApi api = client.get(CatalogApi.class);

        Map<String, Object> dynamicHeaders = new LinkedHashMap<>();
        dynamicHeaders.put("X-Tenant-Id", "tenant-custom");
        dynamicHeaders.put("X-Trace-Id", "trace-999");
        dynamicHeaders.put("X-Ignored", null);
        dynamicHeaders.put(null, "null-key-ignored");

        String result = api.getItem("42", dynamicHeaders);

        assertEquals("item-42", result);

        verify(getRequestedFor(urlPathEqualTo("/api/catalog/items/42"))
                .withQueryParam("api_version", equalTo("2026-10-14"))
                .withQueryParam("format", equalTo("full"))
                .withHeader("User-Agent", equalTo("jRestly-Client/1.0"))
                .withHeader("X-Tenant-Id", equalTo("tenant-custom"))
                .withHeader("X-Trace-Id", equalTo("trace-999"))
                .withoutHeader("X-Ignored"));
    }

    @Test
    @DisplayName("Null @PathVariable throws descriptive IllegalArgumentException")
    void nullPathVariableThrowsIllegalArgumentException() {
        CatalogApi api = client.get(CatalogApi.class);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> api.getItem(null, Map.of()));

        assertTrue(ex.getMessage().contains("Path variable 'id' value cannot be null"),
                "Expected descriptive error message, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Form urlencoded request merges interface defaults, overrides, and dynamic credentials")
    void formUrlencodedWithInheritedDefaultsAndOverrides() {
        stubFor(post(urlEqualTo("/api/oauth/token"))
                .willReturn(aResponse().withStatus(200).withBody("access-token-xyz")));

        OAuthTokenApi api = client.get(OAuthTokenApi.class);

        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("client_id", "my-app");
        credentials.put("client_secret", "s3cr3t");

        String token = api.issueToken("read_write", credentials);

        assertEquals("access-token-xyz", token);

        verify(postRequestedFor(urlEqualTo("/api/oauth/token"))
                .withHeader("User-Agent", equalTo("jRestly-Client/1.0"))
                .withRequestBody(equalTo("api_version=2026-10-14&grant_type=client_credentials&scope=read_write&client_id=my-app&client_secret=s3cr3t")));
    }

    @Test
    @DisplayName("CompletableFuture async twin executes with default overrides and dynamic params")
    void asyncSearchWithDefaultOverrideAndDynamicParams() {
        stubFor(get(urlPathEqualTo("/api/catalog/async-search"))
                .withQueryParam("api_version", equalTo("2026-10-14"))
                .withQueryParam("format", equalTo("compact"))
                .withQueryParam("page", equalTo("1"))
                .withQueryParam("debug", equalTo("true"))
                .willReturn(aResponse().withStatus(200).withBody("async-ok")));

        CatalogApi api = client.get(CatalogApi.class);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("page", 1);
        extra.put("debug", true);

        String result = api.asyncSearch("compact", extra).join();

        assertEquals("async-ok", result);

        verify(getRequestedFor(urlPathEqualTo("/api/catalog/async-search"))
                .withQueryParam("api_version", equalTo("2026-10-14"))
                .withQueryParam("format", equalTo("compact"))
                .withQueryParam("page", equalTo("1"))
                .withQueryParam("debug", equalTo("true"))
                .withHeader("User-Agent", equalTo("jRestly-Client/1.0"))
                .withHeader("X-Tenant-Id", equalTo("tenant-default")));
    }
}
