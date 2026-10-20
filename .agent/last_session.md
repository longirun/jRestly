# Last Session: #341 (2026-10-20)

## Что сделано

**Сессия #341: Netty добавлен в concurrency-матрицу (Anton's catch) + ADR-0081 — измеримые критерии сложности и карта навигации без ЛЛМ.**

1. *Вход:* mailbox (2026-10-20) — «в новом тесте не увидел нетти, хотя он главный бенефициар» + «есть какие-то критерии сложности, а то не разберёшься без ЛЛМ?». HEAD `e70ec7d`.
2. *Netty:* `ConcurrentLoadNetworkBenchmark` действительно предшествовал `engine-netty` (Netty был только в `FeedBenchmark`, #325). Добавлены `jrestlyNettyClient` + `getUser`/`ping` arms; `BenchmarkSmokeTest` теперь проверяет все 10 clients.
3. *Load-матрица run #341* (`-f 1 -wi 1 -i 2`, `-t 1/4/16/64`, `getUser`): Netty 820/770/871/1001 ops/s — JDK-группа. На sub-KB DTO loopback event-loop handoff + pooled-buffer bookkeeping дороже OkHttp/Apache5; зато +35% к default JDK на 64t, 64 потока — 0 ошибок. Настоящие победы Netty — большие тела/аллокации (ADR-0070). Таблица в `BENCHMARKS.md` («Netty in the concurrency matrix, run #341»), ADR-0080 → Amendment #341.
4. *ADR-0081* (`docs/0.13.0/ADR-0081-internal-complexity-criteria-and-navigation-map.md`, Accepted): 13 критериев (C1–C13: physical lines, JaCoCo WMC, method complexity/length/nesting/params, public surface, fan-out, package cycles, javadoc/package-info, collaborator+suite, no static mutable state, zero-dep + coverage) с green/yellow/red и ratchet-правилом. Scorecard: `HttpTemplate` 2,286/461 red, `HttpTemplateBuilder` 1,516/407 red, `MethodMetadata` 1,140/257 red, `ControllerValidator` 871/279 red. Navigation map: 5-step request trace + concern→files→tests table.
5. *Гейт:* benchmarks 10/10 (smoke), полный **1,835/1,835** по XML (0 failures/errors; root/субмодули up-to-date — main-код не менялся).
6. *Highest ADR:* **0081**, next free **0082**. ROADMAP banner обновлён.

## Контекст для следующей сессии (#342)

1. **Ветка** `agent-loop`, HEAD — коммит #341.
2. **Приоритет:** второй структурный шаг — декомпозиция `HttpTemplateBuilder` (1,516 строк / WMC 407 / 110 missed) по ADR-0081:
   - (a) URI assembly (`createUrl`/`createUri`/query) — самый изолированный кусок;
   - (b) header/cookie merge;
   - (c) body/multipart assembly;
   - (d) resilience wiring.
   Каждый шаг: TDD behavior-pins, публичный API не трогать, после шага — C1/C2 вниз.
3. Альтернативы: split `ControllerValidator.validateMethod` (C5 target ≤ 20); аудит `YamlCodec.deserialize` (закладка #339: 27–60 µs / 22.7 KB/op).
4. *Окружение:* `GRADLE_USER_HOME=/tmp/opencode/gradle-home`; JMH concurrency: `./gradlew :benchmarks:jmh -PjmhArgs="-f 1 -wi 1 -i 2 -t 64 -rf json -rff /tmp/x.json .*ConcurrentLoadNetworkBenchmark\..*GetUser"`. Host: 4 vCPU visible / 2 CPU cgroup quota — только throughput, без AverageTime-выводов.
5. Метрики: гейт **1,835/1,835**; JaCoCo — рекорд #338 (Branch 90.81–90.85%), с тех пор main не менялся. Next free ADR **0082**.
