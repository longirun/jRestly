# Last Session: #332 (2026-10-20)

## Что сделано

**Сессия #332: ADR-0075 — оформление Кандидата №14 (header-merge инварианты, live trace-view, response-map fast-paths). Код не тронут — гейт 1,809/1,809 в силе от #330.**

1. *Приём состояния:* HEAD `acf0ec3` (#331), чистое дерево (только счётчик 332), слов Антона в mailbox'е нет.
2. *ADR-0075* (`docs/0.12.0/ADR-0075-header-merge-invariants-and-allocation-pruning.md`, Accepted; стиль — ADR-0072): четыре решения с инвариантами и амплитудой:
   - **Д1:** hash-set-free merge — прямой case-insensitive O(a·s) скан; инвариант порядка (выживший ambient на своём месте → statics в порядке деклараций → dynamics в порядке параметров → `Accept-Encoding` последним); статика бьёт ambient по имени; crossover на больших ambient-списках — задокументированный non-goal.
   - **Д2:** `removeHeaderByName` (reverse in-place) / `hasHeaderNamed` (plain scan) вместо lambda/Stream; null-имена пропускаются (ambient `AuthProvider` — публичное стыко-место ⇒ data-quality issue не NPE).
   - **Д3:** единственный unmodifiable-wrap — compact-конструктор `TraceRequestContext`; live-view семантика («view, не snapshot»: саминжектированный header виден через view) зафиксирована структурно.
   - **Д4:** `ResponseHeaders.buildValueMap` — shape-driven: пусто ⇒ `emptyMap()`-singleton, single-value ⇒ компактный `List.of`, multi-value ⇒ `unmodifiableList`; `TreeMap(CASE_INSENSITIVE_ORDER)` + unmodifiable-map байт-в-байт.
   - Known gaps: O(a·s) для малых списков; backing `ArrayList` multi-value-накопления; `--add-opens java.base/java.util=ALL-UNNAMED` — test-JVM only.
3. *ROADMAP.md:* баннер Current status — Highest ADR **0075**, next free **0076**; тег ADR-0075 у Кандидата №14 в строке цикла 0.12.0; в секции №14 пометка, что оформление последовало в #332.
4. *DRAFT.md:* блок сессии #332; пул 0.12.0 — ADR-0075 перемечен в закрытые.
5. *Гейты:* код main не тронут (ADR + ROADMAP + DRAFT) — 1,809/1,809 от #330 не пере-прогонялся (прецедент #331); javadoc-гейт не затронут.

## Контекст для следующей сессии (#333)

1. **Ветка** `agent-loop`.
2. **Highest ADR:** **0075**, next free **0076**.
3. **Пул 0.12.0 (после #332):** активных эпиков нет — только хвосты:
   - **Валидация `@ExpectStatus`/`@FollowRedirects`** (#207 «фича, не дыра» — инварианты деклараций нигде не объявлены; трогать при тихом исходе; зона — `ControllerValidator`).
   - **Свободный аудит следующего hot-path:** dispatch `HttpTemplate` (`send`/`createUri` после ADR-0072), cookie-разбор (`resolveCookies`/`HttpCookie.parse`), `MethodMetadata`-кэши.
4. **Замечание по окружению:**
   - Команды gradle запускать с `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
   - `bc` в песочнице нет — суммы считать awk'ом или руками.
   - Hygiene mailbox: разобранные обмены удалять, держать последние 2–3 записи.
5. Метрики: гейт **1,809/1,809** (root baseline 1,647 = 1,642 + 5 структурных тестов #330). JaCoCo — рекорд 90.65% Branch (4,051/4,469), 100% Class/Method, без изменений с ADR-0072 (ядро с тех пор — только allocation-shape рефакторинг без поведенческих ветвей).
6. *Мысль дня (для #333+):* ADR на TDD-шаг — другой регистр, чем ROADMAP-секция: ROADMAP = «что сделали + цифры», ADR = «почему именно так + где границы». Факты не копировать дословно, а переформулировать в форму решений (crossover-критерий, «view, не snapshot», мотивация null-skip).
