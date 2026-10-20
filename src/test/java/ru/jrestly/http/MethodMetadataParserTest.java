package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.Authorization;
import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Delete;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.FollowRedirects;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Order;
import ru.jrestly.annotation.Patch;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Put;
import ru.jrestly.annotation.QueryCollectionFormat;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.SetAuthDetails;
import ru.jrestly.annotation.TargetUrl;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.annotation.Trace;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.annotation.WithMetrics;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.metrics.ClientMetricsListener;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-first unit tests for {@link MethodMetadataParser} collaborator (ADR-0090).
 * Verifies 100% extraction logic, reflection resolution, edge branches, and zero missed branches.
 */
class MethodMetadataParserTest {

    // --- Fixture Interfaces ---

    interface BaseApi {
        @Get("/base")
        String base();
    }

    interface LeftApi extends BaseApi {}
    interface RightApi extends BaseApi {}
    interface DiamondApi extends LeftApi, RightApi {
        @Get("/diamond")
        String diamond();
    }

    interface AllVerbsApi {
        @Get("/get") String get();
        @Post("/post") String post();
        @Put("/put") String put();
        @Patch("/patch") String patch();
        @Delete("/delete") String delete();
        String noVerb();
    }

    @ContentType("application/json")
    @Accept({"application/json", "application/xml"})
    @Trace(enabled = true)
    @HttpVersion(HttpClient.Version.HTTP_2)
    @QueryCollectionFormat(CollectionFormat.CSV)
    @Cookie(name = "ifaceCookie", value = "cval")
    @Cookie(name = "   ", value = "blankName")
    @RequestHeader(name = "X-Iface", value = "iface-val")
    @RequestDefaultParam(name = "pIface", value = "vIface")
    interface RichInterfaceApi {
        @Post(value = "/submit", requestType = RequestType.APPLICATION_JSON, params = {
                @RequestDefaultParam(name = "verbParam", value = "vVerb")
        })
        @ContentType("application/xml")
        @Accept("application/xml")
        @Trace(enabled = false)
        @HttpVersion(HttpClient.Version.HTTP_1_1)
        @QueryCollectionFormat(CollectionFormat.PIPES)
        @Cookie(name = "methodCookie", value = "mval")
        @RequestHeader(name = "X-Method", value = "method-val")
        @RequestDefaultParam(name = "pMethod", value = "vMethod")
        @ExpectStatus(statuses = {200, 201})
        @FollowRedirects(count = 3)
        @Retry(times = 2, initialBackoffMs = 50, maxBackoffMs = 200)
        @SetAuthDetails(headerName = "Authorization")
        @Authorization
        String richMethod(
                @TargetUrl String url,
                @PathVariable(name = "id", encoded = true) String id,
                @PathVariable String implicitPath,
                @RequestHeader(name = "X-Custom") String header,
                @RequestHeader String implicitHeader,
                @Cookie(name = "session") String cookie,
                @Cookie String implicitCookie,
                @RequestParam(name = "q", contentType = "text/plain", format = CollectionFormat.SSV) String q,
                @RequestParam String implicitParam,
                @RequestBody Object body,
                @MultipartFormFile(partName = "file", contentType = "image/png") byte[] filePart,
                ResponseHeaders responseHeaders,
                UploadProgressListener uploadListener,
                DownloadProgressListener downloadListener
        );
    }

    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    interface InterfaceTimeoutApi {
        @Get("/timeout")
        String timeout();
    }

    @Timeout(value = -1, unit = TimeUnit.SECONDS)
    interface InvalidInterfaceTimeoutApi {
        @Get("/invalid")
        String invalid();
    }

    interface MethodTimeoutApi {
        @Get("/methodTimeout")
        @Timeout(value = 10, unit = TimeUnit.MILLISECONDS)
        String methodTimeout();

        @Get("/invalidMethodTimeout")
        @Timeout(value = 0, unit = TimeUnit.SECONDS)
        String invalidMethodTimeout();
    }

