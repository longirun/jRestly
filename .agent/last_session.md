# Handover Note: Сессия 64 (концепт ADR-0015 — response streaming, `InputStream` return type; эпик 6 шагов открыт, шаг 1/6 закрыт; следующий ход — шаг 2: wire seam)

## Что сделано в этой сессии
- **Ход:** концепт-сессия по прецеденту #36/#41 (ADR + эпик в DRAFT, без кода)
  — первый эргономики-эпик направления #3 (входящее Антона 2026-10-14,
  основной курс).
- **Решение (моё): стриминг первым**, а не параметры/пайплайн: (1) gap
  движка — сегодня всё тело ответа в heap (`send()`/`sendAsync()` —
  `ofByteArray()`, `WireResponse` — `byte[]`); (2) чистая новая поверхность,
  zero behavior change — классический minor; (3) оформляет оговорку Антона
  про «неявную JSON-сериализацию» — **JSON by return type**: `InputStream`
  объявляет зависимость от байтов (codec не трогается), POJO — от codec
  (SPI уже ADR-0001/0007). SSE/ЛЛМ — ADR-0016 на этом шве; параметры и
  пайплайн — следующие циклы.
- **ADR-0015** (`docs/0.9.0/` — новая папка, метка 0.9.0 **provisional** по
  прецеденту ADR-0014; версию фиксирует cycle-ADR — прецедент ADR-0012;
  ADR-0015 — feature-ADR, не cycle-ADR): 6 аспектов — (1) поверхность:
  return type IS the contract (прецеденты `String`/`void`/
  `CompletableFuture<T>`), `@StreamBody` и wrapper-тип отклонены
  (ADR-0003/0004), async twin — `CompletableFuture<InputStream>`; (2) движок:
  ветка send-path на effective return type (`ofInputStream` vs
  `ofByteArray`), `WireResponse` + компонент `InputStream stream` (ровно
  один из `body`/`stream` non-null — style `WireRequest`), ABI-нейтральный
  secondary 3-arg конструктор, промежуточные хопы (redirect + retry)
  закрывают свой stream; (3) контракт: success bypass codec, error-пути
  (`@OnError`, unexpected status) ПОЕДАЮТ stream и несут объект/полный raw
  payload, decode-ось ADR-0011 — no-op, known gap — codec по-прежнему eager
  на build (lazy — будущий эпик); (4) lifecycle: JDK-контракт как есть
  (EOF/close, ранний close ломает reuse соединения, timeout = до заголовков,
  interrupt → `IOException` на read-месте); (5) logging: payload-строка не
  читает тело, Content-Length из заголовка; (6) retry: пересечения
  ADR-0008/0010/0011. Плюс Shape (seams) и test strategy (8 сценариев).
- **JDK-факты верифицированы** (граблина «сторонние API — по jar/javap»):
  `javap` по `/opt/java/openjdk/jmods` (наличие `ofInputStream`/`ofLines`/
  `fromLineSubscriber`) + Oracle javadoc 21 — четыре цитаты в ADR
  (`BodyHandlers.ofInputStream`, `BodySubscribers.ofInputStream` API/Impl
  note, `HttpRequest.Builder.timeout`).
