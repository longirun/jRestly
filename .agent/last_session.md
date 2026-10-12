# Handover Note: Сессия 12 (MoshiCodec: адаптер + 13 пин-тестов, пин Moshi 1.15.2; ADR-проб оказался фантомом)

## Что сделано в этой сессии
- **Шаг 3 эпика Gson/Moshi-кодеков (DRAFT): `MoshiCodec`** — закрывается.
  - `build.gradle`: `compileOnly` + `testImplementation`
    `com.squareup.moshi:moshi:1.15.2` (latest stable, проверено по
    maven-metadata.xml). moshi-adapters не добавлен (см. ниже).
  - `ru/jrestly/json/MoshiCodec.java`: зеркало GsonCodec/JacksonCodec —
    конструктор «Moshi как есть», `defaults()` = plain
    `new Moshi.Builder().build()` **без проба**, ошибки
    `deserialize`/`serialize` — `JsonDataException` (семья Moshi) с
    целевым типом и origin-cause, `serialize(null)` → `"null"`,
    prettyPrint — token-for-token JsonReader→JsonWriter (best-effort).
  - `MoshiCodecTest` (13 тестов, package `ru.jrestly` как у Gson):
    round-trips DTO (records), List-генерики
    (`Types.newParameterizedType`), null, unknown properties,
    null-поля-опускаются, platform-class-пины (LocalDateTime и Date —
    plain Moshi отшивает с IAE «Platform class X requires explicit
    JsonAdapter to be registered»), explicit-Moshi (свой ISO
    LocalDateTime-адаптер + кастомный скалярный), malformed-пин,
    prettyPrint best-effort + number-fidelity-пин (целые остаются целыми,
    `1.10` — как есть, long — точный).
- **Ключевая находка — ADR-проб moshi-adapters оказался фантомом.**
  ADR-0007 (Aspect 3) гадка `com.squareup.moshi.adapter.JavaTime` в
  moshi-adapters — аналог jsr310. Проверка по jar 1.0.0/1.5.0/1.14.0/
  1.15.2 + upstream tree: `JavaTime` там **никогда не было**. Артефакт =
  только явные адаптеры: Rfc3339DateJsonAdapter (Date), EnumJsonAdapter и
  PolymorphicJsonAdapterFactory (с 1.14). Стандартного java.time-аддона в
  мире Moshi 1.x нет вообще → проб убрали, escape hatch = explicit Moshi
  с пользовательскими адаптерами. ADR-0007 исправлен (Aspect 2/3/4/6,
  Consequences, Known gaps — 8 правок).
- **prettyPrint-реальность (весьма неочевидная):**
  - `JsonValue` в core НЕТ (только интерфейс `JsonValueWriter`);
  - `Object`-адаптер парсит числа в Double → `1` → `1.0` (tree-based
    reprint не годится);
  - `JsonWriter.value(String)` **кавит** значение (в отличие от
    Gson/OkHttp-JsonWriter'ов!) → числа пишутся raw через
    `JsonWriter.value(BufferedSource)`;
  - отсюда — token-for-token `copyValue` (reader.beginObject/Array +
    writer-зеркало, NUMBER — raw literal).
- **Гейты шага:** `./gradlew test --rerun` — **175/175** (162 + 13 новых);
  `pom-default.xml` — **0 `<dependency>`**, версия 0.6.2.

## Контекст для следующей сессии

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (`git tag` не
   трогать; последний тег v0.6.1, версия в build.gradle 0.6.2).
3. **Гигиена проекта — за агентом**: mailbox коротким, расхождения —
   чинить сразу. (В этой сессии новых сообщений Антона не было.)

### Что логично делать дальше
1. **Следующий шаг эпика (DRAFT, шаг 4): `JsonCodecResolver`.**
   - Текущий резолвер: `src/main/java/ru/jrestly/json/JsonCodecResolver.java`
     — пробует только Jackson (ObjectMapper), fail-fast-сообщение
     Jackson-only.
   - Цель: цепочка **jackson → gson → moshi** (первое совпадение),
     fail-fast перечисляет все три + явный codec.
   - **Package-private тестовый шов**: `resolve()` делегирует в
     package-private overload с настраиваемым probe (ADR Aspect 6).
     ВАЖНО: шов в пакете `ru.jrestly.json` → тесты для него писать в
     **test-пакете `ru.jrestly.json`** (первый тест в этом пакете!), а не
     в `ru.jrestly`.
   - Существующий `JsonCodecResolverTest` (в `ru.jrestly`) — Jackson-пин,
     на тест-класспазе все три либы → Jackson выигрывает.
   - Gate: тесты зелёные + POM без runtime-зависимостей.
2. Затем по DRAFT: Javadoc-контракт (шаг 5: интерфейс `JsonCodec`
   normative-level Javadoc — unchecked wrapping/generics/best-effort
   prettyPrint/thread-safety; минверсии у адаптеров — Gson 2.8+? (у нас
   пин 2.14), Moshi 1.x (пин 1.15.2); уточнение: типы обёрток различаются
   — Jackson UncheckedIOException, Gson JsonSyntaxException, Moshi
   JsonDataException — «UncheckedIOException-style», тип за адаптером),
   README (шаг 6: «Jackson, Gson or Moshi is autodetected» — статус-строка,
   три блока установки, SPI-нот; координаты — v0.6.1 = последний тег),
   финальный gate + дистилляция в ROADMAP.
3. **Retry with backoff** (ADR-0003) — следующий эпик.

### Грабли этой и прошлых сессий
- **Чужие API — только по jar/javap/исходникам, не из головы.** В этой
  сессии из головы: JavaTime-класс (фантом), JsonUtils/JsonValue (нет),
  NoJsonAdapterException (факт — IAE), value(String) raw (кавит),
  setIndent fluent (void). Правило «пять правок в ADR после пина» (Gson)
  повторилось.
- **okio в main-коде MoshiCodec — осознанно:** класс грузится только после
  moshi-проба; moshi → okio-jvm жёсткая POM-зависимость.
- **Java-vs-Kotlin API:** okio `writeString` — extension (из Java не
  виден, есть `writeUtf8`); `JsonReader.of(BufferedSource)`/
  `JsonWriter.of(BufferedSink)` — единственные фабрики; `Moshi.adapter`
  требует явный тип-виджет на `JsonAdapter<T>`.
- **`UncheckedIOException` требует IOException-cause** — Gson/Moshi
  бросают RuntimeException, обёртка подбирается по семье либы.
- **Даты/поведение сторонних либ — только по пину.**
- **Ребут контейнера:** `/tmp/opencode` не переживает — GRADLE_USER_HOME
  пересоздать. В этой сессии кэш жив (01:51 старт).
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml`.
- **Подсчёт тестов без python3:** shell-цикл по XML.
- **Web:** maven-metadata.xml стабильны; GitHub API/raw — тоже (curl есть,
  unzip нет — `jar tf`/`jar xf`); search.maven.org/websearch — нестабильны.

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (кэш жив);
   `/tmp` не переживает ребут (пересоздавать каталог).
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 12 → после этой 13).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007 — правки этой сессии).

### Состояние git
- HEAD: коммит сессии #12 (MoshiCodec + тесты + ADR-правки + DRAFT + память)
  поверх `a5cd5ab` (сессия #11, GsonCodec).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **175/175** (162 до сессии + 13 новых MoshiCodecTest).
