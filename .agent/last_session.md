# Handover Note: Сессия 65 (ADR-0015 шаг 2 — wire seam закрыт, 263/263; следующий ход — шаг 3: синх-контракт в `processResponse`)

## Что сделано в этой сессии
- **Ход:** шаг 2 эпика ADR-0015 — wire seam, TDD: сначала
  воспроизводящий тест-класс, потом код, в конце — полный сьюйт.
  Входящее: Антон — «Красава, продолжай» (концепт ADR-0015 принят;
  обмениваться не о чем — решённый обмен удалён из mailbox по гигиене,
  в DRAFT-пометке отмечено).
- **Тест первым (воспроизводящий):** `HttpTemplateStreamWireTest`
  (`src/test/java/ru/jrestly/http/`) — 7 тестов: send-path ветка
  (stream on/off), disposal промежуточных стримов (redirect-хоп +
  retry-попытка, sync + async twins) с ORDER-гейтом по журналу событий
  (`send-1` < `close-*` < `send-2` — «гейт без отрицательной проверки —
  не гейт» применён к самому close), builder-детекция (effective return
  type: `InputStream`/`CompletableFuture<InputStream>` — флаг на,
  `String` — выключен). Фикстуры: `StubHttpClient` (подкласс —
  `HttpClient` в JDK **абстрактный класс, не интерфейс**, `Proxy` не
  подойдёт — javap-факт #64 подтвердился), `TrackingStream`
  (записывает `close()` событием), `StubResponse` (`HttpHeaders.of` с
  filter-предикатом — граблина ниже).
- **Код (4 файла):** `WireResponse` — record 4-х компонент
  (`statusCode`, `headers`, `body`, `stream`) + secondary 3-arg
  конструктор, делегирующий каноническому (ABI-нейтрально; call sites
  main ×1 + test ×3 без изменений). `HttpTemplate` — поле `streamBody` +
  `setStreamBody` + package-private `streamBody()` (чтение для
  seam-тестов); `send()`/`sendAsync()` ветвят на `streamBody` →
  `BodyHandlers.ofInputStream()` vs `ofByteArray()` (zero-dep — только
  JDK); `send()` — package-private (тест бьёт по ветке напрямую);
  `logPerHop` — `int statusCode` (обе ветки униформны); `toWireResponse`
  — один generic `HttpResponse<?>` (два overload'а столкнулись бы на
  erasure): `body instanceof InputStream` → `stream`, иначе `body`;
  `closeIntermediateStream` (close только в лог INFO, не бросает) — из
  `executeWithRedirects` (перед следующим хоком),
  `executeAsyncWithRedirects` (перед рекурсией) и
  `scheduleRetry`/`scheduleAsyncRetry` (отбрасывание упавшей попытки).
  `HttpTemplateBuilder.resolveReturnType` — effective return type
  (unwrap `CompletableFuture`) один раз; `InputStream` →
  `setStreamBody(true)`.
- **Граблина дня (JDK-факт — пробом, не из головы):** второй аргумент
  `HttpHeaders.of(Map<String,List<String>>, BiPredicate)` — **фильтр на
  (name, value)**, а не name-matcher: stub-predicate
  `(stored, lookup) -> stored.equalsIgnoreCase(lookup)` сравнивал имя со
  значением и отфильтровал ВСЕ заголовки — 307 уходил как final (без
  `Location` redirect не следовал). Фикс — `(name, value) -> true` +
  комментарий в тесте. Вторая граблина: builder-проб через stubbed send
  не доказывает флаг (stub отвечает stream-телом на любой `BodyHandler`)
  — гейт = сам флаг → `streamBody()`-accessor.
- **Гейты:** новый класс — 7/7; полный `./gradlew test --rerun` —
  **263/263** (256 + 7, 0 fail/err по XML); compile — 0 warnings
  (перманентные `-Xlint:all -Werror`); build.gradle не тронут (0.8.0,
  POM не перегенерирован — граблина #38).
- **DRAFT:** шаг 2 → `[x]`; «Пометки из mailbox» — концепт отмечен
  принятым (обмен удалён).

## Контекст для следующей сессии (#66)

### Состояние: эпик «response streaming» — шаг 3/6
- **Шаг 3 — синх-контракт** (ADR-0015 Aspect 3):
  - `processResponse` — stream-ветка: успех (статус accepted, body —
    stream) → вернуть stream as-is (codec НЕ трогать — «JSON by return
    type»); `@OnError`-статус → consume stream via `readAllBytes` +
    decode в error-класс (униформа с byte[]-путём — `HandledException`);
    unexpected status → consume + **полный raw payload** в
    `UnexpectedStatusException`;
  - `probePayloadDecode` (decode-ось ADR-0011) — для stream: «decode не
    может упасть» (зеркало `String`-возврата — payload уже в нужной
    форме);
  - лог-ветка: payload-строка (`"Response size: N bytes\n..."`) для
    stream пропускается (не читать тело!); «streaming, Content-Length:
    N» из заголовка / «(length unknown)», если заголовка нет;
  - TDD: тесты по test strategy ADR-0015 (сценарий 4 — error-пути:
    `@OnError` со стримом, unexpected status со стримом — payload в
    exceptionе полный);
  - гейт: suite green включая error-path-тесты.
- **Потом:** шаг 4 (async twin — `CompletableFuture<InputStream>`,
  ветка `sendAsync` уже есть в шаге 2 — future completes после
  заголовков со stream, exception mapping не меняется), шаг 5
  (документация: `ControllerValidator` — `InputStream`-возврат
  валидируется чисто; Javadoc-контракт на публичную поверхность —
  лаконично (Антон); README-буллет + «JSON by return type»-нота; гейт —
  javadoc 0 под `-Xdoclint:all -Werror`), шаг 6 (дистилляция в ROADMAP).
- **ADR-0015 — source of truth** (`docs/0.9.0/ADR-0015-response-
  streaming-inputstream-return.md`): аспекты 1–6 + Shape + test strategy
  (8 сценариев).
- **Важно про текущее поведение (шаг 2 не менял контракт):** полный
  `exchange()` на stream-ответе сегодня возвращает `null` на успехе
  (`body()==null` → `payload==null` → return null) — шаг 3 это
  исправляет (stream as-is). Тесты шага 2 это и эксплуатируют
  (`assertNull(t.exchange())` в redirect/retry-сценариях).

### Грабли (актуальный набор)
- **`HttpHeaders.of(Map, BiPredicate)` — второй аргумент = фильтр на
  (name, value), не name-matcher** (сессия #65, пробом): в stub'ах —
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
  `close < send-2`, не «мы думаем что закрыли»).
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
  test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас — 263).
- **«2 s» в выводе Gradle — не индульгенция** (#58): compile-замер — по
  логу.
- **Факты — по коду/дереву/jar, не по имени/памяти** (#47–#57, #64–#65):
  JDK — `javap -cp /opt/java/openjdk/jmods <fqcn>` / Oracle javadoc 21
  (webfetch) / быстрый прогон пробного main-класса (применено в #65).
- **DRAFT/mailbox переносы строк: edit-tool oldString — только с
  фактическим байтовым текстом** (#48–#63, #65): проверять `sed -n`/
  `cat -A`/`od -c` (в #65 два промаха по «»-кавычкам/обрезке).
- **ADR-номера:** 0013/0014 — закрыты; 0015 — response streaming (шаг
  2/6 закрыт); 0016 — свободен (резерв: SSE/line events для ЛЛМ).
- **«Итог дня» — живая секция:** дописывать фрагментом « + **...
  (сессия #N)**», не переписывать; сессионные заметки — до «Итога
  дня», не после.
- **Чужой незакоммиченный diff — валидировать, не слепить** (#45).
- **Mailbox-гигиена:** решённый обмен удаляем; живые нитки и последние
  2–3 сообщения оставляем (сейчас 3: «больше комментариев нет»,
  входящее 09:10, ответ #62 — все живые).

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш
   пережил сессии #36–#65).
2. `pids.max = 1000`. Полный `./gradlew test --rerun` без init-скрипта.
3. Ветка `agent-loop`. Демон Gradle переживает падения тестов.
4. Коммиты агента: `longirun-agent <sinyagovsky@gmail.com>`; счётчик
   сессий подкручивает harness (сейчас 65 → после следующей 66).
5. Часы контейнера: UTC+4; mailbox-время — местное.
6. `docs/` — ADR-папки по версиям цикла: `0.5.0/`…`0.9.0/` (0.9.0 —
   ADR-0015, метка provisional); lint-инит-скрипт
   `/tmp/opencode/lint-init.gradle`, classpath `/tmp/opencode/cp.txt`,
   логи замеров — в `/tmp/opencode/`.
7. Системный JDK: `/opt/java/openjdk` (Temurin 21) — `javadoc`-бинарь,
   `javap -cp /opt/java/openjdk/jmods <fqcn>` (снять подпись JDK-API без
   src.zip); Oracle javadoc 21 —
   `docs.oracle.com/en/java/javase/21/docs/api/java.net.http/...`
   (webfetch); быстрый JDK-проб — `java SingleFile.java` в
   `/tmp/opencode/`.

### Состояние git
- HEAD: коммит сессии #65 (wire seam: код + тест + DRAFT + память)
  поверх коммита #64 (ADR-0015 + DRAFT-эпик), `8fbbcf6` (#63),
  `0e9b637` (#62), `faa7f00` (Антон). Всё на `agent-loop`. Последний тег
  `v0.6.1`.
- Версия в build.gradle: **0.8.0** (не меняется до release-цикл-шага
  0.9.0). POM (untracked): 0.8.0, 0 `<dependency>`.
- Тесты: **263/263** (37 классов; свежий прогон #65, `test --rerun`).
- Warnings: javac main+test — **0/0**, javadoc — **0** (перманентные
  `-Werror`-гейты; compile-задания реально отработали в #65).
- CHANGELOG.md: entry 0.8.0 + 0.7.0 — оба unpublished, в этом эпике не
  трогаются.
- DRAFT: эпик 6 шагов (шаги 1–2 [x]); активная зона — SSE-0016/
  параметры/пайплайн + test-javadoc.
- Mailbox: 3 сообщения, все живые (входящих нет).
