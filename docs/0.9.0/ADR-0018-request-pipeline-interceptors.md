# ADR-0018: Request pipeline interceptors & hooks

> 2026-10-14 · **Accepted** · Sessions #84–#89 (agent-loop). The epic's 6 steps
> are complete (420/420 tests, 51 classes, 0 warnings). This ADR is the source of
> truth for design decisions, invocation lifecycles, and ergonomics.

## Context

Following the response byte streaming epic (ADR-0015), Server-Sent Events / line streaming
epic (ADR-0016), and request parameter & header flexibility epic (ADR-0017), this is the
fourth ergonomics epic addressing direction 3 from the maintainer's 2026-10-14 mailbox:
> "3. Поэтому естественно желание углублять эргономику библиотеки для использования разработчиком
> в вызовах современных API (например, но не исчерпывась: гибкость параметров запроса, стриминг данных,
> ЛЛМ, тонкая настройка пайплайна запросов и т.д.)."

In real-world enterprise applications, microservice architectures, and modern test automation
frameworks (e.g., Allure, OpenTelemetry, Micrometer, CI/CD observability), HTTP clients must
support flexible request pre-processing and post-execution observation without bloating the core:

1. **Dynamic request decoration:**
   - Dynamic authentication tokens (e.g., rotating Bearer tokens refreshed asynchronously).
   - Distributed tracing & correlation headers (`X-Correlation-ID`, `X-Request-ID`, W3C `traceparent`).
   - Dynamic user-agent or tenant identifiers (`X-Tenant-ID`).
   - URI rewriting or dynamic path/query customization.
2. **Post-execution hooks & observability:**
   - Recording request latency and HTTP metrics (Micrometer `Timer`, Prometheus counters).
   - Structured audit logging and reporting (e.g., Allure test attachments, ELK event emission).
   - Inspecting response status codes, headers, and transport failures.
3. **Invocation metadata & state propagation:**
   - Interceptors need context: which controller interface (`Class<?>`), which method (`Method`),
     and which runtime arguments (`Object[] args`) are being executed.
   - Cross-cutting concerns require passing state from pre-request to post-response
     (e.g., passing a tracing `Span` or start timestamp via context attributes).

### Constraints & Invariants (from Direction 1)

1. **Zero-dep core:** JDK 21 standard library only (`java.net.http`, `java.lang.reflect`,
   `java.util.function`, `java.time`). No external interceptor frameworks, bytecode weaving, or AOP.
2. **Transparent methods without `Response<T>` (ADR-0004):** declarative controller methods remain
   clean, returning domain objects, `InputStream`, `Stream<T>`, or `CompletableFuture<T>`.
3. **Anti-combination (ADR-0003):** no heavyweight servlet-like filter chains with thread-local
   magic; simple, explicit, functional interfaces (`@FunctionalInterface`).
4. **Zero-cost default path:** if no interceptors are configured (the default), the hot execution
   path does not allocate context objects or execute iteration loops (`List.isEmpty()` short-circuit).
5. **Strict backward compatibility:** existing `ModuleInfo`, `AbstractHttpClient`, and `JRestlyClient`
   implementations remain fully binary and source compatible.

---

## Decisions

### Aspect 1: Interceptor Interfaces (`ru.jrestly.interceptor`)

We introduce a dedicated, clean package `ru.jrestly.interceptor` containing two distinct,
purpose-built functional interfaces:

1. **`RequestInterceptor` (Pre-execution request customization):**
   ```java
   @FunctionalInterface
   public interface RequestInterceptor {
       WireRequest intercept(WireRequest request, RequestContext context);

       static RequestInterceptor of(UnaryOperator<WireRequest> operator) {
           Objects.requireNonNull(operator, "operator");
           return (req, ctx) -> operator.apply(req);
       }
   }
   ```
   - Invoked before the HTTP request is placed on the wire.
   - May inspect and transform `WireRequest` (adding headers, altering URI, etc.).
   - Returns the effective `WireRequest` to execute. Returning `null` throws `NullPointerException`.
   - `of(UnaryOperator)` enables ultra-concise lambda expressions:
     `client.builder().addRequestInterceptor(req -> req.withHeader("X-Trace", traceId))`

2. **`ResponseInterceptor` (Post-execution observability hook):**
   ```java
   @FunctionalInterface
   public interface ResponseInterceptor {
       void onResponse(ResponseContext context);
   }
   ```
   - Invoked once the call concludes — either successfully with a `WireResponse`, or with a failure
     (`Throwable`).
   - Designed for observability: logging, metrics, audit, tracing, and test reporting.
   - Robustness guarantee: if a `ResponseInterceptor` throws an exception, it is logged at `WARNING`
     level; it never suppresses or masks the controller method's actual return value or exception,
     and subsequent interceptors in the list are still invoked.

