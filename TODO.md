# jRestly Improvement Plan

## High Priority

- [x] Add @Patch and @Put annotations
- [x] PATCH/PUT support in HttpTemplateBuilder (getMethod, path, body, contentType)
- [x] Add PATCH/PUT tests (WireMock)

## Medium Priority

- [x] Make Mapper configurable externally (not only via AppProperties)
- [x] Configure HTTP timeouts (connect/socket) in HttpClient
- [x] Full configuration refactoring: SimpleModuleInfo + JRestlyClient builder, remove AppProperties, drop commons-configuration2 and commons-beanutils
- [x] `HandledException` — add `getStatusCode()` and `getResponseHeaders()` (currently only `getDetails()` is available — deserialized body, without status and headers)
- [x] URL-encode path variables in HttpTemplateBuilder
- [x] `@ExpectStatus(statuses = {…})` for success-status validation: strict validation of response status code. By default, any non-2xx status (not listed in `@OnError`) throws `UnexpectedStatusException` with the raw body. Decided NOT to implement `Response<T>` wrapper — jRestly API stays transparent (return type = user's type)
- [x] `JRestlyClient.updateAuthHeader(name, value)` — a semantic way to update the auth token after `login()`, instead of `authProvider.setHeaderName(...)` + `setHeaders(List.of(Pair.of(...)))`
- [x] `UrlEncodedFormEntity` in `createUrlEncodedEntity()` is created without specifying a charset → ISO-8859-1 by default. Non-ASCII credentials break. Should be `new UrlEncodedFormEntity(params, StandardCharsets.UTF_8)`
- [x] Auth contract cleanup: `AuthProvider.captureAuthDetails(headerName, responseHeaders)` default method (failed capture = no-op, keeps the active token); builder passes the header name via closure, no build-time provider mutation; `setHeaderName()`/`setHeaders()` removed from the interface; `AuthHeaderProvider` becomes the single concrete provider (initial + dynamic slot, last write wins), `SimpleModuleInfo.SimpleAuthProvider` removed; send side widened: `getAuthHeader()` → `getAuthHeaders()` returning `List` (empty = anonymous, custom providers may return multiple headers, e.g. cookie jars); capture stays hooked to `@SetAuthDetails` methods only
- [ ] Eager controller validation on `get()`: `ControllerValidator` aggregates all method errors of the interface; `validateControllers(boolean)` builder flag, default `true` — throw one `ControllerValidationException` with the full problem list, `false` — WARN log and keep per-method failure on call
- [ ] RequestMeta dispatch in `HttpTemplateBuilder`: resolve annotation metadata (method/path/requestType/defaultParams) once per `Method` instead of six Post/Put/Patch/Delete ladders; fixes broken form-urlencoded/multipart `@Delete` support; subsumes "Remove createRequestParams() duplication in createUrlEncodedEntity()"

## Low Priority

- [x] Remove per-instance shutdown hook in AbstractHttpClient (lifecycle is caller-owned; class now implements AutoCloseable)
- [ ] Remove hardcoded "data/" in multipart

## Done (stale items, resolved during configuration refactoring)

- [x] ~~Fix AppProperties.load() — do not swallow exceptions~~ → AppProperties removed
