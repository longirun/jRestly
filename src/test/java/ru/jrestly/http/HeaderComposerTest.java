package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.AuthProvider;
import ru.jrestly.Environment;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour pins for {@link HeaderComposer}: ambient/static/dynamic header
 * merging, cookie resolution and the header-list primitives shared with the
 * tracing and compression paths (ADR-0075, ADR-0083).
 */
class HeaderComposerTest {

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    interface HeaderApi {
        @Get("/headers")
        @RequestHeader(name = "X-Static-1", value = "static-val-1")
        @RequestHeader(name = "Authorization", value = "Static-Token")
        @RequestHeader(name = "X-Map", value = "static-map")
        String get(
                @RequestHeader(name = "X-Dynamic") String dynamic,
                @RequestHeader Map<String, Object> map,
                @RequestHeader(name = "X-Multi") List<String> multi,
                @RequestHeader(name = "X-Array") String[] array);
    }

    interface DupHeaderApi {
        @Get("/dup")
        @RequestHeader(name = "X-Dup", value = "static")
        String get(@RequestHeader(name = "x-dup") String dynamic);
    }

    interface CookieApi {
        @Get("/cookies")
        @Cookie(name = "staticCookie1", value = "val1")
        @Cookie(name = "staticCookie2", value = "val2")
        String get(
                @Cookie(name = "dynCookie") String dynamic,
                @Cookie Map<String, Object> map,
                @Cookie HttpCookie httpCookie);

        @Get("/cookies-static")
        @Cookie(name = "annotatedCookie", value = "annotatedVal")
        String staticOnly();

        @Get("/cookies-existing")
        @Cookie(name = "annotatedCookie", value = "annotatedVal")
        String withExisting(@RequestHeader(name = "Cookie") String existing);
    }

    interface AcceptEncodingApi {
        @Get("/encoding")
        @RequestHeader(name = "accept-encoding", value = "identity")
        String declared();
    }

    interface MixedApi {
        @Get("/mixed")
        String get(
                @RequestHeader(name = "X-Mixed") String header,
                @Cookie(name = "mixedCookie") String cookie,
                @ru.jrestly.annotation.RequestParam(name = "q") String query);
    }

    interface DynamicOnlyCookieApi {
        @Get("/cookies-dynamic")
        String get(@Cookie(name = "only") String value);

        @Get("/cookies-dynamic-map")
        String withMap(@Cookie Map<String, Object> map);
    }

    interface CookieRemovalApi {
        @Get("/cookies-removal")
        @Cookie(name = "staticCookie1", value = "val1")
        String get(@Cookie(name = "staticCookie1") String value);
    }

    interface CookieWithHeaderApi {
        @Get("/cookies-with-header")
        String get(
                @RequestHeader(name = "X-Other") String other,
                @Cookie(name = "annotatedCookie") String cookie);
    }

    private static AuthProvider authProvider(List<Header> headers) {
        return new AuthProvider() {
            @Override
            public boolean isAuthorized() {
                return true;
            }

            @Override
            public List<Header> getAuthHeaders() {
                return headers;
            }

            @Override
            public void updateAuthHeader(String name, String value) {
                // no-op test double
            }
        };
    }

    private static ModuleInfo module(AuthProvider authProvider, boolean decompression) {
        SimpleModuleInfo base = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .decompression(decompression)
                .build();
        return new ModuleInfo() {
            @Override
            public String getName() {
                return base.getName();
            }

            @Override
            public <T> Environment<T> getEnvironment() {
                return base.getEnvironment();
            }

            @Override
            public AuthProvider getAuthProvider() {
                return authProvider;
            }

            @Override
            public boolean isDecompressionEnabled() {
                return base.isDecompressionEnabled();
            }
        };
    }

    private static ModuleInfo defaultModule() {
        return module(authProvider(List.of()), true);
    }

    private static MethodMetadata metadata(Class<?> controller, String method, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method m = controller.getMethod(method, parameterTypes);
        return MethodMetadata.of(m, controller);
    }

