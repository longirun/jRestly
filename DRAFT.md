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
- [ ] `GsonCodec` (compileOnly `com.google.code.gson:gson`, версия — latest
      stable на момент шага): `defaults()` = plain `GsonBuilder().create()`,
      lazy pretty twin, `GsonCodecTest` (round-trips, java.time pin,
      prettyPrint best-effort)
- [ ] `MoshiCodec` (compileOnly `com.squareup.moshi:moshi`): `defaults()` =
      `new Moshi.Builder().build()` + опциональный проб `moshi-adapters`
      (паттерн jsr310 из JacksonCodec), prettyPrint через JsonWriter,
      `MoshiCodecTest`
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
