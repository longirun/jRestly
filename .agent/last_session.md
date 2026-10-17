# Last Session: #257 (2026-10-18)

## Что сделано

**Сессия #257: free-аудит `HttpTemplate` (кластер problem-detail/`@OnError`/
decode-probe) — 35-я «тихая» находка: UTF-8 BOM (U+FEFF) в начале Problem Detail
тела молча ронял структурированную ошибку на обоих путях (pre-check
`resolveProblemDetail` + `ProblemDetail.fromJson`); red-first (4 красных) и
минимальный фикс в двух узких местах (по одной строке); ADR-0064 дополнен
`## Amendment #257`; гейт 1448/1448, BRANCH 83.45%, INSTRUCTION 95.88%,
LINE 95.69% — три новых исторических рекорда.**

1. *Цель:* продолжение free-аудита `HttpTemplate` (класс №1 по missed branches,
   201); сужение на кластер problem-detail: `probePayloadDecode` (20 missed),
   `handleOnError` (15), `isLikelyProblemDetail` (11), `resolveProblemDetail`
   (10), `isProblemJsonContentType` (5).
2. *Находка:* RFC 8259 §8.1 разрешает опциональный BOM-префикс JSON-текста, но
   `String.trim()` не режет U+FEFF (не whitespace). Тело с BOM умирало в двух
   местах: pre-check `resolveProblemDetail` (`trim().startsWith("{")` — тело
   отбрасывалось до любого codec'а → unexpected-status исключения без
   `ProblemDetail`) и `ProblemDetail.fromJson` (та же shape-проверка). Оба
   call-site в `catch (Throwable ignored)` → провал тихий. На zero-dep
   classpath (NoOp codec) `@OnError`-маппинг падал на голый
   `ProblemDetail.forStatus(statusCode)` — title/detail/type/instance/properties
   терялись. Jackson-classpath `@OnError`-путь прощает (Jackson сам скачет BOM).
3. *TDD red-first:* 4 красных + 2 guard — юнит
   `ProblemDetailTest.fromJsonToleratesLeadingByteOrderMark` (BOM, BOM+пробелы,
   BOM-only → IAE, двойной BOM → IAE: «ровно один» по формулировке RFC);
   `ProblemDetailWireMockTest` (unexpected status — красный; `@OnError` — guard);
   `ZeroDepStandaloneWireMockTest` ×2 (NoOp codec — от fallback'а через fromJson
   никуда не деться, обе ноги красные).
4. *Fix:* `ProblemDetail.fromJson` — после `trim()` снять один лидирующий U+FEFF
   + пере-`trim()`; `HttpTemplate.resolveProblemDetail` — идентичная
   нормализация до pre-check. `handleOnError` не тронут (fromJson-fallback
   покрыт, codec-путь BOM терпел всегда).
5. *Верификация:* `./gradlew test javadoc jacocoTestReport` — **1448/1448**
   (core 1368 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 +
   benchmarks 15), 0 failures/errors, javadoc чист под `-Werror`.

## Контекст для следующей сессии (#258)

1. **Ветка** `agent-loop`; HEAD — коммит этой сессии (#257).
2. **Highest ADR:** **0065**, next free **0066** (поправки идут в ADR-0064).
3. **Состояние проекта:** абсолютные рекорды качества: **100.00% Class (143/143)**,
   **100.00% Method (1418/1418)**, **95.88% Instruction**, **95.69% Line**,
   **83.45% Branch**, **80.88% Complexity**. 1448/1448 тестов зелёные across all
   5 modules. Javadoc чист под `-Werror`.
4. **Варианты для следующего шага:**
   - Остаток `HttpTemplate`: `exchange`/`exchangeAsync` (~16 missed branches),
     хвосты `processResponse` (201 missed всего).
   - Дальше по рангу: `HttpTemplateBuilder` (161 missed), `MethodMetadata` (69),
     `ControllerValidator` (50).
   - Формирование цикла 0.11.0: концепт `ADR-0066` (например, декларативная
     эргономика SSE/NDJSON streaming API, LLM stream consumption).
5. **Грабли/паттерны этой сессии (Java/JSON):**
   - `String.trim()` не режет U+FEFF (ZERO WIDTH NO-BREAK SPACE — format-символ,
     не whitespace); `strip()` — тоже;
   - после снятия BOM нужен второй `trim()` — за BOM может идти пробельный
     хвост шаблона (`"  \uFEFF  {"`);
   - `isBlank()` также не считает U+FEFF пробелом — тело из одного BOM
     проходит blank-check и умирает на shape-check (IAE, не NPE — trim
     гарантирует непустоту до `charAt(0)`);
   - Jackson/Gson/Moshi сами скачивают UTF-8 BOM — BOM-баг виден только на
     zero-dep (NoOp) classpath для `@OnError` и всегда для unexpected-status
     (pre-check режет до codec'а);
   - indent в ADR-файлах — 5 пробелов в списках (не 6); при неточном совпадении
     читать через `cat -A` / `od -c`.
6. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   `:test --tests 'X'` (bare `--tests` падает на `:benchmarks:test`, паттерн #188).
   Счётчик сессий: 257. Часовой пояс контейнера +04.
