package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity checks around {@code @QueryCollectionFormat} (ADR-0034): the validator
 * accepts valid declarations, the {@code CollectionFormat} constants are
 * distinct/non-null, and {@code JRestlyClient} builds a proxy. No network.
 */
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
