package ru.jrestly.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Order;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.QueryCollectionFormat;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.annotation.WithMetrics;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseContext;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.metrics.CallMetrics;
import ru.jrestly.metrics.ClientMetricsListener;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code MethodMetadata} extraction and caching (ADR-0051):
 * HTTP verb/path/expected statuses, parameter and return-type descriptors
 * (async/optional/stream), timeout and interceptor resolution across the
 * interface hierarchy, plus the pre-computed zero-garbage caches of ADR-0055.
 * No network.
 */
class MethodMetadataTest {

    static class DummyError {}

    static class SampleRequestInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request;
        }
    }

    interface SuperApi {
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @Get("/super")
        String superMethod();
    }

    @WithInterceptor(SampleRequestInterceptor.class)
    interface SampleController extends SuperApi {
        @Get("/users/{id}")
        @ExpectStatus(statuses = {200, 201})
        @OnError(statuses = {404}, errorObject = DummyError.class)
        String getUser(String id, ResponseHeaders headers);

        @Post("/upload")
        @Timeout(value = 100, unit = TimeUnit.MILLISECONDS)
        CompletableFuture<Optional<byte[]>> upload(byte[] data, UploadProgressListener listener);

        @Get("/stream")
        InputStream stream();

        @Get("/cookies")
        @ru.jrestly.annotation.Cookie(name = "sessionId", value = "abc123xyz")
        @ru.jrestly.annotation.Cookie(name = "theme", value = "dark")
        String withCookies();

        @Get("/conditional")
        @ru.jrestly.annotation.RequestHeader(name = "If-None-Match", value = "\"etag123\"")
        String withConditional();

        @Post("/explicit-content")
        @ru.jrestly.annotation.RequestHeader(name = "Content-Type", value = "text/plain")
        String withExplicitContentType();

        @Get("/traced")
        @ru.jrestly.annotation.Trace(enabled = true)
        String withTrace();

        @ru.jrestly.annotation.Accept("application/json")
        @ru.jrestly.annotation.Trace(enabled = true)
        @Get("/accept-test")
        String withAcceptAndTrace();

        @Post(value = "/multipart-test", requestType = RequestType.MULTIPART_FORM_DATA)
        void withMultipart(@ru.jrestly.annotation.MultipartFormFile(partName = "file") byte[] file,
                           @ru.jrestly.annotation.RequestParam(name = "desc") String desc);

        @Get("/params-test/${id}")
        void withParams(@ru.jrestly.annotation.PathVariable(name = "id") String id,
                        @ru.jrestly.annotation.RequestHeader("X-Custom") String header);
    }

    interface VerbValueApi {
        @Get("/api/conventional")
        void conventional();
    }

    @BeforeEach
    void setUp() {
        MethodMetadata.clearCache();
    }

    @Test
    @DisplayName("MethodMetadata caches instances across invocations (assertSame)")
    void testCaching() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);

        MethodMetadata meta1 = MethodMetadata.of(getUser, SampleController.class);
        MethodMetadata meta2 = MethodMetadata.of(getUser, SampleController.class);

        assertNotNull(meta1);
        assertSame(meta1, meta2);
    }

    @Test
    @DisplayName("Resolves HTTP verb, path, and request metadata")
    void testRequestMetaResolution() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);
        MethodMetadata meta = MethodMetadata.of(getUser, SampleController.class);

        assertEquals(HttpMethod.GET, meta.requestMeta().httpMethod());
        assertEquals("/users/{id}", meta.requestMeta().path());
        assertEquals(RequestType.APPLICATION_JSON, meta.requestMeta().requestType());
    }

    @Test
    @DisplayName("Verb path declared via the implicit value attribute (concise API, ADR-0067 P1)")
    void verbPathDeclaredViaImplicitValueAttribute() throws NoSuchMethodException {
        Method conventional = VerbValueApi.class.getDeclaredMethod("conventional");
        MethodMetadata meta = MethodMetadata.of(conventional, VerbValueApi.class);

        assertEquals("/api/conventional", meta.requestMeta().path());
        assertEquals(HttpMethod.GET, meta.requestMeta().httpMethod());
    }

    @Test
    @DisplayName("Resolves expected statuses and OnErrorParser")
    void testExpectStatusesAndOnError() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);
        MethodMetadata meta = MethodMetadata.of(getUser, SampleController.class);

        assertEquals(List.of(200, 201), meta.expectStatuses());
        assertNotNull(meta.onErrorParser());
    }

    @Test
    @DisplayName("Resolves parameter indexes for ResponseHeaders and UploadProgressListener")
    void testParamIndexes() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);
        MethodMetadata userMeta = MethodMetadata.of(getUser, SampleController.class);
        assertEquals(1, userMeta.responseHeadersParamIndex());
        assertEquals(-1, userMeta.uploadProgressParamIndex());

        Method upload = SampleController.class.getMethod("upload", byte[].class, UploadProgressListener.class);
        MethodMetadata uploadMeta = MethodMetadata.of(upload, SampleController.class);
        assertEquals(-1, uploadMeta.responseHeadersParamIndex());
        assertEquals(1, uploadMeta.uploadProgressParamIndex());
    }

    @Test
    @DisplayName("Resolves return type info (async, optional, streamBody)")
    void testReturnTypeInfo() throws NoSuchMethodException {
        Method upload = SampleController.class.getMethod("upload", byte[].class, UploadProgressListener.class);
        MethodMetadata uploadMeta = MethodMetadata.of(upload, SampleController.class);

        MethodMetadata.ReturnTypeInfo rt = uploadMeta.returnTypeInfo();
        assertTrue(rt.async());
        assertTrue(rt.optional());
        assertFalse(rt.streamBody());
        assertEquals(byte[].class, rt.effectiveReturnType());

        Method stream = SampleController.class.getMethod("stream");
        MethodMetadata streamMeta = MethodMetadata.of(stream, SampleController.class);
        assertFalse(streamMeta.returnTypeInfo().async());
        assertFalse(streamMeta.returnTypeInfo().optional());
        assertTrue(streamMeta.returnTypeInfo().streamBody());
    }

    @Test
    @DisplayName("Resolves timeout precedence: method overrides super-interface")
    void testTimeoutPrecedence() throws NoSuchMethodException {
        Method upload = SampleController.class.getMethod("upload", byte[].class, UploadProgressListener.class);
        MethodMetadata uploadMeta = MethodMetadata.of(upload, SampleController.class);
        assertEquals(Duration.ofMillis(100), uploadMeta.timeout());

        Method superMethod = SampleController.class.getMethod("superMethod");
        MethodMetadata superMeta = MethodMetadata.of(superMethod, SampleController.class);
        assertEquals(Duration.ofSeconds(5), superMeta.timeout());
    }

    @Test
    @DisplayName("Resolves declarative interceptors from interface hierarchy")
    void testDeclarativeInterceptors() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);
        MethodMetadata meta = MethodMetadata.of(getUser, SampleController.class);

        assertEquals(1, meta.declarativeRequestInterceptorClasses().size());
        assertEquals(SampleRequestInterceptor.class, meta.declarativeRequestInterceptorClasses().getFirst());
    }

    @Test
    @DisplayName("Pre-computes staticCookieHeader and checks cookie params flag")
    void testStaticCookieHeader() throws NoSuchMethodException {
        Method withCookies = SampleController.class.getMethod("withCookies");
        MethodMetadata meta = MethodMetadata.of(withCookies, SampleController.class);

        assertEquals("sessionId=abc123xyz; theme=dark", meta.staticCookieHeader());
        assertFalse(meta.hasCookieParams());

        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);
        MethodMetadata userMeta = MethodMetadata.of(getUser, SampleController.class);
        assertFalse(userMeta.hasCookieParams());
        assertNotNull(userMeta.staticCookies());
        assertTrue(userMeta.staticCookies().isEmpty());
    }

    @Test
    @DisplayName("Pre-computes isStaticConditional and hasExplicitContentTypeInStaticHeaders")
    void testConditionalAndContentTypeFlags() throws NoSuchMethodException {
        Method withCond = SampleController.class.getMethod("withConditional");
        MethodMetadata condMeta = MethodMetadata.of(withCond, SampleController.class);
        assertTrue(condMeta.isStaticConditional());
        assertFalse(condMeta.hasExplicitContentTypeInStaticHeaders());

        Method withExplicit = SampleController.class.getMethod("withExplicitContentType");
        MethodMetadata explicitMeta = MethodMetadata.of(withExplicit, SampleController.class);
        assertFalse(explicitMeta.isStaticConditional());
        assertTrue(explicitMeta.hasExplicitContentTypeInStaticHeaders());

        Method stream = SampleController.class.getMethod("stream");
        MethodMetadata streamMeta = MethodMetadata.of(stream, SampleController.class);
        assertFalse(streamMeta.isStaticConditional());
        assertFalse(streamMeta.hasExplicitContentTypeInStaticHeaders());
    }

    @Test
    @DisplayName("Pre-computes hasTrace flag")
    void testTraceFlag() throws NoSuchMethodException {
        Method withTrace = SampleController.class.getMethod("withTrace");
        MethodMetadata traceMeta = MethodMetadata.of(withTrace, SampleController.class);
        assertTrue(traceMeta.hasTrace());

        Method stream = SampleController.class.getMethod("stream");
        MethodMetadata streamMeta = MethodMetadata.of(stream, SampleController.class);
        assertFalse(streamMeta.hasTrace());
    }

    @Test
    @DisplayName("MethodMetadata accessors for accept, trace, and parameter flags")
    void testAcceptTraceAndParameterFlags() throws NoSuchMethodException {
        Method acceptMethod = SampleController.class.getMethod("withAcceptAndTrace");
        MethodMetadata acceptMeta = MethodMetadata.of(acceptMethod, SampleController.class);
        assertEquals("application/json", acceptMeta.methodAccept());
        assertNull(acceptMeta.interfaceAccept());
        assertNull(acceptMeta.interfaceTrace());

        Method multiMethod = SampleController.class.getMethod("withMultipart", byte[].class, String.class);
        MethodMetadata multiMeta = MethodMetadata.of(multiMethod, SampleController.class);
        assertTrue(multiMeta.hasMultipartParams());
        assertTrue(multiMeta.hasRequestParams());
        assertFalse(multiMeta.hasPathVariables());

        Method paramsMethod = SampleController.class.getMethod("withParams", String.class, String.class);
        MethodMetadata paramsMeta = MethodMetadata.of(paramsMethod, SampleController.class);
        assertTrue(paramsMeta.hasPathVariables());
        assertTrue(paramsMeta.hasRequestHeaders());
        assertFalse(paramsMeta.hasMultipartParams());
    }

    // Fixtures for extended MethodMetadata contract testing

    static class DummyServerError {}
    static class DummyBadRequestError {}
    static class DummyInterfaceDefaultError {}
    static class DummyMethodDefaultError {}

    static class MetricsListenerA implements ClientMetricsListener {
        @Override
        public void onCallComplete(CallMetrics metrics) {}
    }
    static class MetricsListenerB implements ClientMetricsListener {
        @Override
        public void onCallComplete(CallMetrics metrics) {}
    }
    static class MetricsListenerC implements ClientMetricsListener {
        @Override
        public void onCallComplete(CallMetrics metrics) {}
    }
    static class NonListenerClass {}

    @Order(1)
    static class FirstReqInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request;
        }
    }

    @Order(2)
    static class SecondReqInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request;
        }
    }

    static class MethodReqInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request;
        }
    }

    static class RespInterceptorA implements ResponseInterceptor {
        @Override
        public void onResponse(ResponseContext context) {}
    }

    static class RespInterceptorB implements ResponseInterceptor {
        @Override
        public void onResponse(ResponseContext context) {}
    }

    static class DualInterceptor implements RequestInterceptor, ResponseInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request;
        }

        @Override
        public void onResponse(ResponseContext context) {}
    }

    static class NonInterceptorClass {}

    @WithMetrics(MetricsListenerA.class)
    interface BaseMetricsApi {}

    @WithMetrics(MetricsListenerB.class)
    interface SubMetricsApi extends BaseMetricsApi {
        @Get("/metrics-all")
        @WithMetrics({MetricsListenerA.class, MetricsListenerC.class, NonListenerClass.class})
        String allListeners();

        @Get("/metrics-inherited")
        String inheritedOnly();
    }

    @WithInterceptor({SecondReqInterceptor.class, FirstReqInterceptor.class, RespInterceptorA.class})
    interface BaseInterceptorApi {}

    @WithInterceptor({DualInterceptor.class, RespInterceptorB.class})
    interface SubInterceptorApi extends BaseInterceptorApi {
        @Get("/interceptors-method")
        @WithInterceptor({FirstReqInterceptor.class, MethodReqInterceptor.class, NonInterceptorClass.class})
        String methodWithInterceptors();

        @Get("/interceptors-inherited")
        String methodInherited();
    }

    @QueryCollectionFormat(CollectionFormat.SSV)
    interface FormatApi {
        @Get("/format-csv")
        @QueryCollectionFormat(CollectionFormat.CSV)
        String methodCsv();

        @Get("/format-default")
        @QueryCollectionFormat(CollectionFormat.DEFAULT)
        String methodDefault();

        @Get("/format-inherited")
        String methodInherited();
    }

    interface NoFormatApi {
        @Get("/format-plain")
        String methodPlain();
    }

    @OnError(statuses = {500, 502}, errorObject = DummyServerError.class)
    @OnError(errorObject = DummyInterfaceDefaultError.class)
    interface BaseErrorApi {}

    interface SubErrorApi extends BaseErrorApi {
        @Get("/errors-specific")
        @OnError(statuses = {400}, errorObject = DummyBadRequestError.class)
        @OnError(errorObject = DummyMethodDefaultError.class)
        String methodSpecific();

        @Get("/errors-inherited")
        String methodInherited();
    }

    @Compress(algorithm = CompressionAlgorithm.GZIP, minSizeBytes = 512)
    interface BaseCompressApi {
        @Get("/compress-disabled")
        @Compress(enabled = false)
        String methodDisabled();

        @Get("/compress-deflate")
        @Compress(algorithm = CompressionAlgorithm.DEFLATE, minSizeBytes = 1024)
        String methodDeflate();

        @Get("/compress-inherited")
        String methodInherited();
    }

    @Compress(enabled = false)
    interface DisabledIfaceCompressApi {
        @Get("/compress-inherit-disabled")
        String methodInheritDisabled();
    }

    interface NoCompressApi {
        @Get("/compress-none")
        String methodNoCompress();
    }

    @ru.jrestly.annotation.CircuitBreaker(name = "iface-cb")
    @ru.jrestly.annotation.RateLimiter(name = "iface-rl")
    interface ResilienceApi {
        @Get("/resilience-override")
        @ru.jrestly.annotation.CircuitBreaker(name = "method-cb")
        @ru.jrestly.annotation.RateLimiter(name = "method-rl")
        String methodOverridden();

        @Get("/resilience-inherited")
        String methodInherited();
    }

    @ru.jrestly.annotation.HttpVersion(java.net.http.HttpClient.Version.HTTP_2)
    interface HttpVersionApi {
        @Get("/http-v1")
        @ru.jrestly.annotation.HttpVersion(java.net.http.HttpClient.Version.HTTP_1_1)
        String methodHttp1();

        @Get("/http-inherited")
        String methodInherited();

        @Get("/redirects")
        @ru.jrestly.annotation.FollowRedirects(count = 5)
        String methodRedirects();

        @Get("/no-redirects")
        String methodNoRedirects();
    }

    interface RetryApi {
        @Get("/retry-max-after")
        @ru.jrestly.annotation.Retry(times = 4, maxRetryAfterMs = 4000)
        String methodRetryWithMaxAfter();

        @Get("/retry-no-max-after")
        @ru.jrestly.annotation.Retry(times = 2, maxRetryAfterMs = -1)
        String methodRetryWithoutMaxAfter();
    }

    interface InvalidTimeoutMethodApi {
        @Get("/timeout-zero")
        @Timeout(value = 0, unit = TimeUnit.SECONDS)
        String methodZeroTimeout();
    }

    @Timeout(value = -5, unit = TimeUnit.SECONDS)
    interface InvalidTimeoutIfaceApi {
        @Get("/timeout-iface")
        String method();
    }

    interface ConditionalApi {
        @Get("/if-match")
        @ru.jrestly.annotation.RequestHeader(name = "If-Match", value = "\"xyz\"")
        String ifMatch();

        @Get("/if-unmod")
        @ru.jrestly.annotation.RequestHeader(name = "If-Unmodified-Since", value = "date")
        String ifUnmodifiedSince();

        @Get("/if-mod")
        @ru.jrestly.annotation.RequestHeader(name = "If-Modified-Since", value = "date")
        String ifModifiedSince();

        @Get("/non-cond")
        @ru.jrestly.annotation.RequestHeader(name = "X-Foo", value = "bar")
        String nonConditional();
    }

    @ru.jrestly.annotation.Cookie(name = "", value = "blank")
    @ru.jrestly.annotation.Cookie(name = "valid", value = "123")
    @ru.jrestly.annotation.RequestDefaultParam(name = "ifaceParam", value = "val1")
    interface StaticDataApi {
        @Get(value = "/search",  params = {
                @ru.jrestly.annotation.RequestDefaultParam(name = "q", value = "query"),
                @ru.jrestly.annotation.RequestDefaultParam(name = "limit", value = "10")
        })
        @ru.jrestly.annotation.RequestDefaultParam(name = "methodParam", value = "val2")
        String verbWithParams();
    }

    interface ReturnTypeApi {
        @SuppressWarnings("rawtypes")
        @Get("/raw-optional")
        Optional rawOptional();

        @Get("/sse-stream")
        Stream<ServerSentEvent> sseStream();

        @Get("/string-stream")
        Stream<String> stringStream();
    }

    interface ParamDescriptorApi {
        @Post("/full")
        @ru.jrestly.annotation.SetAuthDetails(headerName = "X-Auth-Token")
        @ru.jrestly.annotation.Authorization
        void full(
                @ru.jrestly.annotation.TargetUrl String targetUrl,
                @ru.jrestly.annotation.PathVariable(name = "slug", encoded = true) String slug,
                @ru.jrestly.annotation.RequestHeader(name = "X-Key") String key,
                @ru.jrestly.annotation.Cookie(name = "uid") String uid,
                @ru.jrestly.annotation.RequestParam(name = "fmt", format = CollectionFormat.PIPES, contentType = "text/plain") String fmt,
                @ru.jrestly.annotation.RequestBody String body,
                @ru.jrestly.annotation.MultipartFormFile(partName = "avatar", contentType = "image/png") byte[] avatar,
                ResponseHeaders headers,
                UploadProgressListener uploadListener,
                DownloadProgressListener downloadListener
        );
    }

    @Test
    @DisplayName("Declarative @WithMetrics resolves hierarchy, deduplicates, and filters non-listeners")
    void testDeclarativeMetricsListenersHierarchyAndDeduplication() throws NoSuchMethodException {
        Method allMethod = SubMetricsApi.class.getMethod("allListeners");
        MethodMetadata allMeta = MethodMetadata.of(allMethod, SubMetricsApi.class);

        // Expected: BaseMetricsApi -> SubMetricsApi -> allMethod (MetricsListenerA deduplicated, NonListenerClass filtered)
        assertEquals(List.of(MetricsListenerA.class, MetricsListenerB.class, MetricsListenerC.class),
                allMeta.declarativeMetricsListenerClasses());

        Method inheritedMethod = SubMetricsApi.class.getMethod("inheritedOnly");
        MethodMetadata inheritedMeta = MethodMetadata.of(inheritedMethod, SubMetricsApi.class);
        assertEquals(List.of(MetricsListenerA.class, MetricsListenerB.class),
                inheritedMeta.declarativeMetricsListenerClasses());
    }

    @Test
    @DisplayName("Declarative @WithInterceptor respects @Order within array, separates request/response, and deduplicates")
    void testDeclarativeInterceptorsHierarchyOrderAndDeduplication() throws NoSuchMethodException {
        Method method = SubInterceptorApi.class.getMethod("methodWithInterceptors");
        MethodMetadata meta = MethodMetadata.of(method, SubInterceptorApi.class);

        // Request interceptors:
        // Base: FirstReqInterceptor (@Order 1) sorted before SecondReqInterceptor (@Order 2)
        // Sub: DualInterceptor
        // Method: MethodReqInterceptor (FirstReqInterceptor deduplicated, NonInterceptorClass ignored)
        assertEquals(List.of(FirstReqInterceptor.class, SecondReqInterceptor.class, DualInterceptor.class, MethodReqInterceptor.class),
                meta.declarativeRequestInterceptorClasses());

        // Response interceptors:
        // Base: RespInterceptorA
        // Sub: DualInterceptor, RespInterceptorB
        assertEquals(List.of(RespInterceptorA.class, DualInterceptor.class, RespInterceptorB.class),
                meta.declarativeResponseInterceptorClasses());
    }

    @Test
    @DisplayName("QueryCollectionFormat precedence: method overrides interface, DEFAULT falls back, EXPLODE global default")
    void testDefaultCollectionFormatPrecedenceAndInheritance() throws NoSuchMethodException {
        Method csv = FormatApi.class.getMethod("methodCsv");
        assertEquals(CollectionFormat.CSV, MethodMetadata.of(csv, FormatApi.class).defaultCollectionFormat());

        Method def = FormatApi.class.getMethod("methodDefault");
        assertEquals(CollectionFormat.SSV, MethodMetadata.of(def, FormatApi.class).defaultCollectionFormat());

        Method inherited = FormatApi.class.getMethod("methodInherited");
        assertEquals(CollectionFormat.SSV, MethodMetadata.of(inherited, FormatApi.class).defaultCollectionFormat());

        Method plain = NoFormatApi.class.getMethod("methodPlain");
        assertEquals(CollectionFormat.EXPLODE, MethodMetadata.of(plain, NoFormatApi.class).defaultCollectionFormat());
    }

    @Test
    @DisplayName("OnError hierarchy collects statuses and default fallback parsers from interface and method")
    void testOnErrorHierarchyStatusAndDefaultRegistration() throws NoSuchMethodException {
        Method specific = SubErrorApi.class.getMethod("methodSpecific");
        MethodMetadata specificMeta = MethodMetadata.of(specific, SubErrorApi.class);
        assertNotNull(specificMeta.onErrorParser());

        Method inherited = SubErrorApi.class.getMethod("methodInherited");
        MethodMetadata inheritedMeta = MethodMetadata.of(inherited, SubErrorApi.class);
        assertNotNull(inheritedMeta.onErrorParser());
    }

    @Test
    @DisplayName("Compress precedence: explicit disable, custom algorithm/minSizeBytes, interface inheritance")
    void testCompressionPrecedenceAndExplicitDisable() throws NoSuchMethodException {
        Method disabled = BaseCompressApi.class.getMethod("methodDisabled");
        MethodMetadata.EffectiveCompression disComp = MethodMetadata.of(disabled, BaseCompressApi.class).compression();
        assertTrue(disComp.explicit());
        assertFalse(disComp.enabled());
        assertEquals(CompressionAlgorithm.NONE, disComp.algorithm());

        Method deflate = BaseCompressApi.class.getMethod("methodDeflate");
        MethodMetadata.EffectiveCompression defComp = MethodMetadata.of(deflate, BaseCompressApi.class).compression();
        assertTrue(defComp.explicit());
        assertTrue(defComp.enabled());
        assertEquals(CompressionAlgorithm.DEFLATE, defComp.algorithm());
        assertEquals(1024, defComp.minSizeBytes());

        Method inherited = BaseCompressApi.class.getMethod("methodInherited");
        MethodMetadata.EffectiveCompression inhComp = MethodMetadata.of(inherited, BaseCompressApi.class).compression();
        assertTrue(inhComp.explicit());
        assertTrue(inhComp.enabled());
        assertEquals(CompressionAlgorithm.GZIP, inhComp.algorithm());
        assertEquals(512, inhComp.minSizeBytes());

        Method ifaceDisabled = DisabledIfaceCompressApi.class.getMethod("methodInheritDisabled");
        MethodMetadata.EffectiveCompression ifaceDisComp = MethodMetadata.of(ifaceDisabled, DisabledIfaceCompressApi.class).compression();
        assertTrue(ifaceDisComp.explicit());
        assertFalse(ifaceDisComp.enabled());

        Method noComp = NoCompressApi.class.getMethod("methodNoCompress");
        MethodMetadata.EffectiveCompression noneComp = MethodMetadata.of(noComp, NoCompressApi.class).compression();
        assertFalse(noneComp.explicit());
        assertFalse(noneComp.enabled());
    }

    @Test
    @DisplayName("Resilience annotations (@CircuitBreaker, @RateLimiter): method overrides interface, inherits if omitted")
    void testResilienceAnnotationsHierarchyAndOverride() throws NoSuchMethodException {
        Method override = ResilienceApi.class.getMethod("methodOverridden");
        MethodMetadata overMeta = MethodMetadata.of(override, ResilienceApi.class);
        assertEquals("method-cb", overMeta.circuitBreakerAnnotation().name());
        assertEquals("method-rl", overMeta.rateLimiterAnnotation().name());

        Method inherited = ResilienceApi.class.getMethod("methodInherited");
        MethodMetadata inhMeta = MethodMetadata.of(inherited, ResilienceApi.class);
        assertEquals("iface-cb", inhMeta.circuitBreakerAnnotation().name());
        assertEquals("iface-rl", inhMeta.rateLimiterAnnotation().name());
    }

    @Test
    @DisplayName("HttpVersion and FollowRedirects: method overrides interface, inherits when omitted")
    void testHttpVersionAndFollowRedirectsPrecedence() throws NoSuchMethodException {
        Method v1 = HttpVersionApi.class.getMethod("methodHttp1");
        assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, MethodMetadata.of(v1, HttpVersionApi.class).httpVersion());

        Method inh = HttpVersionApi.class.getMethod("methodInherited");
        assertEquals(java.net.http.HttpClient.Version.HTTP_2, MethodMetadata.of(inh, HttpVersionApi.class).httpVersion());

        Method red = HttpVersionApi.class.getMethod("methodRedirects");
        assertEquals(5, MethodMetadata.of(red, HttpVersionApi.class).followRedirectsNumber());

        Method noRed = HttpVersionApi.class.getMethod("methodNoRedirects");
        assertNull(MethodMetadata.of(noRed, HttpVersionApi.class).followRedirectsNumber());
    }

    @Test
    @DisplayName("Retry policy: parses maxAttempts and maxRetryAfterMs correctly")
    void testRetryPolicyParsingVariants() throws NoSuchMethodException {
        Method withMaxAfter = RetryApi.class.getMethod("methodRetryWithMaxAfter");
        MethodMetadata metaWithAfter = MethodMetadata.of(withMaxAfter, RetryApi.class);
        assertNotNull(metaWithAfter.retryPolicy());
        assertEquals(4, metaWithAfter.retryPolicy().maxAttempts());
        assertNotNull(metaWithAfter.retryPolicy().getMaxRetryAfter());
        assertEquals(Duration.ofMillis(4000), metaWithAfter.retryPolicy().getMaxRetryAfter());

        Method withoutMaxAfter = RetryApi.class.getMethod("methodRetryWithoutMaxAfter");
        MethodMetadata metaNoAfter = MethodMetadata.of(withoutMaxAfter, RetryApi.class);
        assertNotNull(metaNoAfter.retryPolicy());
        assertEquals(2, metaNoAfter.retryPolicy().maxAttempts());
        assertNull(metaNoAfter.retryPolicy().getMaxRetryAfter());
    }

    @Test
    @DisplayName("Timeout validation fails fast with IllegalArgumentException for non-positive values")
    void testTimeoutValidationFailFast() throws NoSuchMethodException {
        Method zeroMethod = InvalidTimeoutMethodApi.class.getMethod("methodZeroTimeout");
        IllegalArgumentException methodEx = assertThrows(IllegalArgumentException.class, () ->
                MethodMetadata.of(zeroMethod, InvalidTimeoutMethodApi.class));
        assertTrue(methodEx.getMessage().contains("must be strictly positive, was 0"));

        Method ifaceMethod = InvalidTimeoutIfaceApi.class.getMethod("method");
        IllegalArgumentException ifaceEx = assertThrows(IllegalArgumentException.class, () ->
                MethodMetadata.of(ifaceMethod, InvalidTimeoutIfaceApi.class));
        assertTrue(ifaceEx.getMessage().contains("must be strictly positive, was -5"));
    }

    @Test
    @DisplayName("checkIsConditional detects all conditional headers: If-Match, If-Unmodified-Since, If-Modified-Since")
    void testConditionalHeadersAllVariants() throws NoSuchMethodException {
        Method ifMatch = ConditionalApi.class.getMethod("ifMatch");
        assertTrue(MethodMetadata.of(ifMatch, ConditionalApi.class).isStaticConditional());

        Method ifUnmod = ConditionalApi.class.getMethod("ifUnmodifiedSince");
        assertTrue(MethodMetadata.of(ifUnmod, ConditionalApi.class).isStaticConditional());

        Method ifMod = ConditionalApi.class.getMethod("ifModifiedSince");
        assertTrue(MethodMetadata.of(ifMod, ConditionalApi.class).isStaticConditional());

        Method nonCond = ConditionalApi.class.getMethod("nonConditional");
        assertFalse(MethodMetadata.of(nonCond, ConditionalApi.class).isStaticConditional());
    }

    @Test
    @DisplayName("Static cookies filter out blank names, static default params merge verb params and annotations")
    void testStaticCookiesBlankFilteringAndDefaultParamsFromVerb() throws NoSuchMethodException {
        Method search = StaticDataApi.class.getMethod("verbWithParams");
        MethodMetadata meta = MethodMetadata.of(search, StaticDataApi.class);

        // Blank cookie is filtered out
        assertEquals(1, meta.staticCookies().size());
        assertEquals("123", meta.staticCookies().get("valid"));
        assertEquals("valid=123", meta.staticCookieHeader());

        // Merged default params: ifaceParam, q, limit, methodParam
        List<String> paramNames = meta.staticDefaultParams().stream().map(Param::name).toList();
        assertTrue(paramNames.contains("ifaceParam"));
        assertTrue(paramNames.contains("q"));
        assertTrue(paramNames.contains("limit"));
        assertTrue(paramNames.contains("methodParam"));
    }

    @Test
    @DisplayName("ReturnTypeInfo correctly handles raw Optional and Stream return types")
    void testReturnTypeRawOptionalAndStreamTypes() throws NoSuchMethodException {
        Method rawOpt = ReturnTypeApi.class.getMethod("rawOptional");
        MethodMetadata.ReturnTypeInfo rawInfo = MethodMetadata.of(rawOpt, ReturnTypeApi.class).returnTypeInfo();
        assertTrue(rawInfo.optional());
        assertFalse(rawInfo.async());
        assertFalse(rawInfo.streamBody());
        assertEquals(Object.class, rawInfo.effectiveReturnType());

        Method sse = ReturnTypeApi.class.getMethod("sseStream");
        MethodMetadata.ReturnTypeInfo sseInfo = MethodMetadata.of(sse, ReturnTypeApi.class).returnTypeInfo();
        assertFalse(sseInfo.optional());
        assertFalse(sseInfo.async());
        assertTrue(sseInfo.streamBody());

        Method str = ReturnTypeApi.class.getMethod("stringStream");
        MethodMetadata.ReturnTypeInfo strInfo = MethodMetadata.of(str, ReturnTypeApi.class).returnTypeInfo();
        assertFalse(strInfo.optional());
        assertFalse(strInfo.async());
        assertTrue(strInfo.streamBody());
    }

    @Test
    @DisplayName("ParameterDescriptor fully extracts all parameter annotations, flags, and types")
    void testParameterDescriptorsCompleteCoverage() throws NoSuchMethodException {
        Method full = ParamDescriptorApi.class.getMethod("full",
                String.class, String.class, String.class, String.class, String.class,
                String.class, byte[].class, ResponseHeaders.class,
                UploadProgressListener.class, DownloadProgressListener.class);
        MethodMetadata meta = MethodMetadata.of(full, ParamDescriptorApi.class);

        assertTrue(meta.hasAuthAnnotation());
        assertEquals("X-Auth-Token", meta.authDetailsHeaderName());
        assertTrue(meta.hasTargetUrl());
        assertEquals(0, meta.targetUrlParamIndex());
        assertEquals(7, meta.responseHeadersParamIndex());
        assertEquals(8, meta.uploadProgressParamIndex());
        assertEquals(9, meta.downloadProgressParamIndex());
        assertEquals(5, meta.requestBodyIndex());

        MethodMetadata.ParameterDescriptor[] desc = meta.parameterDescriptors();
        assertEquals(10, desc.length);

        assertTrue(desc[0].isTargetUrl());

        assertTrue(desc[1].isPathVariable());
        assertEquals("slug", desc[1].pathVariableName());
        assertTrue(desc[1].pathVariableEncoded());

        assertTrue(desc[2].isRequestHeader());
        assertEquals("X-Key", desc[2].requestHeaderName());

        assertTrue(desc[3].isCookie());
        assertEquals("uid", desc[3].cookieName());

        assertTrue(desc[4].isRequestParam());
        assertEquals("fmt", desc[4].requestParamName());
        assertEquals(CollectionFormat.PIPES, desc[4].requestParamFormat());
        assertEquals("text/plain", desc[4].requestParamContentType());

        assertTrue(desc[5].isRequestBody());

        assertTrue(desc[6].isMultipartFile());
        assertEquals("avatar", desc[6].multipartPartName());
        assertEquals("image/png", desc[6].multipartExplicitContentType());

        assertTrue(desc[7].isResponseHeaders());
        assertTrue(desc[8].isUploadProgressListener());
        assertTrue(desc[9].isDownloadProgressListener());
    }

    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    interface InterfaceLevelTimeoutApi {}

    @Test
    @DisplayName("MethodMetadata supports null method or controller for interface-only and default metadata parsing")
    void testNullMethodAndInterfaceParsing() {
        MethodMetadata ifaceMeta = MethodMetadata.of(null, InterfaceLevelTimeoutApi.class);
        assertNotNull(ifaceMeta);
        assertNull(ifaceMeta.requestMeta());
        assertEquals(Duration.ofSeconds(5), ifaceMeta.timeout());
        assertEquals(0, ifaceMeta.parameterDescriptors().length);

        MethodMetadata nullMeta = MethodMetadata.of(null, null);
        assertNotNull(nullMeta);
        assertNull(nullMeta.requestMeta());
        assertEquals(CollectionFormat.EXPLODE, nullMeta.defaultCollectionFormat());
    }
}

