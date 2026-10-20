package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.AuthProvider;
import ru.jrestly.Environment;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.resilience.CircuitBreaker;
import ru.jrestly.resilience.CircuitBreakerConfig;
import ru.jrestly.resilience.CircuitBreakerRegistry;
import ru.jrestly.resilience.RateLimiter;
import ru.jrestly.resilience.RateLimiterConfig;
import ru.jrestly.resilience.RateLimiterRegistry;
import ru.jrestly.resilience.RateLimiters;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Behaviour pins for {@link ResilienceComposer}: declarative circuit breaker,
 * rate limiter, retry policy and timeout resolution extracted from the builder
 * (ADR-0085). The collaborator is exercised directly, without a network round
 * trip or a proxy.
 */
class ResilienceComposerTest {

    interface ResilienceApi {
        @Get("/tuned")
        @ru.jrestly.annotation.CircuitBreaker(
                name = "composer-cb",
                failureRateThreshold = 42.0f,
                minimumNumberOfCalls = 7,
                slidingWindowSize = 11,
                waitDurationMs = 250,
                permittedNumberOfCallsInHalfOpenState = 3)
        @ru.jrestly.annotation.RateLimiter(
                name = "composer-rl",
                limitForPeriod = 5,
                limitRefreshPeriodMs = 1_000,
                timeoutDurationMs = 25)
        @Retry(times = 4)
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        String tuned();

        @Get("/plain")
        String plain();

        @Get("/disabled")
        @ru.jrestly.annotation.CircuitBreaker(name = "composer-cb-off", enabled = false)
        @ru.jrestly.annotation.RateLimiter(name = "composer-rl-off", enabled = false)
        String disabled();
    }

    /**
     * A custom {@link ModuleInfo} that keeps the interface defaults for the
     * registries ({@code null}), so the composer's static fallback registries
     * are observable.
     */
    private static final ModuleInfo BARE_MODULE = new ModuleInfo() {
        @Override
        public String getName() {
            return "resilience-composer-bare";
        }

        @Override
        public <T> Environment<T> getEnvironment() {
            return null;
        }

        @Override
        public AuthProvider getAuthProvider() {
            return new AuthHeaderProvider();
        }
    };

    private final SimpleModuleInfo module = SimpleModuleInfo.builder()
            .baseUrl("https://api.example.com")
            .retry(RetryPolicy.builder().maxAttempts(9).build())
            .build();

    private static MethodMetadata metadata(String methodName) throws Exception {
        return MethodMetadata.of(ResilienceApi.class.getMethod(methodName), ResilienceApi.class);
    }

    @Test
    @DisplayName("@Retry on the method replaces the client-level policy")
    void retryPolicyPrefersMethodAnnotation() throws Exception {
        assertEquals(4, ResilienceComposer.retryPolicy(module, metadata("tuned")).maxAttempts());
    }

    @Test
    @DisplayName("Retry policy falls back to the client level")
    void retryPolicyFallsBackToClientLevel() throws Exception {
        assertSame(module.getRetryPolicy(), ResilienceComposer.retryPolicy(module, metadata("plain")));
        assertSame(module.getRetryPolicy(), ResilienceComposer.retryPolicy(module, null));
    }

    @Test
    @DisplayName("Timeout resolves the annotated duration and stays unset otherwise")
    void timeoutResolvesMethodAnnotation() throws Exception {
        assertEquals(Duration.ofSeconds(3), ResilienceComposer.timeout(metadata("tuned")));
        assertNull(ResilienceComposer.timeout(metadata("plain")));
        assertNull(ResilienceComposer.timeout(null));
    }

    @Test
    @DisplayName("@CircuitBreaker resolves config into the module registry")
    void circuitBreakerUsesAnnotationConfigAndModuleRegistry() throws Exception {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        SimpleModuleInfo withRegistry = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .circuitBreakerRegistry(registry)
                .build();

        CircuitBreaker breaker = ResilienceComposer.circuitBreaker(withRegistry, metadata("tuned"));

        assertSame(registry.get("composer-cb"), breaker);
        assertEquals(42.0f, breaker.config().failureRateThreshold());
        assertEquals(7, breaker.config().minimumNumberOfCalls());
        assertEquals(11, breaker.config().slidingWindowSize());
        assertEquals(Duration.ofMillis(250), breaker.config().waitDurationInOpenState());
        assertEquals(3, breaker.config().permittedNumberOfCallsInHalfOpenState());
    }

    @Test
    @DisplayName("@CircuitBreaker on the method overrides the client-level breaker")
    void circuitBreakerAnnotationOverridesClientLevel() throws Exception {
        CircuitBreaker client = CircuitBreaker.of("client-cb", CircuitBreakerConfig.ofDefaults());
        SimpleModuleInfo withClient = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .circuitBreaker(client)
                .build();

        CircuitBreaker breaker = ResilienceComposer.circuitBreaker(withClient, metadata("tuned"));

        assertEquals("composer-cb", breaker.name());
    }

