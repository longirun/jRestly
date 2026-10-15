# ADR-0029: Declarative Interceptors (@WithInterceptor) per Controller & Method

> 2026-10-15 · **Accepted** · Session #107 (agent-loop). Builds on ADR-0018 (Request Pipeline & Interceptors).
> Source of truth for declarative interceptor binding, ordering, validation, and lifecycle.

## Context

In ADR-0018, jRestly introduced the request pipeline with `RequestInterceptor` and `ResponseInterceptor`.
Those interceptors are configured globally on the client builder level (`JRestlyClient.Builder.addRequestInterceptor`, `addResponseInterceptor`)
and apply uniformly to all HTTP calls executed by that client.

However, real-world microservices and integration gateways frequently require specific interceptors to apply only to certain APIs or endpoints:
1. **Targeted Authentication / Signing:**
   - An internal administration API or payment gateway requires a cryptographic HMAC signature or AWS SigV4 authorization header (`@WithInterceptor(HmacSignatureInterceptor.class)`), while public or health-check endpoints in the same application do not.
2. **Endpoint-Level Auditing & Metrics:**
   - Critical mutative operations (e.g. `@Post("/transfers")`, `@Delete("/accounts/{id}")`) require audit trail logging or specialized latency trackers, without degrading the performance of high-throughput read calls.
3. **Dedicated Header Propagation & Context Injection:**
   - Downstream service integrations requiring custom tenant IDs, feature flags, or routing headers.

Without declarative interceptor binding, developers are forced to either:
- Create separate `JRestlyClient` instances for each controller interface (wasting connection pools, threads, and SSL handshakes).
- Or implement monolithic global interceptors packed with fragile `if (context.method().getName().equals(...) || context.controllerClass().equals(...))` checks, violating Open-Closed and Single-Responsibility principles.

---

## Decisions

### 1. The `@WithInterceptor` Annotation (`ru.jrestly.annotation.WithInterceptor`)

We introduce `@WithInterceptor`, targeted to both types (`ElementType.TYPE`) and methods (`ElementType.METHOD`), with `@Repeatable(WithInterceptor.List.class)` support:

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(WithInterceptor.List.class)
public @interface WithInterceptor {

    Class<?>[] value();

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface List {
        WithInterceptor[] value();
    }
}
```

Developers can declare interceptors cleanly using:
- Single class: `@WithInterceptor(AuditInterceptor.class)`
- Multiple classes: `@WithInterceptor({AuditInterceptor.class, TimingInterceptor.class})`
- Repeatable annotations:
  ```java
  @WithInterceptor(AuditInterceptor.class)
  @WithInterceptor(TimingInterceptor.class)
  ```

### 2. Supported Interceptor Types & Dual Role

Any class referenced by `@WithInterceptor` must implement at least one of:
- `ru.jrestly.interceptor.RequestInterceptor` (for request decoration, URI manipulation, headers injection)
- `ru.jrestly.interceptor.ResponseInterceptor` (for post-execution observation, latency logging, metrics)
- Both interfaces simultaneously (e.g. an audit or profiling interceptor that records a correlation ID on the request and logs response metrics in `onResponse`). When a single class implements both, the same instantiated instance is registered in both chains.

### 3. Pipeline Ordering & Execution Hierarchy

Interceptors are resolved and combined deterministically:
- **`RequestInterceptor` pipeline:**
  `Global Client Interceptors` &rarr; `Super-Interface Interceptors` &rarr; `Sub-Interface Interceptors` &rarr; `Method Interceptors`.
  Each interceptor receives the `WireRequest` transformed by the preceding interceptor.
- **`ResponseInterceptor` pipeline:**
  `Global Client Interceptors` &rarr; `Super-Interface Interceptors` &rarr; `Sub-Interface Interceptors` &rarr; `Method Interceptors`.
  All post-execution hooks receive the full `ResponseContext` including start timestamp, elapsed duration, status code, and optional failure.

### 4. Lifecycle & Hot-Path Zero-Allocation

- Interceptors are instantiated reflectively via their public/accessible no-argument constructor (`clazz.getDeclaredConstructor().newInstance()`).
- Interceptor instances are cached per client (`ConcurrentHashMap<Class<?>, Object>`), ensuring:
  1. **Hot-path zero-allocation:** no new interceptor instances are created during individual HTTP calls.
  2. **Stateful interceptors:** an interceptor can safely hold counters, caches, or state across calls.
- Duplication avoidance: within a method invocation, if the same interceptor class is bound multiple times (e.g. on both super-interface and method), it is deduplicated so that it executes only once in the pipeline.

### 5. Fail-Fast Validation (`ControllerValidator`)

`ControllerValidator.validate(Class<?> controller)` performs compile-time/startup validation:
1. Classes in `@WithInterceptor` must implement `RequestInterceptor` or `ResponseInterceptor`.
2. Classes must not be interfaces or abstract classes (`Modifier.isAbstract`).
3. Classes must declare a no-argument constructor (`clazz.getDeclaredConstructor()`).
Violations immediately report `ControllerValidationException` during client initialization (`AbstractHttpClient.createController`).

---

## Consequences

- **Ergonomics:** Clear, declarative binding of cross-cutting concerns directly on API contracts.
- **Zero-Dep Core:** 100% JDK 21 standard reflection and functional interfaces.
- **Performance:** Pre-resolved pipelines and cached interceptor singletons ensure zero allocation on execution hot paths.
- **Safety:** Compile-time and startup validation catch misconfigured interceptor classes immediately.
