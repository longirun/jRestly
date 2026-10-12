# Handover Note: Сессия 11 (GsonCodec: адаптер + 12 пин-тестов, пин Gson 2.14.0)

## Что сделано в этой сессии
- **Шаг 2 эпика Gson/Moshi-кодеков (DRAFT): `GsonCodec`** — закрывается.
  - `build.gradle`: `compileOnly` + `testImplementation`
    `com.google.code.gson:gson:2.14.0` (latest stable, проверено по
    maven-metadata.xml; ADR гадок 2.13.x — фактический latest 2.14.0,
    2026-04-23).
  - `ru/jrestly/json/GsonCodec.java`: зеркало `JacksonCodec` —
    конструктор «Gson как есть, не донастраиваем» (без requireNonNull,
    точь-в-точь как у Jackson), `defaults()` = plain
    `GsonBuilder().create()`, `prettyPrint` через **lazy pretty twin**
    (`gson.newBuilder().setPrettyPrinting().create()`, volatile-кэш —
    pretty-флаг у Gson per-instance, не per-call), ошибки
    `deserialize`/`serialize` — `JsonSyntaxException` (не
    `UncheckedIOException`: там cause обязан быть IOException, а Gson
    бросает RuntimeException) с целевым типом в сообщении и origin-cause.
  - `GsonCodecTest` (12 тестов): round-trips DTO/List-генерики (TypeToken),
    null, unknown properties, explicit-Gson snake_case, malformed-пин,
    prettyPrint best-effort, java.time-пины.
- **Ключевая находка (зафиксирована в тестах + 5 правках ADR-0007):**
  Gson 2.14 (PR #2948) **заморозил рефлексивное wire-представление
  java.time**: `LocalDateTime` →
  `{"date":{"year":2026,"month":8,"day":24},"time":{"hour":12,"minute":34,"second":56,"nano":0}}`
  — не ISO-8601 (ISO в Gson никогда не был дефолтом plain-билдера).
  `java.util.Date` → строка локала (MEDIUM-стиль DateFormat), **секундная**
  точность — дробная часть Date теряется. Все пины — по реальным прогонам
  на 2.14.0.
- **Гейты шага:** `./gradlew test --rerun` — **162/162** (150 + 12 новых);
  `pom-default.xml` (`build/publications/mavenJava/`) — **0
  `<dependency>`**, версия 0.6.2 → compileOnly в POM не попал.
- **DRAFT:** шаг 2 `[x]`; **mailbox:** короткая заметка-инсайт Антону
  (Gson заморозил рефлексивный java.time формат — user-visible следствие
  autodetection, документировано).

## Контекст для следующей сессии

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать.** `git tag` не
   трогать (последний тег v0.6.1, версия в build.gradle 0.6.2).
3. **Гигиена проекта — за агентом**: mailbox коротким, расхождения —
   чинить сразу. (В этой сессии новых сообщений Антона не было; моя
   заметка 01:40 — инсайт, ответа не требует.)

### Что логично делать дальше
1. **Следующий шаг эпика (DRAFT, шаг 3): `MoshiCodec`.**
   - Пин: latest stable `com.squareup.moshi:moshi` (ADR гадок 1.15.x —
     проверить по maven-metadata.xml, как делалось с Gson:
     `https://repo1.maven.org/maven2/com/squareup/moshi/moshi/maven-metadata.xml`).
   - Форма: зеркало Jackson/Gson — `MoshiCodec(Moshi)` «как есть»,
     `defaults()` = `new Moshi.Builder().build()` + **опциональный проб
     `moshi-adapters`** (`com.squareup.moshi.adapter.JavaTime`) —
     байт-в-байт паттерн jsr310-проба из `JacksonCodec` (nested class,
     чтобы верификатор не резолвил Moshi-типы без адаптеров).
     `testImplementation` добавить и moshi, и moshi-adapters.
   - prettyPrint через JsonWriter (идиом ADR-0007 Aspect 4: «re-print
     through JsonValue + an indented JsonWriter, verify against the pinned
     version»). Ожидаемая реальность java.time в Moshi: ISO-8601 (в
     отличие от Gson — проверять пином, не из головы!).
   - `MoshiCodecTest` в духе `GsonCodecTest`/`JacksonCodecDefaultsTest`.
   - Gate: тесты зелёные + POM без runtime-зависимостей.
2. Затем по DRAFT: `JsonCodecResolver` (цепочка jackson → gson → moshi +
   **package-private тестовый шов** — заметка: шов в `ru.jrestly.json`,
   значит тесты для него — в test-пакете `ru.jrestly.json`, не
   `ru.jrestly`, как существующие), Javadoc-контракт (шаг 5: интерфейс
   `JsonCodec` normative + минверсии; уточнение по типу обёрток: Jackson —
   UncheckedIOException, Gson — JsonSyntaxException, Moshi — по факту),
   README («Jackson, Gson or Moshi is autodetected»), финальный gate +
   дистилляция в ROADMAP.
3. **Retry with backoff** (ADR-0003) — следующий эпик после Gson/Moshi.
4. После первого тег-релиза — поднять координаты README (сейчас осознанно
   на v0.6.1 = последний тег).

### Грабли этой и прошлых сессий
- **Даты в сторонних либах — только по пину, не из головы.** В Gson мои
  оба «знакомых» факта (ISO-8601, Date→millis) оказались неверными.
  Для Moshi тоже: записать реальность пином в первом же прогоне.
- **`UncheckedIOException` требует IOException-cause** — Gson/Moshi
  бросают RuntimeException, обёртка подбирается по семье либы.
- **Ребут контейнера:** `/tmp/opencode` не переживает — GRADLE_USER_HOME
  пересоздать, первый прогон дня медленнее. В этой сессии кэш жив.
- **README всегда ссылается на последний ТЕГ**, а не на версию в
  build.gradle (расщелина «теги не вешаем»).
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log` (мог появиться чужой коммит).
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **CompletableFuture-семантика JDK 21**: в `exceptionally` бросать
  raw-причину → ровно один CE-слой.
- **WireMock**: `urlEqualTo` матчит строку включая query; для `?page=1` —
  `urlPathEqualTo` + `withQueryParam`.
- **Контейнер без python3/bc** — подсчёт тестов: shell-цикл по XML.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml`
  (layout сменился с 0.5.0-эпохи), `generatePomFileForMavenJavaPublication`.
- **JSON-строки в тестах**: закрывающие скобки/кронштейны считать по
  структуре (в этой сессии забыл `]` массива — «End of input path $[2]»).

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (кэш жив);
   `/tmp` не переживает ребут (пересоздавать каталог).
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик сессий
   подкручивает harness (сейчас 11 → после этой 12).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007, правки этой сессии).
7. Web доступ: maven-metadata.xml берутся стабильно; search.maven.org и
   websearch — нестабильны (таймауты/отмены).

### Состояние git
- HEAD: коммит сессии #11 (GsonCodec + тесты + ADR-правки + DRAFT + память)
  поверх `f102d4f` (сессия #10, ADR-0007).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **162/162** (150 до сессии + 12 новых GsonCodecTest).
