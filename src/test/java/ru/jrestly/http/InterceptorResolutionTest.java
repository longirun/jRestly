package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Order;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseContext;
import ru.jrestly.interceptor.ResponseInterceptor;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for declarative interceptor resolution (ADR-0029 / ADR-0044):
 * Global → Super → Sub → Method pipeline order, deduplication and {@code @Order}
 * sorting confined to each declaration (stable, implicit 0, no cross-scope
 * leakage). No network.
 */
class InterceptorResolutionTest {

    public static class ValidRequestInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Valid-Req", "true");
        }
    }

    public static class ValidResponseInterceptor implements ResponseInterceptor {
        @Override
        public void onResponse(ResponseContext context) {
        }
    }

    public static class DualInterceptor implements RequestInterceptor, ResponseInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Dual", "true");
        }

        @Override
        public void onResponse(ResponseContext context) {
        }
    }

    @WithInterceptor(ValidResponseInterceptor.class)
    public interface SuperApi {
        @Get(path = "/super")
        String getSuper();
    }

    @WithInterceptor(DualInterceptor.class)
    public interface SubApi extends SuperApi {
        @WithInterceptor(ValidRequestInterceptor.class)
        @Get(path = "/sub")
        String getSub();
    }

    @WithInterceptor(ValidRequestInterceptor.class)
    public interface DuplicateAnnotatedApi {
        @WithInterceptor(ValidRequestInterceptor.class)
        @Get(path = "/call")
        String call();
    }

    @Order(-10)
    public static class HighPriorityInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Order", "high");
        }
    }

    @Order(10)
    public static class LowPriorityInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Order", "low");
        }
    }

    public static class NeutralInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Order", "neutral");
        }
    }

    @WithInterceptor({LowPriorityInterceptor.class, HighPriorityInterceptor.class, NeutralInterceptor.class})
    public interface OrderedInterceptorApi {
        @Get(path = "/order")
        String call();
    }

    @WithInterceptor(SuperLevelInterceptor.class)
    public interface OrderedSuperApi {
    }

    @WithInterceptor(SubLevelInterceptor.class)
    public interface OrderedSubApi extends OrderedSuperApi {
        @Get(path = "/ordered")
        String call();
    }

    public static class SuperLevelInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Level", "super");
        }
    }

    @Order(-10)
    public static class SubLevelInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Level", "sub");
        }
    }

    @WithInterceptor(FirstAnnotationInterceptor.class)
    @WithInterceptor(SecondAnnotationInterceptor.class)
    public interface CrossAnnotationApi {
        @Get(path = "/cross")
        String call();
    }

    public static class FirstAnnotationInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Cross", "first");
        }
    }

    @Order(-10)
    public static class SecondAnnotationInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request.withHeader("X-Cross", "second");
        }
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

    @Test
    @DisplayName("Pipeline ordering: Global -> Super-interface -> Sub-interface -> Method")
    void pipelineOrderingHierarchy() throws Exception {
        RequestInterceptor globalReq = (req, ctx) -> req.withHeader("X-Order", "global");
        ResponseInterceptor globalResp = ctx -> {};

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .addRequestInterceptor(globalReq)
                .addResponseInterceptor(globalResp)
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        Method method = SubApi.class.getMethod("getSub");
        Map<Class<?>, Object> cache = new ConcurrentHashMap<>();

        HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method, new Object[0], SubApi.class);
        builder.interceptorCache(cache);
        HttpTemplate template = builder.build();

        List<RequestInterceptor> reqInterceptors = template.getRequestInterceptors();
        List<ResponseInterceptor> respInterceptors = template.getResponseInterceptors();

        assertEquals(3, reqInterceptors.size());
        assertSame(globalReq, reqInterceptors.get(0));
        assertInstanceOf(DualInterceptor.class, reqInterceptors.get(1));
        assertInstanceOf(ValidRequestInterceptor.class, reqInterceptors.get(2));

        assertEquals(3, respInterceptors.size());
        assertSame(globalResp, respInterceptors.get(0));
        assertInstanceOf(ValidResponseInterceptor.class, respInterceptors.get(1));
        assertInstanceOf(DualInterceptor.class, respInterceptors.get(2));

        assertSame(reqInterceptors.get(1), respInterceptors.get(2));
        assertTrue(cache.containsKey(DualInterceptor.class));
    }

    @Test
    @DisplayName("Deduplication: duplicate interceptor class in hierarchy executes only once")
    void deduplicationOfSameInterceptorClass() throws Exception {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        Method method = DuplicateAnnotatedApi.class.getMethod("call");
        Map<Class<?>, Object> cache = new HashMap<>();

        HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method, new Object[0], DuplicateAnnotatedApi.class);
        builder.interceptorCache(cache);
        HttpTemplate template = builder.build();

        List<RequestInterceptor> reqInterceptors = template.getRequestInterceptors();
        assertEquals(1, reqInterceptors.size());
        assertInstanceOf(ValidRequestInterceptor.class, reqInterceptors.get(0));
    }

    @Test
    @DisplayName("@Order: interceptors within @WithInterceptor sorted by ascending order value")
    void orderAnnotationSortsInterceptorsAscending() throws Exception {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        Method method = OrderedInterceptorApi.class.getMethod("call");

        HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method,
                new Object[0], OrderedInterceptorApi.class);
        HttpTemplate template = builder.build();

        List<RequestInterceptor> interceptors = template.getRequestInterceptors();
        assertEquals(3, interceptors.size());
        assertInstanceOf(HighPriorityInterceptor.class, interceptors.get(0));
        assertInstanceOf(NeutralInterceptor.class, interceptors.get(1));
        assertInstanceOf(LowPriorityInterceptor.class, interceptors.get(2));
    }

    @Test
    @DisplayName("@Order: equal values preserve original declaration order (stable sort)")
    void orderAnnotationStableSortOnTie() throws Exception {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        Method method = OrderedInterceptorApi.class.getMethod("call");

        HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method,
                new Object[0], OrderedInterceptorApi.class);
        HttpTemplate template = builder.build();

        List<RequestInterceptor> interceptors = template.getRequestInterceptors();
        assertEquals(3, interceptors.size());
        assertInstanceOf(HighPriorityInterceptor.class, interceptors.get(0));
        assertInstanceOf(NeutralInterceptor.class, interceptors.get(1));
        assertInstanceOf(LowPriorityInterceptor.class, interceptors.get(2));
    }

    @Test
    @DisplayName("@Order: interceptor without annotation uses implicit order 0")
    void noOrderAnnotationUsesDefault() throws Exception {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        Method method = DuplicateAnnotatedApi.class.getMethod("call");

        HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method,
                new Object[0], DuplicateAnnotatedApi.class);
        HttpTemplate template = builder.build();

        assertEquals(1, template.getRequestInterceptors().size());
        assertInstanceOf(ValidRequestInterceptor.class, template.getRequestInterceptors().get(0));
    }

    @Test
    @DisplayName("@Order: scoped to a single declaration — scope levels keep the ADR-0029 pipeline order")
    void orderAnnotationDoesNotCrossScopeLevels() throws Exception {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        Method method = OrderedSubApi.class.getMethod("call");

        HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method,
                new Object[0], OrderedSubApi.class);
        HttpTemplate template = builder.build();

        List<RequestInterceptor> interceptors = template.getRequestInterceptors();
        assertEquals(2, interceptors.size());
        assertInstanceOf(SuperLevelInterceptor.class, interceptors.get(0));
        assertInstanceOf(SubLevelInterceptor.class, interceptors.get(1));
    }

    @Test
    @DisplayName("@Order: scoped to a single declaration — separate annotations keep declaration order")
    void orderAnnotationDoesNotReorderAcrossAnnotations() throws Exception {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        Method method = CrossAnnotationApi.class.getMethod("call");

        HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method,
                new Object[0], CrossAnnotationApi.class);
        HttpTemplate template = builder.build();

        List<RequestInterceptor> interceptors = template.getRequestInterceptors();
        assertEquals(2, interceptors.size());
        assertInstanceOf(FirstAnnotationInterceptor.class, interceptors.get(0));
        assertInstanceOf(SecondAnnotationInterceptor.class, interceptors.get(1));
    }
}
