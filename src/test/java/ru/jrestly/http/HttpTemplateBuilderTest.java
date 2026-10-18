package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.AuthProvider;
import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.FollowRedirects;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.QueryCollectionFormat;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.SetAuthDetails;
import ru.jrestly.annotation.TargetUrl;
import ru.jrestly.annotation.Trace;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.annotation.WithMetrics;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.compression.ContentCompressor;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseContext;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.ClientMetricsListener;
import ru.jrestly.resilience.CircuitBreakerConfig;
import ru.jrestly.resilience.CircuitBreakerRegistry;
import ru.jrestly.resilience.RateLimiterConfig;
import ru.jrestly.resilience.RateLimiterRegistry;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive unit test suite for {@link HttpTemplateBuilder}.
 * Verifies URL assembly, header computation, cookie handling, query and body
 * parameter formatting, JSON/multipart/urlencoded payload construction,
 * compression, resilience, interceptors and error handling. No network.
 */
class HttpTemplateBuilderTest {

    private final SimpleModuleInfo defaultModuleInfo = SimpleModuleInfo.builder()
            .baseUrl("https://api.example.com")
            .build();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // -------------------------------------------------------------------------
    // Interfaces for testing
    // -------------------------------------------------------------------------

    interface UrlApi {
        @Get("/users/{id}")
        String getWithPathVariable(@PathVariable(name = "id") String id);

        @Get("/users/{id}")
        String getWithEncodedPathVariable(@PathVariable(name = "id", encoded = true) String id);

        @Get("/items")
        String getWithTargetUrl(@TargetUrl String targetUrl);

        @Get("")
        String getWithEmptyPathTargetUrl(@TargetUrl String targetUrl);

        @Get("https://override.example.com/absolute")
        String getWithAbsoluteOverride(@TargetUrl String targetUrl);
    }

    interface HeaderApi {
        @Get("/headers")
        @RequestHeader(name = "X-Static-1", value = "static-val-1")
        @RequestHeader(name = "Authorization", value = "Static-Token")
        String getWithHeaders(
                @RequestHeader(name = "X-Dynamic") String dynamicHeader,
                @RequestHeader Map<String, Object> headerMap,
                @RequestHeader(name = "X-Multi") List<String> multiList,
                @RequestHeader(name = "X-Array") String[] multiArray
        );

        @Get("/conditional")
        String getConditional(@RequestHeader(name = "If-None-Match") String etag);

        @Get("/conditional-match")
        String getConditionalMatch(@RequestHeader(name = "If-Match") String match);

        @Get("/conditional-unmodified")
        String getConditionalUnmodified(@RequestHeader(name = "If-Unmodified-Since") String date);

        @Get("/conditional-modified")
        String getConditionalModified(@RequestHeader(name = "If-Modified-Since") String date);

        @Get("/non-conditional")
        String getNonConditional(@RequestHeader(name = "X-Random") String random);
    }

    interface CookieApi {
        @Get("/cookies")
        @Cookie(name = "staticCookie1", value = "val1")
        @Cookie(name = "staticCookie2", value = "val2")
        String getWithCookies(
                @ru.jrestly.annotation.Cookie(name = "dynCookie") String dynCookie,
                @ru.jrestly.annotation.Cookie Map<String, Object> cookieMap,
                @ru.jrestly.annotation.Cookie HttpCookie httpCookie
        );
    }

    interface ParamApi {
        @Get(value = "/params",  params = {@RequestDefaultParam(name = "defaultKey", value = "defaultVal")})
        @QueryCollectionFormat(CollectionFormat.CSV)
        String getWithParams(
                @RequestParam(name = "single") String single,
                @RequestParam(name = "csvList", format = CollectionFormat.CSV) List<String> csvList,
                @RequestParam(name = "ssvList", format = CollectionFormat.SSV) List<String> ssvList,
                @RequestParam(name = "tsvList", format = CollectionFormat.TSV) List<String> tsvList,
                @RequestParam(name = "pipesList", format = CollectionFormat.PIPES) List<String> pipesList,
                @RequestParam(name = "bracketsList", format = CollectionFormat.BRACKETS) List<String> bracketsList,
                @RequestParam(name = "explodeList", format = CollectionFormat.EXPLODE) List<String> explodeList,
                @RequestParam(name = "arrayParam", format = CollectionFormat.CSV) String[] arrayParam,
                @RequestParam Map<String, Object> paramMap,
                @RequestParam(name = "nested") Map<String, Object> nestedMap
        );
    }

    interface BodyApi {
        @Post("/body/json")
        String postBody(@RequestBody Object body);

        @Post("/body/supplier")
        String postSupplierBody(@RequestBody Supplier<?> supplier);

        @Get("/body/get")
        String getWithBodyIgnored(@RequestBody String body);

        @Post("/body/dto")
        String postDto(@RequestBody DummyDto dto);
    }

    record DummyDto(String name) {}

