# Last Session: #325 (2026-10-19)

## Что сделано

**Сессия #325: Интеграция Netty в FeedBenchmark (ADR-0070 Фаза 3), замеры Throughput/Allocations/CPU, публикация в BENCHMARKS.md, ADR-0070 Accepted, гейт 1,737/1,737 (+1).**

1. *Приём состояния:* HEAD на #324, чистое дерево. Субмодуль `engine-netty` реализован, 21 паритетный тест зелёный.
2. *Интеграция в модуль бенчмарков:*
   - `benchmarks/build.gradle`: добавлена зависимость `implementation project(':engine-netty')`.
   - `FeedBenchmark.java`: добавлен `jrestlyNettyClient` (`NettyHttpEngine`), настроены lifecycle (`setup()` / `tearDown()`) и новые JMH-методы `jrestlyNetty_buffered` и `jrestlyNetty_streaming`.
3. *Тесты санитарного паритета (+1 тест):*
   - `FeedBenchmarkClientsTest`: добавлен тест `jRestlyNettyBufferedAndStreamingArmsFoldIdenticalSummaries` на fold-паритет и совпадение числа записей на всех 6 маршрутах (`rss-*`, `atom-*`).
   - `FeedBenchmarkTest`: расширен до 13 arm'ов (включая Netty buffered/streaming) с проверкой детерминированного дайджеста.
4. *Эмпирические замеры на стенде JMH (`-prof gc`, `CpuProbe` на `getProcessCpuTime()`):*
   - **Рекорд аллокаций в куче на малых телах:** `jRestly + engine-netty (streaming)` показал **98,342 B/op** на `rss-20` (абсолютный рекорд стенда, опередил raw OkHttp 98,725 B/op и Apache5 101,141 B/op благодаря `PooledByteBufAllocator`).
   - **Стриминг на Netty экономит гигабайты:** на 1 MB телах (`rss-1000`) стриминг экономит **6.26 MB/op** по сравнению с buffered mode (3.49 MB vs 9.75 MB).
   - **Превосходство над дефолтным JDK:** Throughput на `rss-20` составил **308.3 ops/s** (+53% к JDK 201.5 ops/s), CPU time составил **6.39 ms/op** (vs JDK 9.75 ms/op и Feign 9.42 ms/op).
5. *Документация и артефакты:*
   - В `BENCHMARKS.md` добавлены строки Netty во все таблицы (Throughput, Allocations, CPU Time) и новый пункт в Key Insights.
   - `docs/0.12.0/ADR-0070-http-engine-netty-transport.md` переведен в статус **Accepted**, Фаза 3 отмечена завершенной.
   - В `ROADMAP.md` зафиксировано завершение флагманского эпика Netty цикла 0.12.0.
   - В `.agent/mailbox.md` отправлен детальный ответ Антону на его вопросы.
6. *Гейты:* **1,737 / 1,737** green во всех 6 модулях (root 1609 + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + benchmarks 37). Javadoc чист под `-Werror`. JaCoCo ядра: BRANCH 90.64%, INSTRUCTION 98.19%, LINE 97.84%, COMPLEXITY 88.90%, METHOD/CLASS 100%.

## Контекст для следующей сессии (#326)

1. **Ветка** `agent-loop`.
2. **Highest ADR:** **0070** (`docs/0.12.0/ADR-0070-http-engine-netty-transport.md`), next free **0071**.
3. **Статус эпика Netty Transport Engine (ADR-0070):**
   - Фазы 1, 2 и 3 — ПОЛНОСТЬЮ ЗАВЕРШЕНЫ ✅ (ADR-0070 Accepted, таблицы в `BENCHMARKS.md`).
4. **Кандидаты на сессию #326:**
   - **Кандидат №10 — Java 21+ Pattern Matching & Sealed Architecture Audit:** аудит диспетчеризации, response handler'а и error resolver'а на предмет современных фич Java 21 (pattern matching for switch, record patterns).
   - **Кандидат №11 — Экосистема кодеков:** исследование возможности добавления субмодуля Proto (`codec-protobuf`) или YAML (`codec-yaml`).
5. **Замечание по окружению:**
   - Команды gradle запускать с `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
6. Метрики — гейт **1,737/1,737**; BRANCH 90.64%, INSTRUCTION 98.19%, LINE 97.84%, COMPLEXITY 88.90%, METHOD/CLASS 100%.
