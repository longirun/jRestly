# Last Session: #311 (2026-10-19)

## Что сделано

**Сессия #311: приём WIP прерванной #310 (верифицирован, закоммичен c4f620f) +
хвост пула 1-branch-файлов (GsonCodec закрыт, MoshiCodec = defensive); гейт
1,689/1,689; BRANCH 90.64% — рекорд.**

1. *Приём WIP #310 (паттерн #155/#186/#254):* в дереве лежал полный комплект
   #310 без коммита (счётчик 311 + DRAFT-блок + 7 тест-файлов:
   CircuitBreakerTest/RateLimiterTest/TracePropagatorTest/
   MultipartPartFlexibilityTest/OnErrorParserTest/WireResponseUnitTest/
   HttpEngineResolverTest). Диффы прочитаны байт-в-байт (все тестовые, main
   не тронут), гейт перепроверен (1,687/1,687, BRANCH 90.62%) → закоммичено
   как есть; DRAFT #310 блок почищен от искажённых символов.
2. *Хвост пула:* `GsonCodec.L111` (ленивый pretty-twin cache-hit) закрыт
   тестом `GsonCodecTest.prettyPrintReusesCachedPrettyTwin` (второй
   prettyPrint на том же инстансе); `MoshiCodec.L167` switch-default —
   структурно недостижимая defensive (значение-позиция исчерпывает 6 case'ов;
   пустой документ → Moshi `peek()` бросает EOFException, ловится до
   default) — оставлена осознанно (паттерн #268/#309 ApiKeyAuth); добавлен
   контрактный предохранитель `MoshiCodecTest.prettyPrintEmptyAndBlankPayloadsReturnUnchanged`.
3. *Гейты:* **1,689/1,689** green во всех 5 модулях (+2, 0 failures/errors),
   javadoc чист под `-Werror`. JaCoCo: **BRANCH 4,018/4,433 = 90.64%**,
   INSTRUCTION **98.19%**, LINE **97.84%**, COMPLEXITY **88.90%** — рекорды;
   METHOD 1455/1455 и CLASS 149/149 — 100%.
4. Сессионный счётчик: **311 — закоммичен. СЛЕДУЮЩИЙ (#312) — докрутить до 312
   в начале сессии.**

## Контекст для следующей сессии (#312)

1. **Ветка** `agent-loop`; HEAD — коммит сессии #311.
2. **Highest ADR:** **0067** (`docs/0.11.0/`), next free **0068**.
3. **СЛЕДУЮЩИЙ ХОД:** **Кандидат №7 (rss/atom бенчмарк-эпик) разблокирован** —
   Антон ответил (mailbox): «настоящая, не синтетическая задача», парсер НЕ в
   ядре (конфликт с минималистичностью). Первый шаг — концепт/стенд-дизайн
   ADR-0068: сценарий fetch-and-parse на реальном RSS/Atom-стенде, парсер в
   benchmarks-скоупе (не core), конкуренты (raw OkHttp / Retrofit / Feign /
   Spring HTTP Interfaces), метрики ops/sec + CPU + mem. Альтернатива:
   свободный аудит.
4. **Грабли/паттерны (важно!):**
   - JaCoCo — источник правды: `build/reports/jacoco/test/jacocoTestReport.xml`
     (только core-модуль); счётчик тестов — по `TEST-*.xml` **всех** модулей
     (core 1,609 + engine-okhttp 25 + engine-apache5 25 + codec-jackson-xml 15 +
     benchmarks 15 = 1,689).
   - Полный гейт `./gradlew test javadoc` ~1 мин;
     `GRADLE_USER_HOME=/tmp/opencode/gradle-home` обязателен; фильтр
     `:test --tests 'X'` (ведущий `:` обязателен).
   - Moshi `peek()` на пустом/исчерпанном документе бросает `EOFException`
     (IOException), а НЕ возвращает END_DOCUMENT — «пустой payload» не
     покрывает switch-default ветви.
   - Пул 1-2-branch-файлов закрыт; остаток = джиттер-зависимые
     (`RateLimiters` L66/L123, `RetryPolicy` L347/L355) + недостижимые
     defensive (`ApiKeyAuth` L127, `MoshiCodec` L167) — не трогать.
   - Часовой пояс контейнера +04 (логи log4j — UTC).
