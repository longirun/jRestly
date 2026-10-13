# DRAFT: Payload-decode retry — reopen the last substantive known gap (ADR-0011)

> Эпик открыт в сессии #31 (2026-10-13). Вопрос направления из mailbox (#26,
> 08:20) Антон снял 11:20: предпочтения нет — «исходить из
> стратегического планирования — что выгоднее для долгосрочного развития».
> Стратегический разбор (payload-decode = последняя содержательная фича
> known-gaps-бэклога; 0.7.0-цикл = упаковка/версионирование, а не фича;
> сделать фичу первой ⇒ 0.7.0-цикл упаковывает **полный** retry-контракт
> transport + status + decode без остаточных содержательных gap'ов) зафиксирован
> в **ADR-0011** (`docs/0.7.0/`) — концепт, шаг 1 эпика. Правило ADR-0003
> «reopening known gap = новый ADR» выполнено: ADR-0011 supersedes «LEAVE» из
> ADR-0009 кандидат 4. Норма объёма прежняя: один шаг за сессию.

## Эпик: payload-decode retry (ADR-0011)

- [x] **Шаг 1 — концепт ADR-0011** (сессия #31): reopen gap'а, три failure-
      kind (transport/status/decode), «classified exactly once» сохранён
      side-effect-free decode-probe (без auth/log/exception), финальное
      исключение = codec-своё unchecked (без нового класса), отдельный
      opt-in `retryDecodeFailures` (default off — success-path-двойной-декод
      не подмешивать в общее enable), транспорт-освещение ADR-0009-аргумента 1
      (complete-but-unbindable vs incomplete body), 5-шаговый план.
- [x] **Шаг 2 — `RetryPolicy.retryDecodeFailures` + `@Retry`-атрибут +
      шов `probePayloadDecode` (package-private) + юниты** (сессия #32):
      флаг — отдельный opt-in (default off) в `RetryPolicy` (builder +
      getter + Javadoc-буллет) и `@Retry` (атрибут + Javadoc: success-path,
      double-decode-cost, `@OnError` вне scope, финальное исключение =
      codec-своё); probe — чистый предикат decode-ветки `processResponse`,
      те же предикаты/поля (`onErrorParser`/`isExpectedStatus`/
      `returnType`), scope вылезает из формы (без special-case),
      codec-exception — pass-through без обёртки (assertSame-пин);
      `HttpTemplateDecodeProbeTest` (7 юнитов, stub-кодек, template без
      logger/auth — side-effect-free зафиксировано формой фикстуры) +
      `RetryPolicyTest` +1 (default off).
- [x] **Шаг 3 — sync-wiring** (`executeWithRetries`: decode-ось в final-
      условии + decode-DEBUG-строка) + интеграции ADR-0011 Aspect 7 сценарии
      1–5 (200-мусор→200-норма, exhaustion-typing, default-off = zero-change,
      `@OnError` вне scope, «classified exactly once» — auth-capture один раз)
      (сессия #33): decode-ось — `!last && isRetryDecodeFailures()` → probe;
      codec-исключение → DEBUG `decode (<тип>)` + `continue` (строка
      `scheduleRetry` принимает failure-тип по ADR Aspect 3); `.retryDecode-
      Failures(...)` в `resolveRetryPolicy()` (атрибут перестал быть dead
      code); `RetryDecodeTest` (5 интеграций) + `TestAuthProvider`-счётчик
      capture (replace-семантика скрывала двойной вызов — «exactly once»
      проверяется только счётчиком) + метод `@Retry(retryDecodeFailures)` +
      `@SetAuthDetails` в `RetryController`; finding: сценарий 4 —
      malformed error-body бросает codec-исключение в `@OnError`-ветке
      `processResponse` ДО `HandledException` (первая строка ADR-0011 Known
      gaps — закреплена интеграцией: 1 запрос + `UncheckedIOException`);
      250→255/255.
- [ ] **Шаг 4 — async-tвин** (`attemptAsync`: decode-ось через
      `delayedExecutor`) + интеграция 6.
- [ ] **Шаг 5 — Javadoc-контракт** (`RetryPolicy`/`@Retry`/`ModuleInfo`,
      sync/async uniformity) + README «Retry with backoff» (decode-параграф)
      + **финальный gate + дистилляция в ROADMAP** (ADR-0008 Known gaps
      payload-decode-строка зачёркнута, ADR-0009 кандидат 4 аннотирован
      «reopened by ADR-0011»).

## Кандидаты known gaps (триаж — остальное, не эпик)

- **`Retry-After` asctime** (micro-gap) — ~~обе obsolete-формы~~ **закрыто
      (сессия #28, ADR-0010):** `RFC_1123_DATE_TIME` парсит только IMF-fixdate;
      RFC 9110 §5.6.7 требует приёмщика принимать все три формата, обе устаревшие
      формы (RFC 850 + asctime(3)) теперь парсятся.
- **Остаток Known gaps ADR-0008** (принятые, non-blocking, скорее доки) —
      `InterruptedException` never retried, no circuit breaker / per-status caps /
      metrics (non-goals ADR-0003 — держим как non-goals), `Retry-After` только
      для status-retries, backoff-шов package-private. **Доки-аудит (сессия #29):
      свёрстано с реальностью, единообразно** — всё задокументировано
      (`RetryPolicy`/`@Retry` Javadoc + README; указатели в ADR-0008).
- **Контур следующего цикла** — `docs/0.7.0/` несёт ADR-0007/0008/0009/0010
      (**+ ADR-0011**); в 0.6.2 без тега накопились пять unreleased-наборов
      (CompletableFuture, ISO option A, Gson/Moshi, Retry with backoff, 0.x
      polish) — **плюс этот эпик**. 0.7.0-цикл/релиз — после payload-decode
      (стратегическое решение ADR-0011: фича первой, цикл упаковывает полный
      retry-контракт).

## Постоянная нить (не эпик, не чекбокс)

- **Гигиена проекта — за агентом** (Антон, 2026-10-13: «сам следи тут как
      лучше — в этом файле и вообще на проекте»). Практика: mailbox не растёт
      бесконечно (применять правило «решённый обмен удаляем» в каждой сессии,
      где он применим); устаревшие «Пометки из mailbox» в DRAFT сверять с
      реальностью; мелкие расхождения (версии, статусы) чинить сразу, не копить;
      ROADMAP — верифицируемые утверждения (счётчики тестов, версии, хэши,
      ссылки) сверять с реальностью: счётчик чинят in-place, когда секция сама
      реферит post-gate работу (прецедент сессии #30: 0.x-polish-секция 8→11
      units и 239→242, Retry-секция RetryTest 10→11), а gate-снимок с
      задокументированной дельтой («+1», «+5») — оставляют.

Пометки из mailbox (актуальные на 2026-10-13):
- **Вопрос направления (#26) — Антон ответил 11:20:** предпочтения нет,
  исходить из стратегического планирования (что выгоднее долгосрочно). Решение
  (payload-decode первой, 0.7.0-цикл после) зафиксировано в ADR-0011 + это
  DRAFT.
- **Версии — по нашему усмотрению** (сейчас 0.6.2), **теги не вешать** (теги =
  релизные сборки, которые не должны уйти в релизы/спутаться с главным
  релизом; работаем в `agent-loop`, наружу не мержим).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг, делать чаще.
- **ADR-0006** (java.time wire contract) не требуется.
- **Чистку mailbox Антон подтвердил** (2026-10-13) — правило выше в силе.

Правила работы: один эпик в DRAFT.md, шаги — чекбоксы `[ ]`; закрыл →
дистиллируй в ROADMAP.md и зачисти это место. (Пауза-триаж — легитимное
состояние DRAFT: эпик не изобретаем, пока мейнтейнер не выбрал направление или
триаж сам не покажет очевидного кандидата — тут направление выбрано:
стратегический разбор ADR-0011 → payload-decode.)
