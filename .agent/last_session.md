# Last Session: #215 (2026-10-17)

## Что сделано

**Free-аудит (продолжение оси #214 «асимметричный guard»): `@RequestBody` на
form-urlencoded/multipart-методе тихо выбрасывался — 25-я «тихая» находка серии,
симметричное закрытие пары «payload-аннотация ⇄ requestType».**

1. *Ход:* свободный выбор из контекста #214 (активная зона пуста, highest ADR
   **0060**). Взял подсказку #214 буквально: у пары «payload-биндинг ⇄
   requestType» закрыта лишь **одна** половина — `@MultipartFormFile` требует
   `MULTIPART_FORM_DATA` (#214). Вторую (`@RequestBody`) никто не проверял.
2. *Факт (тихий drop):* `@RequestBody` сериализуется JSON-кодеком и читается
   **только** в ветке `APPLICATION_JSON` (`HttpTemplateBuilder.build` →
   `createJsonBodyPayload`, строка ~292). На `APPLICATION_FORM_URLENCODED` тело
   собирает `createUrlEncodedBody` (только `@RequestParam`), на
   `MULTIPART_FORM_DATA` — `createMultipartParts` (только
   `@RequestParam`/`@MultipartFormFile`); ни один путь не зовёт `createBody` →
   аргумент `@RequestBody` **молча выбрасывается** из запроса (ни ошибки, ни
   лога). Проверено зондом: валидатор молчал (0 проблем). Тот же класс, что
   #207–#214.
3. *Fix:* `ControllerValidator.validateMethod` — для каждого
   `@RequestBody`-параметра при `requestType != APPLICATION_JSON` добавляет
   `method(): @RequestBody parameter requires requestType = APPLICATION_JSON` в
   общий агрегированный `ControllerValidationException` при создании прокси.
   `@Get` не затронут (отбивается отдельным сообщением, `resolveRequestType(@Get)`
   = JSON); JSON-путь ведёт себя как раньше. Javadoc `ControllerValidator`
   уточнён (payload-биндинг должен совпадать с requestType). Публичный API не
   тронут.
4. *TDD red-first:* новый
   `ControllerValidationTest.rejectsRequestBodyWithoutJsonRequestTypeEagerly`
   (интерфейсы `BodyParamOnFormController` — form-urlencoded → 1 проблема + eager
   исключение; `BodyParamOnMultipartController` — multipart → 1 проблема;
   `BodyParamOnJsonController` — дефолтный JSON → no-false-positive). Красный на
   до-фикс коде ровно по `assertEquals(1, formProblems.size())` (список пуст) →
   зелёный.
5. *Риск ложных срабатываний проверен:* ни один реальный `@RequestBody` в дереве
   не объявлен на form/multipart-методе — полный прогон зелёный.
6. *Документы:* ADR-0035 → `## Amendment #215`; `DRAFT.md` (блок-запись #215 +
   хвост активной зоны); дневник `.agent/days/2026-10-17.md` (сессия #215 +
   итог дня обновлён до 1243); mailbox (короткое уведомление, #212 вычищен по
   гигиене); `last_session.md`.
7. *Верификация:* `./gradlew test javadoc` — BUILD SUCCESSFUL; счётчик из
   `build/test-results/**/TEST-*.xml` = ровно **1243/1243** (core 1163 +
   engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 + benchmarks 15),
   0 failures/errors, javadoc-гейт (`-Werror`) чист.

## Технические заметки

- `createBody` (`HttpTemplateBuilder` ~859) вызывается **только** из
  `createJsonBodyPayload`; form/multipart-ветки `build()` (~297–313) его не
  зовут. `RequestType.APPLICATION_JSON` — единственный путь, где `@RequestBody`
  реально уходит на провод.
- `@MultipartFormFile`-ветка в `createMultipartParts` идёт через
  `if (p.isMultipartFile()) { … } else if (p.isRequestParam()) { … }` — файл
  выигрывает у `@RequestParam` для части тела.
- `RequestType` javadoc уже описывает `@RequestBody` как JSON-payload —
  расхождение было только в отсутствии guard'а.
- Прогон одного класса: `./gradlew :test --tests 'ru.jrestly.ControllerValidationTest'`
  (обязательно с `:` — иначе фильтр улетает в `:benchmarks:test`).
- Счётчик тестов: python по `**/build/test-results/test/TEST-*.xml` (бакеты:
  `core` + `engine-okhttp` + `engine-apache5` + `codec-jackson-xml` +
  `benchmarks`). `ControllerValidationTest.xml` → 16 тестов (было 15).
- `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.

## Контекст для следующей сессии (#216)

1. **Ветка** `agent-loop`; HEAD — коммит этой сессии (родитель — #214).
2. **Активная зона пуста.** Highest ADR **0060**, next free **0061**.
   Семья «payload-аннотация ⇄ requestType» закрыта симметрично
   (`@RequestBody` → JSON, `@MultipartFormFile` → MULTIPART). Варианты хода:
   (a) **Free-аудит по оси «асимметричный guard» (#214/#215)**: пройтись по
      остальным парным/семейным валидациям и спросить «а обратное направление?».
      Наблюдение #215: составной guard hub-центричен — `@RequestBody`/`@Cookie`
      отбиваются от остальных `{@PathVariable, @RequestParam, @RequestHeader,
      @MultipartFormFile}`, а пары среди самих этих четырёх принимаются
      (double-binding, не тихий drop — трогать только если найдёшь тихий исход).
   (b) **Ось #212 «декларация ⇄ окружение сборки»** — другие флаги/runtime-
      условия. Хвост: `@ExpectStatus(statuses = {})`/`@FollowRedirects(count < 0)`
      (решение #207 «фича, не дыра» — трогать только при тихом исходе: `count < 0`
      в `executeWithRedirects` фактически = 0 хопов, «unlimited»-намерение тихо
      не исполняется — проверить javadoc аннотации).
   (c) **Free-аудит остатков:** Path/File-тела метрик (-1 вместо размера;
      engine-reported byte accounting); `CallMetrics.isSuccess()` (2xx-only,
      задокументировано) против `@ExpectStatus`-whitelist (фича, не дыра).
   (d) **fixtures-javadoc** — 15 вспомогательных типов `ru.jrestly.fixtures`
      (осознанный non-goal кандидата №1).
   (e) **Концепт ADR-0061+** — HTTP-кэш (RFC 9111) ждёт решения Антона
      (вопрос в mailbox от #208, ответа пока нет) — не трогать без его слова.
3. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   счётчик сессий: 215. Часовой пояс контейнера +04 (календарный день
   2026-10-17).
