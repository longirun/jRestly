# Handover Note: Сессия 29 (доки-аудит остатка Known gaps ADR-0008; код не тронут, 242/242, DRAFT — пауза)

## Что сделано в этой сессии
- **Выбор шага.** Mailbox тиш после #28 (~08:50) — вопрос направления
  (payload-decode vs 0.7.0-цикл) без ответа. Fallback-кандидат №1 из
  last_session #28: доки-аудит остатка Known gaps ADR-0008 (не
  payload-decode — не reopening самовольно; эпик не изобретаем).
- **Аудит (по коду и докам, не по формулировкам).** Из семи пунктов
  Known gaps ADR-0008: два закрыты (#25 — HTTP-date, #28 — обе
  obsolete-формы). Из пяти оставшихся: (1) `InterruptedException` never
  retried — ADR пишет «(documented in Javadoc)» — факт только в Javadoc
  `RetryPolicy` (строка 40); в `@Retry` (самодостаточная поверхность —
  пользователь, не знающий про policy-класс, читает только её) и в
  README — нет; (2) backoff-шов package-private — в Javadoc самого шва
  `nextDelayMillis` стоит «the seam is package-private on purpose» —
  ОК; (3) `Retry-After` только для status-retries — везде лишь
  подразумеваемо («on a failed response»), явно — нигде; (4) no circuit
  breaker / per-status caps / metrics — non-goals ADR-0003, стоят в
  ADR — менять нечего. Proxy-builders без Javadoc — house style
  (контракт на value-классе/аннотации — не дописывать).
- **Фиксы (только доки, код не тронут).** Javadoc `@Retry`: заметка про
  `InterruptedException` (зеркало формулировки `RetryPolicy`,
  «whatever transport says») + явная оговорка у Retry-After («a
  transport failure has no response, so the header can only come from a
  status-retried attempt»); структура параграфов приведена к форме
  `RetryPolicy` (после `</ul>` — новый `<p>`). README «Retry with
  backoff»: IE-предложение в абзаце «What gets retried»;
  Retry-After-абзац — «on a failed response» + «(status retries only —
  a transport failure has no response headers)». ADR-0008 Known gaps:
  два bullet'а теперь указывают, где именно задокументировано
  (`RetryPolicy` + `@Retry` + README; `@Retry` + README) — старая
  формулировка «documented in Javadoc» стала точной.
- **Гейты:** `./gradlew test --rerun` — **242/242** (0 failures/0
  errors по XML, тёплый кэш); POM `pom-default.xml` — **0
  `<dependency>`**, 0.6.2.
- **Память.** Mailbox #29 (аудит + вопрос #26 жив; без удаления —
  «последние 2–3» не превышено: #26, #28, #29). DRAFT: пометка аудита в
  «Остаток Known gaps»-кандидате. Дневник #29 + «Итог дня» обновлён
  (день не закрыт — секция живая).

