# Last Session: #175 (2026-10-16)

## Что сделано

**ADR-0059, Ф3 реализована — idle-stall guard; спайк пройден, guard оставлен (keep).
Эпик-кандидат №2 закрыт целиком (Ф1 #161 + Ф2 #163 + Ф3 #175).**

Сессия началась с находки: рабочее дерево пришло с **незакоммиченным WIP**
прерванной сессии (после #163) — `stallTimeout` в `ReconnectPolicy`,
watchdog в `ServerSentEventReconnector`, юниты и спайковые parity-тесты.
WIP принят, проверен, доделан и зафиксирован.

1. **Спайк (разлочивающий close) — ключевой вопрос Ф3.** Закрытие стримингового
   тела из другого потока будит потребителя, стоящего в `read()`, на **всех трёх**
   транспортах:
   - JDK (дефолт): зонд `JdkCloseSpike` → `InputStream.close()` → `IOException: closed`
     за ~41 мс; продевая цепочка `Stream<ServerSentEvent>.close()` →
     `lineStream.close()` → JDK InputStream (см. `ServerSentEventParser.parse`:
     `.onClose(lines::close)`).
   - OkHttp / Apache5: parity-тесты `closingStreamingInputStreamUnblocksBlockedReaderThread`.

2. **Доделано в #175:**
   - **`JdkStreamingCloseUnblockedTest`** (новый, ядро) — JDK-нога спайка как e2e-гейт:
     реальный `JRestlyClient` над raw-TCP сервером отдаёт SSE-событие и залипает;
     блокирующий читатель отпускается из `close()`. Гейт живёт в ядре, т.к. у
     JDK-движка нет субмодуля.
   - **Рефактор watchdog:** `close()` чужого стрима вынесен **из-под монитора**
     (`abortStalled` + `waitForConsumerToUnblock`); таймаут-логика на
     `monitor.wait(ms, ns)` с переоценкой.

3. **Реализация (в коде):**
   - `ReconnectPolicy.stallTimeout(Duration)` — default `null` (выключено),
     положительность валидируется в сеттере и `build()`; `stallTimeout()`,
     `hasStallTimeout()`, участие в `toString()`.
   - `ServerSentEventReconnector` — сторожевой виртуальный поток
     `jrestly-sse-stall-guard` (стартует только при `hasStallTimeout()`);
     `markWaitStart`/`markWaitEnd` обрамляют блокирующий `hasNext()`; при простое
     > таймаута `stalled=true` + закрытие активного стрима → потребитель ловит
     `UncheckedIOException(SocketTimeoutException)` → обычный реконнект Ф1;
     `stalled` сбрасывается на каждой новой стоянке. Скрытых исполнителей нет.
   - Публичный API: `ReconnectPolicy.stallTimeout(...)`.

## Гейты

- **1152/1152** (core 1087 + engine-okhttp 25 + engine-apache5 25 + benchmarks 15),
  `/tmp/opencode/gradle-home ./gradlew test javadoc` (root + оба субмодуля)
  зелёный под `-Werror`, 0 warnings.

## Контекст для следующей сессии (#176)

1. **ADR-0059 полностью завершён** — все фазы (Ф1/Ф2/Ф3) закрыты, эпик-кандидат №2
   исчерпан. Следующий ход — свободный, выбор из активной зоны DRAFT:
   - **Кандидат №1 — test-javadoc** (ADR-0013 Non-goals): лоск javadoc в тестах.
   - **Кандидат №3 — ADR-0058** — завершён; можно перенести ход на другие векторы.
   - Либо новый ADR (next free: **0060**) — например, из «векторов зрелости»
     (mailbox) или из вопроса Антона «какие проблемы не видны».
2. **Возможные Follow-up по Ф3** (не обязательны): `stallTimeout` сейчас
   стартует watchdog на каждый `reconnecting`-стрим с policy; опция «сторожевой
   таймаут на первый байт» и метрика stall-events — кандидаты на будущее.
3. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`,
   ветка `agent-loop`. В дереве на старте не было незакоммиченного WIP (после
   коммита #175 — чисто).
