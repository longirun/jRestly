# DRAFT: активный эпик Request pipeline interceptors & hooks (ADR-0018, 6 шагов; направление #3 — эргономика для современных API)

> Эпик ADR-0018 (`docs/0.9.0/ADR-0018-request-pipeline-interceptors.md`) открыт:
> тонкая настройка пайплайна запросов (RequestInterceptor, ResponseInterceptor,
> RequestContext, ResponseContext, wither-методы WireRequest, zero-cost default path,
> устойчивость и симметрия sync/async).
> Сьюйт: **420/420** (51 класс, 0 fail/err), 0 warnings под перманентными
> `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
> `build.gradle` — 0.8.0 unreleased (не меняется до 0.9.0-цикла).

## Шаги эпика ADR-0018 (тонкая настройка пайплайна запросов)

- [x] **Шаг 1 (сессия #84): концепт-сессия — ADR-0018** (`docs/0.9.0/ADR-0018-request-pipeline-interceptors.md`).
      Дизайн: `RequestInterceptor` (pre-execution request transformation/decoration),
      `ResponseInterceptor` (post-execution observability, metrics, logging, audit hook),
      `RequestContext` и `ResponseContext` (метаданные метода, контроллера, аргументов +
      атрибуты для связи pre/post), wither-методы `WireRequest`, единый call-level
      жизненный цикл, симметрия sync/async twins, zero-cost путь при пустых списках.
- [x] **Шаг 2 (сессия #85): модель и интерфейсы интерцепторов + wither-методы `WireRequest` (TDD).**
      Создан пакет `ru.jrestly.interceptor`: `RequestInterceptor` (с адаптером `of(UnaryOperator)`),
      `ResponseInterceptor`, `RequestContext`, `ResponseContext` (с хелперами `statusCode()` и
      `isSuccess()`), `SimpleRequestContext` (с потокобезопасными атрибутами и защитным копированием `args()`),
      `SimpleResponseContext` (делегирование в RequestContext + метаданные вызова);
      в `WireRequest` добавлены wither-методы (`withHeader`, `withHeaders`, регистронезависимый `withoutHeader`,
      `withUri`, `withMethod`, `withBody`, `withContentType`, `withParts`) и fluent `Builder` (`builder()`, `toBuilder()`);
      сьют: 375 → **390/390** (+15 тестов в 2 новых классах `WireRequestTest` и `RequestContextTest`),
      0 warnings под `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
