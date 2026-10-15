# ADR-0039: Cookie Store Persistence SPI and PersistentCookieStore

## Status
Accepted

## Context
In ADR-0032, jRestly introduced declarative `@Cookie` annotations, cookie inspection in `ResponseHeaders`, and client-level cookie management via `CookieHandler` and `CookieManager`.
However, several architectural and ergonomic limitations remained:
1. **Volatile In-Memory Storage by Default:**
   Standard Java `CookieManager` defaults to `sun.net.www.protocol.http.InMemoryCookieStore`, which loses all session state, authentication tokens (`JSESSIONID`, bearer cookies, OAuth/SSO sessions), and server-assigned cookies whenever the JVM process terminates.
2. **Missing CookieStore in Builder APIs:**
   While `JRestlyClient.Builder` and `SimpleModuleInfo.Builder` offered `cookieHandler(CookieHandler)`, `cookieManager()`, and `cookiePolicy(CookiePolicy)`, they lacked direct `cookieStore(CookieStore)` and `cookieStore(CookieStore, CookiePolicy)` methods. Developers supplying custom or persistent stores had to instantiate `new CookieManager(store, policy)` manually.
3. **Absence of Zero-Dependency Persistence SPI:**
   CLI tools, test runners, bots, automation scripts, and microservice clients often need to persist cookies across process restarts (e.g., to local files, encrypted storage, or database backends) without pulling heavyweight third-party cookie jar dependencies.
4. **Cookie Serialization Standards:**
   RFC 6265 defines standard `Set-Cookie` directives (`Domain`, `Path`, `Max-Age`, `Expires`, `Secure`, `HttpOnly`). The JDK provides `HttpCookie.parse()`, but lacks a canonical, zero-dependency serialization and stream reader/writer mechanism for round-trip persistence and atomic file storage.

## Decision
1. **Cookie Persistence SPI (`ru.jrestly.cookie.CookiePersister`):**
   - Provide a zero-dependency interface with three primary methods:
     - `List<HttpCookie> loadAll()`: loads previously persisted cookies into memory during store initialization.
     - `void saveAll(Collection<HttpCookie> cookies)`: persists active non-expired cookies.
     - `void clear()`: wipes all persisted cookies from storage.
2. **Standard File Persister (`ru.jrestly.cookie.FileCookiePersister`):**
   - A zero-dependency file-backed persister supporting `java.nio.file.Path` and `java.io.File`.
   - Reads and writes RFC 6265 formatted cookie entries with comments and timestamp headers.
   - Filters expired cookies automatically (`!cookie.hasExpired()`).
   - Uses atomic file replacement (`StandardCopyOption.ATOMIC_MOVE` with standard fallback) and UTF-8 encoding to prevent data corruption during process termination or crashes.
3. **Format & Serialization Utility (`ru.jrestly.cookie.CookieCodec`):**
   - `format(HttpCookie cookie)`: serializes an `HttpCookie` into an RFC 6265 compliant string (`name=value; Domain=...; Path=...; Max-Age=...; Secure; HttpOnly`).
   - `write(Writer writer, Collection<HttpCookie> cookies)`: streams cookies to any `Writer`.
   - `read(Reader reader)`: parses cookies from any `Reader`, skipping comments (`#`) and blank lines.
4. **Thread-Safe Persistent Cookie Store (`ru.jrestly.cookie.PersistentCookieStore`):**
   - Implements `java.net.CookieStore`.
   - Wraps an underlying delegate `CookieStore` (defaulting to the JDK standard store via `new CookieManager().getCookieStore()`).
   - Pre-loads cookies upon creation via `persister.loadAll()`.
   - Supports two persistence modes:
     - `AUTO` (default): automatically invokes `persister.saveAll()` whenever cookies are added, updated, or removed (`add`, `remove`, `removeAll`).
     - `MANUAL`: updates memory immediately and persists only on explicit calls to `sync()` / `persist()`.
   - Provides lifecycle and diagnostic methods: `sync()`, `persist()`, `load()`, `size()`, `clear()`.
   - Exposes static factories: `of(CookiePersister)`, `ofFile(Path)`, `ofFile(Path, boolean autoSync)`, `builder()`.
5. **Ergonomic Client Builder Enhancements:**
   - In `SimpleModuleInfo.Builder` and `JRestlyClient.Builder`, introduce:
     - `cookieStore(CookieStore store)`: configures a `CookieManager` with the store and default `CookiePolicy.ACCEPT_ORIGINAL_SERVER`.
     - `cookieStore(CookieStore store, CookiePolicy policy)`: configures a `CookieManager` with the store and given policy.
     - `persistentCookieStore(Path path)`: one-line shortcut configuring a file-backed persistent store.

## Consequences
- **Positive:**
  - Automated session persistence across application or test runs in one fluent line:
    `JRestlyClient.builder().baseUrl(url).persistentCookieStore(Path.of("cookies.txt")).build()`.
  - Zero third-party dependencies: built strictly on Java 21 `java.net.*`, `java.nio.file.*`, `java.io.*`.
  - Thread-safe and resilient against concurrent requests and sudden JVM shutdown.
  - Pluggable SPI allows custom backends (Redis, SQL, SharedPreferences, memory cache) by implementing 3 methods of `CookiePersister`.
  - 100% backward compatible with existing `CookieHandler` and `CookieManager` builder methods.
- **Negative:**
  - File I/O in `AUTO` mode performs disk writes on `Set-Cookie` responses; high-throughput workloads with frequent ephemeral cookies can opt for `MANUAL` mode with background or scheduled `sync()`.
