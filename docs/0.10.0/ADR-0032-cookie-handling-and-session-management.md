# ADR-0032: Cookie Handling, Session Management and Declarative @Cookie Annotations

## Status
Accepted

## Context
In modern HTTP-based microservices, automation tests, and web APIs (RFC 6265):
1. **Session Management & Authentication:** Many stateful services, identity providers, legacy gateways, and web APIs rely on cookie-based authentication (`JSESSIONID`, session cookies, CSRF tokens). While jRestly already supports header-based auth (`AuthProvider`, ADR-0002) and custom headers (ADR-0017), automated session continuity between successive requests required cumbersome manual extraction of `Set-Cookie` headers and passing them as `@RequestHeader(name = "Cookie", value = ...)`.
2. **Standard Java zero-dep capability:** Java 21 `java.base` contains standard, well-designed networking classes in `java.net`: `CookieHandler`, `CookieManager`, `CookiePolicy`, `HttpCookie`, and `CookieStore`. The underlying `java.net.http.HttpClient.Builder` provides native support for `.cookieHandler(CookieHandler)`.
3. **Declarative ergonomic gaps:**
   - Static cookies on interfaces and methods: specifying `@Cookie(name = "theme", value = "dark")` or `@Cookie(name = "stage", value = "canary")` without stringly-typed `@RequestHeader(name = "Cookie", value = "...")`.
   - Dynamic parameter cookies: passing `@Cookie(name = "sessionId") String token` or `@Cookie Map<String, ?> cookies` directly in method signatures.
   - Inspecting response cookies: extracting cookies from `Set-Cookie` response headers currently required manual string splitting, whereas `ResponseHeaders` (ADR-0022) can provide first-class, zero-allocation/lazy parsed access via standard `java.net.HttpCookie`.

## Decision
1. **Zero-Dep Client-Level Cookie Management:**
   - Extend `ModuleInfo` with `default CookieHandler getCookieHandler() { return null; }`.
   - In `SimpleModuleInfo.Builder` and `JRestlyClient.Builder`, add:
     - `cookieHandler(CookieHandler cookieHandler)`: sets an arbitrary custom `CookieHandler`.
     - `cookieManager()`: convenience shortcut configuring `new CookieManager()`.
     - `cookiePolicy(CookiePolicy cookiePolicy)`: shortcut configuring `new CookieManager(null, cookiePolicy)`.
   - In `AbstractHttpClient.createConnection()`: pass `moduleInfo.getCookieHandler()` to `HttpClient.Builder.cookieHandler(...)` if non-null.
   - In `AbstractHttpClient`: expose `public CookieHandler getCookieHandler()` and `public Optional<CookieStore> getCookieStore()`.
2. **Declarative `@Cookie` and `@Cookies` Annotations:**
   - Introduce `@Cookie` in `ru.jrestly.annotation` with `@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})`, `@Retention(RetentionPolicy.RUNTIME)`, and container `@Cookies`.
   - Attributes: `String name() default ""` and `String value() default ""`.
   - Priority hierarchy: `Method argument > Method-level @Cookie > Sub-interface @Cookie > Super-interface @Cookie`.
   - Passing `null` as an argument removes or suppresses that specific cookie.
   - Dynamic maps: `@Cookie Map<String, ?>` expands each entry into `key=value` cookie pair.
   - Cookies are combined into a standard RFC 6265 header `Cookie: name1=value1; name2=value2`.
3. **Response Cookie Inspection in `ResponseHeaders`:**
   - `public List<HttpCookie> cookies()`: parses all `Set-Cookie` headers via `HttpCookie.parse`.
   - `public Optional<HttpCookie> cookie(String name)`: case-insensitive lookup of a specific response cookie.
   - `public Optional<String> cookieValue(String name)`: shortcut for `cookie(name).map(HttpCookie::getValue)`.
   - `public Map<String, String> cookieMap()`: convenient key-value map of all response cookies.
4. **Upfront Fail-Fast Validation (`ControllerValidator`):**
   - Disallow empty or blank cookie names on types and methods.
   - Disallow carriage return (`\r`) or line feed (`\n`) characters in names and values to prevent HTTP header injection.
   - Ensure `@Cookie` parameters are not combined with conflicting parameter annotations (`@RequestParam`, `@PathVariable`, `@RequestHeader`, `@RequestBody`, `@MultipartFormFile`).

## Consequences
- **Positive:**
  - Automated session persistence and cookie sharing between requests in one fluent line: `JRestlyClient.builder().baseUrl(url).cookieManager().build()`.
  - Easy cookie reset between automated tests: `client.getCookieStore().ifPresent(CookieStore::removeAll)`.
  - Type-safe, declarative cookie parameters in controller methods.
  - Standard, clean access to parsed `HttpCookie` attributes (expiration, max age, secure, http-only) without external libraries.
  - Strict zero-dependency core maintained (using only standard `java.net.*` classes from `java.base`).
- **Negative:**
  - `CookieManager` uses thread-safe memory storage by default; developers must manage cookie expiration in long-running processes if unbounded cookies are accepted (standard JDK behavior).
