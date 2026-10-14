# ADR-0026: Virtual Threads & Executor Customization

> 2026-10-15 · **Accepted** · Concept, design, and full implementation verified in session #104.
> Addresses candidate #2 from the active zone of DRAFT.md and ergonomic requirements for
> modern Java 21 applications.

## Context

Java 21 introduced virtual threads (JEP 444 / Project Loom) as a foundational feature for high-throughput,
concurrent I/O applications. Virtual threads are lightweight threads that drastically reduce the resource
overhead of traditional OS-level threads, enabling servers and clients to handle tens of thousands of concurrent
requests seamlessly.

In jRestly, asynchronous controller methods return `CompletableFuture<T>`. Under the hood, declarative requests
are executed by the standard JDK `java.net.http.HttpClient` via `sendAsync()`.

Currently, `AbstractHttpClient.createConnection()` configures timeouts, HTTP protocol version, and SSL/TLS settings,
but leaves the executor unspecified on `HttpClient.newBuilder()`. As a result:
1. The underlying HTTP client falls back to the JVM-wide default thread pool (a shared cached thread pool).
2. Applications running on Java 21 cannot declaratively configure jRestly to run asynchronous requests and
   reactive completions on virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`.
3. Enterprise architectures implementing bulkhead isolation, thread pooling policies, tracing context propagation
   (e.g., OpenTelemetry / Micrometer), or custom thread factory naming cannot provide a dedicated `Executor` to the client.
4. Asynchronous retry delays in `HttpTemplate.scheduleAsyncRetry` rely on `CompletableFuture.delayedExecutor()`
   without binding to the client's custom thread execution pool.

### Constraints & Invariants

1. **Zero-dep core:** Rely strictly on Java 21 standard library classes from `java.util.concurrent`
   (`Executor`, `ExecutorService`, `Executors`) present in `java.base`.
2. **Strict backward compatibility:** Existing `ModuleInfo` implementations must not break;
   default methods must preserve the default JDK platform behavior (`getExecutor() == null`, `isVirtualThreads() == false`).
3. **Clean resource management (Lifecycle):** When jRestly manages an internally created virtual thread executor
   (`useVirtualThreads()`), calling `AbstractHttpClient.close()` must cleanly shut down that executor service.
   When an external `Executor` is supplied by the user, the user maintains ownership of its lifecycle.
4. **Fail-fast builder semantics:** Passing `null` to `executor(...)` throws `NullPointerException("executor")`.
5. **Architectural consistency:** Preserves zero-wrapper transparent methods (ADR-0004) and anti-combination (ADR-0003).

---

## Decisions

### 1. SPI & Configuration Surface: `ModuleInfo`

We add two default methods to `ru.jrestly.ModuleInfo`:

```java
/**
 * The executor used for asynchronous tasks in the underlying HTTP client
 * and asynchronous pipeline stages. When {@code null} (the default), the
 * client uses the platform default executor or virtual threads if
 * {@link #isVirtualThreads()} is enabled.
 *
 * @return the custom executor, or {@code null} for the default
 */
default Executor getExecutor() {
    return null;
}

/**
 * Whether to use virtual threads (via {@link java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()})
 * for asynchronous tasks when no custom {@link #getExecutor()} is provided.
 * Defaults to {@code false}.
 *
 * @return {@code true} if virtual threads are enabled, {@code false} otherwise
 */
default boolean isVirtualThreads() {
    return false;
}
```

### 2. Builder Ergonomics: `SimpleModuleInfo.Builder` & `JRestlyClient.Builder`

Both builders provide fluent configuration methods:

- `executor(Executor executor)`: sets a custom `Executor`, validating non-null with `Objects.requireNonNull(executor, "executor")`.
- `virtualThreads(boolean enabled)`: enables or disables virtual thread execution.
- `useVirtualThreads()`: convenient shortcut for `virtualThreads(true)`.

Precedence rule: If an explicit `executor` is provided, it takes highest precedence over the `virtualThreads` flag.

### 3. Transport Wiring & Lifecycle: `AbstractHttpClient`

In `AbstractHttpClient`:
- Holds `protected Executor executor;` and `protected ExecutorService managedExecutorService;`.
- In `createConnection()`:
  - If `moduleInfo.getExecutor() != null`, wires `builder.executor(moduleInfo.getExecutor())` and records `this.executor`.
  - Else if `moduleInfo.isVirtualThreads()` is true, creates `this.managedExecutorService = Executors.newVirtualThreadPerTaskExecutor()`,
    wires it into `builder.executor(...)`, and records `this.executor = this.managedExecutorService`.
- In `close()`:
  - Closes `httpClient.close()`.
  - If `managedExecutorService != null`, invokes `managedExecutorService.close()`. External executors are not closed.
- In `createController()`: passes `this.executor` to `HttpTemplateBuilder`.

### 4. Async Retry Scheduler: `HttpTemplate`

In `HttpTemplate`:
- Accepts `setExecutor(Executor executor)`.
- In `scheduleAsyncRetry()`: uses `CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, executor)`
  when `executor != null`, ensuring retry delayed dispatch executes on the configured executor or virtual threads.

---

## Verification & Gates

- **Unit tests:**
  - `ExecutorConfigTest`: defaults verification, builder fail-fast null-guards, virtual threads toggling, explicit executor precedence.
- **WireMock integration tests:**
  - `VirtualThreadAndExecutorWireMockTest`:
    1. Async GET using `useVirtualThreads()` verifying execution on Java 21 virtual threads (`Thread.currentThread().isVirtual() == true`).
    2. Async GET using custom `Executor` verifying execution on custom named threads.
    3. Async retry with backoff verifying retry attempts run on the configured custom executor / virtual threads.
    4. Clean shutdown: `client.close()` shuts down internally managed executor service.
    5. External executor lifecycle preserved upon `client.close()`.
- **Gates:**
  - `./gradlew test`: all tests green.
  - `-Xlint:all -Werror` and `-Xdoclint:all -Werror`: 0 warnings.
  - Zero-dep core preserved.
