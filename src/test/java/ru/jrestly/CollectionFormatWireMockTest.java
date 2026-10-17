package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.RequestType;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end for declarative query collection formats (ADR-0034): CSV, SSV, TSV, PIPES,
 * BRACKETS and EXPLODE for lists, arrays and dynamic maps, plus per-parameter format
 * overrides.
 */
class CollectionFormatWireMockTest extends BaseWireMockTest {

    interface QueryFormatsController {
        @Get(path = "/api/csv")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String getCsv(@RequestParam(name = "tag") List<String> tags);

        @Get(path = "/api/csv/array")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String getCsvArray(@RequestParam(name = "tag") String[] tags);

        @Get(path = "/api/csv/primitive")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String getCsvPrimitive(@RequestParam(name = "id") int[] ids);

        @Get(path = "/api/ssv")
        @QueryCollectionFormat(CollectionFormat.SSV)
        String getSsv(@RequestParam(name = "tag") List<String> tags);

        @Get(path = "/api/tsv")
        @QueryCollectionFormat(CollectionFormat.TSV)
        String getTsv(@RequestParam(name = "tag") List<String> tags);

        @Get(path = "/api/pipes")
        @QueryCollectionFormat(CollectionFormat.PIPES)
        String getPipes(@RequestParam(name = "tag") List<String> tags);

        @Get(path = "/api/brackets")
        @QueryCollectionFormat(CollectionFormat.BRACKETS)
        String getBrackets(@RequestParam(name = "tag") List<String> tags);

        @Get(path = "/api/param-override")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String getParamOverride(
                @RequestParam(name = "csvTags") List<String> csvTags,
                @RequestParam(name = "pipeTags", format = CollectionFormat.PIPES) List<String> pipeTags,
                @RequestParam(name = "bracketTags", format = CollectionFormat.BRACKETS) List<String> bracketTags
        );

        @Get(path = "/api/dynamic-map")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String getDynamicMap(@RequestParam Map<String, Object> map);

        @Get(path = "/api/single-item")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String getSingleItemCsv(@RequestParam(name = "tag") List<String> tags);

        @Get(path = "/api/single-item/brackets")
        @QueryCollectionFormat(CollectionFormat.BRACKETS)
        String getSingleItemBrackets(@RequestParam(name = "tag") List<String> tags);

        @Get(path = "/api/async")
        @QueryCollectionFormat(CollectionFormat.CSV)
        CompletableFuture<String> getAsyncCsv(@RequestParam(name = "tag") List<String> tags);
    }

    @QueryCollectionFormat(CollectionFormat.CSV)
    interface InterfaceDefaultCsvController {
        @Get(path = "/api/iface/inherited")
        String getInherited(@RequestParam(name = "tag") List<String> tags);

        @Get(path = "/api/iface/method-override")
        @QueryCollectionFormat(CollectionFormat.PIPES)
        String getMethodOverride(@RequestParam(name = "tag") List<String> tags);
    }

    interface FormAndMultipartController {
        @Post(path = "/api/form/csv", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        @QueryCollectionFormat(CollectionFormat.CSV)
        String postFormCsv(@RequestParam(name = "category") List<String> categories);

        @Post(path = "/api/form/brackets", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        @QueryCollectionFormat(CollectionFormat.BRACKETS)
        String postFormBrackets(@RequestParam(name = "category") List<String> categories);

        @Post(path = "/api/multipart/csv", requestType = RequestType.MULTIPART_FORM_DATA)
        @QueryCollectionFormat(CollectionFormat.CSV)
        String postMultipartCsv(
                @RequestParam(name = "tags") List<String> tags,
                @MultipartFormFile(partName = "file") FileContent file
        );

        @Post(path = "/api/multipart/brackets", requestType = RequestType.MULTIPART_FORM_DATA)
        @QueryCollectionFormat(CollectionFormat.BRACKETS)
        String postMultipartBrackets(
                @RequestParam(name = "tags") List<String> tags,
                @MultipartFormFile(partName = "file") FileContent file
        );
    }

    private QueryFormatsController queryClient() {
        return client.get(QueryFormatsController.class);
    }

    private InterfaceDefaultCsvController ifaceClient() {
        return client.get(InterfaceDefaultCsvController.class);
    }

    private FormAndMultipartController formClient() {
        return client.get(FormAndMultipartController.class);
    }

    @Test
    @DisplayName("CollectionFormat.CSV formats List<String> into comma-separated query parameter")
    void csvFormatList() {
        stubFor(get(urlPathEqualTo("/api/csv"))
                .withQueryParam("tag", equalTo("electronics,gadgets"))
                .willReturn(aResponse().withStatus(200).withBody("csv-list-ok")));

        String result = queryClient().getCsv(List.of("electronics", "gadgets"));

        assertEquals("csv-list-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/csv"))
                .withQueryParam("tag", equalTo("electronics,gadgets")));
    }

