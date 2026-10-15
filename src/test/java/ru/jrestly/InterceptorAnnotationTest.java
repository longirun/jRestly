package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.http.HttpTemplate;
import ru.jrestly.http.HttpTemplateBuilder;
import ru.jrestly.http.WireRequest;
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

import static org.junit.jupiter.api.Assertions.*;

class InterceptorAnnotationTest {

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

    public static class InvalidNotAnInterceptor {
    }

    public abstract static class AbstractInterceptor implements RequestInterceptor {
    }

    public static class NoDefaultConstructorInterceptor implements RequestInterceptor {
        public NoDefaultConstructorInterceptor(String param) {
        }

        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request;
        }
    }

    public interface InvalidTypeApi {
        @WithInterceptor(InvalidNotAnInterceptor.class)
        @Get(path = "/test")
        String test();
    }

    public interface AbstractTypeApi {
        @WithInterceptor(AbstractInterceptor.class)
        @Get(path = "/test")
        String test();
    }

    public interface NoDefaultConstructorApi {
        @WithInterceptor(NoDefaultConstructorInterceptor.class)
        @Get(path = "/test")
        String test();
    }

    @WithInterceptor(InvalidNotAnInterceptor.class)
    public interface InvalidInterfaceApi {
        @Get(path = "/test")
        String test();
    }

    public interface BaseApi {
        @WithInterceptor(ValidRequestInterceptor.class)
        @Get(path = "/base")
        String base();
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

    @Test
    @DisplayName("Validator flags class not implementing RequestInterceptor or ResponseInterceptor")
    void validatorFlagsClassNotImplementingInterceptor() {
        List<String> problems = ControllerValidator.validate(InvalidTypeApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("test(): @WithInterceptor class InvalidNotAnInterceptor must implement RequestInterceptor or ResponseInterceptor"));
    }

    @Test
    @DisplayName("Validator flags abstract interceptor class")
    void validatorFlagsAbstractClass() {
        List<String> problems = ControllerValidator.validate(AbstractTypeApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("test(): @WithInterceptor class AbstractInterceptor must not be abstract or an interface"));
    }

    @Test
    @DisplayName("Validator flags interceptor without no-arg constructor")
    void validatorFlagsMissingNoArgConstructor() {
        List<String> problems = ControllerValidator.validate(NoDefaultConstructorApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("test(): @WithInterceptor class NoDefaultConstructorInterceptor must declare a no-arg constructor"));
    }

    @Test
    @DisplayName("Validator flags invalid interceptor on interface level")
    void validatorFlagsInterfaceLevelInvalidInterceptor() {
        List<String> problems = ControllerValidator.validate(InvalidInterfaceApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("InvalidInterfaceApi: @WithInterceptor class InvalidNotAnInterceptor must implement RequestInterceptor or ResponseInterceptor"));
    }

    @Test
    @DisplayName("Proxy creation throws ControllerValidationException when controller has invalid @WithInterceptor")
    void proxyCreationThrowsControllerValidationException() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .build();
        try {
            ControllerValidationException ex = assertThrows(
                    ControllerValidationException.class,
                    () -> client.get(InvalidTypeApi.class)
            );
            assertTrue(ex.getMessage().contains("InvalidTypeApi"));
            assertTrue(ex.getMessage().contains("InvalidNotAnInterceptor"));
        } finally {
            client.close();
        }
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

        // Global + DualInterceptor (from SubApi interface) + ValidRequestInterceptor (from getSub method)
        assertEquals(3, reqInterceptors.size());
        assertSame(globalReq, reqInterceptors.get(0));
        assertInstanceOf(DualInterceptor.class, reqInterceptors.get(1));
        assertInstanceOf(ValidRequestInterceptor.class, reqInterceptors.get(2));

        // Global + ValidResponseInterceptor (from SuperApi interface) + DualInterceptor (from SubApi interface)
        assertEquals(3, respInterceptors.size());
        assertSame(globalResp, respInterceptors.get(0));
        assertInstanceOf(ValidResponseInterceptor.class, respInterceptors.get(1));
        assertInstanceOf(DualInterceptor.class, respInterceptors.get(2));

        // DualInterceptor is instantiated once and shared across request and response pipelines
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

    @WithInterceptor(ValidRequestInterceptor.class)
    public interface DuplicateAnnotatedApi {
        @WithInterceptor(ValidRequestInterceptor.class)
        @Get(path = "/call")
        String call();
    }
}