## Контекст для следующей сессии (#30)

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (версия 0.6.2).
3. **Гигиена проекта — за агентом.**
4. **Вопрос направления (#26, 08:20) без ответа ~2 часа на 09:59:**
   (a) payload-decode retry → концепт **ADR-0011** (ADR-0010 занят),
   (b) 0.7.0-цикл/релиз, (c) иное.

### Что логично делать дальше
1. **Если Антон ответил в mailbox** — по ответу: (a) «payload-decode» →
   концепт **ADR-0011** (`docs/0.7.0/`): failure-kind в retry-цикле vs
   «classified exactly once» ADR-0008, типизация финального исключения,
   транспорт-покрытие transient-сбоев, три аргумента ADR-0009 за
   «оставить» переубедить или принять; DRAFT становится эпиком с
   чекбоксами. (b) «0.7.0-цикл» → релизный эпик: release notes, версия,
   теги — по его решению. (c) иное — по тексту.
2. **Если ответа нет** — payload-decode по-прежнему не открывать
   самовольно (reaffirmed в ADR-0009). Оставшиеся small steps без
   решения мейнтейнера: (1) рефакторинг/warnings; (2) гигиена-аудит
   ROADMAP (разделы, зачёркнутые кандидаты, устаревшие формулировки —
   сверить с реальностью, как сделано с Known gaps ADR-0008 в #29);
   (3) мелкие расхождения в README/DRAFT, если найдутся. Любой — один
   маленький.
3. **Постоянная нить DRAFT** (гигиена) — как и раньше: mailbox не
   растёт, «Пометки из mailbox» в DRAFT сверять с реальностью.

### Грабли прошлых сессий (актуальный набор)
- **ADR-концепт vs реальность (серия из 9 раз):** #28 — девятый, первый
  в SCOPE-описании gap'а. #29 — тот же принцип для ДОК: «documented in
  Javadoc» из Known gaps ADR-0008 было проверяемым утверждением, и оно
  не выдержало (факт только для `RetryPolicy`, не для `@Retry`/README).
  Аудит доков = та же дисциплина «сверить на шаге, поправить обратно».
- **`RFC_1123_DATE_TIME` — не «все RFC-даты»:** парсит только
  IMF-fixdate. Имя-ловушка.
- **java.time 2-значный год = текущий век** (94 → 2094), без pivot'а;
  распарсенный `EEEE`-weekday — валидируемое поле (DayOfWeek-конфликт).
  Pivot 80–99 — после парса.
- **Wall-clock-пины — с запасом:** дата = now + 3 s, assert elapsed ≥ 2 s
  (интеграция); unit-диапазоны будущих дат — [58_000, 60_000] (wire-форма
  без дробных секунд теряет до секунды).
- **RFC_1123/кастомные formatters в тестах — дату строим в
  ZoneOffset.UTC**, `Locale.ENGLISH` явно (контейнер +0400).
- **NPE ≠ NFE:** при переписывании парсеров смотреть набор catch'ей.
- **`System.Logger` на Temurin 21 — ИНТЕРФЕЙС**: capture-логгер —
  `implements` (абстрактные: `getName()`, `isLoggable`, оба `log(Level,
  ResourceBundle, ...)` перегрузки); parameterless вызов приходит с
  `params == null`. NPE-грабли: логгер в `HttpTemplate` null, пока не
  вызвали `setLogger`.
- **Код может не удержать обещание ADR** — тесты по букве ADR.
- **Чужие API — только по jar/javap/исходникам/прогону; min-версии — по
  тегам.** Multi-release jar'ы: корневой класс — заглушка.
- **CompletableFuture handle-рекурсия (#21):** witness
  `.<CompletableFuture<T>>handle(...)` + `.thenCompose(Function
  .identity())`.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml` (0
  `<dependency>`); `grep -c "<dependency"` возвращает exit 1 при 0
  совпадениях — это и есть зелёный результат.
- **Подсчёт тестов:** grep-цикл по XML-заголовкам (`tests=`/`failures=`/
  `errors=` из строк `<testsuite` в `build/test-results/test/*.xml`);
  python3/bc НЕ пережили ребут.
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **Преценденты решают споры о поверхности** (httpVersion — retry;
  proxy-builders без Javadoc; dedicated-фикстура на новую аннотацию/
  поведение — RetryController/HttpVersionController; log-capture —
  public `setLogger`; шов парсера — package-private метод, не WireMock).
  Контракт-доки живут на value-классе/аннотации/SPI-методе — не
  размазывать по proxy-builders.
- **Аллокации на hot path** — static constants, не per-call списки.
- **GRADLE_USER_HOME=/tmp/opencode/gradle-home** — если кэш стёрся
  (ребут), первый прогон дня качает зависимости заново (~50 с).
- **DRAFT-пауза — легитимное состояние** (#26): эпик не изобретать, пока
  мейнтейнер не выбрал направление или триаж сам не покажет очевидного
  кандидата; зафиксировано в правиле работы DRAFT.
- **Порядок ROADMAP-секций** (прецедент четыре раза): новые
  «COMPLETE ✅ (unreleased)»-разделы — хронологически, перед Post-0.5.0
  shortlist.
- **ADR-номера:** 0.7.0-цикл несёт ADR-0007 (Gson/Moshi), 0008 (retry),
  0009 (0.x polish), 0010 (Retry-After obsolete forms); **0011 —
  свободен** (кандидат: payload-decode).

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 29 → после следующей 30).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0011 — следующий свободный номер в `0.7.0/`).

### Состояние git
- HEAD: коммит сессии #28 (ADR-0010 + код + тесты + доки + память) +
  коммит этой сессии #29 (доки-аудит: Javadoc `@Retry`, README,
  ADR-0008 Known gaps + память).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **242/242**.
