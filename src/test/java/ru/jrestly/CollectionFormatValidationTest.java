package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;
import ru.jrestly.http.HttpTemplate;
import ru.jrestly.http.HttpTemplateBuilder;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CollectionFormatValidationTest")
class CollectionFormatValidationTest {

    interface SuperSearchApi {
        @Get(path = "/super")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String searchSuper(@RequestParam(name = "tags") List<String> tags);
    }

    @QueryCollectionFormat(CollectionFormat.PIPES)
    interface SubSearchApi extends SuperSearchApi {
        @Get(path = "/sub/default")
        String searchSubDefault(@RequestParam(name = "tags") List<String> tags);

        @Get(path = "/sub/override-method")
        @QueryCollectionFormat(CollectionFormat.SSV)
        String searchSubMethodOverride(@RequestParam(name = "tags") List<String> tags);

        @Get(path = "/sub/override-param")
        @QueryCollectionFormat(CollectionFormat.TSV)
        String searchSubParamOverride(@RequestParam(name = "tags", format = CollectionFormat.BRACKETS) List<String> tags);

        @Get(path = "/sub/scalar")
        String searchScalar(@RequestParam(name = "page") int page);
    }

    interface AllFormatsController {
        @Get(path = "/explode")
        @QueryCollectionFormat(CollectionFormat.EXPLODE)
        String explode(@RequestParam(name = "tags") List<String> tags);

        @Get(path = "/csv")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String csv(@RequestParam(name = "tags") List<String> tags);

        @Get(path = "/ssv")
        @QueryCollectionFormat(CollectionFormat.SSV)
        String ssv(@RequestParam(name = "tags") List<String> tags);

        @Get(path = "/tsv")
        @QueryCollectionFormat(CollectionFormat.TSV)
        String tsv(@RequestParam(name = "tags") List<String> tags);

        @Get(path = "/pipes")
        @QueryCollectionFormat(CollectionFormat.PIPES)
        String pipes(@RequestParam(name = "tags") List<String> tags);

        @Get(path = "/brackets")
        @QueryCollectionFormat(CollectionFormat.BRACKETS)
        String brackets(@RequestParam(name = "tags") List<String> tags);
    }

    @Test
    @DisplayName("Validation succeeds for controller with various CollectionFormat declarations")
    void validationSucceeds() {
        List<String> problems = ControllerValidator.validate(SubSearchApi.class);
        assertTrue(problems.isEmpty(), "Expected no problems, got: " + problems);

        problems = ControllerValidator.validate(AllFormatsController.class);
        assertTrue(problems.isEmpty(), "Expected no problems, got: " + problems);
    }

    @Test
    @DisplayName("All CollectionFormat enum values are distinct and non-null")
    void enumIntegrity() {
        CollectionFormat[] values = CollectionFormat.values();
        assertEquals(7, values.length);
        assertTrue(Arrays.asList(values).contains(CollectionFormat.DEFAULT));
        assertTrue(Arrays.asList(values).contains(CollectionFormat.EXPLODE));
        assertTrue(Arrays.asList(values).contains(CollectionFormat.CSV));
        assertTrue(Arrays.asList(values).contains(CollectionFormat.SSV));
        assertTrue(Arrays.asList(values).contains(CollectionFormat.TSV));
        assertTrue(Arrays.asList(values).contains(CollectionFormat.PIPES));
        assertTrue(Arrays.asList(values).contains(CollectionFormat.BRACKETS));
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
        assertEquals("http://localhost:8080/sub/default?tags=alpha%7Cbeta", template.createUri().toString());
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
        assertEquals("http://localhost:8080/sub/override-method?tags=alpha%20beta", template.createUri().toString());
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
        assertEquals("http://localhost:8080/sub/override-param?tags%5B%5D=alpha&tags%5B%5D=beta", template.createUri().toString());
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

        assertEquals("http://localhost:8080/sub/scalar?page=42", template.createUri().toString());
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
        assertEquals("http://localhost:8080/sub/default", emptyTemplate.createUri().toString());

        HttpTemplate nullsTemplate = new HttpTemplateBuilder(module, httpClient, method, new Object[]{Arrays.asList(null, null)}, SubSearchApi.class).build();
        assertEquals("http://localhost:8080/sub/default", nullsTemplate.createUri().toString());
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
        assertEquals("http://localhost:8080/sub/default?tags=first%7Csecond", template.createUri().toString());
    }

    @Test
    @DisplayName("JRestlyClient successfully creates controller proxy with CollectionFormat")
    void clientCreatesProxySuccessfully() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .build();

        SubSearchApi proxy = client.get(SubSearchApi.class);
        assertNotNull(proxy);
        client.close();
    }
}
