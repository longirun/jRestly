# jRestly Improvement Plan

## High Priority

- [x] Добавить @Patch и @Put аннотации
- [x] Поддержка PATCH/PUT в HttpTemplateBuilder (getMethod, path, body, contentType)
- [x] Добавить PATCH/PUT тесты (WireMock)

## Medium Priority

- [x] Сделать Mapper конфигурируемым извне (не только через AppProperties)
- [x] Настроить HTTP таймауты (connect/socket) в HttpClient
- [x] Полный рефакторинг конфигурации: SimpleModuleInfo + JRestlyClient builder, удалить AppProperties, убрать commons-configuration2 и commons-beanutils
- [ ] URL-кодирование path variables в HttpTemplateBuilder

## Low Priority

- [ ] Убрать shutdown hook на каждый AbstractHttpClient instance
- [ ] Убрать дублирование createRequestParams() в createUrlEncodedEntity()
- [ ] Убрать хардкод "data/" в multipart

## Done (устаревшие пункты, выполнены в рамках рефакторинга конфигурации)

- [x] ~~Исправить AppProperties.load() — не глотать исключения~~ → AppProperties удалён
