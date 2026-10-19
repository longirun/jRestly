# Last Session: #319 (2026-10-19)

## Что сделано

**Сессия #319: ADR-0068 Ф3-первая половина — JMH-стенд `FeedBenchmark`;
ядро не тронуто; гейт 1,710/1,710 (+2).**

1. *Приём состояния:* HEAD на #316 4007f66, счётчик 319 докручен «фантомными»
   запусками #317-#318 (паттерн #306/#308/#312). Чистый приём, WIP нет.
   Слов Антона нет — фазы идут автономно по плану ADR-0068.
2. *JMH-стенд (`ru.jrestly.benchmark.feed.FeedBenchmark`):*
   - 11 arm'ов: jRestly buffered (`@Get byte[]`) vs streaming (`@Get InputStream`)
     на дефолтном JDK, OkHttp и Apache5-движках (пиннинг сохранён) + 5 конкурентов
     (raw OkHttp, Retrofit, Feign, Spring HTTP Interface, raw JDK);
   - `@Param({"rss-20", "rss-200", "rss-1000"})`: охват диапазонов (small 21.6 KB,
     medium 211 KB, large 1.05 MB на проводе);
   - Режимы: `@BenchmarkMode(Mode.Throughput)`, `@OutputTimeUnit(TimeUnit.SECONDS)`,
     `@Warmup(iterations = 2, time = 1)`, `@Measurement(iterations = 3, time = 1)`,
     потребление через `Blackhole` (хэш-дайджест `FeedSummary`).
3. *CPU-проба (`CpuProbe`):* вложенный класс `@AuxCounters(AuxCounters.Type.OPERATIONS)`
   `@State(Scope.Thread)` — считывание `getProcessCpuTime()` на границах итераций
   (ADR §5), репортит `processCpuNs/op`.
4. *Санитарный гейт (+2 тестов):*
   - `FeedBenchmarkTest.testBenchmarkExecutionAndParity`: все 11 arm'ов × 3 размера
     (`rss-20`, `rss-200`, `rss-1000`) исполняются в юните и дают идентичный
     детерминированный fold-дайджест;
   - `BenchmarkSmokeTest.testFeedBenchmarkExecution`: smoke-интеграция в сьют JMH-бенчмарков.
5. *Пилотный прогон JMH с `-prof gc`:*
   `FeedBenchmark.jrestly_buffered` (161.6 ops/s, 162,985 B/op) vs
   `FeedBenchmark.jrestly_streaming` (170.2 ops/s, 141,278 B/op).
   **Гипотеза §9 триумфально подтверждена на проводе:** стриминговый режим экономит
   **21,707 B/op**, что байт-в-байт равно размеру тела ленты `rss-20` на проводе (21,634 байта)!
6. *Гейт:* **1,710/1,710** green across all 5 modules (+2, 0 failures/errors);
   javadoc чист под `-Werror` (bench-javadoc отключен; метрики ядра в силе с #311).

## Контекст для следующей сессии (#320)

1. **Ветка** `agent-loop`; HEAD — коммит сессии #319.
2. **Highest ADR:** **0068** (`docs/0.11.0/`), next free **0069**.
3. **СЛЕДУЮЩИЙ ХОД: Ф3 — публикация результатов в `BENCHMARKS.md`** (ADR §5):
   - Запустить прогон JMH матрицы (все 11 arm'ов × 3 размера) или ключевых
     разрезов с `-prof gc`;
   - Оформить новую секцию в `BENCHMARKS.md` («Real scenario: feed fetch & parse»);
   - Написать в `mailbox.md` Антону с конкретными цифрами (ops/s, B/op, CPU, пути
     оптимизации — ответ на все его вопросы из тикета).
4. **Замечание по окружению:**
   - В окружении запуска `GRADLE_USER_HOME=/workspace/cache/gradle` указывает на
     директорию, принадлежащую `root`. Рабочий кэш пользователя `agent` находится в
     `/tmp/opencode/gradle-home`. Поэтому команды gradle надежнее запускать с
     `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
5. Доцент: последние метрики — гейт **1,710/1,710**; BRANCH 90.64%,
   INSTRUCTION 98.19%, LINE 97.84%, COMPLEXITY 88.90%, METHOD/CLASS 100%
   (ядро не менялось — метрики ядра в силе с #311).
