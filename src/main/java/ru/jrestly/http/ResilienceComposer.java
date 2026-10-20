package ru.jrestly.http;

import ru.jrestly.ModuleInfo;
import ru.jrestly.resilience.CircuitBreaker;
import ru.jrestly.resilience.CircuitBreakerConfig;
import ru.jrestly.resilience.CircuitBreakerRegistry;
import ru.jrestly.resilience.RateLimiter;
import ru.jrestly.resilience.RateLimiterConfig;
import ru.jrestly.resilience.RateLimiterRegistry;

import java.time.Duration;

/**
 * Resolves the per-invocation execution policies — retry policy, timeout,
 * circuit breaker and rate limiter — from the method metadata and the module
 * defaults (ADR-0085).
 *
 * <p>Stateless and package-private. Declarative annotations win over the
 * client-level configuration; a disabled annotation opts out even when the
 * client configures a policy. The module is non-null by the time the builder
 * calls this collaborator ({@code build()} reads the environment first).
 */
final class ResilienceComposer {

    private static final CircuitBreakerRegistry DEFAULT_CIRCUIT_BREAKER_REGISTRY = CircuitBreakerRegistry.ofDefaults();
    private static final RateLimiterRegistry DEFAULT_RATE_LIMITER_REGISTRY = RateLimiterRegistry.ofDefaults();

    private ResilienceComposer() {
    }

    /**
     * Resolves the retry policy: a method-level {@code @Retry} replaces the
     * client-level policy entirely (ADR-0008).
     *
     * @param moduleInfo     module configuration supplying the client-level policy
     * @param methodMetadata parsed controller method metadata, or {@code null}
     * @return the effective retry policy
     */
    static RetryPolicy retryPolicy(ModuleInfo moduleInfo, MethodMetadata methodMetadata) {
        if (methodMetadata != null && methodMetadata.retryPolicy() != null) {
            return methodMetadata.retryPolicy();
        }
        return moduleInfo.getRetryPolicy();
    }

    /**
     * Resolves the per-method {@code @Timeout}, if declared (ADR-0027).
     *
     * @param methodMetadata parsed controller method metadata, or {@code null}
     * @return the declared timeout, or {@code null} when unset
     */
    static Duration timeout(MethodMetadata methodMetadata) {
        return methodMetadata != null ? methodMetadata.timeout() : null;
    }

    /**
     * Resolves circuit breaker protection: the enabled method annotation wins,
     * a disabled one opts out, otherwise the client-level breaker applies
     * (ADR-0043).
     *
     * @param moduleInfo     module configuration supplying the registry and fallback
     * @param methodMetadata parsed controller method metadata, or {@code null}
     * @return the effective circuit breaker, or {@code null} when unprotected
     */
    static CircuitBreaker circuitBreaker(ModuleInfo moduleInfo, MethodMetadata methodMetadata) {
        if (methodMetadata != null && methodMetadata.circuitBreakerAnnotation() != null) {
            ru.jrestly.annotation.CircuitBreaker ann = methodMetadata.circuitBreakerAnnotation();
            if (!ann.enabled()) {
                return null;
            }
            return circuitBreakerFromAnnotation(moduleInfo, ann);
        }
        return moduleInfo.getCircuitBreaker();
    }

    /**
     * Resolves rate limiting: the enabled method annotation wins, a disabled
     * one opts out, otherwise the client-level limiter applies (ADR-0043).
     *
     * @param moduleInfo     module configuration supplying the registry and fallback
     * @param methodMetadata parsed controller method metadata, or {@code null}
     * @return the effective rate limiter, or {@code null} when unlimited
     */
    static RateLimiter rateLimiter(ModuleInfo moduleInfo, MethodMetadata methodMetadata) {
        if (methodMetadata != null && methodMetadata.rateLimiterAnnotation() != null) {
            ru.jrestly.annotation.RateLimiter ann = methodMetadata.rateLimiterAnnotation();
            if (!ann.enabled()) {
                return null;
            }
            return rateLimiterFromAnnotation(moduleInfo, ann);
        }
        return moduleInfo.getRateLimiter();
    }

    private static CircuitBreaker circuitBreakerFromAnnotation(ModuleInfo moduleInfo,
                                                               ru.jrestly.annotation.CircuitBreaker ann) {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .failureRateThreshold(ann.failureRateThreshold())
                .minimumNumberOfCalls(ann.minimumNumberOfCalls())
                .slidingWindowSize(ann.slidingWindowSize())
                .waitDurationInOpenState(Duration.ofMillis(ann.waitDurationMs()))
                .permittedNumberOfCallsInHalfOpenState(ann.permittedNumberOfCallsInHalfOpenState())
                .build();
        CircuitBreakerRegistry registry = moduleInfo.getCircuitBreakerRegistry();
        if (registry == null) {
            registry = DEFAULT_CIRCUIT_BREAKER_REGISTRY;
        }
        return registry.getOrCreate(ann.name(), config);
    }

    private static RateLimiter rateLimiterFromAnnotation(ModuleInfo moduleInfo,
                                                         ru.jrestly.annotation.RateLimiter ann) {
        RateLimiterConfig config = RateLimiterConfig.builder()
                .limitForPeriod(ann.limitForPeriod())
                .limitRefreshPeriod(Duration.ofMillis(ann.limitRefreshPeriodMs()))
                .timeoutDuration(Duration.ofMillis(ann.timeoutDurationMs()))
                .build();
        RateLimiterRegistry registry = moduleInfo.getRateLimiterRegistry();
        if (registry == null) {
            registry = DEFAULT_RATE_LIMITER_REGISTRY;
        }
        return registry.getOrCreate(ann.name(), config);
    }
}