### Aspect 2: Invocation Context Models (`RequestContext` & `ResponseContext`)

We decouple invocation metadata and execution state into two clean, read-only interfaces:

1. **`RequestContext` (Available to both `RequestInterceptor` and `ResponseInterceptor`):**
   - `String moduleName()`: name of the module originating the call.
   - `Class<?> controllerClass()`: the proxied controller interface.
   - `Method method()`: the invoked controller method.
   - `Object[] args()`: the runtime arguments passed to the call.
   - Attribute storage for inter-interceptor communication:
     - `void setAttribute(String name, Object value)`
     - `Object getAttribute(String name)`
     - `<T> Optional<T> getAttribute(String name, Class<T> type)`
     - `Map<String, Object> attributes()`

2. **`ResponseContext extends RequestContext` (Available to `ResponseInterceptor`):**
   - `WireRequest request()`: the final wire request sent.
   - `Optional<WireResponse> response()`: the received response (empty on transport failure).
   - `Optional<Throwable> failure()`: the caught exception (empty on successful call).
   - `Duration duration()`: the total elapsed duration of the logical call.
   - Ergonomic query helpers:
     - `int statusCode()`: returns the HTTP status code from response, or from `HandledException`
       if available, or `-1` if no status is known.
     - `boolean isSuccess()`: returns `true` if `failure().isEmpty()` and status is 2xx.

### Aspect 3: Ergonomic `WireRequest` Wither Methods

To allow clean, immutable transformations of `WireRequest` without tedious manual reconstruction:
- `WireRequest withHeader(String name, String value)`
- `WireRequest withHeader(Header header)`
- `WireRequest withHeaders(List<Header> newHeaders)`
- `WireRequest withoutHeader(String name)` (case-insensitive removal)
- `WireRequest withUri(URI newUri)`
- `WireRequest withMethod(HttpMethod newMethod)`
- `WireRequest withBody(byte[] newBody, String newContentType)`
- `WireRequest.Builder toBuilder()` and `static WireRequest.Builder builder()`

All withers return a new `WireRequest` record instance, preserving immutability and thread safety.

### Aspect 4: Execution Lifecycle & Symmetry (Sync & Async Twins)

1. **Logical Call-Level Execution:**
   - `RequestInterceptor` chain executes **once per logical controller method invocation**, before
     entering the retry loop (`executeWithRetries` / `executeAsyncWithRetries`) and before redirect
     hops.
   - Rationale: Correlation IDs and idempotency tokens must remain stable across redirect hops and
     retry attempts.
2. **Response Interceptor Timing:**
   - In synchronous execution (`exchange()`): executed in a `finally` block enclosing the entire
     exchange and response processing lifecycle.
   - In asynchronous execution (`exchangeAsync()`): executed in `.whenComplete((res, ex) -> ...)`
     ensuring non-blocking execution on the completing thread without thread starvation.
3. **Stream Safety:**
   - For streaming responses (`InputStream`, `Stream<String>`, `Stream<ServerSentEvent>`),
     `ResponseInterceptor` has access to response metadata (`statusCode`, `headers`, `stream != null`)
     without reading or consuming the body stream.

### Aspect 5: Configuration API (`ModuleInfo` & `JRestlyClient.Builder`)

1. **`ModuleInfo` Interface:**
   - `default List<RequestInterceptor> getRequestInterceptors() { return List.of(); }`
   - `default List<ResponseInterceptor> getResponseInterceptors() { return List.of(); }`
2. **`SimpleModuleInfo.Builder` & `JRestlyClient.Builder`:**
   - `addRequestInterceptor(RequestInterceptor interceptor)`
   - `addRequestInterceptor(UnaryOperator<WireRequest> interceptor)`
   - `addResponseInterceptor(ResponseInterceptor interceptor)`
   - `requestInterceptors(List<RequestInterceptor> interceptors)`
   - `responseInterceptors(List<ResponseInterceptor> interceptors)`

---

## Validation & Test Strategy

1. **Unit TDD:**
   - Wither methods on `WireRequest` (case-insensitive `withoutHeader`, header appending, URI update).
   - Attribute passing in `RequestContext` to `ResponseContext`.
   - Sequential ordering of multiple `RequestInterceptor`s.
   - `ResponseInterceptor` execution on 2xx success, 4xx/5xx `@OnError` decode, unexpected status,
     and network `IOException`.
   - Resilience against exceptions thrown by `ResponseInterceptor` (log warning, continue chain).
2. **Sync & Async Twin Verification:**
   - Identical interceptor behavior between sync controller methods and `CompletableFuture<T>`.
3. **End-to-End WireMock Suite:**
   - Dynamic bearer auth injection via `RequestInterceptor`.
   - Distributed tracing correlation ID propagation.
   - Latency audit logging and metrics verification via `ResponseInterceptor`.