    @Test
    @DisplayName("Circuit breaker falls back to the client level when not annotated")
    void circuitBreakerFallsBackToClientLevel() throws Exception {
        CircuitBreaker client = CircuitBreaker.of("client-cb", CircuitBreakerConfig.ofDefaults());
        SimpleModuleInfo withClient = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .circuitBreaker(client)
                .build();

        assertSame(client, ResilienceComposer.circuitBreaker(withClient, metadata("plain")));
        assertSame(client, ResilienceComposer.circuitBreaker(withClient, null));
        assertNull(ResilienceComposer.circuitBreaker(module, metadata("plain")));
    }

    @Test
    @DisplayName("@CircuitBreaker(enabled = false) opts out even with a client breaker")
    void disabledCircuitBreakerAnnotationReturnsNull() throws Exception {
        CircuitBreaker client = CircuitBreaker.of("client-cb", CircuitBreakerConfig.ofDefaults());
        SimpleModuleInfo withClient = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .circuitBreaker(client)
                .build();

        assertNull(ResilienceComposer.circuitBreaker(withClient, metadata("disabled")));
    }

    @Test
    @DisplayName("Annotation without a module registry uses the static default registry")
    void circuitBreakerFallsBackToDefaultRegistry() throws Exception {
        CircuitBreaker first = ResilienceComposer.circuitBreaker(BARE_MODULE, metadata("tuned"));
        CircuitBreaker second = ResilienceComposer.circuitBreaker(BARE_MODULE, metadata("tuned"));

        assertNotNull(first);
        assertEquals("composer-cb", first.name());
        assertSame(first, second);
    }

    @Test
    @DisplayName("@RateLimiter resolves config into the module registry")
    void rateLimiterUsesAnnotationConfigAndModuleRegistry() throws Exception {
        RateLimiterRegistry registry = RateLimiterRegistry.ofDefaults();
        SimpleModuleInfo withRegistry = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .rateLimiterRegistry(registry)
                .build();

        RateLimiter limiter = ResilienceComposer.rateLimiter(withRegistry, metadata("tuned"));

        assertSame(registry.get("composer-rl"), limiter);
        assertEquals(5, limiter.config().limitForPeriod());
        assertEquals(Duration.ofSeconds(1), limiter.config().limitRefreshPeriod());
        assertEquals(Duration.ofMillis(25), limiter.config().timeoutDuration());
    }

    @Test
    @DisplayName("@RateLimiter on the method overrides the client-level limiter")
    void rateLimiterAnnotationOverridesClientLevel() throws Exception {
        RateLimiter client = RateLimiters.tokenBucket("client-rl", RateLimiterConfig.ofDefaults());
        SimpleModuleInfo withClient = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .rateLimiter(client)
                .build();

        RateLimiter limiter = ResilienceComposer.rateLimiter(withClient, metadata("tuned"));

        assertEquals("composer-rl", limiter.name());
    }

    @Test
    @DisplayName("Rate limiter falls back to the client level when not annotated")
    void rateLimiterFallsBackToClientLevel() throws Exception {
        RateLimiter client = RateLimiters.tokenBucket("client-rl", RateLimiterConfig.ofDefaults());
        SimpleModuleInfo withClient = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .rateLimiter(client)
                .build();

        assertSame(client, ResilienceComposer.rateLimiter(withClient, metadata("plain")));
        assertSame(client, ResilienceComposer.rateLimiter(withClient, null));
        assertNull(ResilienceComposer.rateLimiter(module, metadata("plain")));
    }

    @Test
    @DisplayName("@RateLimiter(enabled = false) opts out even with a client limiter")
    void disabledRateLimiterAnnotationReturnsNull() throws Exception {
        RateLimiter client = RateLimiters.tokenBucket("client-rl", RateLimiterConfig.ofDefaults());
        SimpleModuleInfo withClient = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .rateLimiter(client)
                .build();

        assertNull(ResilienceComposer.rateLimiter(withClient, metadata("disabled")));
    }

    @Test
    @DisplayName("Annotation without a module registry uses the static default registry")
    void rateLimiterFallsBackToDefaultRegistry() throws Exception {
        RateLimiter first = ResilienceComposer.rateLimiter(BARE_MODULE, metadata("tuned"));
        RateLimiter second = ResilienceComposer.rateLimiter(BARE_MODULE, metadata("tuned"));

        assertNotNull(first);
        assertEquals("composer-rl", first.name());
        assertSame(first, second);
    }
}