    @Test
    @DisplayName("CollectionFormat.CSV formats String[] array into comma-separated query parameter")
    void csvFormatArray() {
        stubFor(get(urlPathEqualTo("/api/csv/array"))
                .withQueryParam("tag", equalTo("alpha,beta,gamma"))
                .willReturn(aResponse().withStatus(200).withBody("csv-array-ok")));

        String result = queryClient().getCsvArray(new String[]{"alpha", "beta", "gamma"});

        assertEquals("csv-array-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/csv/array"))
                .withQueryParam("tag", equalTo("alpha,beta,gamma")));
    }

    @Test
    @DisplayName("CollectionFormat.CSV formats primitive int[] array into comma-separated query parameter")
    void csvFormatPrimitiveArray() {
        stubFor(get(urlPathEqualTo("/api/csv/primitive"))
                .withQueryParam("id", equalTo("10,20,30"))
                .willReturn(aResponse().withStatus(200).withBody("csv-primitive-ok")));

        String result = queryClient().getCsvPrimitive(new int[]{10, 20, 30});

        assertEquals("csv-primitive-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/csv/primitive"))
                .withQueryParam("id", equalTo("10,20,30")));
    }

    @Test
    @DisplayName("CollectionFormat.SSV formats List<String> into space-separated query parameter")
    void ssvFormatList() {
        stubFor(get(urlPathEqualTo("/api/ssv"))
                .withQueryParam("tag", equalTo("red green blue"))
                .willReturn(aResponse().withStatus(200).withBody("ssv-ok")));

        String result = queryClient().getSsv(List.of("red", "green", "blue"));

        assertEquals("ssv-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/ssv"))
                .withQueryParam("tag", equalTo("red green blue")));
    }

    @Test
    @DisplayName("CollectionFormat.TSV formats List<String> into tab-separated query parameter")
    void tsvFormatList() {
        stubFor(get(urlPathEqualTo("/api/tsv"))
                .withQueryParam("tag", equalTo("col1\tcol2\tcol3"))
                .willReturn(aResponse().withStatus(200).withBody("tsv-ok")));

        String result = queryClient().getTsv(List.of("col1", "col2", "col3"));

        assertEquals("tsv-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/tsv"))
                .withQueryParam("tag", equalTo("col1\tcol2\tcol3")));
    }

    @Test
    @DisplayName("CollectionFormat.PIPES formats List<String> into pipe-separated query parameter")
    void pipesFormatList() {
        stubFor(get(urlPathEqualTo("/api/pipes"))
                .withQueryParam("tag", equalTo("active|pending|archived"))
                .willReturn(aResponse().withStatus(200).withBody("pipes-ok")));

        String result = queryClient().getPipes(List.of("active", "pending", "archived"));

        assertEquals("pipes-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/pipes"))
                .withQueryParam("tag", equalTo("active|pending|archived")));
    }

    @Test
    @DisplayName("CollectionFormat.BRACKETS formats List<String> into repeated tag[] query parameters")
    void bracketsFormatList() {
        stubFor(get(urlPathEqualTo("/api/brackets"))
                .withQueryParam("tag[]", equalTo("alpha"))
                .withQueryParam("tag[]", equalTo("beta"))
                .willReturn(aResponse().withStatus(200).withBody("brackets-ok")));

        String result = queryClient().getBrackets(List.of("alpha", "beta"));

        assertEquals("brackets-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/brackets"))
                .withQueryParam("tag[]", equalTo("alpha"))
                .withQueryParam("tag[]", equalTo("beta")));
    }

    @Test
    @DisplayName("Parameter-level format overrides method-level @QueryCollectionFormat")
    void parameterLevelOverride() {
        stubFor(get(urlPathEqualTo("/api/param-override"))
                .withQueryParam("csvTags", equalTo("a,b"))
                .withQueryParam("pipeTags", equalTo("x|y"))
                .withQueryParam("bracketTags[]", equalTo("1"))
                .withQueryParam("bracketTags[]", equalTo("2"))
                .willReturn(aResponse().withStatus(200).withBody("override-ok")));

        String result = queryClient().getParamOverride(
                List.of("a", "b"),
                List.of("x", "y"),
                List.of("1", "2")
        );

        assertEquals("override-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/param-override"))
                .withQueryParam("csvTags", equalTo("a,b"))
                .withQueryParam("pipeTags", equalTo("x|y"))
                .withQueryParam("bracketTags[]", equalTo("1"))
                .withQueryParam("bracketTags[]", equalTo("2")));
    }

    @Test
    @DisplayName("Dynamic map serializes collection entry using effective collection format")
    void dynamicMapWithCollection() {
        stubFor(get(urlPathEqualTo("/api/dynamic-map"))
                .withQueryParam("filters", equalTo("open,closed"))
                .willReturn(aResponse().withStatus(200).withBody("dynamic-map-ok")));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("filters", List.of("open", "closed"));

        String result = queryClient().getDynamicMap(map);

        assertEquals("dynamic-map-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/dynamic-map"))
                .withQueryParam("filters", equalTo("open,closed")));
    }

    @Test
    @DisplayName("Single-item collection formats cleanly in CSV without trailing delimiter")
    void singleItemCollection() {
        stubFor(get(urlPathEqualTo("/api/single-item"))
                .withQueryParam("tag", equalTo("solo"))
                .willReturn(aResponse().withStatus(200).withBody("single-csv-ok")));

        stubFor(get(urlPathEqualTo("/api/single-item/brackets"))
                .withQueryParam("tag[]", equalTo("solo"))
                .willReturn(aResponse().withStatus(200).withBody("single-brackets-ok")));

        assertEquals("single-csv-ok", queryClient().getSingleItemCsv(List.of("solo")));
        assertEquals("single-brackets-ok", queryClient().getSingleItemBrackets(List.of("solo")));
    }

    @Test
    @DisplayName("Interface-level @QueryCollectionFormat is inherited by methods")
    void interfaceLevelInherited() {
        stubFor(get(urlPathEqualTo("/api/iface/inherited"))
                .withQueryParam("tag", equalTo("one,two"))
                .willReturn(aResponse().withStatus(200).withBody("iface-csv-ok")));

        String result = ifaceClient().getInherited(List.of("one", "two"));

        assertEquals("iface-csv-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/iface/inherited"))
                .withQueryParam("tag", equalTo("one,two")));
    }

    @Test
    @DisplayName("Method-level @QueryCollectionFormat overrides interface-level declaration")
    void methodLevelOverridesInterface() {
        stubFor(get(urlPathEqualTo("/api/iface/method-override"))
                .withQueryParam("tag", equalTo("one|two"))
                .willReturn(aResponse().withStatus(200).withBody("method-pipes-ok")));

        String result = ifaceClient().getMethodOverride(List.of("one", "two"));

        assertEquals("method-pipes-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/iface/method-override"))
                .withQueryParam("tag", equalTo("one|two")));
    }

    @Test
    @DisplayName("Form urlencoded request serializes CSV collection into single field")
    void formUrlencodedCsv() {
        stubFor(post(urlEqualTo("/api/form/csv"))
                .withRequestBody(equalTo("category=tech%2Cnews"))
                .willReturn(aResponse().withStatus(200).withBody("form-csv-ok")));

        String result = formClient().postFormCsv(List.of("tech", "news"));

        assertEquals("form-csv-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/form/csv"))
                .withRequestBody(equalTo("category=tech%2Cnews")));
    }

    @Test
    @DisplayName("Form urlencoded request serializes BRACKETS collection into category[] fields")
    void formUrlencodedBrackets() {
        stubFor(post(urlEqualTo("/api/form/brackets"))
                .withRequestBody(equalTo("category%5B%5D=tech&category%5B%5D=news"))
                .willReturn(aResponse().withStatus(200).withBody("form-brackets-ok")));

        String result = formClient().postFormBrackets(List.of("tech", "news"));

        assertEquals("form-brackets-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/form/brackets"))
                .withRequestBody(equalTo("category%5B%5D=tech&category%5B%5D=news")));
    }

    @Test
    @DisplayName("Multipart request serializes CSV collection into single part")
    void multipartCsv() {
        stubFor(post(urlEqualTo("/api/multipart/csv"))
                .willReturn(aResponse().withStatus(200).withBody("multipart-csv-ok")));

        FileContent file = FileContent.of("doc.txt", "file-data".getBytes(StandardCharsets.UTF_8));
        String result = formClient().postMultipartCsv(List.of("docs", "specs"), file);

        assertEquals("multipart-csv-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/multipart/csv"))
                .withRequestBodyPart(aMultipart().withName("tags").withBody(equalTo("docs,specs")).build())
                .withRequestBodyPart(aMultipart().withName("file").withBody(equalTo("file-data")).build()));
    }

    @Test
    @DisplayName("Multipart request serializes BRACKETS collection into parts named tags[]")
    void multipartBrackets() {
        stubFor(post(urlEqualTo("/api/multipart/brackets"))
                .willReturn(aResponse().withStatus(200).withBody("multipart-brackets-ok")));

        FileContent file = FileContent.of("doc.txt", "file-data".getBytes(StandardCharsets.UTF_8));
        String result = formClient().postMultipartBrackets(List.of("docs", "specs"), file);

        assertEquals("multipart-brackets-ok", result);
        verify(postRequestedFor(urlEqualTo("/api/multipart/brackets"))
                .withRequestBodyPart(aMultipart().withName("tags[]").withBody(equalTo("docs")).build())
                .withRequestBodyPart(aMultipart().withName("tags[]").withBody(equalTo("specs")).build())
                .withRequestBodyPart(aMultipart().withName("file").withBody(equalTo("file-data")).build()));
    }

    @Test
    @DisplayName("Async call CompletableFuture<String> with CollectionFormat.CSV")
    void asyncCsv() {
        stubFor(get(urlPathEqualTo("/api/async"))
                .withQueryParam("tag", equalTo("async1,async2"))
                .willReturn(aResponse().withStatus(200).withBody("async-csv-ok")));

        String result = queryClient().getAsyncCsv(List.of("async1", "async2")).join();

        assertEquals("async-csv-ok", result);
        verify(getRequestedFor(urlPathEqualTo("/api/async"))
                .withQueryParam("tag", equalTo("async1,async2")));
    }
}
