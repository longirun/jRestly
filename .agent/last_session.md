# Last Session: #335 (2026-10-20)

## Что сделано

**Сессия #335: свободный аудит хвоста dispatch `HttpTemplate` (send/sendAsync, logPerHop, scheduleRetry/scheduleAsyncRetry, handleDecompression) — 3 реальные зоны закрыты, ADR-0076, 7 новых тестов, гейт 1,818/1,818, новый рекорд JaCoCo Branch 90.80%.**

1. *Находки:* (a) `logPerHop` — per-send per-hop DEBUG-линия строилась без `isLoggable`-гейта (в отличие от `logRequest`/`logResponse`) — аллокация message-строки на каждый hop каждого вызова при выключенном DEBUG; (b) то же в `scheduleRetry` (sync) и `scheduleAsyncRetry` (async) — строка `"Attempt n/m failed: … — retrying in N ms"`; (c) `handleDecompression` — `strippedHeaders` через `.stream().filter().toList()` (Stream-машина + список), поверх которого compact-конструктор `WireResponse` делал ещё `List.copyOf` — двойная копия.
2. *Фиксы:* (a)+(b) — `isLoggable(DEBUG)`-гейт на всех трёх сайтах (гейт на конструкции, а не на транспорте — тот же паттерн, что в logRequest/logResponse); (c) — plain forward-loop с `ArrayList(size)`: те же strip-семантики байт-в-байт (Content-Encoding/Content-Length case-insensitively, nameless-заголовки сохраняются, порядок сохранён), единственная defensive-копия — compact-конструктор `WireResponse`.
3. *TDD:* 7 новых тестов. 2 red-first gate-skip (logPerHop-skip; retry-schedule-skip через полный `exchange()` 503→200 zero-backoff — оба краснели до фиксов). 5 behavior-пинов: logPerHop-emit, retry-schedule emit sync + async, decompression (nameless-keep + order; immutability stripped-листа).
4. *Гейты:* целевые сьюты 31/31 (HttpTemplateLoggingTest 13 + DecompressionConfigTest 18); полный **1,818/1,818** green во всех 8 модулях (root **1,656** = 1,649 + 7). **JaCoCo пересчитан по свежему отчёту:** BRANCH **4,058/4,469 = 90.80% — новый рекорд** (все 3 новые gate-ветки покрыты с обеих сторон: sync-skip/emit — новыми тестами, async-skip — существующими `NO_OP_LOGGER`-тестами `HttpTemplateStreamWireTest`, async-emit — новым). INSTRUCTION 98.13%, LINE 97.77%, COMPLEXITY 89.06%, CLASS/METHOD 100%.
5. *Память:* ADR-0076 (`docs/0.12.0/ADR-0076-dispatch-tail-log-gates-and-decompression-strip-loop.md`, Accepted) — гейт на конструкции, plain-loop с единственной копией, «чистые зоны» записаны против ре-аудита. ROADMAP-баннер: Highest ADR **0076**, next free **0077**, гейт 1,818/1,818 (root 1,656), JaCoCo пересчитан; тег ADR-0076 в строке цикла 0.12.0. Побочный фикс: off-by-one в старом баннере (root «1,648» → 1,649: 1,647+2 и 1,811−162).

## Контекст для следующей сессии (#336)

1. **Ветка** `agent-loop`, HEAD — коммит #335.
2. **Highest ADR:** **0076**, next free **0077**.
3. **Пул 0.12.0:** активных эпиков нет. Dispatch-хвост (send/retry/decompression/per-hop-логирование) считается закрытым. Следующий свободный срез — **response-decode зона**: `processResponse`/`decode`, `ResponseBody` (streaming-линии 671–707 уже имеют `isLoggable(INFO)`-гейты — проверить конструкцию payload-line и другие аллокации), `RetryAfter`, `MultipartBody`. Либо — новый эпик цикла 0.13.0, если появится осмысленный кандидат (осмысление/ретроспектива легитимны).
4. *Чистые зоны (не ре-аудитовать — факты по коду, зафиксированы в ADR-0076 §1):* lazy `engineOptions` в `send`/`sendAsync` (builder всегда проставляет), `MethodMetadata`-кэши (`CACHE` ConcurrentHashMap, `placeholderToken`, прецисленный `staticCookieHeader`), cookie hot-path (#334), header-merge/trace/response-map (ADR-0075), `MetricsNotifier` (silent на null-listener).
5. *Замечания по окружению:*
   - Команды gradle: `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
   - `bc` в песочнице нет — суммы считать awk'ом по `build/test-results/test/*.xml` + `*/build/test-results/test/*.xml` (два glob'а — корень и модули отдельно).
   - JaCoCo-отчёт корня: `build/reports/jacoco/test/jacocoTestReport.xml`; root-суммы — в последних `<counter>` элементах.
   - **Правило дня:** цифры «рекордов» пересчитывать по свежему отчёту, а не переносить (грабли #334).
   - *Грабля #335 (test-сторона):* отрицательные log-тесты — `LevelGatedLogger` с maxLevel `TRACE`, не `WARNING`: WARNING пропускает INFO-записи, и 0-count ассерт падает.
   - Hygiene mailbox: разобранные обмены удалять, держать последние 2–3 сообщения.
6. Метрики: гейт **1,818/1,818** (root 1,656 + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + codec-yaml 15 + codec-protobuf 19 + benchmarks 37). JaCoCo — рекорд 90.80% Branch (4,058/4,469), 100% Class/Method.