    private static List<String> values(List<Header> headers, String name) {
        return headers.stream()
                .filter(h -> name.equalsIgnoreCase(h.name()))
                .map(Header::value)
                .toList();
    }

    private static String cookieValue(List<Header> headers) {
        List<String> values = values(headers, "Cookie");
        assertEquals(1, values.size(), "expected exactly one Cookie header");
        return values.getFirst();
    }

    // -------------------------------------------------------------------------
    // Header merging (ADR-0075)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("compose keeps the documented merge order: ambient survivor, statics, dynamics, Accept-Encoding")
    void mergeOrderAmbientStaticDynamicAndAcceptEncoding() throws Exception {
        AuthProvider auth = authProvider(List.of(
                new Header("Authorization", "Bearer ambient-token"),
                new Header("X-Ambient", "ambient-val")));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("X-Map-1", "val1");
        map.put(null, "ignored");
        map.put("X-Map-Null", null);
        map.put("x-map", "dynamic-map");

        List<Header> headers = HeaderComposer.compose(
                module(auth, true),
                metadata(HeaderApi.class, "get", String.class, Map.class, List.class, String[].class),
                new Object[]{"dyn-val", map, Arrays.asList("m1", null, "m2"), new String[]{"a1", null, "a2"}});

        assertEquals(
                List.of("X-Ambient", "X-Static-1", "Authorization", "X-Dynamic", "X-Map-1",
                        "x-map", "X-Multi", "X-Multi", "X-Array", "X-Array", "Accept-Encoding"),
                headers.stream().map(Header::name).toList());
        assertEquals(List.of("Static-Token"), values(headers, "Authorization"));
        assertEquals(List.of("dynamic-map"), values(headers, "x-map"));
        assertEquals(List.of("m1", "m2"), values(headers, "X-Multi"));
        assertEquals(List.of("a1", "a2"), values(headers, "X-Array"));
        assertEquals(List.of("gzip, deflate"), values(headers, "Accept-Encoding"));
    }