- **DRAFT:** эпик 6 шагов — (1) концепт [x] → (2) wire seam → (3)
  синх-контракт → (4) async twin → (5) документация → (6) дистилляция;
  активная зона — остаток направления #3 (SSE-0016, параметры, пайплайн) +
  кандидат №2 test-javadoc; «Пометки из mailbox» — живая нить обновлена
  (концепт — сессия #64).
- **Mailbox:** написал Антону (чекпоинт: выбор среза + форма; «если не
  нравится — сейчас самое дешёвое время, код — с шага 2»; оговорка про JSON
  оформлена как «JSON by return type»). Ответ не обязателен для хода.
- **Гейты:** markdown-only; свежий `test --rerun` — **256/256** (0 failures,
  код не менялся — страховочный прогон, прецедент #39); build.gradle не
  тронут (0.8.0); POM не перегенерирован.
- **In-place-гигиена:** постоянная нить «Факты — по коду/дереву» дополнена
  «/jar» + примером #64 (JDK-контракт снят с javap/javadoc, не из головы).

## Контекст для следующей сессии (#65)

### Состояние: эпик «response streaming» — шаг 2/6
- **Шаг 2 — wire seam** (по ADR-0015 Aspect 2 + Shape):
  - `WireResponse` — record: добавить компонент `InputStream stream`
    (четвёртый) + secondary конструктор `WireResponse(int, List<Header>,
    byte[])` → `this(status, headers, body, null)` (ABI-нейтрально; call
    sites main ×1 = `HttpTemplate.toWireResponse:643`, test ×3 —
    `HttpTemplateDecodeProbeTest:43`, `HttpTemplateRetryAfterTest:28,110` —
    останутся source-совместимы без правки);
  - `HttpTemplate` — флага `streamBody` (setter `setStreamBody`), ветка в
    `send()`/`sendAsync()`: `BodyHandlers.ofInputStream()` vs
    `ofByteArray()`; `toWireResponse` — fill stream-компонента (для
    `HttpResponse<InputStream>`);
  - close промежуточных стримов: `executeWithRedirects` (redirect-хоп —
    body предыдущего ответа закрыть перед следующим `send`),
    `executeWithRetries` (retry-попытка — закрыть перед следующей), + async
    twins (`executeAsyncWithRedirects`/`attemptAsync`) — то же по chained
    ответам;
  - `HttpTemplateBuilder` — detection: effective return type
    (unwrapped `CompletableFuture` type argument) == `InputStream` →
    `setStreamBody(true)`;
  - TDD: воспроизводящий тест первым (WireMock: 4 MiB body — прецедент
    0.6.0; redirect 307 со стримом; retry-503 со стримом);
  - гейт: suite green + промежуточный stream закрыт до следующего хопа
    (отрицательная проверка — нить #58 в духе: seam должен
    доказуемо закрывать, не «мы думаем что закрывает»).
- **Потом:** шаг 3 (синх-контракт: `processResponse`-ветка,
  `probePayloadDecode`, logging), шаг 4 (async twin), шаг 5 (документация:
  validator + Javadoc + README), шаг 6 (дистилляция в ROADMAP).
- **ADR-0015 — source of truth** (`docs/0.9.0/ADR-0015-response-streaming-
  inputstream-return.md`): аспекты 1–6 + Shape + test strategy (8
  сценариев — по одному на шаги 2–4, документация — шаг 5).
- **В mailbox может прийти ответ Антона** — проверить в начале сессии
  (чекпоинт-сообщение сессии #64; живая нить «автономия + 3 направления»).

### Грабли (актуальный набор)
- **JDK-контракт стриминга — в ADR, цитаты не пересказывать:** timeout =
  до заголовков (javadoc: «response is not received» + ofInputStream
  available после заголовков); interrupt на read → `IOException` + cancel;
  ранний close → соединение без reuse. Не «улучшать» без нового ADR.
- **`WireResponse` — record, ABI-чуткий:** canonical ctor 4-arg + secondary
  3-arg; «ровно один из `body`/`stream` non-null» — invariant в Javadoc
  (style `WireRequest` «unused for multipart»).
- **Гейт без отрицательного теста — не гейт** (сессии #58/#59): step-гейт
  проверяется и отрицательной стороной.
- **POM — не артефакт `test`** (сессия #38): после изменения build.gradle —
  `generatePomFileForMavenJavaPublication`; zero-dep: 0 `<dependency>`.
  (build.gradle в этом эпике не меняется до cycle-шага.)
- **Changelog — навигатор, не зеркало** (сессия #40): в этом эпике entry не
  пишем (он — за release-циклом); 0.7.0/0.8.0 — unpublished, не трогать.
- **Цикл — упаковка, не фича** (сессия #36): ADR-0015 — feature-ADR
  (проектирует), не cycle-ADR; cycle-ADR — позже, по прецеденту ADR-0012/0014.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон, с #33);
  body — гейты-факты. Форма концепт-коммита — прецедент #60/#64
  (`docs: … concept (…) + DRAFT epic with N steps`).
- **`./gradlew test --rerun`** — «UP-TO-DATE ≠ мой прогон».
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/
  test/*.xml | awk -F'"' '{s+=$2} END{print s}'`.
- **«2 s» в выводе Gradle — не индульгенция** (сессия #58): compile-замер —
  по логу.
- **Факты — по коду/дереву/jar, не по имени/памяти** (сессии #47–#57, #64):
  перед ADR/entry/секцией ROADMAP сверять по `git diff`/`git show`/
  `grep -n`; JDK — `javap` по jmods / Oracle javadoc 21.
- **DRAFT/ROADMAP-переносы строк: edit-tool oldString — только с
  фактическим байтовым текстом** (сессии #48–#63): проверять `sed -n`/
  `tail`/`cat -A`.
- **ADR-номера:** 0013/0014 — закрыты; **0015 — response streaming
  (открыт в #64)**; **0016 — свободен** (резерв: SSE/line events для ЛЛМ,
  по ADR-0015 Consequences).
- **Сторонние API — по jar/javap/тегам, не из головы; NPE ≠ NFE.**
- **«Итог дня» — живая секция:** дописывать фрагментом « + **...
  (сессия #N)**», не переписывать; сессионные заметки — до «Итога дня»,
  не после (#64: блок стоял после — перенесён).
- **Чужой незакоммиченный diff — валидировать, не слепить** (сессия #45).
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние
  2–3 сообщения оставляем (сейчас 4 сообщения, все живые: «больше
  комментариев нет», входящее 09:10, ответы #62 и #64).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш
   пережил сессии #36–#64).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 64 → после следующей 65).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`…`0.8.0/` + **`0.9.0/`
   (новая: ADR-0015; метка 0.9.0 provisional)**; lint-инит-скрипт
   `/tmp/opencode/lint-init.gradle`, classpath `/tmp/opencode/cp.txt`,
   логи замеров — в `/tmp/opencode/`.
7. Системный JDK: `/opt/java/openjdk` (Temurin 21) — `javadoc`-бинарь для
   прямых замеров; **`javap -cp /opt/java/openjdk/jmods <fqcn>`** — способ
   снять подпись JDK-API без src.zip (применён в #64); Oracle javadoc 21 —
   `docs.oracle.com/en/java/javase/21/docs/api/java.net.http/...` (webfetch).

### Состояние git
- HEAD: коммит сессии #64 (ADR-0015 + DRAFT-эпик + память) поверх
  `8fbbcf6` (#63, дистилляция цикла 0.8.0), `0e9b637` (#62, bump 0.8.0),
  `faa7f00` (Антон), `4df4f0c` (#61), `81fbabd` (#60). Всё на `agent-loop`.
  Последний тег `v0.6.1`.
- Версия в build.gradle: **0.8.0** (не меняется до release-цикл-шага 0.9.0).
  POM `pom-default.xml` (untracked): 0.8.0, 0 `<dependency>`.
- Тесты: **256/256** (36 классов; свежий прогон #64, markdown-only).
- Warnings: javac main+test — **0/0**, javadoc — **0** (перманентные
  `-Werror`-гейты в build.gradle; эпик не трогал код).
- CHANGELOG.md: entry 0.8.0 + 0.7.0 — оба unpublished, в этом эпике не
  трогаются.
- DRAFT: эпик 6 шагов (шаг 1 [x]); активная зона — SSE-0016/параметры/
  пайплайн + test-javadoc.
- Mailbox: 4 сообщения, все живые (чекпоинт #64 ждёт необязательного
  ответа).
