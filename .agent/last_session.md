# Handover Note: Сессия 67 (ADR-0015 шаг 4 — async twin закрыт, 276/276; следующий ход — шаг 5: документация)

## Что сделано в этой сессии
- **Ход:** шаг 4 эпика ADR-0015 — контракт async twin (Aspect 4, test
  strategy сценарий 2), TDD. Входящих нет (автономный ход; живые нитки
  mailbox'а — 3 — на месте). Чисто тестовый шаг, как предсказано в
  handover'е: код уже был в месте (`sendAsync`-ветка + builder-детекция —
  шаг 2; общий хвост `processResponse` + exception mapping
  `exchangeAsync` — шаг 3); тесты закрепили контракт.
- **Новый тест-класс** `HttpTemplateStreamAsyncTest`
  (`src/test/java/ru/jrestly/http/`) — 6 тестов: (1) успех = future
  несёт сам stream — `assertSame` на объект из wire, `bytesRead == 0`,
  не закрыт, codec-free (codec, бросаемый при использовании); (2) те же
  байты до EOF (сценарий 2 «the same bytes»); (3) пустое тело на
  успехе — future всё равно завершается stream'ом (не null), EOF на
  первом read — фиксирует семантику из «Важно» handover'а #66; (4)
  `@OnError` — join() → `CompletionException`, cause —
  `HandledException` (decoded-объект + статус + заголовки), stream
  поеден полностью и закрыт; (5) unexpected status —
  `UnexpectedStatusException` с полным raw payload, stream поеден; (6)
  транспортный сбой — тот же `RuntimeException`, что бросил бы sync
  twin (`IOException` в cause). Фикстуры — house-style: `StubHttpClient`
  -подкласс (`sendAsync` отвечает из очереди completed/failed future'ов —
  вся цепочка детерминированна на вызывающем потоке), `TrackingStream`
  (`bytesRead`/`closed`), `StubCodec`, no-op logger. Cause join'а — через
  house-фикстуру `TestFutures.causeOf` (`ru.jrestly.fixtures`,
  используется в `CompletableFutureReturnTest`) — переиспользовал, не
  изобретал.
- **Граблина дня (JDK-факт — пробом, не из головы):** JDK 21
  `InputStream.readAllBytes()` **не закрывает** stream — проб
  `/tmp/opencode/ReadAllBytesProbe.java` (closed == false). Первая
  версия ассерции была неверной — заменил byte count по `bytesRead` +
  `assertFalse(closed)` с комментарием. Первый прогон: 5 из 6 зелёные —
  подтверждение «чисто тестовый шаг».
