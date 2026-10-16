# Last Session: #156 (2026-10-16)

## Что сделано

**ADR-0058, фаза 2a закрыта: `:engine-okhttp` полностью зелёный (23/23).**

1. Разобраны 4 WIP-падения из #155. Диагностика зондом (временный
   `ProbeTest` с печатью сырых заголовков/тел через WireMock), корней два:
   - **Прогресс (`total == -1`, 0 событий):** OkHttp `BridgeInterceptor`
     без `Accept-Encoding` инжектит `Accept-Encoding: gzip` и **прозрачно
     распаковывает** ответ (снимает `Content-Encoding`, теряет
     `Content-Length`). Ломало `total` (ADR-0033) и
     `isDecompressionEnabled()==false` (ADR-0021).
   - **Multipart — баг теста:** канонический рендер text-части содержит
     `Content-Type: text/plain; charset=UTF-8` (сверено с
     `MultipartTest.expectedLegacyBody`), ассерт ждал его отсутствия.
   - **Стенд-находка:** WireMock 3.12 (Jetty) без явного `Content-Length`
     в стабе ВСЕГДА отвечает `Transfer-Encoding: chunked` (проверено curl
     через wiremock-standalone). Core-тесты прогресса декларируют CL явно.
2. **Фикс движка — пассивный транспорт:** `NoTransparentGzipInterceptor`
   (application: при отсутствии AE подставляет `gzip` + tag-маркер
   `SuppressedAcceptEncoding`) + `StripSuppressedAcceptEncodingInterceptor`
   (network: снимает подставленный заголовок перед проводом, value-check
   `"gzip"` щадит подмену другим интерцептором). Канонический конструктор
   оборачивает ЛЮБОЙ клиент (включая внешний) через `newBuilder()`;
   `getOkHttpClient()` возвращает обёртку (pool/dispatcher общие).
3. Тесты: +2 (`noAcceptEncodingInjectedByTransport` — red-first,
   `explicitAcceptEncodingIsPassedThroughCompressed` — предохранитель),
   3 стаба прогресса получили явный `Content-Length`, multipart-ассерт
   исправлен к канону. Итого engine-okhttp: **23 теста** (17+2+4).
4. ADR-0058 дополнен секцией **Amendment (сессия #156)**; javadoc класса
   движка и тест-класса обновлены (пассивность транспорта, framing-заметка
   WireMock).

## Гейты (честный статус)

- Полный `./gradlew test`: **1086/1086** (core 1048 + engine-okhttp 23 +
  benchmarks 15), 0 failures, ~18 с. Хэнг #155 не вернулся.
- 0 warnings (`-Werror`), ядро не менялось, zero-dep POM.

## Контекст для следующей сессии (#157)

### Порядок работ
1. **Фаза 2b: `engine-apache5`** по ADR-0058 (сессия #155 подготовила обзор):
   - `build.gradle` с `api httpclient5:5.4.1`, maven-publish + javadoc-гейт
     (копия `engine-okhttp/build.gradle`), ServiceLoader-регистрация,
     провайдер `priority()=50`.
   - Перенос прототипа `benchmarks/.../engine/Apache5HttpEngine` (ещё на
     месте!) + контракт паритета (timeout/version/stream/progress/trailers/
     close/ModuleInfo). `sendAsync` — **не** на общем ForkJoinPool
     (в прототипе `supplyAsync` без executor'а — истощение).
   - **Учесть урок #156:** Apache5 тоже умеет прозрачно декодировать
     (`Content-Encoding` handling в HttpClient5) — проверить пассивность
     транспорта тем же зондом (прогресс/`total`, `Content-Encoding` сквозь
     движок); при необходимости отключить автоматическую декомпрессию
     (`disableContentCompression()`), а не полагаться на дефолт.
   - WireMock-паритет по образцу `OkHttpEngineParityTest` (стабы прогресса —
     с явным `Content-Length`!).
2. `benchmarks` на `project(':engine-apache5')`, удалить Apache5-прототип.
3. Полный `./gradlew test` + опционально публичные JMH `-f 2 -i 5`.
4. Публичная бенчмарк-документация (методология raw-TCP + TCP_NODELAY,
   таблица #147, ограничения variance).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
   **Всегда запускать тесты с внешним `timeout`** (грабли #155).
2. Ветка `agent-loop`. Версия ядра/субмодуля: **0.9.0**. Next free ADR: **0059**.
3. Часы контейнера — UTC; таймстампы mailbox — локальное время (≈ UTC+9:30).
4. `engine-okhttp` использует okhttp3.internal.tls.OkHostnameVerifier
   (внутренний API OkHttp) — под наблюдением при апгрейде транспорта.
5. WireMock 3.12 без явного `Content-Length` в стабе отвечает chunked —
   для assertions по `total`/CL всегда декларировать длину.

### Техники, которые сработали
- **Зонд вместо догадки:** временный JUnit-тест с печатью сырых
  заголовков/тел (или `java -jar wiremock-standalone` + curl) — за один
  прогон разделил «движок/стенд/тест» и убил неверную гипотезу одного корня.
- Red-first: новый тест на пассивность транспорта краснел до фикса
  (`containsHeader("Accept-Encoding") == true`).
