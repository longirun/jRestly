# Last Session: #145 (2026-10-15)

## Что сделано

**Кандидат №3 (развилка Антона №1) — стенд бенчмарков починен; сняты первые честные цифры.**

1. **`BenchmarkHttpServer` переписан на zero-dep raw-TCP HTTP/1.1:**
   - `ServerSocket` (bind `127.0.0.1:0`, backlog 128) + виртуальные потоки на accept/соединения.
   - `setTcpNoDelay(true)` на каждом accepted-сокете.
   - Ответ (статус + заголовки + тело) собирается в один `byte[]` и пишется одним `write` + flush.
   - Keep-alive по `Content-Length`; поддерживает `Connection: close`, `Expect: 100-continue`,
     chunked-запросы; статические эндпоинты предвычислены.
   - `stop()`: `running=false` → `executor.shutdownNow()` (прерывает accept-loop) →
     `await` выхода accept-loop (`CountDownLatch`, ≤2 с) → закрытие сокетов.
     Причина: `close()` во время blocked `accept()` завершает TCP-хендшейк новых коннектов
     уже после возврата из `close()` (зонд: **44/200** «принятых после stop» → **0/200** после фикса).
   - Публичный API сохранён (`start()`, `stop()`, `port()`, `baseUrl()`, обе JSON-константы);
     добавлен `implements AutoCloseable`.
2. **`BenchmarkHttpServerTest` (6 тестов, `benchmarks/src/test`):**
   - все эндпоинты на одном keep-alive соединении (+ проверка `Content-Length`);
   - chunked-запрос; `Connection: close` → сервер закрывает сокет;
   - 404 / 405 / query-строка;
   - **латентностный гейт**: 10 keep-alive `GET /api/ping`, min < 25 мс (защита от возврата артефакта);
   - `stop()`: закрывает живые соединения и не принимает новые.
   - **Red-check выполнен**: на старом сервере (git HEAD) — `keep-alive round trip took 43.949933 ms`
     (тест красный); на новом — зелёный.
3. **Первые честные цифры** (`:benchmarks:jmh`,
   `-PjmhArgs='-f 1 -wi 2 -i 3 -tu us -bm avgt HttpNetworkBenchmark.(...)_getUser$'`;
   3 итерации → предварительно, variance высокая):
   - okHttp 324 µs, Apache5 346 µs, Retrofit 372 µs, raw JDK HttpClient 1384 µs, jrestly (JDK-движок) 1653 µs.
   - Старый стенд давал плоские ~44 000 µs/op у всех клиентов — артефакт устранён.

## Гейты

- `./gradlew test` → BUILD SUCCESSFUL, **1058/1058** (core 1043 без изменений + benchmarks 15, +6 новых).
- Core-модули не менялись: warnings/javadoc/POM без изменений (0/0/0 из #142).
- `:benchmarks:compileJava` — только прежняя deprecation-нота `OkHttpEngine`.

## Контекст для следующей сессии (#146)

### Следующий шаг:
1. Полный прогон матрицы (10 клиентов × getUser/ping + движки jrestly JDK/OkHttp/Apache5/HTTP-1.1)
   с достаточным числом итераций (напр. `-f 2 -wi 3 -i 5 -tu us -bm avgt 'HttpNetworkBenchmark.*'`).
2. Разобрать диспропорцию `getUser`: jrestly 1653 µs vs okHttp 324 µs. Гипотезы: HTTP/2-upgrade
   к HTTP/1.1-серверу у JDK-клиента, кодек jrestly (Jackson) vs кодек OkHttp-бенчмарка,
   аллокации диспетчеризации (сверить с `DispatchOverheadBenchmark`).
3. Затем ADR-0058 (внешние субмодули `OkHttpEngine`/`ApacheHttpEngine`) и публичная
   документация по бенчмаркам.

### Грабли (актуальный набор)
- **Стенд: `com.sun.net.httpserver` на keep-alive = +42 мс/запрос** (Nagle+delayed-ACK, две записи
  ответа). Новый стенд — raw-TCP, `TCP_NODELAY`, один write. Любые «числа ~44 мс/op» — это он.
- **`ServerSocket.close()` vs blocked `accept()`**: после `close()` новые коннекты ещё могут
  завершаться (44/200 в зонде). В `stop()` прерывать accept-loop и дожидаться выхода.
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`** — всегда при запуске `./gradlew`.
- **JMH-аргументы прибора**: `:benchmarks:jmh` при пустом `-PjmhArgs` использует
  `-f 1 -wi 2 -i 3 -tu us -bm avgt`; если передать `-PjmhArgs`, ВСЕ флаги задать самому.
- **Фильтрованный прогон тестов — через `:test`**: `./gradlew :test --tests 'X'` не задевает `:benchmarks:test`.
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
5. Сессия #144 не оставила артефактов (`.agent/session_counter.txt` перескочил 143 → 145).
