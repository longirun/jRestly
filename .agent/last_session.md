# Last Session: #345 (2026-10-20)

## Что сделано

**Сессия #345: структурная волна 2 (ADR-0081), шаг (d) — resilience wiring вынесен из `HttpTemplateBuilder` в `ResilienceComposer` (ADR-0085). Волна 2 закрыта.**

1. *Вход:* HEAD `2cdae9d` (#344), чистое дерево (только счётчик 345), mailbox без новых сообщений с #341.
2. *Срез:* `retryPolicy(ModuleInfo, MethodMetadata)` — методный `@Retry` заменяет клиентскую политику; `timeout(MethodMetadata)`; `circuitBreaker(...)` и `rateLimiter(...)` — enabled annotation wins, disabled opt-out, client-level fallback; private `circuitBreakerFromAnnotation`/`rateLimiterFromAnnotation` строят config и берут instance из registry.
3. *Форма:* package-private `final`, private constructor, stateless. Статические default-регистраторы переехали вместе с мапперами. `HttpEngineOptions` сознательно оставлен в builder'е — это transfer уже resolved-значений, не policy.
4. *Швы перенесены, не продублированы:* `build()` зовёт `ResilienceComposer.*`; шесть старых методов, два поля и шесть импортов удалены. Client-level политики возвращаются теми же инстансами (`assertSame`).
5. *Инвариант вместо guard'а:* `build()` дереференсит `moduleInfo.getEnvironment()` до resilience-вызовов, поэтому `moduleInfo != null` в circuit-breaker/rate-limiter fallback'ах недостижим — убран (−2 ветки). `methodMetadata != null` сохранён. Static-registry fallback сохранён для кастомных `ModuleInfo` с null-регистраторами (`SimpleModuleInfo` их нормализует).
6. *TDD:* `ResilienceComposerTest` до класса (compile-red), 13 тестов. Первый прогон упал на `MethodMetadata.parse` — фикстурам нужен HTTP-verb; добавил `@Get` (правка фикстуры, не ожиданий против кода).
7. *Сьют:* 13 тестов, **22/22 branches, 38/38 lines, 18/18 complexity, 122/122 instructions, 0 missed** (C11-планка).
8. *Замеры:* builder **850 → 779** (−71) / WMC **205 → 186** (−19) / missed **66 → 56** (−10); `ResilienceComposer` 124 / 18 / **0**. Branch-вселенная 4,458 → 4,450 (−8 net) при +22 слотах. Волна 2: 1,516 → 779 строк (−49%), WMC 407 → 186 (−54%).
9. *ADR-0085* (`docs/0.13.0/ADR-0085-resilience-composer-extraction.md`, Accepted); navigation map ADR-0081: resilience → `http/ResilienceComposer` + `resilience/*`, wave 2 complete; `package-info` упоминает коллаборатор. Highest ADR **0085**, next free **0086**.
10. *Гейт:* полный **1,903/1,903** (root **1,739** = 1,726 + 13; субмодули 164), 0 failures/errors; `:javadoc` чисто. JaCoCo: **Branch 92.04% (4,096/4,450) — рекорд**, **Complexity 90.55% (3,372/3,724) — рекорд**, Instruction 98.37%, Line 98.05%, Class/Method 100%.

## Контекст для следующей сессии (#346)

1. **Ветка** `agent-loop`, HEAD — коммит #345.
2. **Приоритет:** split `ControllerValidator.validateMethod` (C5 ≤ 20) — следующий кандидат ADR-0081. Затем аудит `YamlCodec.deserialize` (закладка #339: 27–60 µs / 22.7 KB/op).
3. Альтернатива: новая структурная волна по scorecard — `HttpTemplate` (2,221 / WMC 441) или `MethodMetadata` (WMC ~257).
4. *Окружение:* `GRADLE_USER_HOME=/tmp/opencode/gradle-home`; полный гейт `./gradlew test` ~2 мин; `:javadoc` отдельной командой. JaCoCo — `build/reports/jacoco/test/jacocoTestReport.xml`; тесты — `**/build/test-results/**/TEST-*.xml`.
5. *Метрики:* гейт **1,903/1,903**; JaCoCo Branch **92.04%** / Complexity **90.55%** — новые рекорды. Next free ADR **0086**.
