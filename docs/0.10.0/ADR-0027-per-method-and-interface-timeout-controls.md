# ADR-0027: Per-Method & Interface-Level Timeout Controls (@Timeout)

> 2026-10-15 · **Accepted** · Concept, design, and full implementation verified in session #105.
> Addresses candidate #2 from the active zone of DRAFT.md and ergonomic requirements for
> modern real-world REST API integrations.

## Context

In real-world distributed architectures, HTTP endpoints exposed by external microservices or third-party APIs
exhibit drastically different response time characteristics:
1. **Health checks & fast probes** (`/health`, `/ping`): must fail quickly (e.g. 500 ms – 1 s) to prevent
   cascading latencies and thread pool starvation.
2. **Standard CRUD requests** (`/api/items/{id}`): operate reliably under standard timeouts (default 30 seconds).
3. **Heavy analytical queries, reports, batch exports, and LLM completions** (`/reports/generate`, `/v1/chat/completions`):
   require long execution budgets (e.g. 60–300 seconds).

Previously, jRestly configured socket (request) timeout globally per client instance via
`JRestlyClient.builder().socketTimeout(int ms)`. As a consequence:
- Developers needing to support both fast health checks and slow batch/LLM requests had to either raise the
  global timeout to the maximum (degrading fault isolation and resilience for standard calls) or construct
  and maintain multiple `JRestlyClient` instances.
- Declarative controller interfaces lacked any mechanism to state expected latency bounds directly on methods
  or controller interfaces.
- Builder timeout methods only accepted primitive millisecond integers rather than idiomatic Java `java.time.Duration`.

### Constraints & Invariants

1. **Zero-dep core:** Rely strictly on Java standard library types `java.util.concurrent.TimeUnit` and
   `java.time.Duration` from `java.base`.
2. **Clear precedence model:**
   `Method @Timeout` > `Sub-interface @Timeout` > `Super-interface @Timeout` > `Client socketTimeout`.
3. **Strict backward compatibility:** Existing clients without `@Timeout` annotations continue to execute with
   `moduleInfo.getSocketTimeout()`.
4. **Fail-fast validation:** A timeout value `<= 0` is rejected at controller proxy creation time by
   `ControllerValidator` (when validation is enabled) and at request dispatch by `HttpTemplateBuilder`.
5. **Full transport coverage:** Works seamlessly across synchronous calls, streaming responses, and asynchronous
   `CompletableFuture<T>` pipelines, and integrates naturally with `@Retry` transport error handling.

---

## Decisions

### 1. Declarative `@Timeout` Annotation

We introduce `ru.jrestly.annotation.Timeout`:

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Timeout {

    /**
     * The duration of the timeout. Must be strictly positive (> 0).
     *
     * @return timeout duration value
     */
    long value();

    /**
     * The time unit of {@link #value()}.
     * Defaults to {@link TimeUnit#MILLISECONDS}.
     *
     * @return the time unit
     */
    TimeUnit unit() default TimeUnit.MILLISECONDS;
}
```

Usage examples:
- `@Timeout(500)`: 500 milliseconds.
- `@Timeout(value = 5, unit = TimeUnit.SECONDS)`: 5 seconds.
- `@Timeout(value = 2, unit = TimeUnit.MINUTES)` on a heavy report method or controller interface.

### 2. Validation in `ControllerValidator`

`ControllerValidator` inspects both interface declarations and method declarations:
- If an interface or method declares `@Timeout` with `value() <= 0`, it records a human-readable validation problem:
  - `<InterfaceName>: @Timeout value must be strictly positive, was <val>`
  - `<methodName>(): @Timeout value must be strictly positive, was <val>`

### 3. Builder Ergonomics: `Duration` Overloads

We introduce `Duration` overloads in both `SimpleModuleInfo.Builder` and `JRestlyClient.Builder`:
- `connectTimeout(Duration duration)`: converts duration to milliseconds with non-null and non-negative checks.
- `socketTimeout(Duration duration)`: converts duration to milliseconds with non-null and non-negative checks.

### 4. Resolution & Transport Wiring in `HttpTemplate` & `HttpTemplateBuilder`

In `HttpTemplate`:
- Added field `private Duration timeout;` with getter and setter.
- In `toHttpRequest(WireRequest request)`:
  ```java
  Duration effectiveTimeout = timeout != null
          ? timeout
          : Duration.ofMillis(moduleInfo.getSocketTimeout());
  HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
          .timeout(effectiveTimeout);
  ```

In `HttpTemplateBuilder`:
- `resolveTimeout()` checks:
  1. `method.getAnnotation(Timeout.class)`: if present, validates and converts to `Duration.of(value, unit.toChronoUnit())`.
  2. Hierarchy of controller interfaces (`collectInterfaceHierarchy(controller)`): iterates in reverse order
     (sub-interface takes precedence over parent interfaces).
  3. Returns `null` if no annotation is found, letting `toHttpRequest` use the module's `getSocketTimeout()`.

---

## Consequences

- Endpoints can declaratively specify customized request timeouts tailored to their SLAs.
- Heavy endpoints and LLM streams can run without compromising tight timeouts for fast operations.
- Synchronous calls timing out throw `RuntimeException` wrapping `java.net.http.HttpTimeoutException`.
- Asynchronous `CompletableFuture` calls complete exceptionally with `HttpTimeoutException`.
- `@Retry` transport policies recognize `HttpTimeoutException` as an `IOException` and retry accordingly.
- Core remains 100% zero-dependency on standard Java 21 platform classes.