    interface MultipartApi {
        @Post(value = "/multipart",  requestType = RequestType.MULTIPART_FORM_DATA)
        String upload(
                @ru.jrestly.annotation.MultipartFormFile(partName = "file") Object file,
                @RequestParam(name = "desc") String desc,
                @RequestParam(name = "tags", format = CollectionFormat.CSV) List<String> tags,
                @RequestParam(name = "ssvTags", format = CollectionFormat.SSV) List<String> ssvTags,
                @RequestParam(name = "tsvTags", format = CollectionFormat.TSV) List<String> tsvTags,
                @RequestParam(name = "pipeTags", format = CollectionFormat.PIPES) List<String> pipeTags,
                @RequestParam(name = "bracketTags", format = CollectionFormat.BRACKETS) List<String> bracketTags,
                @RequestParam(name = "explodeTags", format = CollectionFormat.EXPLODE) List<String> explodeTags,
                @RequestParam(name = "arrayTags", format = CollectionFormat.CSV) String[] arrayTags,
                @RequestParam Map<String, Object> paramMap
        );
    }

    interface UrlEncodedApi {
        @Post(value = "/form",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String submitForm(
                @RequestParam(name = "user") String user,
                @RequestParam(name = "empty") String empty
        );

        @Post(value = "/form-empty",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String submitEmpty();
    }

    interface CompressionApi {
        @Post("/compressed")
        @Compress(algorithm = CompressionAlgorithm.GZIP, minSizeBytes = 10)
        String postCompressed(@RequestBody byte[] data);
    }

    interface ResilienceApi {
        @Get("/resilient")
        @ru.jrestly.annotation.CircuitBreaker(name = "cb-test", enabled = true)
        @ru.jrestly.annotation.RateLimiter(name = "rl-test", enabled = true)
        String getResilient();

        @Get("/disabled-resilience")
        @ru.jrestly.annotation.CircuitBreaker(name = "cb-disabled", enabled = false)
        @ru.jrestly.annotation.RateLimiter(name = "rl-disabled", enabled = false)
        String getDisabledResilience();
    }

    static class PrivateNoArgInterceptor implements RequestInterceptor {
        private PrivateNoArgInterceptor() {}
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request;
        }
    }

    static class ThrowingInterceptor implements RequestInterceptor {
        public ThrowingInterceptor(String arg) {}
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request;
        }
    }

    @WithInterceptor(PrivateNoArgInterceptor.class)
    interface InterceptorApi {
        @Get("/intercepted")
        String getIntercepted();

        @Get("/bad-interceptor")
        @WithInterceptor(ThrowingInterceptor.class)
        String getBadInterceptor();
    }

    static class MetricsListenerOne implements ClientMetricsListener {
        @Override
        public void onCallComplete(CallMetrics metrics) {}
    }

    static class MetricsListenerTwo implements ClientMetricsListener {
        @Override
        public void onCallComplete(CallMetrics metrics) {}
    }

    @WithMetrics({MetricsListenerOne.class, MetricsListenerTwo.class})
    interface MetricsApi {
        @Get("/metrics")
        String getMetrics();
    }

    interface TracingApi {
        @Get("/trace-w3c")
        @Trace(enabled = true, w3c = true, forceNew = true)
        String traceW3c();

        @Get("/trace-custom")
        @Trace(enabled = true, w3c = false, header = "X-My-Trace", forceNew = true)
        String traceCustom();
    }

    interface ContractInspectionApi {
        @Get("/inspect")
        @ExpectStatus(statuses = {200, 204})
        @FollowRedirects(count = 3)
        @HttpVersion(HttpClient.Version.HTTP_2)
        @Retry(times = 3)
        @SetAuthDetails(headerName = "X-Auth-Token")
        CompletableFuture<java.util.Optional<String>> inspect();
    }

    // -------------------------------------------------------------------------
    // Tests: URL construction and Path / TargetUrl variables
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createUrl handles path variables, encoding, and targetUrl variations")
    void testCreateUrlVariations() throws Exception {
        Method pathMethod = UrlApi.class.getMethod("getWithPathVariable", String.class);
        HttpTemplate t1 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, pathMethod,
                new Object[]{"john doe"}, UrlApi.class).build();
        assertEquals("https://api.example.com/users/john%20doe", t1.getUrl());