- [x] **Шаг 3 (сессия #86): интеграция в конфигурацию клиента (`ModuleInfo`, `SimpleModuleInfo`, `JRestlyClient.Builder`).**
      Добавление `getRequestInterceptors()` и `getResponseInterceptors()` с дефолтными реализациями `List.of()` в `ModuleInfo`;
      методы `addRequestInterceptor(RequestInterceptor)`, `addRequestInterceptor(UnaryOperator<WireRequest>)`,
      `requestInterceptors(List)`, `addResponseInterceptor(ResponseInterceptor)`, `responseInterceptors(List)`
      в `SimpleModuleInfo.Builder` и `JRestlyClient.Builder` с защитным копированием и строгой non-null валидацией;
      проброс метаданных вызова (`method`, `controller`, `args`) и списков интерцепторов в `HttpTemplateBuilder` -> `HttpTemplate`;
      сьют: 390 → **398/398** (+8 тестов в `InterceptorConfigurationTest`, 49 классов, 0 fail/err),
      0 warnings под `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
- [x] **Шаг 4 (сессия #88): исполнение в `HttpTemplate` (sync & async twins execution seam) (TDD).**
      Выполнение цепочки `RequestInterceptor` перед стартом вызова (с защитой от null, сохранением последнего валидного запроса),
      выполнение `ResponseInterceptor` при завершении вызова (в `finally` для sync `exchange()`,
      в `whenComplete` для async `exchangeAsync()`), передача контекста и атрибутов,
      изоляция исключений хуков (logging warning, непрерывность цепочки), сохранение
      стрим-безопасности для `InputStream` / SSE; 13 unit TDD тестов в `RequestPipelineTest`,
      сьют: 398 → **411/411** (+13 тестов в `RequestPipelineTest`, 50 классов, 0 fail/err),
      0 warnings под `-Xlint:all -Werror` и `-Xdoclint:all -Werror`.
- [x] **Шаг 5 (сессия #89): end-to-end WireMock сьюйт, документация и Javadoc.**
      Комплексный сьюйт `RequestPipelineWireMockTest` (9 тестов: dynamic bearer auth токен,
      propagation correlation-id, сбор latency/метрик, обработка ошибок 400 @OnError и 500 unexpected status,
      сетевые сбои connection reset, async CompletableFuture twin, failure isolation при сбоях хуков,
      цепочка UnaryOperator + RequestInterceptor);
      секция «Request pipeline: Interceptors & hooks» и обновление Features в `README.md`;
      0 warnings под перманентными `-Xlint:all -Werror` и `-Xdoclint:all -Werror`;
      сьют: 411 → **420/420** (+9 тестов в `RequestPipelineWireMockTest`, 51 класс, 0 fail/err).
- [ ] **Шаг 6 (следующая сессия): дистилляция в `ROADMAP.md` и зачистка `DRAFT.md`.**
      Фиксация завершения эпика ADR-0018 в `ROADMAP.md`, перевод ADR-0018 в статус Accepted,
      зачистка DRAFT.md, выбор следующего эпика из кандидатов (release-цикл 0.9.0).

## Кандидаты после закрытия эпика ADR-0018

- **Кандидат №1: release-цикл 0.9.0** (упаковка 4 эргономических эпиков: ADR-0015 streaming,
  ADR-0016 SSE/LLM, ADR-0017 params/headers flexibility, ADR-0018 request pipeline).
- **Кандидат №2: test-javadoc** (ADR-0013 Non-goals: «test-source javadoc» вынесен за скобки аудита).

## Постоянная нить (не эпик, не чекбокс)

- **Гигиена проекта — за агентом** (Антон, 2026-10-13: «сам следи тут как
      лучше — в этом файле и вообще на проекте»). Практика: mailbox не растёт
      бесконечно (применять правило «решённый обмен удаляем» в каждой сессии,
      где он применим); устаревшие «Пометки из mailbox» в DRAFT сверять с
      реальностью; мелкие расхождения (версии, статусы) чинить сразу, не копить;
      ROADMAP — верифицируемые утверждения (счётчики тестов, версии, хэши,
      ссылки) сверять с реальностью: счётчик чинят in-place, когда секция сама
      реферит post-gate работу (прецедент сессии #30: 0.x-polish-секция 8→11
      units и 239→242, Retry-секция RetryTest 10→11), а gate-снимок с
      задокументированной дельтой («+1», «+5») — оставляют.
- **Javadoc-контракт — асимметрия поверхностей** (сессия #35): одна и та же
      контракт-деталь (финальное исключение decode-оси = codec-своё unchecked)
      была в Javadoc `@Retry` (атрибут), но не в `RetryPolicy` — при
      «сверить по тексту»-шаге сверять **каждую** поверхность отдельно и
      выравнивать: контракт живёт и на value-классе, и на аннотации.
- **Цикл — упаковка, не фича** (сессия #36): концепт релиз-цикла фиксирует
      контур (версия, механика версионирования по правилам мейнтейнера, форма
      release notes, контур шагов) — дизайн-решения цикла не переосмысляются,
      они стоят в ADR 0007…0011 и в COMPLETE-секциях ROADMAP; концепт-шаг
      и дистилляция-секция ссылаются, а не пересказывают (прецеденты закрыты:
      ROADMAP-секции «Cycle 0.7.0 — COMPLETE ✅» и «Cycle 0.8.0 — COMPLETE ✅»
      — только линки).
- **Changelog — навигатор, не зеркало** (сессия #40): пункт изменения —
      «что изменилось» (1–2 строки) + указатель, где читать детали
      (README-секция / Javadoc / ADR). Поведенческая механика («как
      работает», стоимость, RFC) в changelog не живёт — она уже в
      README/Javadoc/ADR, и дубль — это и есть «ченжлог-документация».
      Entry 0.7.0 переписан до тега (окно дёшево); с 0.7.1 — писать
      сразу в этой форме.
- **После закрытия эпика DRAFT не пустеет** (сессия #39): «после цикла»-
      кандидаты поднимаются в активную зону — следующий ход выбирается из
      них, а DRAFT с пустым эпиком «без кандидатов» — антипаттерн (эпик
      открывается из кандидата, не из ничего).
- **Аудит-сессия = измерения + scope, без кода** (сессия #41, прецедент
      концепт-шагов): каждый site просмотреть по коду, решения — в ADR с
      рационалом (suppress — точечный, не blanket), замеры — gate-снимок
      сессии (пересчитывать на шаге, который трогает, не читать из ADR);
      «устаревших фрагментов нет» — тоже finding (записать, что проверено).
- **Гейт без отрицательного теста — не гейт** (сессия #58): gate в
      build.gradle проверяется и в положительную, и в отрицательную
      сторону — probe-класс без Javadoc обязан ронять сборку, а после
      удаления — давать зелёную; только тогда «-Werror вооружён» —
      факт, а не предположение.
- **Факты — по коду/дереву/jar, не по имени/памяти** (сессии #47–#57,
      #64): перед ADR/entry/секцией ROADMAP сверять по `git diff`/`git
      show`/`grep -n`; сторонние API — по `javap`/javadoc/тегам. В #64 —
      JDK-контракт стриминга (ofInputStream/timeout) снят с Oracle
      javadoc 21 + `javap` по jmods, а не из головы.

Пометки из mailbox (актуальные на 2026-10-14):
- **Автономия + 3 направления (Антон, 2026-10-14 09:10, живая нить):**
      (1) инварианты — zero-dep (с оговоркой про неявную
      JSON-сериализацию), прозрачные методы без Response (ADR-0004),
      анти-комбайн (ADR-0003) — рамка-ограничение; (2) ниша — идеальный
      декларативный HTTP-клиент для современной Java; (3) эргономика для
      современных API (параметры запроса, стриминг, ЛЛМ, пайплайн) —
      основной следующий курс. Ответил (09:5x, сессия #62); концепт
      первого эпика — сессия #64 (ADR-0015: response streaming, байт-
      стриминг первым, оговорка про JSON оформлена как «JSON by return
      type»). Концепт принят (входящее 2026-10-14: «Красава,
      продолжай»; обмен удалён из mailbox по гигиене — решение в
      ADR-0015).
- **Вопрос направления (#26) — закрыт** (Антон, 11:20: предпочтения нет,
  стратегическое планирование; решение зафиксировано в ADR-0011:
  payload-decode первой — и та закрыта; следующий ход — 0.7.0-цикл —
  **закрыт в сессии #39**, дистиллят в ROADMAP).
- **Форма changelog — ЗАКРЫТО (Антон, 2026-10-13):** «Ченжлогом доволен» +
      «больше комментариев нет»; обмен удалён из mailbox по гигиене
      (сессия #42). Entry 0.7.0 — чистый change list с указателями
      (changelog навигатор, не зеркало); entry unpublished.
- **«Больше комментариев нет — продолжай сам» (Антон, 2026-10-13):**
      живые нитки разряжены: changelog — закрыта; `-Werror` (вопрос #41) —
      решение за агентом: gate с `-Werror` (javadocJar так или иначе в
      каждом build, cost 0; выгода — перманентность; снять — одна строка) —
      **применён в шаге 6 (сессия #58)**. Дальше — автономный ход (с
      2026-10-14 09:10 — уже с рамкой из входящего: автономия + 3
      направления, живая нить выше).
- **Версионирование (Антон, 2026-10-13) — применено в шагах 3 (#38, #62):**
      версия в `build.gradle` (сейчас 0.8.0) + названа в коммит-сообщении,
      гит-тега нет (JitPack-координаты — v0.6.1 до тега). Работаем в
      `agent-loop`, наружу не мержим (в ADR-0012 — цитатой).
- **Коммиты — краткие, не дублируют ченджлог** (Антон): subject — суть шага
      одной строкой, body — гейты-факты (применяется с #37: `release 0.7.0:
      bump version in build.gradle (cycle step 3/4)`).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг, делать чаще.
- **ADR-0006** (java.time wire contract) не требуется.
- **Чистку mailbox Антон подтвердил** (2026-10-13) — правило выше в силе.

Правила работы: один эпик в DRAFT.md, шаги — чекбоксы `[ ]`; закрыл →
дистиллируй в ROADMAP.md и зачисти это место.