- **Гейты:** `./gradlew test --rerun` — **276/276** (39 классов, 0
  fail/err по XML; 270 + 6). `compileJava compileTestJava --rerun-tasks`
  — **0 warnings** (перманентные `-Xlint:all -Werror`; оба compile-
  задания реально отработали). build.gradle не тронут — 0.8.0 / 0
  `<dependency>` (POM не перегенерирован — граблина #38).
- **DRAFT:** шаг 4 → `[x]`. Mailbox не трогал (входящих нет; по
  существу нечего — шаг записан в handover).

## Контекст для следующей сессии (#68)

### Состояние: эпик «response streaming» — шаг 5/6
- **Шаг 5 — документация** (ADR-0015 Aspect 6 + test strategy сценарий 8):
  (a) `ControllerValidator` — `InputStream`-возврат валидируется чисто:
  факт — `validateMethod` НЕ смотрит return type вообще (только HTTP-
  аннотация + параметры), так что код менять не нужно — гейт = тест-
  фиксация (house-style: тест-класс валидатора есть, добавить кейс
  `InputStream`-метода — sync + async); (b) Javadoc-контракт на
  затронутую публичную поверхность — **лаконично** (Антон, 2026-10-14):
  контракт — в ADR-0015 Aspect 3+4+5 (success as-is; error-пути едят
  stream; streaming payload line; lifecycle: EOF/close, timeout = до
  заголовков, interrupt → `IOException` на read-месте) — Javadoc
  пересылает, а не пересказывает; поверхность: `HttpTemplate`
  (class-level — уже описывает async twin; @return-блок `exchange()`,
  `setStreamBody` — уже есть с ADR-ссылкой), `WireResponse` (record —
  инвариант «ровно один из body/stream non-null»), possibly
  `HttpTemplateBuilder` (если есть зацепка на return type); (c) README —
  feature-буллет «response streaming: `InputStream` return type» + нота
  «JSON by return type» (POJO = codec-биндинг, `InputStream` = байты,
  codec не трогается; `String` = raw). Gate: javadoc 0 под
  `-Xdoclint:all -Werror` (lint-инит-скрипт `/tmp/opencode/lint-init.
  gradle`), suite green.
- **Потом:** шаг 6 (дистилляция в ROADMAP + зачистка DRAFT; свежий
  `test --rerun`; после эпика — активная зона: ADR-0016 SSE/line events
  на этом шве, параметры запроса, пайплайн, test-javadoc).
- **ADR-0015 — source of truth** (`docs/0.9.0/ADR-0015-response-
  streaming-inputstream-return.md`): аспекты 1–6 + Shape + test strategy
  (8 сценариев). Статус: закрыты 2 (async twin, #67), 3/4/5 (wire+
  контракт, #65/#66), 6 частично (probe-codec «не трогается» закреплён
  «codec, бросаемый при использовании» в sync + async контракт-тестах),
  8 частично (validator-тест — шаг 5). Остаётся: 1 — базовый 4 MiB
  byte-exact download (WireMock-уровень, прецедент 0.6.0-стриминг-теста)
  + 7 — server disconnect mid-body → `IOException` на read-месте —
  кандидаты на малое дополнение в рамках шагов 5/6 (решение — на
  сессии; контур шагов DRAFT менять не обязательно, если решим
  включить в шаг 5).
- **Важно:** полный `exchange()` на stream-успехе возвращает сам stream
  (as-is, codec-free) — не null; пустое тело на успехе — stream (не
  null) — EOF при первом read. Async twin: future несёт то же;
  exception mapping — cause join'а = тот же класс, что бросил бы sync
  twin (house-фикстура `TestFutures.causeOf`).

### Грабли (актуальный набор)
- **JDK 21 `InputStream.readAllBytes()` НЕ закрывает stream** (#67,
  пробом, `/tmp/opencode/ReadAllBytesProbe.java`): ассерции про
  «поеденный» stream — по `bytesRead`/`closed`, а не «readAllBytes сам
  закроет».
- **JDK 21 `thenApply` распаксовывает `CompletionException`** (#66,
  пробом): mapping видит plain cause; обёртка в future — один
  `RuntimeException`. CE-цепи проверять пробом, не из головы.
- **Generic `exchange()` в метод-референсе не инферируется** (#67,
  компилятором): `causeOf(t.exchange()::join)` — «invalid method
  reference»; нужен локальный `CompletableFuture<Object> future =
  t.exchange();` + `future::join`. Varargs generic-типа
  (`CompletableFuture<HttpResponse<?>>...`) — два unchecked-warning'а —
  под `-Werror` падают; в stub'ах — `List<...>` + двойной cast
  `(CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>)`.
- **`HttpHeaders.of(Map, BiPredicate)` — второй аргумент = фильтр на
  (name, value), не name-matcher** (#65, пробом): в stub'ах —
  `(name, value) -> true`.
- **`java.net.http.HttpClient` — абстрактный КЛАСС** (не интерфейс):
  stub = подкласс; 12 abstract-методов (в т.ч. оба `sendAsync` +
  `send`) — копия stub'а из `HttpTemplateStreamWireTest`/
  `HttpTemplateStreamContractTest`.
- **JDK-контракт стриминга — в ADR-0015, цитаты не пересказывать:**
  timeout = до заголовков; interrupt на read → `IOException` + cancel;
  ранний close → соединение без reuse. Не «улучшать» без нового ADR.
- **`WireResponse` — record, ABI-чуткий:** canonical 4-arg + secondary
  3-arg; инвариант «ровно один из `body`/`stream` non-null» (fill —
  `HttpTemplate.toWireResponse` — единственное место в main).
- **`toWireResponse` — один generic-метод, не overload'ы** (erasure
  clash на `HttpResponse<T>`); cast `(byte[]) body` — `@SuppressWarnings
  (unchecked)` у метода.
- **Gates без отрицательного теста — не гейты** (#58/#59): step-гейт
  проверяется и отрицательной стороной (в #67 — codec, бросаемый при
  использовании, на success-пути; `bytesRead == 0` на as-is стриме).
- **POM — не артефакт `test`** (#38): после изменения build.gradle —
  `generatePomFileForMavenJavaPublication`; zero-dep: 0
  `<dependency>`. (build.gradle в этом эпике не меняется до
  cycle-шага 0.9.0.)
- **Changelog — навигатор, не зеркало** (#40): entry пишем за
  release-циклом 0.9.0; 0.7.0/0.8.0 — unpublished, не трогать.
- **Цикл — упаковка, не фича** (#36): ADR-0015 — feature-ADR;
  cycle-ADR — позже, по прецеденту ADR-0012/0014.
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон);
  body — гейты-факты.
- **`./gradlew test --rerun`** — «UP-TO-DATE ≠ мой прогон».
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/
  test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас — 276).
- **«2 s» в выводе Gradle — не индульгенция** (#58): compile-замер — по
  логу (`N actionable tasks: N executed` + 0 warning-строк).
- **Факты — по коду/дереву/jar, не по имени/памяти** (#47–#57,
  #64–#67): JDK — `javap -cp /opt/java/openjdk/jmods <fqcn>` / Oracle
  javadoc 21 (webfetch) / быстрый прогон пробного main-класса
  (применено в #67: ReadAllBytesProbe).
- **DRAFT/mailbox/дневник переносы строк: edit-tool oldString — только
  с фактическим байтовым текстом** (#48–#63, #65, #67): проверять
  `sed -n`/`cat -A` (в #67 промах по ведущему пробелу строки).
- **ADR-номера:** 0013/0014 — закрыты; 0015 — response streaming (шаг
  4/6 закрыт); 0016 — свободен (резерв: SSE/line events для ЛЛМ).
- **«Итог дня» — живая секция:** дописывать фрагментом « + **...
  (сессия #N)**», не переписывать; сессионные заметки — до «Итога
  дня», не после.
- **Чужой незакоммиченный diff — валидировать, не слепить** (#45).
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние
  2–3 сообщения оставляем (сейчас 3: «больше комментариев нет»,
  входящее 09:10, ответ #62 — все живые).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш
   пережил сессии #36–#67).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 67 → после следующей 68).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`…`0.9.0/` (0.9.0 —
   ADR-0015, метка provisional); lint-инит-скрипт
   `/tmp/opencode/lint-init.gradle`, classpath `/tmp/opencode/cp.txt`,
   логи замеров — в `/tmp/opencode/` (включая `CephProbe.java` —
   thenApply/CE-проб #66, `ReadAllBytesProbe.java` — readAllBytes/
   close-проб #67).
7. Системный JDK: `/opt/java/openjdk` (Temurin 21) — `javadoc`-бинарь,
   `javap -cp /opt/java/openjdk/jmods <fqcn>` (снять подпись JDK-API без
   src.zip); Oracle javadoc 21 —
   `docs.oracle.com/en/java/javase/21/docs/api/java.net.http/...`
   (webfetch); быстрый JDK-проб — `java SingleFile.java` в
   `/tmp/opencode/`.

### Состояние git
- HEAD: коммит сессии #67 (async twin: тесты + DRAFT + память) поверх
  `034ae03` (#66), `e483689` (#65), `3246c48` (#64 ADR-0015 + DRAFT-
  эпик), `8fbbcf6` (#63), `0e9b637` (#62), `faa7f00` (Антон). Всё на
  `agent-loop`. Последний тег `v0.6.1`.
- Версия в build.gradle: **0.8.0** (не меняется до release-цикл-шага
  0.9.0). POM (untracked): 0.8.0, 0 `<dependency>`.
- Тесты: **276/276** (39 классов; свежий прогон #67, `test --rerun`).
- Warnings: javac main+test — **0/0**, javadoc — **0** (перманентные
  `-Werror`-гейты; compile-задания реально отработали в #67).
- CHANGELOG.md: entry 0.8.0 + 0.7.0 — оба unpublished, в этом эпике не
  трогаются.
- DRAFT: эпик 6 шагов (шаги 1–4 [x]); активная зона — SSE-0016/
  параметры/пайплайн + test-javadoc.
- Mailbox: 3 сообщения, все живые (входящих нет).
