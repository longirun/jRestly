# jRestly Improvement Plan

## High Priority

- [x] Добавить @Patch и @Put аннотации
- [x] Поддержка PATCH/PUT в HttpTemplateBuilder (getMethod, path, body, contentType)
- [x] Добавить PATCH/PUT тесты (WireMock)

## Medium Priority

- [x] Сделать Mapper конфигурируемым извне (не только через AppProperties)
- [x] Настроить HTTP таймауты (connect/socket) в HttpClient
- [x] Полный рефакторинг конфигурации: SimpleModuleInfo + JRestlyClient builder, удалить AppProperties, убрать commons-configuration2 и commons-beanutils
- [x] `HandledException` — добавить `getStatusCode()` и `getResponseHeaders()` (сейчас доступен только `getDetails()` — десериализованное тело, без статуса и заголовков)
- [ ] URL-кодирование path variables в HttpTemplateBuilder
- [ ] Опциональный `Response<T>` wrapper или аннотация `@ExpectStatus` для проверки success-status (201 на POST, 204 на DELETE) — сейчас success-status недоступен, только body
- [ ] `JRestlyClient.updateAuthHeader(name, value)` — семантичный способ обновить auth-токен после `login()`, вместо связки `authProvider.setHeaderName(...)` + `setHeaders(List.of(Pair.of(...)))`
- [ ] `UrlEncodedFormEntity` в `createUrlEncodedEntity()` создаётся без указания charset → ISO-8859-1 по умолчанию. Non-ASCII credentials ломаются. Нужно `new UrlEncodedFormEntity(params, StandardCharsets.UTF_8)`

## Low Priority

- [ ] Убрать shutdown hook на каждый AbstractHttpClient instance
- [ ] Убрать дублирование createRequestParams() в createUrlEncodedEntity()
- [ ] Убрать хардкод "data/" в multipart

## Done (устаревшие пункты, выполнены в рамках рефакторинга конфигурации)

- [x] ~~Исправить AppProperties.load() — не глотать исключения~~ → AppProperties удалён
