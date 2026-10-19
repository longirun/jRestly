# Last Session: #314 (2026-10-19)

## Что сделано

**Сессия #314: ADR-0068 Ф1 исполнена — фикстуры `FeedFixtures` + ленты-маршруты
`BenchmarkHttpServer` + гейт-тесты; ядро не тронуто; гейт 1,697/1,697 (+8).**

1. *Приём состояния:* счётчик 314 докручен «фантомным» запуском без коммита
   (паттерн #306/#308/#312), HEAD на #313 d3da911, WIP нет. Новых слов Антона
   нет — фазы идут автономно (заявлено в mailbox #313).
2. *`FeedFixtures` (`ru.jrestly.benchmark.feed`):* детерминированная генерация
   RSS 2.0/Atom 1.0 × 20/200/1000 из фиксированного пула реалистичных шаблонов:
   CDATA-секции (description/content:encoded), escaped entities
   (`&amp;`/`&#39;`/`&#8212;`), namespaces (`atom:link`, `content:encoded`,
   Atom `xmlns`), RFC 822 (RSS) / RFC 3339 (Atom) даты, `guid`/`id`,
   канальные метаданные; ротация тем/авторов/категорий/тел по индексу.
   Конструктор — на отрицательных/нуле → IAE. Размеры на проводе ровно в
   целевом диапазоне ADR: rss **21.6/211/1057 KB**, atom **16/159/794 KB**
   (small/medium/large) — large впервые даёт «мегабайт на проводе».
3. *Стенд:* 6 ленты-маршрутов `/feed/rss-{20,200,1000}` + `/feed/atom-{20,200,1000}`
   в `BenchmarkHttpServer` — пре-вычисленные byte-тела (record `FeedRoute`
   держит keep-alive + close-варианты; ноль аллокаций на запрос),
   `Content-Type: application/rss+xml|atom+xml; charset=utf-8`; POST → 405,
   неизвестный лент-путь → 404. GZIP-режим — сознательно НЕ в Ф1 (secondary,
   отдельный прогон; сделаю в Ф2/Ф3 при построении матрицы).
4. *Гейт-тесты (+8, только bench-скоп; код ядра не тронут):*
   - `FeedFixturesTest` (5): well-formedness + счёт item/entry через StAX по
     localName; детерминизм byte-в-byte; маркеры сложности (CDATA/entities/
     namespaces/RFC 822/3339); fail-fast IAE.
   - `BenchmarkHttpServerTest` (+3): все 6 маршрутов (фрейминг, Content-Length
     ровно = UTF-8-байты тела, `<?xml`-префикс), сквозной счёт записей,
     латентностный гейт `min < 25 ms` на rss-20 (паттерн ping-гейта).
5. *Грабля строки (записана в ADR §7):* `&mdash;` — HTML entity, в XML не
   определена → валидны только предопределённые + числовые (`&#8212;`).
   Внутри CDATA entities не парсятся — сырой текст легален. Тест маркеров
   сложности поймал оба промаха (заменители в шаблонах были no-op: апострофов
   и амперсандов в исходных текстах не было — маркеры теперь честно в контенте).
6. *ADR-0068:* Status → «Proposed — Ф1 реализована (#314)» + отметка в §7
   с размерами и составом.

## Контекст для следующей сессии (#315)

1. **Ветка** `agent-loop`; HEAD — коммит сессии #314 (Ф1: 2 новых main-файла +
   правка `BenchmarkHttpServer` + 2 тест-файла + ADR-0068 + память; счётчик 314
   закоммичен. СЛЕДУЮЩИЙ (#315) — докрутить до 315).
2. **Highest ADR:** **0068** (`docs/0.11.0/`), next free **0069**.
3. **СЛЕДУЮЩИЙ ХОД:** **ADR-0068 Ф2** — `FeedParser` (StAX `XMLStreamReader`,
   zero-dep, fail-fast на искажённый документ, — ОДИН код для всех клиентов) +
   `FeedEntry` (record: title/link/published→Instant/author/uid) +
   `FeedSummary` (count/latest/digest) + клиенты матрицы (jRestly buffered
   `@Get byte[]` vs streaming `@Get InputStream` — главный разрез; raw OkHttp /
   Retrofit / Feign java11 / Spring RestClient / raw JDK). Санитарная связка:
   парсер читает все 6 фикстур до ожидаемого количества записей (гейт Ф1 уже
   обеспечивает well-formedness по StAX). Закрепить в FeedFixturesTest или в
   парсерном сьюте парity RSS⬌Atom по числу записей.
4. **Грабли/паттерны (важно!):**
   - Полный гейт `./gradlew test javadoc` ~1 мин, `GRADLE_USER_HOME=/tmp/opencode/
     gradle-home` обязателен; фильтр `:test --tests 'X'` (ведущий `:` обязателен;
     для benchmarks — `:benchmarks:test`).
   - JMH: `./gradlew :benchmarks:jmh -PjmhArgs="..."` заменяет аргументы целиком.
   - Фикстуры — пре-вычислять на старте (паттерн SAMPLE_BATCH_USERS_JSON);
     FeedFixtures.rss/atom — чистые функции (пересчитывать не дорого, но в
     сервере тела уже кэшированы в FEED_ROUTES).
   - Счётчик может быть докручен без коммита — сверять HEAD и
     `.agent/session_counter.txt` при входе.
5. Доцент: последние сессионные метрики — гейт **1,697/1,697**, BRANCH 90.64%,
   INSTRUCTION 98.19%, LINE 97.84%, COMPLEXITY 88.90%, METHOD/CLASS — 100%
   (ядро не менялось — метрики ядра в силе с #311; рост только в bench-скопе).
