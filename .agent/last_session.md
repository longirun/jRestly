# Last Session: #148 (2026-10-16)

## Что сделано

**Кандидат №3 (развилка Антона №1) — ADR-0058: концепт внешних субмодулей движков.**

Оформлен `docs/0.10.0/ADR-0058-external-http-engine-submodules-okhttp-apache5.md`
(Status: **Proposed**, следующий свободный — **0059**). Ключевые решения:

1. **Два публикуемых субмодуля**: `jrestly-engine-okhttp`
   (пакет `ru.jrestly.engine.okhttp`, `api 'com.squareup.okhttp3:okhttp:4.12.0'`)
   и `jrestly-engine-apache5` (пакет `ru.jrestly.engine.apache`,
   `api 'org.apache.httpcomponents.client5:httpclient5:5.4.1'`). Ядро — 0 deps.
   `benchmarks` → `project(':engine-*')`; прототипы из
   `ru.jrestly.benchmark.engine` удаляются (один источник правды).
2. **ServiceLoader**: каждый субмодуль регистрирует `HttpEngineProvider`;
   `JdkHttpEngine` провайдером НЕ является (остаётся fallback'ом —
   поведение ядра без субмодулей не меняется).
3. **`default int priority()`** в `HttpEngineProvider` (бинарно-совместимо);
   `HttpEngineResolver` — стабильная сортировка по приоритету (официальные
   адаптеры — 50; равенство → порядок ServiceLoader, в доках).
4. **Контракт паритета** (чек-лист в ADR): timeout (callTimeout /
   RequestConfig.responseTimeout), streamResponse (byteStream / entity
   content), version (best-effort), прогресс-листнеры (финал total/total +
   нулевая 0/0), trailers, `close()` с уважением `ownsClient`;
   исключительный контракт (IOException/InterruptedException, exceptional
   completion) без изменений.
5. **Маппинг ModuleInfo в createEngine**: SSL (ADR-0020), CookieHandler →
   CookieJar/CookieStore (0032/0039), ProxySelector → per-request прокси,
   Authenticator → challenge-интерцептор best-effort, isVirtualThreads →
   virtual-thread executor для колбэков.
6. **Адаптер body publisher**: выкачивание JDK `Flow.Publisher<ByteBuffer>`
   → `InputStream` одним адаптером (стриминг ADR-0035 + multipart ADR-0024);
   `BodyPublisher` из `java.base` — генерализация wire-типа не обязательна
   (Non-Goal).

## Грабли (новые, в дополнении к старому набору)

- **Дыры прототипов (в ADR-0058, раздел Context.3)**: оба прототипа игнорируют
  timeout/version/stream/progress, не зарегистрированы в ServiceLoader.
  `sendAsync` OkHttp-прототипа закрывает `Response` в колбэке — стриминг в
  async-режиме не прочитать. `sendAsync` Apache5 — supplyAsync на общем
  ForkJoinPool (истощение под нагрузкой).
- **Недетерминизация ServiceLoader**: «первый доступный» при нескольких
  провайдерах = произвольный порядок итерации; лечится `priority()` +
  стабильная сортировка (не только `Map.copyOf`-солт).
- Старые грабли #147 в силе (raw-TCP стенд, GRADLE_USER_HOME, порядок JMH,
  счётчик тестов, краткие коммит-субъекты, git tags нет).

## Гейты

- Ядро не менялось: **1058/1058** (core 1043 + benchmarks 15), 0 warnings
  (`./gradlew test` UP-TO-DATE green).
- Изменены: ADR-0058 (новый), DRAFT.md, дневник 2026-10-16 (первая сессия
  дня), mailbox (+1 сообщение), last_session.md.

## Контекст для следующей сессии (#149)

### Следующий шаг (TDD по ADR-0058, двухфазный)
1. **Фаза 1 — ядро (маленький шаг):** `priority()` в
   `HttpEngineProvider` + стабильная сортировка в
   `HttpEngineResolver.resolveFromSpi`. Red-first: кейс в
   `HttpEngineResolverTest` — провайдер с меньшим приоритетом первым в
   итерации, но с меньшим приоритетом не выбирается.
2. **Фаза 2 — субмодули (крупный, можно на 2 сессии):** `settings.gradle`
   + include; перенос прототипов → `engine-okhttp`/`engine-apache5`
   (пакеты `ru.jrestly.engine.okhttp`/`ru.jrestly.engine.apache`),
   доведение до контракта паритета, `META-INF/services` файлы,
   WireMock-тесты паритета; benchmarks → project(':engine-*'),
   удаление `benchmarks/.../engine/*`.
3. **Потом**: публичная документация по бенчмаркам (методология raw-TCP +
   TCP_NODELAY, таблица #147, ограничения variance). Опционально перед ней —
   повторный прогон JMH `-f 2 -i 5` для публичных цифр.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **1058/1058**.
4. **Next free ADR: 0059.** Пропуски в нумерации сессий (144, 146) —
   сверяться по `git log`.
