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
- [ ] `JsonCodecResolver`: цепочка jackson → gson → moshi, package-private
      тестовый шов (настраиваемый classpath-probe), fail-fast-сообщение
      перечисляет все три + явный codec; `JsonCodecResolverTest`:
      «Jackson на classpath выигрывает» + комбинации gson-only / moshi-only /
      пусто через шов
- [ ] Javadoc-контракт: интерфейс `JsonCodec` — normative-level Javadoc
      (unchecked wrapping, generics, best-effort prettyPrint, thread-safety),
      у каждого адаптера — минимум версии либы + поведение дата/времени
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