        Method encodedPathMethod = UrlApi.class.getMethod("getWithEncodedPathVariable", String.class);
        HttpTemplate t2 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, encodedPathMethod,
                new Object[]{"john+doe"}, UrlApi.class).build();
        assertEquals("https://api.example.com/users/john+doe", t2.getUrl());

        // TargetUrl with query and fragment
        Method targetMethod = UrlApi.class.getMethod("getWithTargetUrl", String.class);
        HttpTemplate t3 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, targetMethod,
                new Object[]{"https://custom.org/base?tab=1#frag"}, UrlApi.class).build();
        assertEquals("https://custom.org/base/items?tab=1#frag", t3.getUrl());

        // TargetUrl with query only
        HttpTemplate t4 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, targetMethod,
                new Object[]{"https://custom.org/base?tab=1"}, UrlApi.class).build();
        assertEquals("https://custom.org/base/items?tab=1", t4.getUrl());

        // TargetUrl with fragment only
        HttpTemplate t5 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, targetMethod,
                new Object[]{"https://custom.org/base#frag"}, UrlApi.class).build();
        assertEquals("https://custom.org/base/items#frag", t5.getUrl());

        // TargetUrl plain
        HttpTemplate t6 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, targetMethod,
                new Object[]{"https://custom.org/base"}, UrlApi.class).build();
        assertEquals("https://custom.org/base/items", t6.getUrl());

        // TargetUrl with empty path
        Method emptyPathMethod = UrlApi.class.getMethod("getWithEmptyPathTargetUrl", String.class);
        HttpTemplate t7 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, emptyPathMethod,
                new Object[]{"https://custom.org/exact"}, UrlApi.class).build();
        assertEquals("https://custom.org/exact", t7.getUrl());

        // TargetUrl with method having absolute URL path override
        Method absMethod = UrlApi.class.getMethod("getWithAbsoluteOverride", String.class);
        HttpTemplate t8 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, absMethod,
                new Object[]{"https://ignored.com"}, UrlApi.class).build();
        assertEquals("https://override.example.com/absolute", t8.getUrl());

        // Null targetUrl fails fast
        assertThrows(IllegalArgumentException.class, () ->
                new HttpTemplateBuilder(defaultModuleInfo, httpClient, targetMethod, new Object[]{null}, UrlApi.class).build());

        // Null path variable fails fast
        assertThrows(IllegalArgumentException.class, () ->
                new HttpTemplateBuilder(defaultModuleInfo, httpClient, pathMethod, new Object[]{null}, UrlApi.class).build());
    }

    @Test
    @DisplayName("joinUrlPath covers all 4 slash permutations")
    void testJoinUrlPathPermutations() {
        assertEquals("https://base/path", HttpTemplateBuilder.joinUrlPath("https://base/", "/path"));
        assertEquals("https://base/path", HttpTemplateBuilder.joinUrlPath("https://base/", "path"));
        assertEquals("https://base/path", HttpTemplateBuilder.joinUrlPath("https://base", "/path"));
        assertEquals("https://base/path", HttpTemplateBuilder.joinUrlPath("https://base", "path"));
    }

    // -------------------------------------------------------------------------
    // Tests: Headers, Auth, Cookies, Tracing and Conditionals
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createHeaders merges static headers, dynamic headers, maps, arrays and auth overrides")
    void testHeadersMergingAndAuthOverride() throws Exception {
        AuthProvider authProvider = new AuthProvider() {
            @Override
            public boolean isAuthorized() {
                return true;
            }

            @Override
            public List<Header> getAuthHeaders() {
                return List.of(new Header("Authorization", "Bearer ambient-token"), new Header("X-Ambient", "ambient-val"));
            }

            @Override
            public void updateAuthHeader(String name, String value) {}
        };
        SimpleModuleInfo baseInfo = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .decompression(true)
                .build();
        ru.jrestly.ModuleInfo moduleWithAuth = new ru.jrestly.ModuleInfo() {
            @Override public String getName() { return baseInfo.getName(); }
            @Override public <T> ru.jrestly.Environment<T> getEnvironment() { return baseInfo.getEnvironment(); }
            @Override public AuthProvider getAuthProvider() { return authProvider; }
            @Override public boolean isDecompressionEnabled() { return baseInfo.isDecompressionEnabled(); }
        };

        Method method = HeaderApi.class.getMethod("getWithHeaders", String.class, Map.class, List.class, String[].class);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("X-Map-1", "val1");
        map.put("X-Map-Null", null);
        map.put(null, "ignored");

        HttpTemplate template = new HttpTemplateBuilder(moduleWithAuth, httpClient, method,
                new Object[]{"dyn-val", map, Arrays.asList("m1", null, "m2"), new String[]{"a1", null, "a2"}},
                HeaderApi.class).build();

        List<Header> headers = template.getHeaders();
        assertNotNull(headers);

        // Verify static header overrides ambient Authorization
        Header authH = headers.stream().filter(h -> "Authorization".equalsIgnoreCase(h.name())).findFirst().orElse(null);
        assertNotNull(authH);
        assertEquals("Static-Token", authH.value());

        // Verify ambient header not overridden survived
        assertTrue(headers.stream().anyMatch(h -> "X-Ambient".equalsIgnoreCase(h.name())));
        // Verify decompression Accept-Encoding added
        assertTrue(headers.stream().anyMatch(h -> "Accept-Encoding".equalsIgnoreCase(h.name()) && "gzip, deflate".equals(h.value())));
        // Verify map entries
        assertTrue(headers.stream().anyMatch(h -> "X-Map-1".equalsIgnoreCase(h.name())));
        // Verify collections and arrays with null filtering
        assertEquals(2, headers.stream().filter(h -> "X-Multi".equalsIgnoreCase(h.name())).count());
        assertEquals(2, headers.stream().filter(h -> "X-Array".equalsIgnoreCase(h.name())).count());

        // Verify redirect auth header drop list: Authorization is explicit static in HeaderApi, so it should NOT be dropped!
        // X-Ambient is ambient without explicit declaration, so it MUST be in drop list!
        List<String> dropList = template.getAuthHeaderNames();
        assertTrue(dropList.contains("X-Ambient"));
        assertFalse(dropList.contains("Authorization"));
    }

    @Test
    @DisplayName("isConditionalRequest recognizes conditional headers and static annotations")
    void testConditionalHeadersDetection() throws Exception {
        Method m1 = HeaderApi.class.getMethod("getConditional", String.class);
        HttpTemplate t1 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, m1, new Object[]{"\"etag\""}, HeaderApi.class).build();
        assertTrue(t1.isConditional());

        Method m2 = HeaderApi.class.getMethod("getConditionalMatch", String.class);
        HttpTemplate t2 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, m2, new Object[]{"\"etag\""}, HeaderApi.class).build();
        assertTrue(t2.isConditional());

        Method m3 = HeaderApi.class.getMethod("getConditionalUnmodified", String.class);
        HttpTemplate t3 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, m3, new Object[]{"date"}, HeaderApi.class).build();
        assertTrue(t3.isConditional());

        Method m4 = HeaderApi.class.getMethod("getConditionalModified", String.class);
        HttpTemplate t4 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, m4, new Object[]{"date"}, HeaderApi.class).build();
        assertTrue(t4.isConditional());

        Method m5 = HeaderApi.class.getMethod("getNonConditional", String.class);
        HttpTemplate t5 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, m5, new Object[]{"random"}, HeaderApi.class).build();
        assertFalse(t5.isConditional());
    }

    @Test
    @DisplayName("resolveCookies handles static cookies, HttpCookie, Map and removals")
    void testResolveCookies() throws Exception {
        Method cookieMethod = CookieApi.class.getMethod("getWithCookies", String.class, Map.class, HttpCookie.class);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("staticCookie2", null); // Remove staticCookie2
        map.put("mapCookie", "mapVal");
        map.put(null, "ignored");

        HttpCookie httpCookie = new HttpCookie("httpCookie", "httpVal");

        HttpTemplate template = new HttpTemplateBuilder(defaultModuleInfo, httpClient, cookieMethod,
                new Object[]{"dynVal", map, httpCookie}, CookieApi.class).build();

        Header cookieHeader = template.getHeaders().stream()
                .filter(h -> "Cookie".equalsIgnoreCase(h.name()))
                .findFirst().orElse(null);
        assertNotNull(cookieHeader);
        String val = cookieHeader.value();
        assertTrue(val.contains("staticCookie1=val1"));
        assertFalse(val.contains("staticCookie2=")); // Removed!
        assertTrue(val.contains("mapCookie=mapVal"));
        assertTrue(val.contains("dynCookie=dynVal"));
        assertTrue(val.contains("httpCookie=httpVal"));
    }

    @Test
    @DisplayName("Tracing headers apply w3c and custom headers with forceNew override")
    void testTracingHeaders() throws Exception {
        Method w3cMethod = TracingApi.class.getMethod("traceW3c");
        HttpTemplate t1 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, w3cMethod, new Object[0], TracingApi.class).build();
        assertTrue(t1.getHeaders().stream().anyMatch(h -> "traceparent".equalsIgnoreCase(h.name())));

        Method customMethod = TracingApi.class.getMethod("traceCustom");
        HttpTemplate t2 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, customMethod, new Object[0], TracingApi.class).build();
        assertTrue(t2.getHeaders().stream().anyMatch(h -> "X-My-Trace".equalsIgnoreCase(h.name())));
    }

    // -------------------------------------------------------------------------
    // Tests: Query parameters and formatting
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createRequestParams formats collections across all formats and nested maps")
    void testRequestParamsFormatting() throws Exception {
        Method method = ParamApi.class.getMethod("getWithParams", String.class, List.class, List.class,
                List.class, List.class, List.class, List.class, String[].class, Map.class, Map.class);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("k1", "v1");
        map.put("kNull", null);
        map.put(null, "ignored");

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("field", "val");

        HttpTemplate template = new HttpTemplateBuilder(defaultModuleInfo, httpClient, method,
                new Object[]{
                        "singleVal",
                        Arrays.asList("a", "b"),
                        Arrays.asList("c", "d"),
                        Arrays.asList("e", "f"),
                        Arrays.asList("g", "h"),
                        Arrays.asList("i", "j"),
                        Arrays.asList("k", "l"),
                        new String[]{"arr1", "arr2"},
                        map,
                        nested
                }, ParamApi.class).build();

        List<Param> params = template.getRequestParams();
        assertNotNull(params);

        // default param present
        assertTrue(params.stream().anyMatch(p -> "defaultKey".equals(p.name()) && "defaultVal".equals(p.value())));
        // single param present
        assertTrue(params.stream().anyMatch(p -> "single".equals(p.name()) && "singleVal".equals(p.value())));
        // CSV format: a,b
        assertTrue(params.stream().anyMatch(p -> "csvList".equals(p.name()) && "a,b".equals(p.value())));
        // SSV format: c d
        assertTrue(params.stream().anyMatch(p -> "ssvList".equals(p.name()) && "c d".equals(p.value())));
        // TSV format: e\tf
        assertTrue(params.stream().anyMatch(p -> "tsvList".equals(p.name()) && "e\tf".equals(p.value())));
        // PIPES format: g|h
        assertTrue(params.stream().anyMatch(p -> "pipesList".equals(p.name()) && "g|h".equals(p.value())));
        // BRACKETS format: bracketsList[]
        assertEquals(2, params.stream().filter(p -> "bracketsList[]".equals(p.name())).count());
        // EXPLODE format: explodeList
        assertEquals(2, params.stream().filter(p -> "explodeList".equals(p.name())).count());
        // Array formatted as CSV
        assertTrue(params.stream().anyMatch(p -> "arrayParam".equals(p.name()) && "arr1,arr2".equals(p.value())));
        // Map param
        assertTrue(params.stream().anyMatch(p -> "k1".equals(p.name()) && "v1".equals(p.value())));
        // Nested map param: nested[field]=val
        assertTrue(params.stream().anyMatch(p -> "nested[field]".equals(p.name()) && "val".equals(p.value())));
    }

    // -------------------------------------------------------------------------
    // Tests: JSON Body and BodyPublisher suppliers
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createJsonBodyPayload supports byte[], String, Publisher, InputStream and Supplier variants")
    void testJsonBodyVariants() throws Exception {
        Method postMethod = BodyApi.class.getMethod("postBody", Object.class);

        // byte[] body
        byte[] raw = new byte[]{1, 2, 3};
        HttpTemplate t1 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, postMethod, new Object[]{raw}, BodyApi.class).build();
        assertArrayEquals(raw, t1.getBody());

        // String body
        HttpTemplate t2 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, postMethod, new Object[]{"hello"}, BodyApi.class).build();
        assertEquals("hello", new String(t2.getBody(), StandardCharsets.UTF_8));
        assertEquals("application/json", t2.getContentType());

        // InputStream body
        InputStream in = new ByteArrayInputStream(new byte[]{4, 5});
        HttpTemplate t3 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, postMethod, new Object[]{in}, BodyApi.class).build();
        assertNotNull(t3.getBodyPublisherSupplier());
        assertNotNull(t3.getBodyPublisherSupplier().get());

        // BodyPublisher body
        HttpRequest.BodyPublisher bp = HttpRequest.BodyPublishers.ofString("test");
        HttpTemplate t4 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, postMethod, new Object[]{bp}, BodyApi.class).build();
        assertSame(bp, t4.getBodyPublisherSupplier().get());

        // GET method ignores body
        Method getMethod = BodyApi.class.getMethod("getWithBodyIgnored", String.class);
        HttpTemplate t5 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, getMethod, new Object[]{"ignored"}, BodyApi.class).build();
        assertNull(t5.getBody());

        // Missing codec for DTO throws IllegalStateException
        Method dtoMethod = BodyApi.class.getMethod("postDto", DummyDto.class);
        SimpleModuleInfo zeroCodecModule = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .codecRegistry(ru.jrestly.codec.CodecRegistry.builder().build())
                .build();
        assertThrows(IllegalStateException.class, () ->
                new HttpTemplateBuilder(zeroCodecModule, httpClient, dtoMethod, new Object[]{new DummyDto("test")}, BodyApi.class).build());
    }

    @Test
    @DisplayName("Supplier body publisher handles null, InputStream, BodyPublisher, Flow.Publisher, Path, File, byte[], String and errors")
    void testSupplierBodyBranches() throws Exception {
        Method supplierMethod = BodyApi.class.getMethod("postSupplierBody", Supplier.class);

        // Supplier returning null -> BodyPublishers.noBody()
        Supplier<?> sNull = () -> null;
        HttpTemplate tNull = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sNull}, BodyApi.class).build();
        assertEquals(0, tNull.getBodyPublisherSupplier().get().contentLength());

        // Supplier returning InputStream
        Supplier<?> sIn = () -> new ByteArrayInputStream(new byte[]{1, 2});
        HttpTemplate tIn = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sIn}, BodyApi.class).build();
        assertNotNull(tIn.getBodyPublisherSupplier().get());

        // Supplier returning BodyPublisher
        HttpRequest.BodyPublisher bp = HttpRequest.BodyPublishers.ofByteArray(new byte[]{1});
        Supplier<?> sBp = () -> bp;
        HttpTemplate tBp = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sBp}, BodyApi.class).build();
        assertSame(bp, tBp.getBodyPublisherSupplier().get());

        // Supplier returning Flow.Publisher
        Flow.Publisher<ByteBuffer> flowPub = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {}
                @Override public void cancel() {}
            });
            subscriber.onNext(ByteBuffer.wrap(new byte[]{1}));
            subscriber.onComplete();
        };
        Supplier<?> sFlow = () -> flowPub;
        HttpTemplate tFlow = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sFlow}, BodyApi.class).build();
        assertNotNull(tFlow.getBodyPublisherSupplier().get());

        // Supplier returning existing Path and File
        File tempFile = File.createTempFile("jrestly-test-", ".txt");
        tempFile.deleteOnExit();
        Supplier<?> sPath = tempFile::toPath;
        HttpTemplate tPath = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sPath}, BodyApi.class).build();
        assertNotNull(tPath.getBodyPublisherSupplier().get());

        Supplier<?> sFile = () -> tempFile;
        HttpTemplate tFile = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sFile}, BodyApi.class).build();
        assertNotNull(tFile.getBodyPublisherSupplier().get());

        // Supplier returning byte[]
        Supplier<?> sBytes = () -> new byte[]{7, 8};
        HttpTemplate tBytes = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sBytes}, BodyApi.class).build();
        assertEquals(2, tBytes.getBodyPublisherSupplier().get().contentLength());

        // Supplier returning String
        Supplier<?> sStr = () -> "sup-str";
        HttpTemplate tStr = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sStr}, BodyApi.class).build();
        assertEquals(7, tStr.getBodyPublisherSupplier().get().contentLength());

        // Supplier returning unsupported type throws IllegalArgumentException
        Supplier<?> sBad = () -> 12345L;
        HttpTemplate tBad = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sBad}, BodyApi.class).build();
        assertThrows(IllegalArgumentException.class, () -> tBad.getBodyPublisherSupplier().get());

        // Supplier returning non-existent Path throws UncheckedIOException
        Supplier<?> sMissingPath = () -> Path.of("/non-existent-path-" + System.nanoTime());
        HttpTemplate tMissingPath = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sMissingPath}, BodyApi.class).build();
        assertThrows(UncheckedIOException.class, () -> tMissingPath.getBodyPublisherSupplier().get());

        // Supplier returning non-existent File throws UncheckedIOException
        Supplier<?> sMissingFile = () -> new File("/non-existent-file-" + System.nanoTime());
        HttpTemplate tMissingFile = new HttpTemplateBuilder(defaultModuleInfo, httpClient, supplierMethod, new Object[]{sMissingFile}, BodyApi.class).build();
        assertThrows(UncheckedIOException.class, () -> tMissingFile.getBodyPublisherSupplier().get());
    }

    @Test
    @DisplayName("adaptToByteBufferPublisher adapts ByteBuffer, byte[], String, Object, errors and completes")
    void testAdaptToByteBufferPublisher() {
        assertNull(HttpTemplateBuilder.adaptToByteBufferPublisher(null, StandardCharsets.UTF_8));

        class TestSource implements Flow.Publisher<Object> {
            Flow.Subscriber<? super Object> subscriber;

            @Override
            public void subscribe(Flow.Subscriber<? super Object> subscriber) {
                this.subscriber = subscriber;
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() {}
                });
            }
        }

        TestSource source = new TestSource();
        Flow.Publisher<ByteBuffer> adapted = HttpTemplateBuilder.adaptToByteBufferPublisher(source, StandardCharsets.UTF_8);
        List<ByteBuffer> received = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        adapted.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {}
            @Override public void onNext(ByteBuffer item) { received.add(item); }
            @Override public void onError(Throwable throwable) { errorRef.set(throwable); }
            @Override public void onComplete() { completed.set(true); }
        });

        source.subscriber.onNext(ByteBuffer.wrap(new byte[]{1}));
        source.subscriber.onNext(new byte[]{2});
        source.subscriber.onNext("hello");
        source.subscriber.onNext(123L);

        assertEquals(4, received.size());

        source.subscriber.onError(new RuntimeException("boom"));
        assertNotNull(errorRef.get());
        assertEquals("boom", errorRef.get().getMessage());

        source.subscriber.onComplete();
        assertTrue(completed.get());
    }

    @Test
    @DisplayName("parseCharset handles quotes, semicolons and unknown charsets")
    void testParseCharset() {
        assertEquals(StandardCharsets.UTF_8, HttpTemplateBuilder.parseCharset(null, StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8, HttpTemplateBuilder.parseCharset("", StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.ISO_8859_1, HttpTemplateBuilder.parseCharset("text/plain; charset=ISO-8859-1", StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_16, HttpTemplateBuilder.parseCharset("text/plain; charset=\"UTF-16\"; other=1", StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8, HttpTemplateBuilder.parseCharset("text/plain; charset=NON_EXISTENT_CS", StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // Tests: Multipart and Urlencoded
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createMultipartParts handles FileContent, File, Path, String, collections, arrays and errors")
    void testMultipartHandling() throws Exception {
        Method uploadMethod = MultipartApi.class.getMethod("upload", Object.class, String.class,
                List.class, List.class, List.class, List.class, List.class, List.class, String[].class, Map.class);

        File tempFile = File.createTempFile("multipart-", ".dat");
        tempFile.deleteOnExit();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mapKey", "mapVal");
        map.put("mapList", List.of("m1", "m2"));
        map.put("mapArray", new String[]{"a1", "a2"});
        map.put(null, "ignored");

        HttpTemplate template = new HttpTemplateBuilder(defaultModuleInfo, httpClient, uploadMethod,
                new Object[]{
                        tempFile,
                        "description",
                        List.of("t1", "t2"),
                        List.of("s1", "s2"),
                        List.of("v1", "v2"),
                        List.of("p1", "p2"),
                        List.of("b1", "b2"),
                        List.of("e1", "e2"),
                        new String[]{"arr1", "arr2"},
                        map
                }, MultipartApi.class).build();

        List<MultipartPart> parts = template.getMultipartFormParts();
        assertNotNull(parts);
        assertNotNull(template.getMultipartBoundary());
        assertTrue(parts.stream().anyMatch(p -> "file".equals(p.name())));
        assertTrue(parts.stream().anyMatch(p -> "tags".equals(p.name()) && "t1,t2".equals(new String((byte[]) p.content(), StandardCharsets.UTF_8))));
        assertTrue(parts.stream().anyMatch(p -> "ssvTags".equals(p.name()) && "s1 s2".equals(new String((byte[]) p.content(), StandardCharsets.UTF_8))));
        assertTrue(parts.stream().anyMatch(p -> "tsvTags".equals(p.name()) && "v1\tv2".equals(new String((byte[]) p.content(), StandardCharsets.UTF_8))));
        assertTrue(parts.stream().anyMatch(p -> "pipeTags".equals(p.name()) && "p1|p2".equals(new String((byte[]) p.content(), StandardCharsets.UTF_8))));
        assertEquals(2, parts.stream().filter(p -> "bracketTags[]".equals(p.name())).count());
        assertEquals(2, parts.stream().filter(p -> "explodeTags".equals(p.name())).count());
        assertTrue(parts.stream().anyMatch(p -> "mapKey".equals(p.name())));

        // Bare byte[] for multipart file throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
                new HttpTemplateBuilder(defaultModuleInfo, httpClient, uploadMethod,
                        new Object[]{new byte[]{1, 2}, "desc", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), new String[0], Map.of()},
                        MultipartApi.class).build());

        // Unsupported multipart file type throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
                new HttpTemplateBuilder(defaultModuleInfo, httpClient, uploadMethod,
                        new Object[]{12345L, "desc", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), new String[0], Map.of()},
                        MultipartApi.class).build());

        // FileContent accepted
        FileContent fc = FileContent.of("custom.txt", new byte[]{1, 2, 3});
        HttpTemplate tFc = new HttpTemplateBuilder(defaultModuleInfo, httpClient, uploadMethod,
                new Object[]{fc, "desc", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), new String[0], Map.of()},
                MultipartApi.class).build();
        assertTrue(tFc.getMultipartFormParts().stream().anyMatch(p -> "custom.txt".equals(p.filename())));

        // Calling createMultipartParts directly on non-multipart builder throws UnsupportedOperationException
        Method jsonMethod = BodyApi.class.getMethod("postBody", Object.class);
        HttpTemplateBuilder nonMultiBuilder = new HttpTemplateBuilder(defaultModuleInfo, httpClient, jsonMethod, new Object[]{"body"}, BodyApi.class);
        nonMultiBuilder.build();
        assertThrows(UnsupportedOperationException.class, nonMultiBuilder::createMultipartParts);
    }

    @Test
    @DisplayName("createUrlEncodedBody encodes parameters and validates constraints")
    void testUrlEncodedHandling() throws Exception {
        Method formMethod = UrlEncodedApi.class.getMethod("submitForm", String.class, String.class);
        HttpTemplate t1 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, formMethod,
                new Object[]{"john doe", ""}, UrlEncodedApi.class).build();
        String body = new String(t1.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("user=john+doe"));
        assertTrue(body.contains("empty="));

        // When parameter is null, it is omitted
        HttpTemplate tNull = new HttpTemplateBuilder(defaultModuleInfo, httpClient, formMethod,
                new Object[]{"john doe", null}, UrlEncodedApi.class).build();
        String bodyNull = new String(tNull.getBody(), StandardCharsets.UTF_8);
        assertEquals("user=john+doe", bodyNull);

        // Method with no parameters throws UnsupportedOperationException
        Method emptyMethod = UrlEncodedApi.class.getMethod("submitEmpty");
        assertThrows(UnsupportedOperationException.class, () ->
                new HttpTemplateBuilder(defaultModuleInfo, httpClient, emptyMethod, new Object[0], UrlEncodedApi.class).build());

        // Direct call on non-urlencoded builder throws UnsupportedOperationException
        Method jsonMethod = BodyApi.class.getMethod("postBody", Object.class);
        HttpTemplateBuilder nonFormBuilder = new HttpTemplateBuilder(defaultModuleInfo, httpClient, jsonMethod, new Object[]{"body"}, BodyApi.class);
        nonFormBuilder.build();
        assertThrows(UnsupportedOperationException.class, nonFormBuilder::createUrlEncodedBody);
    }

    // -------------------------------------------------------------------------
    // Tests: Compression, Resilience, Interceptors, Metrics and Options
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Compression compresses body above threshold and handles errors")
    void testCompression() throws Exception {
        Method compressMethod = CompressionApi.class.getMethod("postCompressed", byte[].class);
        byte[] large = new byte[100];
        Arrays.fill(large, (byte) 'A');

        HttpTemplate template = new HttpTemplateBuilder(defaultModuleInfo, httpClient, compressMethod,
                new Object[]{large}, CompressionApi.class).build();

        assertTrue(template.getBody().length < large.length);
        assertTrue(template.getHeaders().stream().anyMatch(h -> "Content-Encoding".equalsIgnoreCase(h.name()) && "gzip".equals(h.value())));

        // Compressor throwing IOException wrapped in RuntimeException
        ContentCompressor brokenCompressor = new ContentCompressor() {
            @Override public String encoding() { return "gzip"; }
            @Override public byte[] compress(byte[] body) throws IOException { throw new IOException("disk full"); }
            @Override public byte[] decompress(byte[] compressedBody) { return compressedBody; }
        };
        SimpleModuleInfo moduleWithBroken = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .contentCompressor(brokenCompressor)
                .requestCompression(CompressionAlgorithm.GZIP)
                .build();
        assertThrows(RuntimeException.class, () ->
                new HttpTemplateBuilder(moduleWithBroken, httpClient, compressMethod, new Object[]{large}, CompressionApi.class).build());
    }

    @Test
    @DisplayName("Resilience annotations resolve enabled CircuitBreaker and RateLimiter")
    void testResilienceAnnotations() throws Exception {
        CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.ofDefaults();
        RateLimiterRegistry rlReg = RateLimiterRegistry.ofDefaults();
        SimpleModuleInfo resModule = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .circuitBreakerRegistry(cbReg)
                .rateLimiterRegistry(rlReg)
                .build();

        Method resilientMethod = ResilienceApi.class.getMethod("getResilient");
        HttpTemplate t1 = new HttpTemplateBuilder(resModule, httpClient, resilientMethod, new Object[0], ResilienceApi.class).build();
        assertNotNull(t1.getCircuitBreaker());
        assertNotNull(t1.getRateLimiter());

        Method disabledMethod = ResilienceApi.class.getMethod("getDisabledResilience");
        HttpTemplate t2 = new HttpTemplateBuilder(resModule, httpClient, disabledMethod, new Object[0], ResilienceApi.class).build();
        assertNull(t2.getCircuitBreaker());
        assertNull(t2.getRateLimiter());
    }

    @Test
    @DisplayName("Interceptors instantiation and caching")
    void testInterceptorsResolution() throws Exception {
        Method m1 = InterceptorApi.class.getMethod("getIntercepted");
        Map<Class<?>, Object> cache = new HashMap<>();
        HttpTemplateBuilder builder = new HttpTemplateBuilder(defaultModuleInfo, httpClient, m1, new Object[0], InterceptorApi.class);
        builder.interceptorCache(cache);
        HttpTemplate t1 = builder.build();
        assertEquals(1, t1.getRequestInterceptors().size());
        assertTrue(cache.containsKey(PrivateNoArgInterceptor.class));

        // Interceptor without accessible no-arg constructor throws IllegalStateException
        Method m2 = InterceptorApi.class.getMethod("getBadInterceptor");
        assertThrows(IllegalStateException.class, () ->
                new HttpTemplateBuilder(defaultModuleInfo, httpClient, m2, new Object[0], InterceptorApi.class).build());
    }

    @Test
    @DisplayName("Metrics listeners combined into composite when multiple are present")
    void testMetricsResolution() throws Exception {
        Method m = MetricsApi.class.getMethod("getMetrics");
        HttpTemplate template = new HttpTemplateBuilder(defaultModuleInfo, httpClient, m, new Object[0], MetricsApi.class).build();
        assertNotNull(template.getMetricsListener());
        // CompositeClientMetricsListener created
        assertInstanceOf(ClientMetricsListener.class, template.getMetricsListener());
    }

    @Test
    @DisplayName("Builder options: custom logger, null httpClient, and HttpEngine constructor")
    void testBuilderOptions() throws Exception {
        Method m = UrlApi.class.getMethod("getWithPathVariable", String.class);
        System.Logger customLogger = System.getLogger("custom-logger");

        HttpTemplateBuilder b1 = new HttpTemplateBuilder(defaultModuleInfo, httpClient, m, new Object[]{"1"}, UrlApi.class);
        b1.logger(customLogger);
        HttpTemplate t1 = b1.build();
        assertSame(customLogger, t1.getLogger());

        // Null HttpClient constructor passes null engine
        HttpTemplateBuilder b2 = new HttpTemplateBuilder(defaultModuleInfo, (HttpClient) null, m, new Object[]{"1"}, UrlApi.class);
        HttpTemplate t2 = b2.build();
        assertNull(t2.getHttpEngine());

        // HttpEngine constructor
        ru.jrestly.engine.HttpEngine engine = ru.jrestly.engine.JdkHttpEngine.from(httpClient);
        HttpTemplateBuilder b3 = new HttpTemplateBuilder(defaultModuleInfo, engine, m, new Object[]{"1"}, UrlApi.class);
        HttpTemplate t3 = b3.build();
        assertSame(engine, t3.getHttpEngine());
    }

    @Test
    @DisplayName("HttpTemplate getters return configured metadata from builder")
    void testContractGetters() throws Exception {
        Method method = ContractInspectionApi.class.getMethod("inspect");
        HttpTemplate template = new HttpTemplateBuilder(defaultModuleInfo, httpClient, method, new Object[0], ContractInspectionApi.class).build();

        assertEquals(HttpMethod.GET, template.getHttpMethod());
        assertNotNull(template.getAuthDetailsConsumer());
        assertEquals(String.class, template.getReturnType());
        assertTrue(template.isAsync());
        assertTrue(template.isOptional());
        assertEquals(List.of(200, 204), template.getExpectStatuses());
        assertEquals(3, template.getFollowRedirectsNumber());
        assertEquals(HttpClient.Version.HTTP_2, template.getHttpVersion());
        assertEquals(3, template.getRetryPolicy().maxAttempts());
    }
}