    @Order(10)
    static class OrderedReqInterceptor1 implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, ru.jrestly.interceptor.RequestContext context) {
            return request;
        }
    }

    @Order(5)
    static class OrderedReqInterceptor2 implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, ru.jrestly.interceptor.RequestContext context) {
            return request;
        }
    }

    static class SimpleRespInterceptor implements ResponseInterceptor {
        @Override
        public void onResponse(ru.jrestly.interceptor.ResponseContext context) {}
    }

    static class MethodOnlyReqInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, ru.jrestly.interceptor.RequestContext context) {
            return request;
        }
    }

    static class MethodOnlyRespInterceptor implements ResponseInterceptor {
        @Override
        public void onResponse(ru.jrestly.interceptor.ResponseContext context) {}
    }

    @WithInterceptor({OrderedReqInterceptor1.class, OrderedReqInterceptor2.class, SimpleRespInterceptor.class, String.class})
    interface InterceptorParentApi {}

    @WithInterceptor({OrderedReqInterceptor1.class}) // duplicate test
    interface InterceptorSubApi extends InterceptorParentApi {
        @Get("/intercepted")
        @WithInterceptor({MethodOnlyRespInterceptor.class, MethodOnlyReqInterceptor.class, String.class})
        String intercepted();
    }

    static class MetricsListener1 implements ClientMetricsListener {
        @Override
        public void onCallComplete(ru.jrestly.metrics.CallMetrics metrics) {}
    }

    static class MetricsListener2 implements ClientMetricsListener {
        @Override
        public void onCallComplete(ru.jrestly.metrics.CallMetrics metrics) {}
    }

    @WithMetrics({MetricsListener1.class, String.class})
    interface MetricsParentApi {}

    @WithMetrics({MetricsListener1.class, MetricsListener2.class}) // duplicate test
    interface MetricsSubApi extends MetricsParentApi {
        @Get("/metrics")
        @WithMetrics({MetricsListener2.class, String.class, MetricsListener1.class})
        String metrics();
    }

    interface DuplicateParamsApi {
        @Get("/duplicates")
        @Cookie(name = "")
        String duplicates(
                @TargetUrl String u1,
                @TargetUrl String u2,
                @RequestBody Object b1,
                @RequestBody Object b2,
                ResponseHeaders r1,
                ResponseHeaders r2,
                UploadProgressListener up1,
                UploadProgressListener up2,
                DownloadProgressListener dp1,
                DownloadProgressListener dp2,
                @RequestParam(format = CollectionFormat.DEFAULT) String p1
        );
    }

    @Compress(enabled = false)
    interface DisabledCompressApi {
        @Get("/comp")
        String comp();
    }

    @Compress(enabled = true, algorithm = CompressionAlgorithm.GZIP, minSizeBytes = -100)
    interface NegativeMinSizeCompressApi {
        @Get("/comp")
        String comp();
    }

    interface MethodCompressApi {
        @Get("/disabled")
        @Compress(enabled = false)
        String disabled();

        @Get("/enabled")
        @Compress(enabled = true, algorithm = CompressionAlgorithm.DEFLATE, minSizeBytes = 512)
        String enabled();
    }

    @ru.jrestly.annotation.CircuitBreaker(name = "ifaceCb")
    @ru.jrestly.annotation.RateLimiter(name = "ifaceRl")
    interface ResilienceParentApi {
        @Get("/parent")
        String parent();

        @Get("/override")
        @ru.jrestly.annotation.CircuitBreaker(name = "methodCb")
        @ru.jrestly.annotation.RateLimiter(name = "methodRl")
        String override();
    }

    interface ReturnTypesApi {
        @Get("/sync") String sync();
        @Get("/async") CompletableFuture<String> async();
        @Get("/optional") Optional<String> optional();
        @Get("/asyncOptional") CompletableFuture<Optional<String>> asyncOptional();
        @SuppressWarnings("rawtypes")
        @Get("/rawOptional") Optional rawOptional();
        @Get("/inputStream") InputStream inputStream();
        @Get("/stringStream") Stream<String> stringStream();
        @Get("/sseStream") Stream<ServerSentEvent> sseStream();
    }

    @QueryCollectionFormat(CollectionFormat.DEFAULT)
    interface DefaultFormatParentApi {
        @Get("/format")
        @QueryCollectionFormat(CollectionFormat.DEFAULT)
        String format();
    }

    @OnError(statuses = {400, 404}, errorObject = String.class)
    @OnError(errorObject = RuntimeException.class)
    interface OnErrorParentApi {}

    @OnError(statuses = {500}, errorObject = IllegalStateException.class)
    interface OnErrorSubApi extends OnErrorParentApi {
        @Get("/error")
        @OnError(statuses = {503}, errorObject = UnsupportedOperationException.class)
        @OnError(errorObject = IllegalArgumentException.class)
        String error();

        @Get("/noError")
        String noError();
    }

    // --- Tests ---

    @Test
    @DisplayName("Private constructor throws UnsupportedOperationException")
    void privateConstructorThrows() throws Exception {
        Constructor<MethodMetadataParser> ctor = MethodMetadataParser.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, ctor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }

    @Test
    @DisplayName("Full parse of rich method extracts all metadata correctly")
    void richMethodParse() throws Exception {
        Method method = RichInterfaceApi.class.getMethod("richMethod",
                String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, Object.class,
                byte[].class, ResponseHeaders.class, UploadProgressListener.class, DownloadProgressListener.class);

        MethodMetadata meta = MethodMetadataParser.parse(method, RichInterfaceApi.class);

        assertNotNull(meta);
        assertEquals(HttpMethod.POST, meta.requestMeta().httpMethod());
        assertEquals("/submit", meta.requestMeta().path());
        assertEquals(RequestType.APPLICATION_JSON, meta.requestMeta().requestType());
        assertEquals("application/xml", meta.methodContentType());
        assertEquals("application/json", meta.interfaceContentType());
        assertEquals("application/xml", meta.methodAccept());
        assertEquals("application/json, application/xml", meta.interfaceAccept());
        assertFalse(meta.hasTrace());
        assertEquals(HttpClient.Version.HTTP_1_1, meta.httpVersion());
        assertEquals(CollectionFormat.PIPES, meta.defaultCollectionFormat());
        assertEquals(List.of(200, 201), meta.expectStatuses());
        assertEquals(3, meta.followRedirectsNumber());
        assertEquals(2, meta.retryPolicy().maxAttempts());
        assertEquals("Authorization", meta.authDetailsHeaderName());
        assertTrue(meta.hasAuthAnnotation());
        assertTrue(meta.hasTargetUrl());
        assertEquals(0, meta.targetUrlParamIndex());
        assertTrue(meta.hasPathVariables());
        assertTrue(meta.hasRequestHeaders());
        assertTrue(meta.hasCookieParams());
        assertTrue(meta.hasRequestParams());
        assertEquals(9, meta.requestBodyIndex());
        assertTrue(meta.hasMultipartParams());
        assertEquals(11, meta.responseHeadersParamIndex());
        assertEquals(12, meta.uploadProgressParamIndex());
        assertEquals(13, meta.downloadProgressParamIndex());

        // Parameter descriptors
        MethodMetadata.ParameterDescriptor[] descs = meta.parameterDescriptors();
        assertEquals(14, descs.length);

        // Path variables
        assertTrue(descs[1].isPathVariable());
        assertEquals("id", descs[1].pathVariableName());
        assertEquals("{id}", descs[1].placeholderToken());
        assertTrue(descs[1].pathVariableEncoded());

        assertTrue(descs[2].isPathVariable());
        assertNotNull(descs[2].pathVariableName());

        // Headers
        assertTrue(descs[3].isRequestHeader());
        assertEquals("X-Custom", descs[3].requestHeaderName());
        assertTrue(descs[4].isRequestHeader());

        // Cookies
        assertTrue(descs[5].isCookie());
        assertEquals("session", descs[5].cookieName());
        assertTrue(descs[6].isCookie());

        // Request params
        assertTrue(descs[7].isRequestParam());
        assertEquals("q", descs[7].requestParamName());
        assertEquals("text/plain", descs[7].requestParamContentType());
        assertEquals(CollectionFormat.SSV, descs[7].requestParamFormat());
        assertTrue(descs[8].isRequestParam());

        // Body and multipart
        assertTrue(descs[9].isRequestBody());
        assertTrue(descs[10].isMultipartFile());
        assertEquals("file", descs[10].multipartPartName());
        assertEquals("image/png", descs[10].multipartExplicitContentType());

        // ResponseHeaders & Listeners
        assertTrue(descs[11].isResponseHeaders());
        assertTrue(descs[12].isUploadProgressListener());
        assertTrue(descs[13].isDownloadProgressListener());
    }

    @Test
    @DisplayName("Parse all HTTP verbs and fail on missing verb")
    void httpVerbsParse() throws Exception {
        Method get = AllVerbsApi.class.getMethod("get");
        Method post = AllVerbsApi.class.getMethod("post");
        Method put = AllVerbsApi.class.getMethod("put");
        Method patch = AllVerbsApi.class.getMethod("patch");
        Method delete = AllVerbsApi.class.getMethod("delete");
        Method noVerb = AllVerbsApi.class.getMethod("noVerb");

        assertEquals(HttpMethod.GET, MethodMetadataParser.parseRequestMeta(get).httpMethod());
        assertEquals(HttpMethod.POST, MethodMetadataParser.parseRequestMeta(post).httpMethod());
        assertEquals(HttpMethod.PUT, MethodMetadataParser.parseRequestMeta(put).httpMethod());
        assertEquals(HttpMethod.PATCH, MethodMetadataParser.parseRequestMeta(patch).httpMethod());
        assertEquals(HttpMethod.DELETE, MethodMetadataParser.parseRequestMeta(delete).httpMethod());

        assertNull(MethodMetadataParser.parseRequestMeta(null));
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> MethodMetadataParser.parseRequestMeta(noVerb));
        assertTrue(ex.getMessage().contains("No HTTP method annotation found"));
    }

    @Test
    @DisplayName("collectInterfaceHierarchy handles diamond inheritance and null class")
    void diamondInheritanceHierarchy() {
        List<Class<?>> hierarchy = MethodMetadataParser.collectInterfaceHierarchy(DiamondApi.class);
        assertEquals(4, hierarchy.size());
        assertEquals(BaseApi.class, hierarchy.get(0));
        assertTrue(hierarchy.contains(LeftApi.class));
        assertTrue(hierarchy.contains(RightApi.class));
        assertEquals(DiamondApi.class, hierarchy.get(3));

        assertTrue(MethodMetadataParser.collectInterfaceHierarchy(null).isEmpty());
    }

    @Test
    @DisplayName("removeCaseInsensitive handles null name and case-insensitivity")
    void removeCaseInsensitive() {
        Map<String, String> map = new HashMap<>();
        map.put("Content-Type", "json");
        map.put("Accept", "text");

        MethodMetadataParser.removeCaseInsensitive(map, null);
        assertEquals(2, map.size());

        MethodMetadataParser.removeCaseInsensitive(map, "content-type");
        assertEquals(1, map.size());
        assertFalse(map.containsKey("Content-Type"));
    }

    @Test
    @DisplayName("formatCookieHeader handles null, empty, and multiple entries")
    void formatCookieHeader() {
        assertNull(MethodMetadataParser.formatCookieHeader(null));
        assertNull(MethodMetadataParser.formatCookieHeader(Collections.emptyMap()));

        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("a", "1");
        cookies.put("b", "2");
        assertEquals("a=1; b=2", MethodMetadataParser.formatCookieHeader(cookies));
    }

    @Test
    @DisplayName("checkHasContentTypeHeader handles null, missing, and case variants")
    void checkHasContentTypeHeader() {
        assertFalse(MethodMetadataParser.checkHasContentTypeHeader(null));
        assertFalse(MethodMetadataParser.checkHasContentTypeHeader(List.of()));
        assertFalse(MethodMetadataParser.checkHasContentTypeHeader(List.of(new Header("Accept", "json"))));
        assertTrue(MethodMetadataParser.checkHasContentTypeHeader(List.of(new Header("content-type", "json"))));
        assertTrue(MethodMetadataParser.checkHasContentTypeHeader(List.of(new Header("Content-Type", "json"))));
    }

    @Test
    @DisplayName("checkIsConditional covers all conditional headers and null checks")
    void checkIsConditional() {
        assertFalse(MethodMetadataParser.checkIsConditional(null));
        assertFalse(MethodMetadataParser.checkIsConditional(List.of()));
        assertFalse(MethodMetadataParser.checkIsConditional(List.of(new Header(null, "val"))));
        assertFalse(MethodMetadataParser.checkIsConditional(List.of(new Header("Other", "val"))));

        assertTrue(MethodMetadataParser.checkIsConditional(List.of(new Header("if-none-match", "\"123\""))));
        assertTrue(MethodMetadataParser.checkIsConditional(List.of(new Header("IF-MODIFIED-SINCE", "date"))));
        assertTrue(MethodMetadataParser.checkIsConditional(List.of(new Header("If-Match", "\"123\""))));
        assertTrue(MethodMetadataParser.checkIsConditional(List.of(new Header("if-unmodified-since", "date"))));
    }

    @Test
    @DisplayName("parseStaticCookies filters blank names and merges interface and method")
    void parseStaticCookies() throws Exception {
        Method method = RichInterfaceApi.class.getMethod("richMethod",
                String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, Object.class,
                byte[].class, ResponseHeaders.class, UploadProgressListener.class, DownloadProgressListener.class);
        List<Class<?>> hierarchy = List.of(RichInterfaceApi.class);

        Map<String, String> cookies = MethodMetadataParser.parseStaticCookies(method, hierarchy);
        assertEquals(2, cookies.size());
        assertEquals("cval", cookies.get("ifaceCookie"));
        assertEquals("mval", cookies.get("methodCookie"));
        assertFalse(cookies.containsKey("   "));

        // With null method
        Map<String, String> ifaceOnly = MethodMetadataParser.parseStaticCookies(null, hierarchy);
        assertEquals(1, ifaceOnly.size());
        assertEquals("cval", ifaceOnly.get("ifaceCookie"));
    }

    @Test
    @DisplayName("parseStaticDefaultParams covers null requestMeta, verb params, and method params")
    void parseStaticDefaultParams() throws Exception {
        Method method = RichInterfaceApi.class.getMethod("richMethod",
                String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, Object.class,
                byte[].class, ResponseHeaders.class, UploadProgressListener.class, DownloadProgressListener.class);
        List<Class<?>> hierarchy = List.of(RichInterfaceApi.class);

        RequestMeta requestMeta = MethodMetadataParser.parseRequestMeta(method);
        List<Param> params = MethodMetadataParser.parseStaticDefaultParams(method, hierarchy, requestMeta);

        assertEquals(3, params.size());
        assertEquals(new Param("pIface", "vIface"), params.get(0));
        assertEquals(new Param("verbParam", "vVerb"), params.get(1));
        assertEquals(new Param("pMethod", "vMethod"), params.get(2));

        // When requestMeta is null or has null defaultParams
        List<Param> paramsNullMeta = MethodMetadataParser.parseStaticDefaultParams(method, hierarchy, null);
        assertEquals(2, paramsNullMeta.size());
    }

    @Test
    @DisplayName("parseDefaultCollectionFormat handles DEFAULT fallback to EXPLODE")
    void defaultCollectionFormatFallback() throws Exception {
        Method format = DefaultFormatParentApi.class.getMethod("format");
        List<Class<?>> hierarchy = List.of(DefaultFormatParentApi.class);

        assertEquals(CollectionFormat.EXPLODE, MethodMetadataParser.parseDefaultCollectionFormat(format, hierarchy));
        assertEquals(CollectionFormat.EXPLODE, MethodMetadataParser.parseDefaultCollectionFormat(null, List.of()));
    }

    @Test
    @DisplayName("parseTimeout validates method and interface timeouts")
    void parseTimeoutValidation() throws Exception {
        Method validIface = InterfaceTimeoutApi.class.getMethod("timeout");
        Duration d1 = MethodMetadataParser.parseTimeout(validIface, List.of(InterfaceTimeoutApi.class));
        assertEquals(Duration.ofSeconds(5), d1);

        Method invalidIface = InvalidInterfaceTimeoutApi.class.getMethod("invalid");
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
                () -> MethodMetadataParser.parseTimeout(invalidIface, List.of(InvalidInterfaceTimeoutApi.class)));
        assertTrue(ex1.getMessage().contains("must be strictly positive"));

        Method validMethod = MethodTimeoutApi.class.getMethod("methodTimeout");
        Duration d2 = MethodMetadataParser.parseTimeout(validMethod, List.of(MethodTimeoutApi.class));
        assertEquals(Duration.ofMillis(10), d2);

        Method invalidMethod = MethodTimeoutApi.class.getMethod("invalidMethodTimeout");
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> MethodMetadataParser.parseTimeout(invalidMethod, List.of(MethodTimeoutApi.class)));
        assertTrue(ex2.getMessage().contains("must be strictly positive"));

        assertNull(MethodMetadataParser.parseTimeout(null, List.of()));
    }

    @Test
    @DisplayName("parseInterceptors respects order, deduplicates, and splits request/response")
    void parseInterceptorsOrderAndDeduplication() throws Exception {
        Method method = InterceptorSubApi.class.getMethod("intercepted");
        List<Class<?>> hierarchy = List.of(InterceptorParentApi.class, InterceptorSubApi.class);

        var resolved = MethodMetadataParser.parseInterceptors(method, hierarchy);
        assertEquals(3, resolved.requestInterceptors().size());
        // OrderedReqInterceptor2 has order 5, OrderedReqInterceptor1 has order 10
        assertEquals(OrderedReqInterceptor2.class, resolved.requestInterceptors().get(0));
        assertEquals(OrderedReqInterceptor1.class, resolved.requestInterceptors().get(1));
        assertEquals(MethodOnlyReqInterceptor.class, resolved.requestInterceptors().get(2));

        assertEquals(2, resolved.responseInterceptors().size());
        assertEquals(SimpleRespInterceptor.class, resolved.responseInterceptors().get(0));
        assertEquals(MethodOnlyRespInterceptor.class, resolved.responseInterceptors().get(1));
    }

    @Test
    @DisplayName("parseMetricsListeners deduplicates and filters classes")
    void parseMetricsListeners() throws Exception {
        Method method = MetricsSubApi.class.getMethod("metrics");
        List<Class<?>> hierarchy = List.of(MetricsParentApi.class, MetricsSubApi.class);

        List<Class<? extends ClientMetricsListener>> listeners = MethodMetadataParser.parseMetricsListeners(method, hierarchy);
        assertEquals(2, listeners.size());
        assertEquals(MetricsListener1.class, listeners.get(0));
        assertEquals(MetricsListener2.class, listeners.get(1));
    }

    @Test
    @DisplayName("parseCompression handles disabled, negative minSizeBytes, and method overrides")
    void parseCompression() throws Exception {
        Method disabled = DisabledCompressApi.class.getMethod("comp");
        var c1 = MethodMetadataParser.parseCompression(disabled, List.of(DisabledCompressApi.class));
        assertTrue(c1.explicit());
        assertFalse(c1.enabled());

        Method negative = NegativeMinSizeCompressApi.class.getMethod("comp");
        var c2 = MethodMetadataParser.parseCompression(negative, List.of(NegativeMinSizeCompressApi.class));
        assertTrue(c2.explicit());
        assertTrue(c2.enabled());
        assertEquals(0, c2.minSizeBytes());

        Method methodDisabled = MethodCompressApi.class.getMethod("disabled");
        var c3 = MethodMetadataParser.parseCompression(methodDisabled, List.of(MethodCompressApi.class));
        assertTrue(c3.explicit());
        assertFalse(c3.enabled());

        Method methodEnabled = MethodCompressApi.class.getMethod("enabled");
        var c4 = MethodMetadataParser.parseCompression(methodEnabled, List.of(MethodCompressApi.class));
        assertTrue(c4.explicit());
        assertTrue(c4.enabled());
        assertEquals(512, c4.minSizeBytes());
        assertEquals(CompressionAlgorithm.DEFLATE, c4.algorithm());

        var cDefault = MethodMetadataParser.parseCompression(null, List.of());
        assertFalse(cDefault.explicit());
        assertFalse(cDefault.enabled());
    }

    @Test
    @DisplayName("parseCircuitBreaker and parseRateLimiter handle inheritance and overrides")
    void parseResilienceAnnotations() throws Exception {
        Method parent = ResilienceParentApi.class.getMethod("parent");
        Method override = ResilienceParentApi.class.getMethod("override");
        List<Class<?>> hierarchy = List.of(ResilienceParentApi.class);

        assertEquals("ifaceCb", MethodMetadataParser.parseCircuitBreaker(parent, hierarchy).name());
        assertEquals("methodCb", MethodMetadataParser.parseCircuitBreaker(override, hierarchy).name());
        assertNull(MethodMetadataParser.parseCircuitBreaker(null, List.of()));

        assertEquals("ifaceRl", MethodMetadataParser.parseRateLimiter(parent, hierarchy).name());
        assertEquals("methodRl", MethodMetadataParser.parseRateLimiter(override, hierarchy).name());
        assertNull(MethodMetadataParser.parseRateLimiter(null, List.of()));
    }

    @Test
    @DisplayName("parseReturnType parses sync, async, optional, raw optional, and streaming types")
    void parseReturnTypeVariants() throws Exception {
        Method sync = ReturnTypesApi.class.getMethod("sync");
        var rSync = MethodMetadataParser.parseReturnType(sync.getGenericReturnType());
        assertEquals(String.class, rSync.effectiveReturnType());
        assertFalse(rSync.async());
        assertFalse(rSync.optional());
        assertFalse(rSync.streamBody());

        Method async = ReturnTypesApi.class.getMethod("async");
        var rAsync = MethodMetadataParser.parseReturnType(async.getGenericReturnType());
        assertEquals(String.class, rAsync.effectiveReturnType());
        assertTrue(rAsync.async());
        assertFalse(rAsync.optional());

        Method optional = ReturnTypesApi.class.getMethod("optional");
        var rOptional = MethodMetadataParser.parseReturnType(optional.getGenericReturnType());
        assertEquals(String.class, rOptional.effectiveReturnType());
        assertFalse(rOptional.async());
        assertTrue(rOptional.optional());

        Method asyncOptional = ReturnTypesApi.class.getMethod("asyncOptional");
        var rAsyncOptional = MethodMetadataParser.parseReturnType(asyncOptional.getGenericReturnType());
        assertEquals(String.class, rAsyncOptional.effectiveReturnType());
        assertTrue(rAsyncOptional.async());
        assertTrue(rAsyncOptional.optional());

        Method rawOptional = ReturnTypesApi.class.getMethod("rawOptional");
        var rRawOptional = MethodMetadataParser.parseReturnType(rawOptional.getGenericReturnType());
        assertEquals(Object.class, rRawOptional.effectiveReturnType());
        assertTrue(rRawOptional.optional());

        Method is = ReturnTypesApi.class.getMethod("inputStream");
        assertTrue(MethodMetadataParser.parseReturnType(is.getGenericReturnType()).streamBody());

        Method ss = ReturnTypesApi.class.getMethod("stringStream");
        assertTrue(MethodMetadataParser.parseReturnType(ss.getGenericReturnType()).streamBody());

        Method sse = ReturnTypesApi.class.getMethod("sseStream");
        assertTrue(MethodMetadataParser.parseReturnType(sse.getGenericReturnType()).streamBody());
    }

    @Test
    @DisplayName("parseOnErrorParser registers interface, sub-interface and method error handlers")
    void parseOnErrorHierarchy() throws Exception {
        Method error = OnErrorSubApi.class.getMethod("error");
        List<Class<?>> hierarchy = List.of(OnErrorParentApi.class, OnErrorSubApi.class);

        OnErrorParser parser = MethodMetadataParser.parseOnErrorParser(error, hierarchy);
        assertNotNull(parser);
        assertEquals(UnsupportedOperationException.class, parser.getErrorClass(503));
        assertEquals(IllegalStateException.class, parser.getErrorClass(500));
        assertEquals(String.class, parser.getErrorClass(400));
        assertEquals(String.class, parser.getErrorClass(404));
        assertEquals(IllegalArgumentException.class, parser.getErrorClass(418)); // method default

        Method noError = OnErrorSubApi.class.getMethod("noError");
        OnErrorParser parser2 = MethodMetadataParser.parseOnErrorParser(noError, hierarchy);
        assertNotNull(parser2);
        assertEquals(RuntimeException.class, parser2.getErrorClass(418)); // interface default

        assertNull(MethodMetadataParser.parseOnErrorParser(null, List.of()));
    }

    @Test
    @DisplayName("Parse duplicate parameters and default format parameter")
    void duplicateParamsAndDefaults() throws Exception {
        Method method = DuplicateParamsApi.class.getMethod("duplicates",
                String.class, String.class, Object.class, Object.class,
                ResponseHeaders.class, ResponseHeaders.class,
                UploadProgressListener.class, UploadProgressListener.class,
                DownloadProgressListener.class, DownloadProgressListener.class,
                String.class);

        MethodMetadata meta = MethodMetadataParser.parse(method, DuplicateParamsApi.class);
        assertNotNull(meta);
        assertEquals(0, meta.targetUrlParamIndex());
        assertEquals(2, meta.requestBodyIndex());
        assertEquals(4, meta.responseHeadersParamIndex());
        assertEquals(6, meta.uploadProgressParamIndex());
        assertEquals(8, meta.downloadProgressParamIndex());

        // Parameter 10 has format DEFAULT -> resolves to EXPLODE
        assertEquals(CollectionFormat.EXPLODE, meta.parameterDescriptors()[10].requestParamFormat());
    }

    @Test
    @DisplayName("parseStaticDefaultParams with custom RequestMeta defaultParams")
    void parseStaticDefaultParamsNullParams() throws Exception {
        RequestMeta metaWithNull = new RequestMeta(HttpMethod.GET, "/test", RequestType.APPLICATION_JSON, null);
        List<Param> list = MethodMetadataParser.parseStaticDefaultParams(
                DuplicateParamsApi.class.getMethod("duplicates",
                        String.class, String.class, Object.class, Object.class,
                        ResponseHeaders.class, ResponseHeaders.class,
                        UploadProgressListener.class, UploadProgressListener.class,
                        DownloadProgressListener.class, DownloadProgressListener.class,
                        String.class),
                List.of(DuplicateParamsApi.class),
                metaWithNull
        );
        assertNotNull(list);
    }
}
