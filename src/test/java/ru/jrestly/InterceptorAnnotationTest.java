package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.http.WireRequest;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseContext;
import ru.jrestly.interceptor.ResponseInterceptor;

import java.util.List;

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
}
