package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.QueryCollectionFormat;
import ru.jrestly.annotation.RequestParam;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit-level tests for {@link CollectionFormat} resolution in the URI pipeline:
 * interface-hierarchy order, method/parameter {@code DEFAULT} inheritance,
 * scalar handling, null/empty filtering, and form-body omission.
 */
class CollectionFormatUriResolutionTest {

    interface SuperSearchApi {
        @Get("/super")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String searchSuper(@RequestParam List<String> tags);
    }

    @QueryCollectionFormat(CollectionFormat.PIPES)
    interface DefaultFallbackApi {
        @Get("/df/inherit")
        @QueryCollectionFormat(CollectionFormat.DEFAULT)
        String inheritInterfaceDefault(@RequestParam List<String> tags);

        @Get("/df/param-default")
        @QueryCollectionFormat(CollectionFormat.SSV)
        String paramDefaultFallsBack(@RequestParam(name = "tags", format = CollectionFormat.DEFAULT) List<String> tags);

        @Post(value = "/df/form-empty",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
        @QueryCollectionFormat(CollectionFormat.CSV)
        String formEmptyCollection(@RequestParam List<String> tags);
    }

    @QueryCollectionFormat(CollectionFormat.CSV)
    interface GrandSearchApi {
        @Get("/deep/grand")
        String deepGrand(@RequestParam List<String> tags);
    }

    @QueryCollectionFormat(CollectionFormat.TSV)
    interface MidSearchApi extends GrandSearchApi {
        @Get("/deep/mid")
        String deepMid(@RequestParam List<String> tags);
    }

    interface LeafSearchApi extends MidSearchApi {
        @Get("/deep/leaf")
        String deepLeaf(@RequestParam List<String> tags);
    }

    @QueryCollectionFormat(CollectionFormat.PIPES)
    interface SubSearchApi extends SuperSearchApi {
        @Get("/sub/default")
        String searchSubDefault(@RequestParam List<String> tags);

        @Get("/sub/override-method")
        @QueryCollectionFormat(CollectionFormat.SSV)
        String searchSubMethodOverride(@RequestParam List<String> tags);

        @Get("/sub/override-param")
        @QueryCollectionFormat(CollectionFormat.TSV)
        String searchSubParamOverride(@RequestParam(name = "tags", format = CollectionFormat.BRACKETS) List<String> tags);

        @Get("/sub/scalar")
        String searchScalar(@RequestParam int page);
    }

    @Test
    @DisplayName("HttpTemplateBuilder resolves interface hierarchy: sub-interface overrides super-interface")
    void hierarchyResolution() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = SubSearchApi.class.getMethod("searchSubDefault", List.class);
        HttpTemplate template = new HttpTemplateBuilder(module, httpClient, method, new Object[]{List.of("alpha", "beta")}, SubSearchApi.class).build();

        // SubSearchApi has @QueryCollectionFormat(PIPES), overriding default EXPLODE
        assertEquals("http://localhost:8080/sub/default?tags=alpha%7Cbeta", UrlComposer.resolve(template.getUrl(), template.getRequestParams()).toString());
    }

    @Test
    @DisplayName("HttpTemplateBuilder resolves method override over interface annotation")
    void methodOverrideResolution() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = SubSearchApi.class.getMethod("searchSubMethodOverride", List.class);
        HttpTemplate template = new HttpTemplateBuilder(module, httpClient, method, new Object[]{List.of("alpha", "beta")}, SubSearchApi.class).build();

