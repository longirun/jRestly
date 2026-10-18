package ru.jrestly.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ControllerValidator;
import ru.jrestly.JRestlyClient;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Get;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration-surface tests for resilience (ADR-0043): the {@link ModuleInfo}
 * default methods, the {@link SimpleModuleInfo} / {@link JRestlyClient} fluent
 * builders, and {@link ControllerValidator} acceptance and rejection of
 * {@code @CircuitBreaker} / {@code @RateLimiter}. Pure unit tests, no HTTP.
 */
class ResilienceConfigTest {

    @Test
    @DisplayName("ModuleInfo default methods return null for resilience components")
    void moduleInfoDefaults() {
        ModuleInfo info = new ModuleInfo() {
            @Override
            public String getName() {
                return "test";
            }
            @Override
            public <T> ru.jrestly.Environment<T> getEnvironment() {
                return null;
            }
            @Override
            public ru.jrestly.AuthProvider getAuthProvider() {
                return null;
            }
            @Override
            public ru.jrestly.json.JsonCodec getJsonCodec() {
                return null;
            }
        };

        assertNull(info.getCircuitBreaker());
        assertNull(info.getCircuitBreakerRegistry());
        assertNull(info.getRateLimiter());
        assertNull(info.getRateLimiterRegistry());
    }

    @Test
    @DisplayName("SimpleModuleInfo defaults provide non-null registries and null individual components")
    void simpleModuleInfoDefaults() {
        SimpleModuleInfo info = new SimpleModuleInfo.Builder()
                .baseUrl("http://localhost:8080")
                .build();

        assertNull(info.getCircuitBreaker());
        assertNotNull(info.getCircuitBreakerRegistry());
        assertNull(info.getRateLimiter());
        assertNotNull(info.getRateLimiterRegistry());
    }

    @Test
    @DisplayName("SimpleModuleInfo and JRestlyClient fluent builders configure resilience components")
    void buildersConfigureResilience() {
        CircuitBreaker cb = CircuitBreaker.of("cb-builder");
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.ofDefaults();
        RateLimiter rl = RateLimiter.of("rl-builder");
        RateLimiterRegistry rlRegistry = RateLimiterRegistry.ofDefaults();

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .circuitBreaker(cb)
                .circuitBreakerRegistry(cbRegistry)
                .rateLimiter(rl)
                .rateLimiterRegistry(rlRegistry)
                .build();

        assertSame(cb, moduleInfo.getCircuitBreaker());
        assertSame(cbRegistry, moduleInfo.getCircuitBreakerRegistry());
        assertSame(rl, moduleInfo.getRateLimiter());
        assertSame(rlRegistry, moduleInfo.getRateLimiterRegistry());

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .circuitBreaker(cb)
                .circuitBreakerRegistry(cbRegistry)
                .rateLimiter(rl)
                .rateLimiterRegistry(rlRegistry)
                .build();

        assertNotNull(client);
    }

    interface ValidResilientApi {
        @Get("/api/items")
        @ru.jrestly.annotation.CircuitBreaker(name = "items", failureRateThreshold = 40.0f, minimumNumberOfCalls = 5, slidingWindowSize = 10, waitDurationMs = 2000, permittedNumberOfCallsInHalfOpenState = 2)
        @ru.jrestly.annotation.RateLimiter(name = "items", limitForPeriod = 5, limitRefreshPeriodMs = 1000, timeoutDurationMs = 200)
        String getItems();
    }

    @Test
    @DisplayName("ControllerValidator accepts valid CircuitBreaker and RateLimiter annotations")
    void controllerValidatorValid() {
        List<String> problems = ControllerValidator.validate(ValidResilientApi.class);
        assertTrue(problems.isEmpty(), () -> "Expected no problems, got: " + problems);
    }

    interface InvalidBlankNamesApi {
        @Get("/api/1")
        @ru.jrestly.annotation.CircuitBreaker(name = "   ")
        String method1();

        @Get("/api/2")
        @ru.jrestly.annotation.RateLimiter(name = "")
        String method2();
    }

    @Test
    @DisplayName("ControllerValidator rejects blank names in annotations")
    void controllerValidatorRejectsBlankNames() {
        List<String> problems = ControllerValidator.validate(InvalidBlankNamesApi.class);
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("@CircuitBreaker name must not be blank"));
        assertTrue(problems.get(1).contains("@RateLimiter name must not be blank"));
    }

    interface InvalidBoundsApi {
        @Get("/api/cb")
        @ru.jrestly.annotation.CircuitBreaker(failureRateThreshold = 0.0f, minimumNumberOfCalls = 0, slidingWindowSize = 0, waitDurationMs = -1, permittedNumberOfCallsInHalfOpenState = 0)
        String methodCb();

        @Get("/api/rl")
        @ru.jrestly.annotation.RateLimiter(limitForPeriod = 0, limitRefreshPeriodMs = -10, timeoutDurationMs = -5)
        String methodRl();
    }

    @Test
    @DisplayName("ControllerValidator rejects invalid threshold, call counts, timeouts and window sizes")
    void controllerValidatorRejectsInvalidBounds() {
        List<String> problems = ControllerValidator.validate(InvalidBoundsApi.class);
        assertTrue(problems.size() >= 5);
        assertTrue(problems.stream().anyMatch(p -> p.contains("failureRateThreshold must be in (0.0, 100.0]")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("minimumNumberOfCalls must be strictly positive")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("waitDurationMs must be strictly positive")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("limitForPeriod must be strictly positive")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("limitRefreshPeriodMs must be strictly positive")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("timeoutDurationMs must not be negative")));
    }
}
