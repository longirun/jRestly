# Handover Note: Сессия 13 (JsonCodecResolver: цепочка jackson → gson → moshi + package-private шов + 8-путная таблица-тест)

## Что сделано в этой сессии
- **Шаг 4 эпика Gson/Moshi-кодеков (DRAFT): `JsonCodecResolver`** — закрыт.
  - `src/main/java/ru/jrestly/json/JsonCodecResolver.java`: `resolve()`
    делегирует в package-private `static JsonCodec
    resolve(Predicate<String> classpathProbe)` (шов ADR-0007 Aspect 6).
    Цепочка jackson → gson → moshi, первое совпадение. Fail-fast
    перечисляет все три либы + явный `jsonCodec(...)`.
  - `src/test/java/ru/jrestly/json/JsonCodecResolverChainTest.java` —
    **первый тест в тест-пакете `ru.jrestly.json`** (шов package-private →
    тест обязан быть в том же пакете). 7 тестов: полная таблица комбинаций
    classpath (все три → Jackson; j+g → J; j+m → J; gson-only → Gson;
    g+m → G; moshi-only → Moshi; пусто → ISE с сообщением, где есть
    «jackson-databind», «gson», «moshi», «jsonCodec»). Функциональные пины
    «defaults() рабочий» (gson-only и moshi-only) через общий
    `ru.jrestly.fixtures.TestDto` (ожидаемая JSON-строка совпадает с пином
    MoshiCodecTest).
  - ADR-0007: Aspect 6 (шов покрыт ChainTest, 8-путная таблица) + Known
    gaps (Moshi отшивает конкретные platform-коллекции).
- **Гейты шага:** `./gradlew test --rerun` — **182/182** (175 + 7 новых);
  `pom-default.xml` — **0 `<dependency>`**, версия 0.6.2.

## Контекст для следующей сессии

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать** (последний тег
   v0.6.1, версия в build.gradle 0.6.2).
3. **Гигиена проекта — за агентом**: mailbox коротким, расхождения — чинить
   сразу. (В этой сессии новых сообщений Антона не было.)

### Что логично делать дальше
1. **Следующий шаг эпика (DRAFT, шаг 5): Javadoc-контракт.**
   - Интерфейс `JsonCodec` — normative-level Javadoc: `serialize`/
     `deserialize` синхронные, unchecked wrapping (UncheckedIOException-
     **style** — тип обёртки за адаптером: Jackson UncheckedIOException,
     Gson JsonSyntaxException, Moshi JsonDataException), `deserialize`
     уважает `java.lang.reflect.Type` (генерики), `prettyPrint` —
     best-effort, инстансы thread-safe (шарятся всеми вызовами клиента).
   - Класс-Javadoc каждого адаптера: либа + минверсия (Gson — у нас пин
     2.14.0, ADR пишет «Gson 2.8+»; Moshi — 1.x, пин 1.15.2), что
     настраивает `defaults()` (и что сознательно нет), поведение
     дата/времени.
   - Файлы: `src/main/java/ru/jrestly/json/JsonCodec.java`, `JacksonCodec.
     java` (есть Javadoc — сверить с контрактом), `GsonCodec.java`,
     `MoshiCodec.java` (MoshiCodec уже с подробным Javadoc — сверить, а не
     писать заново).
2. **Затем шаг 6: README** — «Jackson 2 is autodetected» → «Jackson, Gson,
   or Moshi is autodetected»: статус-строка, комменты трёх блоков установки
   (Kotlin/Groovy/Maven), SPI-нот в одном месте; координаты — v0.6.1 (последний тег, релизы на паузе).
3. **Финальный gate эпика:** `./gradlew test` зелёный + POM без
   runtime-зависимостей + **дистилляция эпика в ROADMAP.md** (закрытые шаги
   → Roadmap, DRAFT зачистить под следующий эпик).
4. **Следующий эпик — Retry with backoff** (ADR-0003, Post-0.5.0
   shortlist): концепт + `@OnError`-семантика.

### Грабли этой и прошлых сессий
- **Чужие API — только по jar/javap/исходникам, не из головы.** В этой
  сессии из головы: «LinkedHashMap в whitelist'е Moshi» (нет — любой
  platform-коллекционный класс отшивается), «reflection всё пропустит» (нет —
  `MethodHandles.lookup()` в чужом коде проверяет доступ от их класса).
- **Multi-release jar'ы: корневой класс — заглушка.** У Moshi `RecordJsonAdapter`
  в корне jar'а — stub (AssertionError), реальный код — в
  `META-INF/versions/16/`. javap'ить надо версию, соответствующую JDK.
- **Plain Moshi + конкретные коллекции = IAE** («Moshi only supports the
  collection interfaces by default»); runtime-class резолвится коДЕКом →
  `Map.of(...)`/`HashMap` не сериализуются с defaults(). В ADR Known gaps.
- **`UncheckedIOException` требует IOException-cause** — Gson/Moshi
  бросают RuntimeException, обёртка подбирается по семье либы.
- **Даты/поведение сторонних либ — только по пину.**
- **Параллельные сессии:** перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log`.
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **POM-гейт:** `build/publications/mavenJava/pom-default.xml`.
- **Подсчёт тестов без python3:** shell-цикл по XML (см. сессии #11–#13).
- **Web:** maven-metadata.xml стабильны; GitHub API/raw — тоже (curl есть,
  unzip нет — `jar tf`/`jar xf`); search.maven.org/websearch — нестабильны.

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (кэш жив);
   `/tmp` не переживает ребут (пересоздавать каталог).
2. `pids.max = 1000`. Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 13 → после этой 14).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, `0.7.0/`
   (ADR-0007 — правки сессий #11–#13).

### Состояние git
- HEAD: коммит сессии #13 (JsonCodecResolver + ChainTest + ADR-правки +
  DRAFT + память) поверх `34ee553` (сессия #12, MoshiCodec).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: **182/182** (175 до сессии + 7 новых JsonCodecResolverChainTest).
