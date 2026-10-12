# Handover Note: Сессия 10 (концепт Gson/Moshi-кодеков: ADR-0007 + эпик в DRAFT)

## Что сделано в этой сессии
- **Концепт Gson / Moshi codec adapters** (шаг 1 нового эпика из DRAFT):
  `docs/0.7.0/ADR-0007-gson-moshi-codec-adapters.md`. Ключевые решения:
  1. Цепочка **jackson → gson → moshi**, первое совпадение выигрывает;
     explicit `jsonCodec(...)` всегда выше (порядок — по тексту ADR-0001).
  2. Форма адаптеров — зеркало `JacksonCodec`: `GsonCodec(Gson)` /
     `MoshiCodec(Moshi)` — «как есть, не донастраиваем»; `defaults()` —
     plain-инстанс (`new GsonBuilder().create()` / `new Moshi.Builder().build()`).
  3. java.time: Gson — встроен с 2.8.0 (проверка пина + тест-пин на шаге);
     Moshi — опциональный проб `moshi-adapters`, точь-в-точь паттерн jsr310.
  4. prettyPrint: у Gson — lazy-кэш pretty twin (Gson не имеет
     per-call pretty-флага); у Moshi — через JsonWriter (идиом зафиксировать
     на шаге по пину версии).
  5. Javadoc-контракт: normative interface-Javadoc на `JsonCodec`
     (unchecked wrapping, generics, best-effort prettyPrint, thread-safety)
     + min-version + дата-поведение у каждого адаптера.
  6. Тест-стратегия: `GsonCodecTest`/`MoshiCodecTest` в духе
     `JacksonCodecDefaultsTest`; существующий `JsonCodecResolverTest` = спека
     приоритета (все три либы на тест-класспазе → Jackson выигрывает);
     комбинации gson-only/moshi-only/пусто — через **package-private
     тестовый шов** в `JsonCodecResolver` (настраиваемый probe), без
     URLClassLoader.
- **DRAFT.md** перестроен: эпик «Gson / Moshi codec adapters» с 7 чекбоксами,
  концепт `[x]`; README v0.6.1 — в «отложенные кандидаты» (закрыт).
- **Кода нет** — независимый `./gradlew test --rerun`: **150/150**
  (XML: 150 tests / 0 failures / 0 errors; 14 с, тёплый кэш).

## Контекст для следующей сессии

### Вводные Антона (накопленные, актуально на 2026-10-13)
1. **Объём сессии — в 2–3 раза меньше:** один маленький шаг.
2. **Версии — по нашему усмотрению, теги — не вешать.** `git tag` не трогать.
3. **ISO 'T'** — выполнен (вариант A, сессия #7). **ADR-0006** — не требуется.
4. **Гигиена проекта — за агентом**: mailbox по правилу «решённый обмен
   удаляем, живые нитки оставляем»; мелкие расхождения — чинить сразу.
   В этой сессии новых сообщений Антона не было (mailbox тиш с 00:45).

### Что логично делать дальше
1. **Следующий шаг эпика (DRAFT, шаг 2): `GsonCodec`** — `compileOnly`
   `com.google.code.gson:gson` (latest stable ~2.13.x),
   `defaults()` = plain `GsonBuilder().create()`, lazy pretty twin,
   `GsonCodecTest`. Gate шага: тесты зелёные + POM без runtime-зависимостей.
2. Затем по DRAFT: `MoshiCodec` (+ `moshi-adapters` probe), resolver +
   тестовый шов, Javadoc-контракт, README, финальный gate + дистилляция
   в ROADMAP.
3. **Retry with backoff** (ADR-0003) — следующий эпик после Gson/Moshi.
4. После первого тег-релиза 0.6.2+ — не забыть поднять координаты README
   (сейчас осознанно на v0.6.1 = последний тег).

### Грабли этой и прошлых сессий
- **Ребут контейнера:** `/tmp/opencode` не переживает — `GRADLE_USER_HOME`
  пересоздать, первый прогон дня медленнее (49 с вместо 13–26 с). В этой
  сессии кэш жив (14 с).
- **README всегда ссылается на последний ТЕГ**, а не на версию в
  build.gradle — расщелина из-за «теги не вешаем».
- **Параллельные сессии**: перед правкой памяти/коммитом — `stat` mtime,
  `ps` на чужие процессы, `git log` (мог появиться чужой коммит).
- **UP-DATE ≠ мой прогон**: `./gradlew test --rerun`.
- **Комбинация Gson+Jackson на classpath** — в ADR-0007 решено: Jackson
  всегда выигрывает (порядок цепочки). Не «умный» выбор по метрикам.
- **prettyPrint у Gson** — per-instance флаг, не per-call: без кэша
  аллоцируем Gson на каждый логированный ответ (горячий путь логгирования).
- **CompletableFuture-семантика JDK 21**: в `exceptionally` бросать
  raw-причину → ровно один CE-слой, cause = «синхронное» исключение.
- **WireMock**: `urlEqualTo` матчит строку включая query; для `?page=1` —
  `urlPathEqualTo` + `withQueryParam`.
- `appendPattern` принимает литералы в одинарных кавычках:
  `"yyyy-MM-dd'T'HH:mm:ss"` — билдер не нужен (для optional-дроби — нужен).
- **Контейнер без python3/bc** — подсчёт тестов из XML-отчётов: простой
  shell-цикл `grep -o ' tests="[0-9]*"'` по файлам.

### Окружение
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (кэш + демон);
   `/tmp` не переживает ребут (пересоздавать каталог).
2. `pids.max = 1000` (поднят Антоном). Полный `./gradlew test` БЕЗ init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик сессий
   подкручивает harness на старте (сейчас 10 → после этой сессии 11).
5. Часы контейнера: UTC+4; mailbox-время — местное (конвенция всех сессий).
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`, `0.6.0/`, теперь
   `0.7.0/` (ADR-0007). Прецедент «папка для ещё не существующего цикла»
   создан в этой сессии.

### Состояние git
- HEAD: коммит сессии #10 (ADR-0007 + DRAFT-эпик + память) поверх `3966962`
  (сессия #9, mailbox cleanup).
- Всё на `agent-loop`. Последний тег `v0.6.1` — дальше теги за Антоном.
- Версия в build.gradle: **0.6.2** (без тега, релизы на паузе).
- Тесты: 150/150 (кода в этой сессии не менял, прогон — для чистоты).
