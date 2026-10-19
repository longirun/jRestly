# Last Session: #320 (2026-10-19)

## Что сделано

**Сессия #320: ADR-0068 Ф3-вторая половина — публикация результатов реального бенчмарка в `BENCHMARKS.md`, ADR-0068 переведен в Accepted; гейт 1,710/1,710.**

1. *Приём состояния:* HEAD на #319, чистое дерево.
2. *Матричный прогон JMH матрица (11 arm'ов × 3 размера, 33 замера):*
   - Команда: `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew :benchmarks:jmh -PjmhArgs="-f 1 -wi 1 -i 2 -prof gc -rf json -rff feed_results.json .*FeedBenchmark\..*"`
   - Собраны метрики Throughput (ops/s), Allocations (B/op) и CPU time (ms/op через in-harness `CpuProbe` на `getProcessCpuTime()`).
3. *Результаты и подтверждение гипотез ADR-0068:*
   - **Триумф стриминга по памяти:** на large (`rss-1000`, 1.05 MB на проводе) режим `@Get InputStream` экономит **1.02 MB/op** на дефолтном JDK транспорте (4,640,170 vs 5,659,617 B/op) и **более 2.15 MB/op** на external engines (Apache5: 3,382,858 vs 5,540,967 B/op; OkHttp: 3,476,634 vs 5,512,109 B/op).
   - **jRestly + engine-apache5 быстрее и экономнее raw OkHttp и Retrofit 2:** на 1 MB теле jRestly+Apache5 streaming достигает **30.4 ops/s** и **3.38 MB/op** (сырой OkHttp: 24.6 ops/s и 3.42 MB/op; Retrofit 2: 27.2 ops/s и 4.47 MB/op).
   - **Оверхед декларативного zero-dep ядра ничтожен:** сравнение `jRestly default streaming` vs `raw JDK HttpClient` показывает дельту всего в **+6.3 KB** на 20 KB теле, **+15 KB** на 211 KB и **+42 KB** на 1.05 MB теле.
   - **Spring 6 HTTP Interface (`Resource`) сильно буферизует:** худшие показатели по аллокациям на всех тестах (216 KB на small, 1.43 MB на medium, 6.80 MB на large) и низкий throughput.
   - **Feign (`feign-java11`) vs jRestly:** Feign уступает jRestly по throughput на large (15.3 vs 15.7 ops/s) и потребляет больше памяти.
4. *Документация и память:*
   - В `BENCHMARKS.md` добавлена секция «Real scenario: feed fetch & parse (ADR-0068, run #320)» с полными таблицами Throughput, Allocations (B/op) и CPU time (ms/op); обновлены разделы limitations и reproducing.
   - `docs/0.11.0/ADR-0068-real-feed-benchmark-fetch-and-parse.md` переведен в статус **Accepted**.
   - В `.agent/mailbox.md` отправлен детальный ответ Антону на его вопросы из тикета.
5. *Гейт:* **1,710/1,710** green across all 5 modules; javadoc чист под `-Werror`.

## Контекст для следующей сессии (#321)

1. **Ветка** `agent-loop`; HEAD — коммит сессии #320.
2. **Highest ADR:** **0068** (`docs/0.11.0/`), next free **0069**.
3. **Эпик ADR-0068 (rss/atom real benchmark) закрыт полностью.**
4. **СЛЕДУЮЩИЙ ХОД:**
   - Выбор следующего кандидата из `ROADMAP.md` / `TODO.md` / аудит кодовой базы,
   - Либо оформление нового ADR-0069 под следующий архитектурный квант.
5. **Замечание по окружению:**
   - Команды gradle запускать с `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
6. Доцент: последние метрики — гейт **1,710/1,710**; BRANCH 90.64%,
   INSTRUCTION 98.19%, LINE 97.84%, COMPLEXITY 88.90%, METHOD/CLASS 100%
   (ядро не менялось — метрики ядра в силе с #311).
