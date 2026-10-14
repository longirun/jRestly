# Handover Note: Сессия 71 (ADR-0015 шаг 6/6 — дистилляция в ROADMAP и зачистка DRAFT; эпик закрыт, 282/282; следующий ход — выбор нового эпика из активной зоны)

## Что сделано в этой сессии
- **Ход:** шаг 6 эпика ADR-0015 — дистилляция в ROADMAP и зачистка DRAFT. Входящих нет (автономный ход; живые нитки mailbox'а — 3 — на месте).
- **ROADMAP:** Добавлена новая секция `## Response streaming — InputStream return type (ADR-0015) — COMPLETE ✅ (2026-10-14, unreleased)` (source of truth: `docs/0.9.0/ADR-0015-response-streaming-inputstream-return.md`; сессии #64–#71; ключевые решения по Aspect 1–6: surface return type is the contract, engine `BodyHandlers.ofInputStream()`, `WireResponse.stream` ABI-neutral, intermediate stream disposal, JSON by return type, error paths consume, lifecycle & logging, validation & end-to-end; гейты: 282/282 tests, 0 warnings under `-Xlint:all -Werror` / `-Xdoclint:all -Werror`, build.gradle 0.8.0). Предыдущая сноска обновлена in-place (ADR-0015 complete, ADR-0016 free).
- **DRAFT:** Эпик response streaming закрыт 6/6; верх зачищен сводкой закрытия; в активную зону подняты 4 кандидата:
  - Кандидат №1 (основной курс, направление #3): SSE / line events для LLM — ADR-0016 (строится прямо на стрим-шве ADR-0015; JDK-основа проверена: `BodyHandlers.ofLines()` / `fromLineSubscriber`, charset из Content-Type per ofString-контракт);
  - Кандидат №2: гибкость параметров запроса (class-level headers, nullable-параметры, catch-all) — следующий срез направления #3;
  - Кандидат №3: тонкая настройка пайплайна (hooks/interceptors) — следующий срез направления #3;
  - Кандидат №4: test-javadoc (ADR-0013 Non-goals: «test-source javadoc» вынесен за скобки аудита).
- **Гейты (clean rebuild & test rerun):**
  - `./gradlew test --rerun` — **282/282** (40 классов, 0 fail/err по XML; 276 + 6 в цикле 0.9.0).
  - `compileJava compileTestJava javadoc --rerun-tasks` — **0 warnings** под `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
  - build.gradle не менялся (0.8.0 unreleased).
- **Память:** дневник дня `.agent/days/2026-10-14.md` пополнен заметкой сессии #71 и дополнением в итог дня.

## Контекст для следующей сессии (#72)

### Состояние: выбор следующего эпика из активной зоны
- **Кандидат №1 (рекомендуемый):** ADR-0016: SSE / line events для LLM (стриминг токенов).
  - Строится поверх шва стриминга ADR-0015 (`BodyHandlers.ofInputStream()` / `BodyHandlers.ofLines()`).
  - Эргономика возврата: например, `Stream<String>` или реактивный Subscriber / Consumer строк / событий.
  - Следующий логический шаг — концепт-сессия: оформить `docs/0.9.0/ADR-0016-sse-line-events.md` и DRAFT-эпик.
- **Либо другие кандидаты из активной зоны:** гибкость параметров запроса или пайплайн.

### Грабли (актуальный набор)
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew` в этой среде.
- **`UnexpectedStatusException.getRawBody()`**: getter называется `getRawBody()`, не `getResponseBody()`.
- **JDK 21 `InputStream.readAllBytes()` НЕ закрывает stream** (#67, пробом): закрытие стрима — ответственность вызывающего (в try-with-resources) либо `processResponse` при ошибках.
- **JDK 21 `thenApply` распаксовывает `CompletionException`** (#66, пробом): mapping видит plain cause.
- **POM — не артефакт `test`** (#38): build.gradle в этом эпике не менялся (остаётся 0.8.0 до cycle-ADR 0.9.0).
- **Changelog — навигатор, не зеркало** (#40): entry 0.9.0 пишем в release-цикле 0.9.0; сейчас CHANGELOG не трогать.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **`./gradlew test --rerun`** — «UP-TO-DATE ≠ мой прогон».
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас — 282).
- **«Итог дня» — живая секция:** дописывать фрагментом « + **... (сессия #N)**», не переписывать; сессионные заметки — до «Итога дня», не после.
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние 2–3 сообщения оставляем (сейчас 3).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`.
4. Версия в build.gradle: **0.8.0**. Тесты: **282/282** (40 классов).
5. Warnings: main 0, test 0, javadoc 0 (под `-Werror`).
