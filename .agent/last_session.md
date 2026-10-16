# Last Session: #158 (2026-10-16)

## Что сделано

**ADR-0058 закрыт с двух сторон: публичная документация + найден скрытый дефект интеграции.**

1. **Публичный `BENCHMARKS.md`** (корень репо, ссылка из README → новая секция
   «Performance & benchmarks»): методология (raw-TCP стенд, TCP_NODELAY, история
   42-мс Nagle-артефакта), матрица 10 клиентов, таблицы прогона #158
   (свежий, `-i 5`: getUser/ping + добор `jrestly_ping_zeroDepRaw` 1298 µs),
   кросс-чек #147, честные ограничения (шум ±30–90 %, loopback, 1 fork),
   команды воспроизведения (`-PjmhArgs`), выбор транспорта.
   Сырые JSON: /tmp/opencode/jmh-158.json, jmh-158-ping.json (в репо не кладём).
2. **Finding #158 (главное): `AbstractHttpClient.createConnection()` НЕ вызывает
   `HttpEngineResolver`.** При `null`-движке он строит JDK-клиент напрямую
   (`new JdkHttpEngine(httpClient, true)`), поэтому ServiceLoader-автообнаружение
   `engine-okhttp`/`engine-apache5` end-to-end не работает — только явный
   `builder.httpEngine(...)`. Доказательства: ноль call-site у
   `HttpEngineResolver.resolve` в src/main; JMH #158 с обоими субмодулями на
   classpath дал у дефолтного клиента цифры JDK (1666 µs), не движков (~400–540);
   javadoc `ModuleInfo.getHttpEngine()` обещает резолвер. Зафиксировано в
   ADR-0058 (секция «Finding (сессия #158)», header: Accepted + открытый Finding).
   README/BENCHMARKS сформулированы честно: явный конструктор, auto-detection —
   «планируемый следующий шаг».
3. ADR-0058 окончательно приведён в порядок: header (Accepted, открытые
   пункты), Amendment #157 (фаза 2b: HC5 5.4.1 deprecations, пассивный транспорт),
   Finding #158.

## Гейты

- Код не менялся (docs + README + ADR + .agent + BENCHMARKS.md).
  `./gradlew test` в #157 — 1109/1109 зелёных; в #158 не перезапускался.

## Контекст для следующей сессии (#159)

1. **Фикс Finding #158** (первый приоритет, red-first):
   - Красный e2e-тест: провайдер в `src/test/resources/META-INF/services/
     ru.jrestly.engine.HttpEngineProvider` с `isAvailable()` под статическим
     флагом (по умолчанию false — чтобы не ломать другие тесты) + счётчик
     вызовов; тест: дефолтный `JRestlyClient` должен получить движок провайдера
     (сейчас — нет).
   - Фикс: в `AbstractHttpClient.createConnection()` делегировать
     `HttpEngineResolver.resolve(moduleInfo)`; для `JdkHttpEngine` забрать
     `getHttpClient()` (как в ветке явного движка); проверить судьбу
     `managedExecutorService` (виртуальные потоки) — вероятно, её создание
     переезжает в `JdkHttpEngine.create` или остаётся с закрытием в close().
   - Проверить `ModuleInfo` javadoc соответствие; прогнать все тесты ядра
     (1048) + субмодулей.
   - Бенчмарки после фикса: дефолтный клиент в benchmarks СТАНЕТ движковым
     (оба провайдера на classpath, приоритет 50) — **это сломает смысл строки
     «jrestly default (JDK)» в BENCHMARKS.md**; закрепить в
     `JRestlyBenchmarkClient(baseUrl)` явный `new JdkHttpEngine(...)`/или engine
     из ModuleInfo и добавить это примечание в BENCHMARKS.md.
2. Мелочь: adr-0058 остался Next free ADR 0059 — следующий ADR 0059.
3. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`,
   внешний timeout; ветка agent-loop; WireMock без явного Content-Length
   отвечает chunked (урок #156); JMH: 1 fork, машина шумная — цифры для
   сравнения групп, не ранжирования.
