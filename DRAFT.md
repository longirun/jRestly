# DRAFT: Gson / Moshi codec adapters (расширение цепочки classpath-probe)

> Эпик открыт в сессии #10 (2026-10-13). Концепт зафиксирован в
> ADR-0007 (`docs/0.7.0/ADR-0007-gson-moshi-codec-adapters.md`):
> порядок цепочки jackson → gson → moshi (первое совпадение выигрывает,
> explicit codec всегда выше), форма адаптеров = зеркало JacksonCodec
> (конструктор принимает инстанс «как есть», `defaults()`-фабрика,
> опциональный java.time-проб), тестовый шов package-private для
> комбинаторики classpath. Норма объёма: по одному шагу за сессию.
> Тегов нет, версия 0.6.2 (релизы на паузе).

## Шаги эпика

- [x] Концепт: ADR-0007 — порядок проб, приоритет, форма адаптеров,
      Javadoc-контракт `JsonCodec`, тестовая стратегия (сессия #10)
- [x] `GsonCodec` (compileOnly `com.google.code.gson:gson`, запинен **2.14.0** =
      latest stable, сессия #11): `defaults()` = plain `GsonBuilder().create()`,
      lazy pretty twin, `GsonCodecTest` (12 тестов: round-trips, java.time-пин —
      Gson 2.14 зафиксировал рефлексивное представление `{"date":{...},"time":{...}}`
      (не ISO!), `Date` → строка локала с секундной точностью, prettyPrint
      best-effort). Обёртка ошибок — `JsonSyntaxException` (Gson-семья, а не
      UncheckedIOException: cause — не IOException)
- [x] `MoshiCodec` (compileOnly `com.squareup.moshi:moshi`, запинен
      **1.15.2** = latest stable, сессия #12): `defaults()` = plain
      `new Moshi.Builder().build()` **без проба** — `moshi-adapters`-проб
      из ADR оказался фантомом (`JavaTime` никогда не существовал в
      moshi-adapters 1.x: проверено по jar 1.0.0/1.5.0/1.14.0/1.15.2 +
      upstream; артефакт = только явные Date/Enum/Polymorphic-адаптеры).
      Третья реальность: plain Moshi отшивает `java.*`-platform-классы
      («Platform class X requires explicit JsonAdapter to be registered» —
      IAE, не JsonDataException) → кодек оборачивает в JsonDataException
      с целевым типом (паттерн обёрток: семья либы). prettyPrint —
      token-for-token через JsonReader→JsonWriter (JsonValue в core нет,
      Object-адаптер расширяет числа в Double; NUMBER — raw literal через
      `value(BufferedSource)`, т.к. `value(String)` кавычит).
      `MoshiCodecTest` (13 тестов): round-trips DTO/List-генерики
      (Types.newParameterizedType), null, unknown properties,
      null-поля-опускаются, platform-class-пины (LocalDateTime/Date),
      explicit-Moshi (ISO java.time-адаптер + кастомный скалярный),
      malformed-пин, prettyPrint best-effort + number-fidelity-пин
- [x] `JsonCodecResolver`: цепочка jackson → gson → moshi, package-private
      тестовый шов (настраиваемый classpath-probe), fail-fast-сообщение
      перечисляет все три + явный codec (сессия #13): `resolve()` делегирует
      в `static JsonCodec resolve(Predicate<String> classpathProbe)`;
      `JsonCodecResolverChainTest` (package `ru.jrestly.json`, первый тест в
      пакете) — полная 8-путная таблица комбинаций classpath + функциональные
      пины «defaults() рабочий» через общий фикстура-DTO. Пин-реальность
      Moshi 1.15.2: конкретные platform-коллекции (`Map.of(...)` →
      `ImmutableCollections$Map1`) отшиваются («Moshi only supports the
      collection interfaces by default») — в ADR Known gaps
- [x] Javadoc-контракт (сессия #14): интерфейс `JsonCodec` —
      normative-level Javadoc (unchecked wrapping в семье исключений
      либы — «UncheckedIOException-style»: Jackson UncheckedIOException,
      Gson JsonSyntaxException, Moshi JsonDataException; message называет
      тип значения, cause — исходное; `Type` уважается, включая генерики;
      top-level `null` → JSON `null` и обратно; best-effort prettyPrint;
      thread-safety — обещание, на которое либа опирается, включая
      user-кодеки) + method-level Javadoc всех трёх методов. Адаптеры:
      Jackson 2.x (ADR-0001, пин 2.18.2, thread-safety: ObjectMapper
      thread-safe после конфигурации), **Gson 2.8.3+** — реальность-пин
      (pretty-twin строится через `Gson.newBuilder()` — API 2.8.3+;
      проверено по тегам 2.8.0/2.8.1/2.8.2/2.8.3. До 2.14 у Gson нет ни
      одного java.time-адаптера — java.time падает в общий рефлективный
      путь, на современных JDK нужен `--add-opens`; пин 2.14.0), Moshi
      1.x (пин 1.15.2 — сверено, было). + пин `serialize(null) → "null"`
      в JacksonCodecDefaultsTest (контракт теперь закреплён тестом во
      всех трёх кодеках). ADR-0007: Aspect 3 (java.time pre-2.14 по тегу
      2.13.1), Aspect 5 (Gson 2.8.3+ + отметка выполнения), нумерация
      шагов (заголовок, Consequences: README = шаг 6)
- [ ] README: «Jackson 2 is autodetected» → «Jackson, Gson or Moshi is
      autodetected» (статус-строка, комменты трёх блоков установки, SPI-нот)
- [ ] Gate: `./gradlew test` зелёный + POM без runtime-зависимостей
      (проверить `pom-default.xml`) + дистилляция в ROADMAP.md

## Отложенные кандидаты

- [ ] Retry with backoff (Post-0.5.0 shortlist, ADR-0003) — концепт + `@OnError`-семантика
- [x] README устарел: «current release v0.6.0» при версии 0.6.2 — закрыт в
      сессии #8: статус-строка и три блока установки на v0.6.1 (последний тег);
      0.6.2 в координатах не фигурирует — тега нет, релизы на паузе

## Постоянная нить (не эпик, не чекбокс)

- **Гигиена проекта — за агентом** (Антон, 2026-10-13: «сам следи тут как
      лучше — в этом файле и вообще на проекте»). Практика: mailbox не
      растёт бесконечно (применять правило «решённый обмен удаляем» в каждой
      сессии, где он применим); устаревшие «Пометки из mailbox» в DRAFT
      сверять с реальностью; мелкие расхождения (версии, статусы) чинить
      сразу, не копить.

Пометки из mailbox (Антон, 2026-10-12, актуальные):
- ADR-0006 (java.time wire contract) **не требуется** — вопрос закрыт.
- **Версии — по нашему усмотрению** (сейчас 0.6.2), **теги не вешать**,
  чтобы либа не ушла в релизы и не спутали с главным релизом.
- **Объём одной сессии сократить в 2–3 раза** — брать меньше, делать чаще.
- **Чистку mailbox Антон подтвердил** (2026-10-13) — правило из «постоянной
  нити» выше в силе.

Правила работы: один эпик в DRAFT.md, шаги — чекбоксы `[ ]`; закрыл →
дистиллируй в ROADMAP.md и зачисти это место.
