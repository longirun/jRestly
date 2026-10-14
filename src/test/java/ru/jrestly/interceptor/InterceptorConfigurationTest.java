package ru.jrestly.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.AuthProvider;
import ru.jrestly.Environment;
import ru.jrestly.JRestlyClient;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.http.HttpTemplate;
import ru.jrestly.http.HttpTemplateBuilder;
import ru.jrestly.http.WireRequest;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterceptorConfigurationTest {

    @Test
    @DisplayName("ModuleInfo interface default methods return empty unmodifiable lists")
    void moduleInfoDefaultMethodsReturnEmptyLists() {
        ModuleInfo customModule = new ModuleInfo() {
            @Override
            public String getName() {
                return "custom";
            }

            @Override
            public <T> Environment<T> getEnvironment() {
                return null;
            }

            @Override
            public AuthProvider getAuthProvider() {
                return null;
            }
        };

        assertNotNull(customModule.getRequestInterceptors());
        assertTrue(customModule.getRequestInterceptors().isEmpty());
        assertNotNull(customModule.getResponseInterceptors());
        assertTrue(customModule.getResponseInterceptors().isEmpty());

        assertThrows(UnsupportedOperationException.class, () ->
                customModule.getRequestInterceptors().add((req, ctx) -> req));
        assertThrows(UnsupportedOperationException.class, () ->
                customModule.getResponseInterceptors().add(ctx -> {}));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder defaults have empty interceptor lists")
    void simpleModuleInfoBuilderDefaultsAreEmpty() {
        SimpleModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        assertNotNull(module.getRequestInterceptors());
        assertTrue(module.getRequestInterceptors().isEmpty());
        assertNotNull(module.getResponseInterceptors());
        assertTrue(module.getResponseInterceptors().isEmpty());

        assertThrows(UnsupportedOperationException.class, () ->
                module.getRequestInterceptors().add((req, ctx) -> req));
        assertThrows(UnsupportedOperationException.class, () ->
                module.getResponseInterceptors().add(ctx -> {}));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder collects request interceptors and adapts UnaryOperator")
    void simpleModuleInfoAddRequestInterceptorsPreservesOrderAndAdaptsOperator() {
        RequestInterceptor explicit = (req, ctx) -> req.withHeader("X-Explicit", "1");
        UnaryOperator<WireRequest> operator = req -> req.withHeader("X-Operator", "2");

        SimpleModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .addRequestInterceptor(explicit)
                .addRequestInterceptor(operator)
                .build();

        List<RequestInterceptor> list = module.getRequestInterceptors();
        assertEquals(2, list.size());
        assertSame(explicit, list.get(0));

        WireRequest initial = WireRequest.builder().uri(URI.create("http://localhost")).build();
        WireRequest afterExplicit = list.get(0).intercept(initial, null);
        assertEquals("1", afterExplicit.headers().stream()
                .filter(h -> h.name().equalsIgnoreCase("X-Explicit"))
                .findFirst()
                .map(ru.jrestly.http.Header::value)
                .orElse(null));

        WireRequest afterOperator = list.get(1).intercept(afterExplicit, null);
        assertEquals("2", afterOperator.headers().stream()
                .filter(h -> h.name().equalsIgnoreCase("X-Operator"))
                .findFirst()
                .map(ru.jrestly.http.Header::value)
                .orElse(null));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder requestInterceptors replaces and defensively copies")
    void simpleModuleInfoRequestInterceptorsReplacesAndDefensivelyCopies() {
        RequestInterceptor first = (req, ctx) -> req;
        RequestInterceptor second = (req, ctx) -> req;
        List<RequestInterceptor> original = new ArrayList<>();
        original.add(first);

        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .addRequestInterceptor((req, ctx) -> req) // Will be replaced
                .requestInterceptors(original);

        original.add(second); // Mutating original list after passing to builder

        SimpleModuleInfo module = builder.build();
        assertEquals(1, module.getRequestInterceptors().size());
        assertSame(first, module.getRequestInterceptors().get(0));

        assertThrows(UnsupportedOperationException.class, () ->
                module.getRequestInterceptors().add(second));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder collects response interceptors and defensively copies")
    void simpleModuleInfoResponseInterceptorsPreservesOrderAndDefensivelyCopies() {
        ResponseInterceptor first = ctx -> {};
        ResponseInterceptor second = ctx -> {};
        List<ResponseInterceptor> original = new ArrayList<>();
        original.add(first);

        SimpleModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .responseInterceptors(original)
                .addResponseInterceptor(second)
                .build();

        original.clear(); // Mutate original list

        List<ResponseInterceptor> list = module.getResponseInterceptors();
        assertEquals(2, list.size());
        assertSame(first, list.get(0));
        assertSame(second, list.get(1));

        assertThrows(UnsupportedOperationException.class, () -> list.add(ctx -> {}));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder rejects null interceptors and null collections")
    void simpleModuleInfoValidationRejectsNulls() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080");

        assertThrows(NullPointerException.class, () ->
                builder.addRequestInterceptor((RequestInterceptor) null));
        assertThrows(NullPointerException.class, () ->
                builder.addRequestInterceptor((UnaryOperator<WireRequest>) null));
        assertThrows(NullPointerException.class, () ->
                builder.requestInterceptors(null));
        assertThrows(NullPointerException.class, () ->
                builder.requestInterceptors(Collections.singletonList(null)));

        assertThrows(NullPointerException.class, () ->
                builder.addResponseInterceptor(null));
        assertThrows(NullPointerException.class, () ->
                builder.responseInterceptors(null));
        assertThrows(NullPointerException.class, () ->
                builder.responseInterceptors(Collections.singletonList(null)));
    }

    @Test
    @DisplayName("JRestlyClient.Builder forwards interceptors to ModuleInfo")
    void jRestlyClientBuilderForwardsInterceptorsToModule() {
        RequestInterceptor req1 = (req, ctx) -> req;
        UnaryOperator<WireRequest> req2 = req -> req;
        ResponseInterceptor res1 = ctx -> {};

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .addRequestInterceptor(req1)
                .addRequestInterceptor(req2)
                .addResponseInterceptor(res1)
                .build();

        ModuleInfo info = client.getModuleInfo();
        assertEquals(2, info.getRequestInterceptors().size());
        assertSame(req1, info.getRequestInterceptors().get(0));
        assertEquals(1, info.getResponseInterceptors().size());
        assertSame(res1, info.getResponseInterceptors().get(0));

        // Test list replacement via JRestlyClient.Builder
        JRestlyClient client2 = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .requestInterceptors(List.of(req1))
                .responseInterceptors(List.of(res1))
                .build();

        assertEquals(1, client2.getModuleInfo().getRequestInterceptors().size());
        assertEquals(1, client2.getModuleInfo().getResponseInterceptors().size());
    }

    @Test
    @DisplayName("HttpTemplateBuilder populates HttpTemplate with interceptors and invocation metadata")
    void httpTemplateBuilderPopulatesInterceptorsAndMetadata() throws NoSuchMethodException {
        RequestInterceptor reqInterceptor = (req, ctx) -> req;
        ResponseInterceptor resInterceptor = ctx -> {};

        SimpleModuleInfo module = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .addRequestInterceptor(reqInterceptor)
                .addResponseInterceptor(resInterceptor)
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        Method method = TestController.class.getMethod("getItem", Long.class);
        Object[] args = new Object[]{42L};

        HttpTemplateBuilder builder = new HttpTemplateBuilder(module, httpClient, method, args, TestController.class);
        HttpTemplate template = builder.build();

        assertEquals(List.of(reqInterceptor), template.getRequestInterceptors());
        assertEquals(List.of(resInterceptor), template.getResponseInterceptors());
        assertSame(TestController.class, template.getControllerClass());
        assertSame(method, template.getMethod());
        assertArrayEquals(args, template.getArgs());
    }
}
