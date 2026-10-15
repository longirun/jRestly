# Last Session: #147 (2026-10-15)

## Что сделано

**Кандидат №3 (развилка Антона №1) — полный прогон матрицы + разбор диспропорции jrestly vs OkHttp.**

1. **Полная честная матрица** (JMH, 1 fork, 2 wi + 3 iter, avgt, µs/op; variance высокая —
   машина не выделена, порядок величин стабилен):

   | клиент | getUser | ping |
   |---|---|---|
   | Apache HttpClient 5 | 341 | 363 |
   | Retrofit (OkHttp) | 362 | 312 |
   | OkHttp raw | 428 | 254 |
   | jRestly + OkHttp-движок | 450 | 342 |
   | jRestly + Apache5-движок | 478 | 331 |
   | raw JDK HttpClient | 1364 | 1191 |
   | jRestly + JDK (HTTP/1.1 пин) | 1636 | 1221 |
   | jRestly + JDK (default) | 1524 | 1359 |
   | Feign (JDK) | 2223 | 1803 |
   | Spring RestClient (JDK) | 2591 | 2144 |

2. **Разбор диспропорции (главный вывод):**
   - **Транспорт — не декларативность.** Raw JDK HttpClient (0 строк jRestly) = 1364/1191 µs —
     те же ~1.2–1.4 мс, что и jRestly на JDK-движке. Разрыв jrestly(JDK) − raw JDK =
     всего **+160…170 µs** (конвертация JDK↔Wire-объектов + per-request аллокации).
   - **Диспетчеризация дешёвая:** `DispatchOverheadBenchmark` in-memory = 2.4–9 µs
     полный пайплайн + Jackson (vs 1.3 µs чистый parse). Гипотеза «кодек/диспетчер»
     опровергнута.
   - **h2c-upgrade не подтверждён:** пиннинг HTTP/1.1 не ускорил (в пределах шума).
   - **Engine SPI подтверждён:** jRestly+OkHttp = 450 µs = уровень raw OkHttp (428, +5%).
     Ответ Антону «обойдём ли?»: на JDK-транспорте позади OkHttp в 3–4× (свойство
     JDK HttpClient), на подставленных движках — на уровне эталона. Путь к вершине —
     ADR-0058.
   - Feign/Spring (JDK + своя надстройка) 1.8–2.6 мс — jRestly ближе к чистому транспорту.

3. Полный лог: `/tmp/opencode/jmh_session147.log` (30 Result-блоков, EXIT=0).

## Гейты

- Ядро не менялось: 1058/1058 (core 1043 + benchmarks 15), 0 warnings.
- Изменены: `DRAFT.md` (цифры + выводы в кандидате №3), дневник, mailbox, `last_session.md`.

## Контекст для следующей сессии (#148)

### Следующий шаг:
1. **ADR-0058 — внешние субмодули `OkHttpEngine` / `ApacheHttpEngine`:** концепт + ADR
   в `docs/0.10.0/`. Суть (уже есть в evidence): текущие `benchmarks/.../engine/*` —
   рабочий прототип SPI (ADR-0052); ADR-0058 оформляет вынос в публичные субмодули
   `jrestly-engine-okhttp` / `jrestly-engine-apache5` (каждый со своей зависимостью,
   ядро остаётся zero-dep), выбор дефолтного движка, SPI `HttpEngineProvider`
   (ServiceLoader уже есть в ядре).
2. **Публичная документация по бенчмаркам** (`docs/BENCHMARKS.md` или секция в README):
   методология (raw-TCP стенд, TCP_NODELAY, loopback), таблица цифр, ограничения
   (loopback, 1 fork, variance). **Сначала подтвердить цифры прогоном `-f 2 -i 5`**
   (сейчас CI огромный — публичные цифры должны быть с плотным CI).
3. Опционально (мелкий шаг): зонд, из чего складываются +160 µs декларативного
   overhead на сетевом пути (toHttpRequest/toWireResponse — аллокации списков Header;
   JMH-взвешивание или async-profiler).

### Грабли (актуальный набор)
- **Стенд: raw-TCP + `TCP_NODELAY` + один write** — любые «числа ~44 мс/op» = старый
  `com.sun.net.httpserver` (Nagle+delayed-ACK).
- **`ServerSocket.close()` vs blocked `accept()`**: после `close()` коннекты ещё могут
  завершаться. В `stop()` прерывать accept-loop и дожидаться выхода.
- **JMH на этой машине: 30 бенчмарков ≈ 7 минут** — вписывается в сессию, но не с
  запасом на большие прогоны.
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`** — всегда при запуске `./gradlew`.
- **JMH-аргументы:** при передаче `-PjmhArgs` ВСЕ флаги задавать самому
  (по умолчанию `-f 1 -wi 2 -i 3 -tu us -bm avgt`); несколько regex-фильтров через
  пробел в одной строке работают.
- **Порядок бенчмарков JMH = declaration order, не алфавит** — ранние/поздние бенчмарки
  одного класса могут иметь разный нагрев; разницу <10% между похожими конфигурациями
  не интерпретировать.
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml benchmarks/build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'`
  (сейчас **1058** = core 1043 + benchmarks 15).
- **`Map.copyOf`/`Set.of` не сохраняют порядок** (солт на JVM-запуск).
- **Коммит-субъект: краткий** (Антон); body — гейты-факты.
- **No git tags.** **Next free ADR number:** ADR-0058.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **1058/1058**.
4. Warnings: main 0, test 0, javadoc 0 (`-Werror`). Бенчмарки: deprecation-нота `OkHttpEngine`.
5. Пропуски в нумерации сессий были (144, 146 без артефактов) — сверяться по `git log`,
   а не по счётчику.
