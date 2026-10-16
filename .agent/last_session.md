# Last Session: #155 (2026-10-16)

## Что сделано

**Восстановление после сессий #150–#154 + фикс дедлока `PublisherInputStream` (ADR-0058, фаза 2a закоммичена как WIP).**

1. В рабочем дереве найдены незакоммиченные следы сессий #150–#154: субмодуль
   `engine-okhttp` целиком (OkHttpEngine 1070 строк, OkHttpEngineProvider с
   `priority()=50`, `META-INF/services`, `build.gradle` с maven-publish +
   javadoc-гейтом, 2 тест-класса = 21 тест), миграция `benchmarks` на
   `project(':engine-okhttp')` (прототип OkHttpEngine удалён, Apache5-прототип
   пока на месте), `settings.gradle`, пометка в ADR-0058. Память эти сессии
   не обновляли (коммита не было).
2. **Причина смертей сессий**: `./gradlew test` вечно зависал на
   `OkHttpEngineParityTest.streamingBodyPublisherIsDrained` (в прошлом прогоне
   488 с → kill по лимиту сессии; поэтому #150–#154 не доходили до коммита).
3. **Диагноз (jstack)**: `PublisherInputStream.nextChunk()` — маркер
   `END_OF_STREAM` одноразовый: буферный `read(8192)` добирает остаток
   последнего чанка, попутно съедает END из очереди и возвращает данные;
   следующий `read` вечно поллит пустую очередь. Второй латентный баг там же:
   `FailureHolder` обрабатывался `continue` без повторной проверки `failure` —
   тоже вечное ожидание при ошибке публикатора.
4. **Фикс**: липкий `volatile boolean eof` (END/ошибка/закрытие → EOF навсегда),
   ошибка — немедленный `throw` через `toIOException`; `eof` проверяется в
   начале `nextChunk()`.
5. Ревью кода движка сделано (timeout/stream/progress/trailers/close/ModuleInfo —
   всё на месте), warning-гейты в build.gradle субмодуля (−Werror, javadoc
   Werror, как у ядра).

## Гейты (честный статус)

- `:engine-okhttp:test`: **17/21 зелёных, 4 падения** (хэнг устранён — прогон
  11 с вместо вечного). Коммит WIP-осознанный: главное — не терять работу снова.
- Ядро не менялось: 1048 зелёных на #149; полный `./gradlew test` — за #156.

## 4 падения (первый приоритет #156)

1. `Zero-length download fires exactly one (0,0) event` — событий 0 вместо 1.
2. `Download progress ... Content-Length bodies` — `total == -1` вместо 100000.
3. `Streaming download progress ends at (total,total)` — `total == -1` вместо 30000.
4. `Multipart bodies are rendered per RFC 7578` — «text part missing» (другие
   ассерты multipart-теста прошли).

**Ведущая гипотеза для 1–3**: OkHttp прозрачно добавляет `Accept-Encoding: gzip`
и после распаковки `body.contentLength() == -1`; в ядре декомпрессия — своя
(ADR-0021, `ContentCompressor` SPI), т.е. это ещё и риск **двойной распаковки**.
Фикс: выключить прозрачный gzip в OkHttpEngine (паритет с контрактом ядра) —
например, интерцептором, фиксирующим `Accept-Encoding` так, как его ставит
декларативный слой; проверить `toBufferedWireResponse`/`toStreamingWireResponse`
на `contentLength()` после фикса. Пункт 4 — проверить сырое тело на WireMock
(`findAll(...)`, hexdump) после фикса gzip: возможно, общий корень.

## Контекст для следующей сессии (#156)

### Порядок работ
1. Починить 4 падения выше (TDD: они и есть красные тесты).
2. `./gradlew test` целиком (ядро + engine-okhttp + benchmarks) — гейт зелёный.
3. Дальше по ADR-0058: `engine-apache5` (build.gradle с `api httpclient5:5.4.1`,
   перенос `benchmarks/.../engine/Apache5HttpEngine`, провайдер 50,
   ServiceLoader, контракт паритета; `sendAsync` — не на общем ForkJoinPool),
   затем `benchmarks` на `project(':engine-apache5')`.
4. Потом — публичная бенчмарк-документация (методология raw-TCP + TCP_NODELAY,
   таблица #147, ограничения variance); опционально JMH `-f 2 -i 5`.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
   **Всегда запускать тесты с внешним `timeout` (например `timeout 420`)** —
   зависший тест маскирует остальные результаты (грабли #155).
2. Ветка `agent-loop`. Версия ядра/субмодуля: **0.9.0**. Next free ADR: **0059**.
3. Часы контейнера — UTC; таймстампы mailbox — локальное время (≈ UTC+9:30).
4. `engine-okhttp` использует okhttp3.internal.tls.OkHostnameVerifier (внутренний
   API OkHttp) — под наблюдением при апгрейде транспорта.