    @Test
    @DisplayName("a dynamic header replaces a static header case-insensitively")
    void dynamicHeaderOverridesStaticCaseInsensitively() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(DupHeaderApi.class, "get", String.class),
                new Object[]{"dynamic"});

        assertEquals(List.of("dynamic"), values(headers, "X-Dup"));
        assertEquals(1, values(headers, "X-Dup").size());
    }

    @Test
    @DisplayName("null args still compose ambient and static headers")
    void nullArgsComposeStaticAndAmbientHeaders() throws Exception {
        AuthProvider auth = authProvider(List.of(new Header("X-Ambient", "ambient")));

        List<Header> headers = HeaderComposer.compose(
                module(auth, true),
                metadata(HeaderApi.class, "get", String.class, Map.class, List.class, String[].class),
                null);

        assertEquals(List.of("X-Ambient", "X-Static-1", "Authorization", "X-Map", "Accept-Encoding"),
                headers.stream().map(Header::name).toList());
    }

    @Test
    @DisplayName("null ambient auth headers fall back to static headers")
    void nullAmbientAuthHeadersFallBackToStatic() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                module(authProvider(null), true),
                metadata(DupHeaderApi.class, "get", String.class),
                new Object[]{"dynamic"});

        assertEquals(List.of("x-dup", "Accept-Encoding"), headers.stream().map(Header::name).toList());
    }

    @Test
    @DisplayName("a null dynamic header argument is skipped")
    void nullDynamicHeaderArgumentIsSkipped() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(HeaderApi.class, "get", String.class, Map.class, List.class, String[].class),
                new Object[]{null, Map.of(), List.of(), new String[0]});

        assertTrue(values(headers, "X-Dynamic").isEmpty());
    }

    @Test
    @DisplayName("an args array shorter than the parameter list skips missing arguments")
    void shorterArgsArraySkipsMissingParameters() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(HeaderApi.class, "get", String.class, Map.class, List.class, String[].class),
                new Object[0]);

        assertEquals(List.of("X-Static-1", "Authorization", "X-Map", "Accept-Encoding"),
                headers.stream().map(Header::name).toList());
    }

    @Test
    @DisplayName("non-header parameters are ignored by the header resolver")
    void nonHeaderParametersAreIgnored() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(MixedApi.class, "get", String.class, String.class, String.class),
                new Object[]{"h", "c", "q"});

        assertEquals(List.of("X-Mixed", "Cookie", "Accept-Encoding"), headers.stream().map(Header::name).toList());
        assertEquals("mixedCookie=c", cookieValue(headers));
    }
    @Test
    @DisplayName("a caller-declared Accept-Encoding is never duplicated")
    void declaredAcceptEncodingIsNotDuplicated() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(AcceptEncodingApi.class, "declared"),
                new Object[0]);

        assertEquals(List.of("identity"), values(headers, "Accept-Encoding"));
    }

    @Test
    @DisplayName("disabled decompression adds no Accept-Encoding")
    void disabledDecompressionAddsNoAcceptEncoding() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                module(authProvider(List.of()), false),
                metadata(AcceptEncodingApi.class, "declared"),
                new Object[0]);

        assertEquals(List.of("identity"), values(headers, "Accept-Encoding"));
        assertTrue(HeaderComposer.compose(
                module(authProvider(List.of()), false),
                metadata(DupHeaderApi.class, "get", String.class),
                new Object[]{"dynamic"}).stream().noneMatch(h -> "Accept-Encoding".equalsIgnoreCase(h.name())));
    }

    @Test
    @DisplayName("a byte[] header value fails fast on scalar, collection and map positions")
    void byteArrayHeaderValuesFailFast() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("X-Bytes", new byte[]{1, 2});

        IllegalArgumentException scalar = assertThrows(IllegalArgumentException.class,
                () -> HeaderComposer.compose(defaultModule(),
                        metadata(DupHeaderApi.class, "get", String.class),
                        new Object[]{new byte[]{1}}));
        assertTrue(scalar.getMessage().contains("byte[]"));

        IllegalArgumentException element = assertThrows(IllegalArgumentException.class,
                () -> HeaderComposer.compose(defaultModule(),
                        metadata(HeaderApi.class, "get", String.class, Map.class, List.class, String[].class),
                        new Object[]{"dyn", Map.of(), List.of(new byte[]{1}), new String[]{"a"}}));
        assertTrue(element.getMessage().contains("byte[]"));

        IllegalArgumentException mapValue = assertThrows(IllegalArgumentException.class,
                () -> HeaderComposer.compose(defaultModule(),
                        metadata(HeaderApi.class, "get", String.class, Map.class, List.class, String[].class),
                        new Object[]{"dyn", map, List.of(), new String[]{"a"}}));
        assertTrue(mapValue.getMessage().contains("byte[]"));
    }

    // -------------------------------------------------------------------------
    // Cookies
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("static-only cookies use the precomputed declaration header")
    void staticOnlyCookies() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(), metadata(CookieApi.class, "staticOnly"), new Object[0]);

        assertEquals("annotatedCookie=annotatedVal", cookieValue(headers));
    }

    @Test
    @DisplayName("dynamic cookies merge Map removals, HttpCookie values and annotated parameters")
    void dynamicCookiesMergeMapRemovalAndHttpCookie() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("staticCookie2", null);
        map.put("mapCookie", "mapVal");
        map.put(null, "ignored");

        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(CookieApi.class, "get", String.class, Map.class, HttpCookie.class),
                new Object[]{"dynVal", map, new HttpCookie("httpCookie", "httpVal")});

        assertEquals("staticCookie1=val1; dynCookie=dynVal; mapCookie=mapVal; httpCookie=httpVal",
                cookieValue(headers));
    }

    @Test
    @DisplayName("a blank existing Cookie header is replaced, not prefixed with a separator")
    void blankExistingCookieHeaderIsReplaced() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(CookieApi.class, "withExisting", String.class),
                new Object[]{""});

        assertEquals("annotatedCookie=annotatedVal", cookieValue(headers));
    }

    @Test
    @DisplayName("a non-blank existing Cookie header is joined with '; '")
    void existingCookieHeaderIsJoined() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(CookieApi.class, "withExisting", String.class),
                new Object[]{"manual=value"});

        assertEquals("manual=value; annotatedCookie=annotatedVal", cookieValue(headers));
    }

    @Test
    @DisplayName("a null-valued ambient Cookie header is replaced by the resolved cookies")
    void nullValuedAmbientCookieHeaderIsReplaced() throws Exception {
        AuthProvider auth = authProvider(List.of(new Header("Cookie", null)));

        List<Header> headers = HeaderComposer.compose(
                module(auth, true),
                metadata(CookieApi.class, "staticOnly"),
                new Object[0]);

        assertEquals("annotatedCookie=annotatedVal", cookieValue(headers));
    }

    @Test
    @DisplayName("dynamic-only cookies work with non-null, null and short argument arrays")
    void dynamicOnlyCookiesWithoutStaticDeclarations() throws Exception {
        MethodMetadata metadata = metadata(DynamicOnlyCookieApi.class, "get", String.class);

        List<Header> withValue = HeaderComposer.compose(defaultModule(), metadata, new Object[]{"value"});
        assertEquals("only=value", cookieValue(withValue));

        List<Header> nullArgs = HeaderComposer.compose(defaultModule(), metadata, null);
        assertTrue(values(nullArgs, "Cookie").isEmpty());

        List<Header> shortArgs = HeaderComposer.compose(defaultModule(), metadata, new Object[0]);
        assertTrue(values(shortArgs, "Cookie").isEmpty());
    }

    @Test
    @DisplayName("a null dynamic cookie argument removes its static twin and can leave no header")
    void nullCookieArgumentRemovesStaticTwin() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(CookieRemovalApi.class, "get", String.class),
                new Object[]{null});

        assertTrue(values(headers, "Cookie").isEmpty());
    }

    @Test
    @DisplayName("a cookie map removing every cookie emits no Cookie header")
    void cookieMapRemovingEveryCookieEmitsNoHeader() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("only", null);

        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(DynamicOnlyCookieApi.class, "withMap", Map.class),
                new Object[]{map});

        assertTrue(values(headers, "Cookie").isEmpty());
    }

    @Test
    @DisplayName("a Cookie header is found after non-cookie headers")
    void cookieHeaderIsFoundAfterOtherHeaders() throws Exception {
        List<Header> headers = HeaderComposer.compose(
                defaultModule(),
                metadata(CookieWithHeaderApi.class, "get", String.class, String.class),
                new Object[]{"other", "cookie"});

        assertEquals(List.of("X-Other", "Cookie", "Accept-Encoding"),
                headers.stream().map(Header::name).toList());
        assertEquals("annotatedCookie=cookie", cookieValue(headers));
    }

    // -------------------------------------------------------------------------
    // Primitives shared with tracing / compression
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("removeHeaderByName is case-insensitive and skips null names")
    void removeHeaderByNameSkipsNullNames() {
        List<Header> headers = new ArrayList<>(List.of(
                new Header(null, "nameless"),
                new Header("X-One", "1"),
                new Header("x-one", "2")));

        HeaderComposer.removeHeaderByName(headers, "X-ONE");

        assertEquals(1, headers.size());
        assertNull(headers.getFirst().name());
    }

    @Test
    @DisplayName("hasHeaderNamed is case-insensitive and skips null names")
    void hasHeaderNamedSkipsNullNames() {
        List<Header> headers = new ArrayList<>(List.of(
                new Header(null, "nameless"),
                new Header("X-One", "1")));

        assertTrue(HeaderComposer.hasHeaderNamed(headers, "x-one"));
        assertFalse(HeaderComposer.hasHeaderNamed(headers, "x-two"));
    }

    @Test
    @DisplayName("textValue renders null and rejects raw byte arrays")
    void textValueRendersNullAndRejectsBytes() {
        assertEquals("null", HeaderComposer.textValue(null, "header"));
        assertEquals("42", HeaderComposer.textValue(42, "header"));
        assertThrows(IllegalArgumentException.class,
                () -> HeaderComposer.textValue(new byte[]{1}, "parameter"));
    }
}
