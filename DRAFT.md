# DRAFT: warnings и модернизация (ADR-0013) — шаг 5 закрыт: javadoc 0 (сессия #57), впереди гейт шага 6

> Свободный ход после 0.7.0-цикла: кандидат №1 (warnings/рефакторинг-аудит,
> fallback с #30) открыт как эпик. Сессия #41 — сам аудит (кандидат так и
> требовал: сначала «что именно», потом план): замеры по `javac -Xlint:all`
> (12 warnings: 9 main / 3 test), `javadoc -Xdoclint=all` (100 warnings —
> все missing-comment/@param/@return; уточнение #44/#45: это потолок
> тула, истинный total 237 — см. ADR-0013), идеомы Java 21 (устаревших фрагментов
> нет — pattern-matching/arrow-switch/records на месте; 2 build-level
> модернизации: `options.release`, javadoc-gate), hot path (новых находок
> нет). ADR-0013 (`docs/0.8.0/` — папка следующего цикла создана при
> открытии, версионная метка провизорная) = findings + scope-решения; шаги
> ниже.

## Эпик: warnings и модернизация (ADR-0013)

- [x] Шаг 1 — аудит и scope (ADR-0013, сессия #41): замеры, findings,
      решения D1–D4 (каждый warning чинится; suppress — точечный, с
      рационалом; javadoc — все 100 + gate `-Xdoclint=all`/`-Werror` в
      build.gradle; `release = 21`; hot path — чист).
- [x] Шаг 2 — main + build (сессия #42): 5 sites (serial — `@Serial` UID +
      class-`@SuppressWarnings` с рационалом на трёх exception'ах; rawtypes →
      `Class<?>[]`; this-escape — точечный suppress с рационалом на
      КОНСТРУКТОРЕ (escape site: javac стучит в call site, не в вызываемый
      hook — уточнение зафиксировано в ADR-0013); `//noinspection` →
      `@SuppressWarnings` в `HandledException.getDetails`) +
      `options.release = 21` вместо source/targetCompatibility. Валидация:
      `-Xlint:all` → 0 в main (было 9), 256/256, POM 0 deps.
- [x] Шаг 3 — test (сессия #43): `TestModuleInfo.getEnvironment`
      приведён к generic-подписи интерфейса `<T> Environment<T>` + cast
      (паттерн `SimpleModuleInfo`; поле осталось `Environment<Header>`)
      → 3-й тестовый site закрыт; 2× `causeOf`-дубль схлопнут в один
      общий helper `TestFutures.causeOf` в `ru.jrestly.fixtures`
      (final-утилитарий, `public static`, единственный suppress с
      рационалом; статические импорты в двух классах) → лоск тестов.
      Валидация: `-Xlint:all` → **0 в main и 0 в test** (было 3),
      256/256 (36 классов). Уточнение (helper — public, не private)
      зафиксировано в ADR-0013 D1.
- [x] Шаг 4 — javadoc, нормативная поверхность (сессии #44–45): 3
      exception'а (class/field/ctor/getter-комментарии), `AuthProvider`,
      `Environment`, `FileContent`, `MultipartPart` (включая `@param`
      record-компонентов и `@throws` canonical-конструктора), `@return` у
      verb-аннотаций (Delete/Get/Post/Put/Patch — все атрибуты). Код
      менять не пришлось — только Javadoc (факты сверены: IAE у
      MultipartPart, null-authDetails у Environment, @SetAuthDetails-контракт
      у AuthProvider). Валидация (замер #45, `-Xdoclint:all -Xmaxwarns 999`
      прямым `javadoc`'ом): **237 → 173 warnings**; все 12 файлов шага — 0.
      `./gradlew test --rerun` — 256/256 (36 классов). Уточнение: «100»
      в ADR-0013 — было потолком javadoc-тула (100/run без `Xmaxwarns`);
      истинный baseline 237 (поправка зафиксирована в ADR-0013).
- [x] Шаг 5 — javadoc, остальное (100 warnings, замер #48 — реальный,
      не capped). Сессия #46 закрыла контрактную пару `RetryPolicy` 25
      + `Retry` 8 (173→140; обе поверхности — 0; fluent-`@return` —
      house-style «this builder, for chaining»). Сессия #47 закрыла
      `HttpTemplate` 25 (140→115; поверхность 0 — `exchange`
      `@param <T>`/`@return`, явный no-arg конструктор с Javadoc
      (единственная кодовая правка шага — «default constructor» требует
      именно его), `logRequest`-hook, 21 сеттер; null-семантика
      сверена по `HttpTemplateBuilder.build()`). Сессия #48 закрыла
      `AbstractHttpClient` 15 (115→100; поверхность 0 — класс,
      4 поля, конструктор, 5 protected-методов (login/createConnection —
      protected hooks, createController/isLoginInBackgroundNeeded/
      getClassLoader), 4 публичных (`close` — plain-комментарий
      перенесён в Javadoc, `get`, `getModuleInfo`, `updateAuthHeader`);
      ноль кода — чистый Javadoc; факты сверены: `login`/
      `isLoginInBackgroundNeeded` — по аннотациям `@Authorization`/
      `@Anonymous`, `createController` — по `ControllerValidator`,
      `createConnection` — connectTimeout + httpVersion + Redirect.NEVER).
      Сессия #49 закрыла `SimpleModuleInfo` 14 (100→86; поверхность 0 —
      класс, `builder()`, `Builder` + явный no-arg конструктор с Javadoc
      (единственная кодовая правка сессии — «default constructor»-warning,
      прецедент #47), 9 fluent-сеттеров (house-style «this builder, for
      chaining»), `build()`; факты сверены: дефолты против default-методов
      `ModuleInfo` (name — abstract, уточнение в class-doc), `baseUrl` —
      required → ISE, `jsonCodec` → SPI-фоллбэк (`JsonCodecResolver`),
      `authHeader` — шлётся только при обоих non-null (по
      `AuthHeaderProvider.getAuthHeaders`), validation — в момент создания
      proxy (ленивое `get`, не «at client creation» — поправил по ходу),
      `getEnvironment()` — свежий инстанс на каждый вызов). Сессия #50
      закрыла `JRestlyClient` 14 (86→72; поверхность 0 — конструктор
      (модуль хранится, соединение создаётся сразу — факт из
      `AbstractHttpClient`), `builder()`, `Builder` + явный no-arg
      конструктор с Javadoc (единственная кодовая правка сессии —
      прецеденты #47/#49), 9 forwarder-сеттеров (семантика 1:1 с
      `SimpleModuleInfo.Builder`; authHeader-описание урезано до
      фактов, не зависящих от subclass-`login()` — у `JRestlyClient`
      `login()` no-op), `build()` (два ISE-путя: baseUrl + codec не
      найден — факт из `JsonCodecResolver.resolve()`)). Сессия #51
      закрыла `ModuleInfo` 10 (72→62; поверхность 0 — сама
      нормативная поверхность: класс (модульная конфигурация — всё,
      из чего строится клиент: identity, окружение, auth-заголовки,
      codec, таймауты, версия протокола, валидация контроллеров,
      retry; обе реализации — `SimpleModuleInfo` #49 и forwarder
      `JRestlyClient.Builder` #50 — теперь опираются на эти
      описания), getName, getEnvironment (`@param <T>`; base URL +
      типизированные auth-детали — по `Environment`), getAuthProvider
      (роль в клиенте сверена по `AbstractHttpClient`/
      `HttpTemplateBuilder`: isAuthorized → background login,
      updateAuthHeader → login, getAuthHeaders → в каждый запрос),
      4 default'а — codec (SPI-фоллбэк jackson→gson→moshi + ISE без
      codec — по `JsonCodecResolver.resolve()`), 5 000/30 000 ms,
      HTTP_2, validation up front (формулировки 1:1 из
      `SimpleModuleInfo`), getRetryPolicy — только `@return`;
      ноль кода). Сессия #52 закрыла `WireRequest` 7
      (62→55; поверхность 0 — `@param` у 7 record-компонентов; ноль
      кода; факты сверены по `HttpTemplate`: `nextHop` — rewrite
      method/uri/payload на redirect-хопе (307/308 — preserve),
      cross-origin — drop default auth headers; `toHttpRequest` —
      contentType шлётся когда non-null; `bodyPublisher` — parts →
      fresh streaming publisher per send, body → ofByteArray / null
      → noBody). Сессия #53 закрыла `OnErrorParser` 7
      (55→48; поверхность 0 — класс (per-method `@OnError`-конфигурация:
      статусы + класс ошибки), явный no-arg конструктор с Javadoc
      (единственная кодовая правка сессии — «default constructor»-
      граблина, прецеденты #47/#49/#50; видимость `public` не сломана —
      конструктор используется в тестах), `canParse`/`getStatuses`/
      `setStatuses`/`getErrorClass`/`setErrorClass`; факты сверены по
      коду: собирается только `HttpTemplateBuilder.createOnErrorParser()`
      при наличии `@OnError` на контроллер-методе (иначе в шаблон
      ставится `null`), потребляется `HttpTemplate.processResponse`
      (классификация статуса + decode тела в `HandledException`) и
      retry-loop/decode-probe'ом через `canParse`). Сессия #54 закрыла
      пару enum'ов `HttpMethod` 6 + `RequestType` 5 (48→37; поверхности 0;
      ноль кода — чистый Javadoc; `HttpMethod` — class-doc (mapping
      аннотаций 1:1 по `resolveRequestMeta`; body-лог — все кроме
      `GET` — по `BODY_LOGGING_METHODS`; 307/308 — preserve, остальные
      → `GET`) + 5 констант (RFC 9110: `POST`/`PATCH` — non-idempotent
      — по `isNonIdempotent`, `GET` — тело никогда не несёт — по
      `createBody`/`toHttpRequest`, `PUT`/`DELETE` — idempotent);
      `RequestType` — class-doc (`requestType`-атрибут verb-аннотаций,
      `@Get` — фиксированно `APPLICATION_JSON`; `mime()` —
      `Content-Type`, у multipart — + per-request boundary) + 3
      константы + `mime()` (зеркало граблины #46: main-description БЕЗ
      `@return` — doclint требует обеим; факты сверены по
      `HttpTemplateBuilder.build()`/`createUrlEncodedBody`/
      `createMultipartParts` + `ControllerValidator`: JSON — дефолт
      всех verb-аннотаций + `@RequestBody` через codec; form/multipart
      — без query-параметров, ≥1 параметр, boundary `jrestly-<uuid>`).
      Сессия #55 закрыла блок 4 файла × 3 (37→25; поверхности 0; ноль
      кода — чистый Javadoc; `WireResponse` — 3 record-`@param`
      (house-style #52: статус — классификация @OnError/@ExpectStatus/
      redirect-статусов, headers — один `Header` на запись JDK-карты,
      body — null при пустом ответе; факты по
      `HttpTemplate.toWireResponse`/`processResponse`/
      `redirectLocation`); `AuthHeaderProvider` — class-doc (дефолтный
      `AuthProvider`: `SimpleModuleInfo` ставит его при отсутствии
      кастомного — с парой `authHeader`, если задана, иначе пустым;
      initial-пара из конструктора, fresh-пара из
      `updateAuthHeader` (capture по `@SetAuthDetails`) приоритетна,
      пока обе её части non-null) + 2 конструктора (null-ность частей
      допустима — шлётся только полная пара); `HttpTemplateBuilder` —
      class-doc (per-invocation: создаётся в proxy-dispatch на каждый
      вызов, discarded после `build()`, движок — в шаблоне) +
      `@param` конструктора (controller — имя логгера запросов) +
      `build()` (verb-аннотация → method/path/type, UOE — backstop за
      controller-validation на proxy-создании; `@PathVariable` —
      URL-encoded в путь, относительный путь — + base URL окружения;
      headers — auth-заголовки модуля + `@RequestHeader`;
      `@RequestParam`/`@RequestDefaultParam` → query, у form/multipart
      — в body; body по типу: JSON — `@RequestBody` через codec
      (String — as-is, GET — без тела), urlencoded — params, multipart
      — `@MultipartFormFile` + `@RequestParam` части под сгенерированным
      boundary `jrestly-<uuid>`; `CompletableFuture<T>` — async, `T` —
      wire-контракт; `@OnError`-парсер только при аннотации (иначе
      null); `@ExpectStatus`/`@FollowRedirects`/`@HttpVersion` —
      when present; per-method `@Retry` заменяет policy клиента
      целиком — ADR-0008); `JacksonCodec` — конструктор (mapper
      as-is, без reconfigure — «configure it before handing it in») +
      2× `@return` у `defaults(...)` (зеркало граблины #46/#54:
      description + `@return` — обе обязательны). Граблина дня:
      `@param`-секция БЕЗ main description у конструктора — doclint
      требует main description у ВСЕХ сайтов (в первый заход
      пропустил, всплыло на повторном замере — «no main description»).
      Остаток (17 файлов): 8 × 2 + 9 × 1. Сессия #56 закрыла блок 8
      файлов × 2 (25→9; поверхности 0; ноль кода — чистый Javadoc;
      `MoshiCodec`/`GsonCodec` — конструкторы (зеркало JacksonCodec #55:
      «used as is, never reconfigured — configure it before handing it
      in» + `@param`) + `@return` у `defaults()`; `Param`/`Header` —
      `@param` record-компонентов (house-style #52); `ControllerValidator`
      — class-doc (валидация controller-интерфейса до создания proxy:
      методы интерфейса + родительских, sub-interface выигрывает по
      сигнатуре, ровно одна verb-аннотация, form/multipart — ≥1
      параметр) + `validate()` (`@param`/`@return`; «кто вызывает и что
      с результатом» — по `AbstractHttpClient.validateController`:
      eager — `ControllerValidationException`, отключена — только лог)
      + `RequestDefaultParam` name/value (шлётся с каждым вызовом
      аннотированного метода — `createRequestDefaultParams`) + `OnError`
      statuses (`empty by default, so no status triggers it` — `canParse`
      = `statuses.contains`) + errorObject (`Void.class` by default;
      пустой payload — null-тело в `HandledException` —
      `HttpTemplate.processResponse`) + `MultipartFormFile` partName
      (общий для всех частей, порождённых из аргумента —
      `createMultipartParts`) + `@return` у contentType (пусто →
      `contentTypeFor(filename)` — встроенная таблица расширений,
      иначе `application/octet-stream` — `MultipartPart.file`).
      Граблина дня: атрибут аннотации — метод, description без
      `@return` недостаточен (3 single-line-атрибута — «no @return» на
      повторном замере; зеркало #46/#54 в сторону аннотаций). Остаток
      (9 файлов, 9 × 1 — природа измерена, не гадана): 7 атрибутов
      аннотаций с description без `@return` (`ExpectStatus`,
      `FollowRedirects`, `HttpVersion`, `PathVariable`, `RequestHeader`,
      `RequestParam`, `SetAuthDetails`), `JsonCodecResolver.resolve()`
      (description без `@return`), `RequestMeta` (class-комментария нет
      вовсе — внимание: после добавления class-комментария могут
      появиться 4 × `no @param` record-компонентов, прецедент
      `Param`/`Header` — замерить и закрыть одним шагом). Сессия #57
      закрыла финальный блок 9 × 1 (9→0; поверхности 0; ноль кода —
      чистый Javadoc; 7 атрибутов аннотаций — блочная форма с
      `@return` (граблина #56, все 7 сайтов: `ExpectStatus.statuses`
      — ожидаемые успешные статусы, без аннотации 2xx —
      `createExpectStatuses`, `FollowRedirects.count` — макс. число
      redirect-хопов, `HttpVersion.value` — переопределение версии
      протокола — `getHttpVersion`, `PathVariable.name` —
      плейсхолдер `${name}`, пусто → имя Java-параметра —
      `createUrl`, `RequestHeader.name` — имя хедера,
      `RequestParam.name` — имя параметра, пусто → имя
      Java-параметра, `SetAuthDetails.headerName` — response-хедер
      для capture — `getAuthDetailsConsumer`),
      `JsonCodecResolver.resolve()` — `@return` (defaults-кодек первой
      найденной библиотеки, jackson → gson → moshi) + `@throws ISE`
      (ни одной поддерживаемой библиотеки), `RequestMeta` —
      class-комментарий (per-method-метаданные из verb-аннотации:
      method/path/type/defaultParams; собирает `HttpTemplateBuilder`;
      относительные пути достраиваются base URL окружения в момент
      запроса; `@Get` — фиксированно JSON, иначе значение
      аннотации) + 4 × record-`@param` (прогноз природы из #56
      сработал: record без class-комментария мерился как 1, после
      class-комментария — ровно 5 сайтов). Полное дерево: **9 → 0
      javadoc-warnings** (baseline 237, замер #57).
- [ ] Шаг 6 — gate: `javadoc { Xdoclint=all, Werror }` в build.gradle,
      полный ре-аудит (`-Xlint:all` → 0, javadoc → 0), дистилляция в
      ROADMAP.

Кандидаты «после эпика» — в ROADMAP (known-gaps выработан; новых не
появилось).

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
      и дистилляция-секция ссылаются, а не пересказывают (прецедент закрыт:
      ROADMAP-секция «Cycle 0.7.0 — COMPLETE ✅» — только линки).
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

Пометки из mailbox (актуальные на 2026-10-13):
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
      каждом build, cost 0; выгода — перманентность; снять — одна строка).
      Дальше — автономный ход до новых вводных.
- **Версионирование (Антон, 2026-10-13) — применено в шаге 3 (#38):**
      версия 0.7.0 в `build.gradle` + названа в коммит-сообщении, гит-тега
      нет (JitPack-координаты — v0.6.1 до тега). Работаем в `agent-loop`,
      наружу не мержим (в ADR-0012 — цитатой).
- **Коммиты — краткие, не дублируют ченджлог** (Антон): subject — суть шага
  одной строкой, body — гейты-факты (применяется с #37: `release 0.7.0:
  bump version in build.gradle (cycle step 3/4)`).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг, делать чаще.
- **ADR-0006** (java.time wire contract) не требуется.
- **Чистку mailbox Антон подтвердил** (2026-10-13) — правило выше в силе.

Правила работы: один эпик в DRAFT.md, шаги — чекбоксы `[ ]`; закрыл →
дистиллируй в ROADMAP.md и зачисти это место.
