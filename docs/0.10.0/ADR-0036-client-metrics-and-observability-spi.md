# ADR-0036: Client Metrics & Observability SPI (Micrometer / OpenTelemetry Readiness)

## Status
Accepted

## Context
When adopting a declarative HTTP client in production microservices and enterprise integration pipelines, observability is a primary non-functional requirement. Engineering teams must track:
1. **Request latencies & call rates** (e.g. Micrometer `Timer` or OpenTelemetry HTTP client semantic conventions: `http.client.request.duration`);
2. **Active in-flight requests** (Micrometer `Gauge`: incremented on request initiation, decremented upon completion);
3. **Retry attempts & failure causes** (counters indicating how often `@Retry` is triggered and why);
4. **Per-attempt wire latencies and data volumes** (request body bytes uploaded, response body bytes downloaded);
5. **Distributed tracing integration** (starting and concluding tracing spans linked to the HTTP client call).

Previous versions of jRestly introduced `RequestInterceptor` and `ResponseInterceptor` (ADR-0018, ADR-0029). While valuable for request decoration and logging, `ResponseInterceptor` has fundamental limitations for observability:
- It only triggers once the entire logical call completes. It cannot track when a request begins, making in-flight gauges and tracing span lifecycle management awkward.
- Intermediate retry attempts (under `@Retry`) are invisible to `ResponseInterceptor` because it strictly observes the "classified exactly once" principle (ADR-0008).
- It carries no information about attempt counts, retry delays, or wire payload byte counts.

At the same time, jRestly maintains its foundational architectural principle:
**Zero-dependency core** (ADR-0001, ADR-0003, Java 21 stdlib only). The library must not depend on `io.micrometer:micrometer-core` or `io.opentelemetry:opentelemetry-api` in `implementation` dependencies.

## Decision
Introduce a zero-dependency, high-performance Client Metrics & Observability SPI in `ru.jrestly.metrics`:

1. **`ClientMetricsListener` Interface (`ru.jrestly.metrics.ClientMetricsListener`):**
   - `@FunctionalInterface` where the primary method is `void onCallComplete(CallMetrics metrics)`.
   - Default lifecycle methods:
     - `default void onCallStart(RequestContext context, WireRequest request)`: invoked before placing the first request on the wire.
     - `default void onRetry(RequestContext context, WireRequest request, int attempt, Throwable reason, Duration delay)`: invoked when an attempt fails and a retry backoff is scheduled.
     - `default void onAttemptComplete(RequestContext context, WireRequest request, int attempt, Optional<WireResponse> response, Optional<Throwable> failure, Duration duration)`: invoked when an individual transmission attempt completes across the wire.
   - Static factory `static ClientMetricsListener composite(ClientMetricsListener... listeners)`.

2. **`CallMetrics` Model (`ru.jrestly.metrics.CallMetrics`):**
   - Immutable view of the completed HTTP call:
     - `RequestContext context()`: module name, controller class, method, args, and custom attributes.
     - `WireRequest request()`: initial wire request.
     - `Optional<WireResponse> response()`: final wire response, if any.
     - `Optional<Throwable> failure()`: final failure exception, if any.
     - `Duration duration()`: total elapsed call time.
     - `int attempts()`: total attempts executed.
     - `long requestBytes()`: uploaded body byte size (-1 if unknown).
     - `long responseBytes()`: downloaded body byte size (-1 if unknown).
     - Convenience helper methods: `statusCode()`, `isSuccess()`, `isRetried()`, `method()`, `uri()`.

3. **Declarative Annotation `@WithMetrics` (`ru.jrestly.annotation.WithMetrics`):**
   - Applicable to controller interfaces (`ElementType.TYPE`) and methods (`ElementType.METHOD`).
   - Binds a `Class<? extends ClientMetricsListener>` with an accessible no-argument constructor.
   - Merges with client-level listener using `ClientMetricsListener.composite(...)`.

4. **Client & Module Configuration:**
   - `ModuleInfo.getMetricsListener()` default returns `null`.
   - `SimpleModuleInfo.Builder.metricsListener(ClientMetricsListener listener)` (NPE fail-fast, composite chaining).
   - `JRestlyClient.Builder.metricsListener(ClientMetricsListener listener)` (NPE fail-fast, composite chaining).

5. **Upfront Validation (`ControllerValidator`):**
   - Validates that classes referenced in `@WithMetrics` implement `ClientMetricsListener`, are concrete (not interface/abstract), and have an accessible no-arg constructor.

6. **Safety & Fault-Tolerance:**
   - Exceptions thrown by `ClientMetricsListener` callbacks are caught and logged at `WARNING` level, never disrupting the business flow of the HTTP call.

## Consequences
- Full production observability readiness for Micrometer, OpenTelemetry, Prometheus, and custom telemetry systems with zero external dependencies.
- Precise monitoring of retries, in-flight gauges, and wire byte sizes.
- 100% backward compatibility: existing configurations with no listener incur zero overhead.