        // searchSubMethodOverride has @QueryCollectionFormat(SSV) -> space separated
        assertEquals("http://localhost:8080/sub/override-method?tags=alpha%20beta", UrlComposer.resolve(template.getUrl(), template.getRequestParams()).toString());
    }

    @Test
    @DisplayName("HttpTemplateBuilder resolves parameter format override over method and interface")
    void paramOverrideResolution() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = SubSearchApi.class.getMethod("searchSubParamOverride", List.class);
        HttpTemplate template = new HttpTemplateBuilder(module, httpClient, method, new Object[]{List.of("alpha", "beta")}, SubSearchApi.class).build();

        // Parameter has format = BRACKETS -> tags[]=alpha&tags[]=beta (tags%5B%5D=alpha&tags%5B%5D=beta)
        assertEquals("http://localhost:8080/sub/override-param?tags%5B%5D=alpha&tags%5B%5D=beta", UrlComposer.resolve(template.getUrl(), template.getRequestParams()).toString());
    }

    @Test
    @DisplayName("Scalar parameter is not affected by collection formatting and remains scalar param=value")
    void scalarParamUnaffected() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = SubSearchApi.class.getMethod("searchScalar", int.class);
        HttpTemplate template = new HttpTemplateBuilder(module, httpClient, method, new Object[]{42}, SubSearchApi.class).build();

        assertEquals("http://localhost:8080/sub/scalar?page=42", UrlComposer.resolve(template.getUrl(), template.getRequestParams()).toString());
    }

    @Test
    @DisplayName("Empty collection or collection with only nulls is completely omitted from query")
    void emptyOrNullCollectionOmitted() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = SubSearchApi.class.getMethod("searchSubDefault", List.class);

        HttpTemplate emptyTemplate = new HttpTemplateBuilder(module, httpClient, method, new Object[]{List.of()}, SubSearchApi.class).build();
        assertEquals("http://localhost:8080/sub/default", UrlComposer.resolve(emptyTemplate.getUrl(), emptyTemplate.getRequestParams()).toString());

        HttpTemplate nullsTemplate = new HttpTemplateBuilder(module, httpClient, method, new Object[]{Arrays.asList(null, null)}, SubSearchApi.class).build();
        assertEquals("http://localhost:8080/sub/default", UrlComposer.resolve(nullsTemplate.getUrl(), nullsTemplate.getRequestParams()).toString());
    }

    @Test
    @DisplayName("Collection with null elements filters out nulls before formatting")
    void collectionFiltersNullElements() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = SubSearchApi.class.getMethod("searchSubDefault", List.class);
        HttpTemplate template = new HttpTemplateBuilder(module, httpClient, method, new Object[]{Arrays.asList("first", null, "second")}, SubSearchApi.class).build();

        // PIPES format: first|second
        assertEquals("http://localhost:8080/sub/default?tags=first%7Csecond", UrlComposer.resolve(template.getUrl(), template.getRequestParams()).toString());
    }

    @Test
    @DisplayName("Method-level @QueryCollectionFormat(DEFAULT) inherits the interface-level format")
    void methodLevelDefaultInheritsInterfaceFormat() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = DefaultFallbackApi.class.getMethod("inheritInterfaceDefault", List.class);
        HttpTemplate template = new HttpTemplateBuilder(module, httpClient, method, new Object[]{List.of("alpha", "beta")}, DefaultFallbackApi.class).build();

        // DEFAULT on the method must fall through to the interface-level PIPES
        assertEquals("http://localhost:8080/df/inherit?tags=alpha%7Cbeta", UrlComposer.resolve(template.getUrl(), template.getRequestParams()).toString());
    }

    @Test
    @DisplayName("Parameter-level format=DEFAULT falls back to the method-level @QueryCollectionFormat")
    void paramLevelDefaultFallsBackToMethodFormat() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = DefaultFallbackApi.class.getMethod("paramDefaultFallsBack", List.class);
        HttpTemplate template = new HttpTemplateBuilder(module, httpClient, method, new Object[]{List.of("alpha", "beta")}, DefaultFallbackApi.class).build();

        // Parameter DEFAULT must fall back to the method-level SSV
        assertEquals("http://localhost:8080/df/param-default?tags=alpha%20beta", UrlComposer.resolve(template.getUrl(), template.getRequestParams()).toString());
    }

    @Test
    @DisplayName("Three-level hierarchy resolves child-first: unannotated leaf takes the nearest annotated ancestor")
    void deepHierarchyResolvesChildFirst() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = LeafSearchApi.class.getMethod("deepLeaf", List.class);
        HttpTemplate template = new HttpTemplateBuilder(module, httpClient, method, new Object[]{List.of("alpha", "beta")}, LeafSearchApi.class).build();

        // LeafSearchApi has no annotation; nearest annotated ancestor is MidSearchApi (TSV), not GrandSearchApi (CSV)
        assertEquals("http://localhost:8080/deep/leaf?tags=alpha%09beta", UrlComposer.resolve(template.getUrl(), template.getRequestParams()).toString());
    }

    @Test
    @DisplayName("Empty collection is omitted from the urlencoded form body entirely")
    void formEmptyCollectionOmitted() throws NoSuchMethodException {
        ModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        Method method = DefaultFallbackApi.class.getMethod("formEmptyCollection", List.class);
        HttpTemplate template = new HttpTemplateBuilder(module, httpClient, method, new Object[]{List.of()}, DefaultFallbackApi.class).build();

        assertEquals("http://localhost:8080/df/form-empty", UrlComposer.resolve(template.getUrl(), template.getRequestParams()).toString());
        assertArrayEquals(new byte[0], template.getBody());
    }
}
