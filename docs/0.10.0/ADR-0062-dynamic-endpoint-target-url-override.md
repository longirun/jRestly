# ADR-0062: Dynamic Endpoint Target URL Override (@TargetUrl)

## Status

Accepted (2026-10-17, Session #237)

## Context

Following the competitive analysis and strategic gap identification in ADR-0061, the most critical functional limitation of jRestly identified for production adoption was the inability to dynamically override request endpoints at invocation time.

In real-world enterprise architectures, request target URLs are frequently dynamic and resolved at runtime:
1. **Cloud Object Storage (AWS S3, Google Cloud Storage, Azure Blob):** Upload and download presigned URLs generated on-demand with temporary authentication signatures and query parameters.
2. **RESTful Pagination & HATEOAS:** Traversing resource collections using `Link: <...>; rel="next"` or embedded hypermedia links pointing to absolute next-page URLs.
3. **Dynamic Multi-Tenant Routing & Webhooks:** Services routing requests to customer-specific domains or arbitrary webhook endpoints provided in database records.

Previously, jRestly bound each controller interface strictly to the immutable `baseUrl` configured on `ModuleInfo` / `JRestlyClient`, requiring static `@Get(path = ...)` annotations. Attempting to call dynamic endpoints forced users to construct new `JRestlyClient` instances or abandon the declarative client in favor of raw `HttpClient`.

## Decision

We introduce first-class, zero-dependency declarative support for dynamic endpoint URLs via the `@TargetUrl` parameter annotation, with 100% eager validation and zero-allocation metadata caching.

### 1. The `@TargetUrl` Annotation

Added in `ru.jrestly.annotation.TargetUrl`:
```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TargetUrl {
}
```

Supported parameter types:
- `java.net.URI`
- `java.net.URL`
- `java.lang.String` (or `java.lang.CharSequence`)

### 2. Ergonomics: Default Empty Path on HTTP Verbs

All HTTP method annotations (`@Get`, `@Post`, `@Put`, `@Patch`, `@Delete`) are updated with `path() default ""`. This allows clean, idiomatic declarative contracts:
```java
public interface S3Client {
    @Get
    String downloadPresigned(@TargetUrl URI presignedUrl);
}
```
When relative subpaths or path variables are used alongside `@TargetUrl`, they seamlessly append to the dynamic base:
```java
public interface TenantClient {
    @Get(path = "/subpath")
    String fetchTenantData(@TargetUrl String tenantBaseUrl);

    @Get(path = "/items/${id}")
    Item getItem(@TargetUrl URI tenantBaseUrl, @PathVariable(name = "id") String id);
}
```

### 3. URL Resolution Rules in Pipeline (`HttpTemplateBuilder.createUrl`)

When a `@TargetUrl` parameter is present on the invoked method:
1. **Null Safety:** If the runtime argument is `null`, an `IllegalArgumentException("@TargetUrl parameter value cannot be null")` is thrown immediately.
2. **Dynamic Endpoint Resolution:**
   - If the method's `path` is empty (`""`), the parameter value is used directly as the request URL.
   - If the method's `path` is non-empty and relative, it is joined with `targetUrl` using slash normalization (avoiding duplicate or missing slashes).
   - If `targetUrl` contains query parameters (e.g. S3 presigned URL `?AWSAccessKeyId=...`) and path is non-empty, the path is cleanly inserted before the query string.
   - Additional `@RequestParam` query parameters declared on the method are appended to the URL query string using RFC-compliant `?` or `&` separator resolution.
3. **Fallback:** If no `@TargetUrl` parameter is declared, standard resolution against `moduleInfo.getEnvironment().getUrl()` remains unchanged.

### 4. Eager Validation Invariants (`ControllerValidator`)

`ControllerValidator` enforces the contract eagerly at proxy creation:
1. **Parameter Type Safety:** A parameter annotated with `@TargetUrl` must be assignable to `java.net.URI`, `java.net.URL`, or `java.lang.CharSequence`/`java.lang.String`. Any other type fails with `@TargetUrl parameter must be of type java.net.URI, java.net.URL, or CharSequence/String`.
2. **Mutual Exclusion:** A `@TargetUrl` parameter must not be annotated with other HTTP parameter annotations (`@RequestParam`, `@PathVariable`, `@RequestHeader`, `@Cookie`, `@RequestBody`, or `@MultipartFormFile`).
3. **Side-Channel Hooks:** Special side-channel parameters (`ResponseHeaders`, `UploadProgressListener`, `DownloadProgressListener`) must not carry `@TargetUrl`.
4. **Arity Constraint:** At most one parameter may carry `@TargetUrl` per method (`multiple parameters annotated with @TargetUrl, at most one is allowed`).

### 5. Hot-Path Zero-Reflection Optimization (`MethodMetadata`)

To preserve the zero-garbage dispatch pipeline (ADR-0051 / ADR-0055):
- Parameter inspection occurs exactly once at proxy initialization and is cached in `MethodMetadata`.
- `targetUrlParamIndex` is stored as an integer offset (or `-1` if absent).
- Runtime lookup in `HttpTemplateBuilder` checks `methodMetadata.hasTargetUrl()` and directly reads `args[methodMetadata.targetUrlParamIndex()]`, requiring zero reflection or stream allocations on hot path dispatch.

## Verification

- **12 Red-First Tests:** Comprehensive test suite in `ru.jrestly.TargetUrlTest` covering eager validation of types, arity, and annotation combinations, plus WireMock end-to-end scenarios (URI, URL, String, subpaths, path variables, query parameters, null safety, and async `CompletableFuture`).
- **Full Regression Gate:** Total project test count increased from **1258** to **1270** (1270/1270 passing, 0 failures, 0 errors).
- **Quality Metrics:** Javadoc gate (`-Werror`) completely clean; JaCoCo instruction coverage maintained at **91.35%**, line coverage at **91.13%**, class coverage at **98.50%** (131/133 classes).
