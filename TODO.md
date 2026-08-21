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
- [ ] `UrlEncodedFormEntity` in `createUrlEncodedEntity()` is created without specifying a charset → ISO-8859-1 by default. Non-ASCII credentials break. Should be `new UrlEncodedFormEntity(params, StandardCharsets.UTF_8)`

## Low Priority

- [ ] Remove per-instance shutdown hook in AbstractHttpClient
- [ ] Remove createRequestParams() duplication in createUrlEncodedEntity()
- [ ] Remove hardcoded "data/" in multipart

## Done (stale items, resolved during configuration refactoring)

- [x] ~~Fix AppProperties.load() — do not swallow exceptions~~ → AppProperties removed
