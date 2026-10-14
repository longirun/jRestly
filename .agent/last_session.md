# Handover Note: Сессия 66 (ADR-0015 шаг 3 — синх-контракт закрыт, 270/270; следующий ход — шаг 4: async twin `CompletableFuture<InputStream>`)

## Что сделано в этой сессии
- **Ход:** шаг 3 эпика ADR-0015 — синх-контракт в `processResponse`
  (Aspects 3+5), TDD: воспроизводящий тест-класс, код, полный сьюйт.
  Входящих нет (автономный ход; живые нитки mailbox'а — 3 — на месте).
- **Тест первым (воспроизводящий):** `HttpTemplateStreamContractTest`
  (`src/test/java/ru/jrestly/http/`) — 7 тестов, контрактные области
  (сценарии test strategy ADR-0015 3/4/5): (1) успех = stream as-is —
  `assertSame` на тот же объект из wire, `bytesRead == 0`, не закрыт;
  (2) лог — streaming-вариант payload-строки: «streaming, Content-Length:
  N» при заголовке, «streaming (length unknown)» без — в обоих случаях
  тело НЕ читается; (3) `@OnError` — stream поеден полностью
  (`readAllBytes` + close), error-объект decoded через codec,
  `HandledException` несёт его; пустой stream — null payload (зеркало
  пустого byte[]-тела); (4) unexpected status — полный raw payload в
  `UnexpectedStatusException`; (5) `probePayloadDecode` — stream = «decode
  не может упасть». Фикстуры — house-style #65: `StubHttpClient`
  (подкласс — `HttpClient` абстрактный класс), `TrackingStream`
  (`bytesRead`/`closed`), `StubCodec` (вызовы + последний payload),
  `CapturingLogger`. Первый прогон: 6 из 7 красные (probe-guard зелёный) —
  воспроизведение по дизайну.
- **Код (`HttpTemplate`, один файл):** stream-ветка в `processResponse`:
  успех — stream as-is (codec-free — «JSON by return type»); `@OnError` —
  `consumePayload` + `jsonCodec.deserialize` → `HandledException`;
  unexpected status — `consumePayload` → полный raw payload в
  `UnexpectedStatusException` (payload cap — revisit-on-demand, не
  0.x-дефолт). `consumePayload` — try-with-resources + `readAllBytes`,
  пустой stream → null payload. `streamingPayloadLine` — replacement
  payload-лог-строки (Content-Length из заголовка, тело не читает).
  `probePayloadDecode` — stream-предикат «byte binding — nothing is
  decoded». `processResponse` — `throws IOException` (checked из
  `readAllBytes` на error-пути): sync `exchange()` уже ловит;
  `exchangeAsync` — `try/catch (IOException)` в `thenApply` →
  `CompletionException`. Временное поведение шага 2 (полный `exchange()`
  → `null` на stream-успехе) исправлено: 4 ассерции
  `HttpTemplateStreamWireTest` `assertNull` →
  `assertSame(finalStream, ...)` + class-javadoc-нота.
- **Граблина дня (JDK-факт — пробом, не из головы):** JDK 21 `thenApply`
  распаксовывает `CompletionException` — mapping-функция видит plain
  `IOException`, не обёртку; итог в future — один `RuntimeException`, без
  двойного вложения. Проб `/tmp/opencode/CephProbe.java`: `join()` CE-cause
  = `IOException`. Итог: existing `exceptionally`-маппинг `exchangeAsync`
  работает без правок (sync twin'а `catch (IOException)` — тот же класс).
- **Гейты:** `./gradlew test --rerun` — **270/270** (38 классов, 0 fail/err
  по XML; 263 + 7). `compileJava compileTestJava --rerun-tasks` — **0
  warnings** (перманентные `-Xlint:all -Werror`; оба compile-задания
  реально отработали). build.gradle не тронут — 0.8.0 / 0
  `<dependency>` (POM не перегенерирован — граблина #38).
- **DRAFT:** шаг 3 → `[x]`. Mailbox не трогал (входящих нет; по существу
  нечего — шаг записан в handover).

## Контекст для следующей сессии (#67)

### Состояние: эпик «response streaming» — шаг 4/6
- **Шаг 4 — async twin** (ADR-0015 Aspect 4, test strategy сценарий 2):
  `CompletableFuture<InputStream>` как return type. Контур уже в месте:
  async send-ветка (`sendAsync` → `ofInputStream`) — шаг 2; builder-
  детекция effective return type (unwrap `CompletableFuture`) — шаг 2;
  exception mapping `exchangeAsync` (unwrapping CE → plain cause →
  `RuntimeException`) — шаг 3. Осталось: контракт-тесты async twin'а —
  будущий future на stream-успехе несёт сам stream (не null, не
  декодированный объект), error-пути завершают future exception'ом того
  же класса, что sync twin (`HandledException`/
  `UnexpectedStatusException` — cause-цепь), intermediate-стримы в
  async-ветках уже закрываются (шаг 2, order-гейт есть). TDD —
  воспроизводящие тесты перед кодом, если код ещё что-то прячет
  (предположительно шаг чисто тестовый: ветки синхронны с sync по
  `processResponse` — одна и та же хвостовая функция). Гейт — suite
  green включая новые async-тесты.
- **Потом:** шаг 5 (документация: `ControllerValidator` —
  `InputStream`-возврат валидируется чисто; Javadoc-контракт на
  публичную поверхность — лаконично (Антон); README-буллет +
  «JSON by return type»-нота; гейт — javadoc 0 под `-Xdoclint:all
  -Werror`), шаг 6 (дистилляция в ROADMAP + зачистка DRAFT).
- **ADR-0015 — source of truth** (`docs/0.9.0/ADR-0015-response-
  streaming-inputstream-return.md`): аспекты 1–6 + Shape + test strategy
  (8 сценариев; закрыты 3/4/5 wire+контрактом #65/#66, остаётся: 1 —
  базовый 4 MiB byte-exact download, 2 — async twin, 6 — probe-codec,
  бросающий из `deserialize` (stream-успех codec не трогает), 7 —
  server disconnect mid-body → `IOException` на read-месте, 8 —
  validation + docs).
- **Важно:** полный `exchange()` на stream-успехе теперь возвращает сам
  stream (as-is, codec-free) — не null (временное поведение шага 2
  исправлено в #66); пустое тело на успехе — stream (не null) —
  семантика «нет payload» в стриме = EOF при первом read.

### Грабли (актуальный набор)
- **JDK 21 `thenApply` распаксовывает `CompletionException`** (#66,
  пробом): mapping видит plain cause; обёртка в future — один
  `RuntimeException`. Проверять CE-цепи пробом, не из головы.
- **`HttpHeaders.of(Map, BiPredicate)` — второй аргумент = фильтр на
  (name, value), не name-matcher** (#65, пробом): в stub'ах —
  `(name, value) -> true`. `map()` возвращает backing map as-is
  (case-sensitive), `firstValue/allValues` — через map по точному ключу.
- **`java.net.http.HttpClient` — абстрактный КЛАСС** (не интерфейс):
  stub = подкласс; 11 abstract-методов (javap-факт #64, подтверждён
  компиляцией #65).
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
  проверяется и отрицательной стороной (в #65 — order-ассерция
  `close < send-2`; в #66 — `bytesRead == 0` на as-is стриме, не
  «мы думаем что не трогали»).
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
  test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас — 270).
- **«2 s» в выводе Gradle — не индульгенция** (#58): compile-замер — по
  логу.
- **Факты — по коду/дереву/jar, не по имени/памяти** (#47–#57,
  #64–#66): JDK — `javap -cp /opt/java/openjdk/jmods <fqcn>` / Oracle
  javadoc 21 (webfetch) / быстрый прогон пробного main-класса (применено
  в #65/#66).
- **DRAFT/mailbox переносы строк: edit-tool oldString — только с
  фактическим байтовым текстом** (#48–#63, #65): проверять `sed -n`/
  `cat -A`/`od -c` (в #65 два промаха по «»-кавычкам/обрезке).
- **ADR-номера:** 0013/0014 — закрыты; 0015 — response streaming (шаг
  3/6 закрыт); 0016 — свободен (резерв: SSE/line events для ЛЛМ).
- **«Итог дня» — живая секция:** дописывать фрагментом « + **...
  (сессия #N)**», не переписывать; сессионные заметки — до «Итога
  дня», не после.
- **Чужой незакоммиченный diff — валидировать, не слепить** (#45).
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние
  2–3 сообщения оставляем (сейчас 3: «больше комментариев нет»,
  входящее 09:10, ответ #62 — все живые).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш
   пережил сессии #36–#66).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 66 → после следующей 67).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`…`0.9.0/` (0.9.0 —
   ADR-0015, метка provisional); lint-инит-скрипт
   `/tmp/opencode/lint-init.gradle`, classpath `/tmp/opencode/cp.txt`,
   логи замеров — в `/tmp/opencode/` (включая `CephProbe.java` —
   thenApply/CE-проб #66).
7. Системный JDK: `/opt/java/openjdk` (Temurin 21) — `javadoc`-бинарь,
   `javap -cp /opt/java/openjdk/jmods <fqcn>` (снять подпись JDK-API без
   src.zip); Oracle javadoc 21 —
   `docs.oracle.com/en/java/javase/21/docs/api/java.net.http/...`
   (webfetch); быстрый JDK-проб — `java SingleFile.java` в
   `/tmp/opencode/`.

### Состояние git
- HEAD: коммит сессии #66 (синх-контракт: код + тесты + DRAFT + память)
  поверх `e483689` (#65), `3246c48` (#64 ADR-0015 + DRAFT-эпик),
  `8fbbcf6` (#63), `0e9b637` (#62), `faa7f00` (Антон). Всё на
  `agent-loop`. Последний тег `v0.6.1`.
- Версия в build.gradle: **0.8.0** (не меняется до release-цикл-шага
  0.9.0). POM (untracked): 0.8.0, 0 `<dependency>`.
- Тесты: **270/270** (38 классов; свежий прогон #66, `test --rerun`).
- Warnings: javac main+test — **0/0**, javadoc — **0** (перманентные
  `-Werror`-гейты; compile-задания реально отработали в #66).
- CHANGELOG.md: entry 0.8.0 + 0.7.0 — оба unpublished, в этом эпике не
  трогаются.
- DRAFT: эпик 6 шагов (шаги 1–3 [x]); активная зона — SSE-0016/
  параметры/пайплайн + test-javadoc.
- Mailbox: 3 сообщения, все живые (входящих нет).
