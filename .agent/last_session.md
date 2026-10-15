# Last Session: #143 (2026-10-15)

## Что сделано

**Старт кандидата №3 (развилка Антона №1) — сравнительный бенчмаркинг.**

1. **Матрица сравнения доукомплектована:**
   - Новый `benchmarks/.../client/OkHttpBenchmarkClient.java` — raw-OkHttp бейзлайн
     (getUser DTO, ping, batch, POST; `TypeReference<List<UserDto>>`).
   - В `HttpNetworkBenchmark` добавлены jRestly-варианты движков по паритету с
     `ConcurrentLoadNetworkBenchmark`: `jrestlyOkHttp_*`, `jrestlyApache5_*`,
     `jrestlyHttp11_*` + `okHttp_*` (группы getUser + ping).
   - `benchmarks/build.gradle`: `okhttp3:okhttp:4.12.0` задекларирован явно
     (раньше транзитивно 3.14.9 от Retrofit 2.11). Движок Антона (`OkHttpEngine`)
     компилируется без правок.

2. **🎯 Главная находка — стенд бенчмарков измерял TCP-артефакт:**
   - Прогон `HttpNetworkBenchmark.*getUser` (JMH): **все десять клиентов** дали
     плоские ~44–48 мс/op — разница между библиотеками замаскирована.
   - Диагностика зондами вне JMH (`/tmp/opencode/*.java`):
     * голый JDK HttpClient, loopback, keep-alive = 44 мс/op;
     * сырой сокет + `HttpServer`, `Connection: close` = 1.4 мс/op;
     * та же пара на keep-alive = 43.7 мс/op;
     * **решающий зонд:** сырой сервер `TCP_NODELAY=false` + две записи ответа = 43.7 мс/op,
       он же с `TCP_NODELAY=true` = **1.85 мс/op (×23 быстрее)**.
   - Механизм: `com.sun.net.httpserver` не включает `TCP_NODELAY` и пишет ответ
     двумя записями (headers → body); на keep-alive-соединении вторая запись ждёт
     delayed ACK ~40 мс (quickack гасится после старта соединения).
     Системного property в JDK нет (grep по `lib/modules`).
   - **Вывод: публичные цифры нельзя снимать до починки стенда.**

## Гейты

- `./gradlew test` → BUILD SUCCESSFUL, **1052/1052** (0 failures/0 errors;
  `:benchmarks:test` выполнен, `:test` up-to-date — ядро не менялось).
- Core-модули не менялись: warnings/javadoc/POM без изменений (0/0/0 репутация из #142).

## Контекст для следующей сессии (#144)

### Следующий шаг (важный, уже расписан):
1. Заменить `BenchmarkHttpServer` на zero-dep raw-TCP HTTP/1.1 сервер:
   `ServerSocket` + виртуальные потоки, `socket.setTcpNoDelay(true)` на каждом
   accepted-сокете, те же эндпоинты (`/api/users/{id}`, `/api/users/batch`,
   `/api/users` POST, `/api/ping`), graceful stop.
   Ориентир производительности: keep-alive ping должен быть ~1.5–2 мс/op, не 44 мс.
2. Добавить тест на регрессию: keep-alive латентность < ~5 мс (защита от возврата артефакта).
3. Перезапустить `HttpNetworkBenchmark` (матрица уже полная) и снять честные цифры.
4. После этого — ADR-0058 (внешние субмодули OkHttpEngine/ApacheHttpEngine) и публичная документация по бенчмаркам.

### Грабли (актуальный набор)
- **Стенд бенчмарков: keep-alive к `com.sun.net.httpserver` = +42 мс на запрос**
  (Nagle+delayed ACK). Любые «медленные» числа ~44 мс на loopback — это он.
- **`GRADLE_USER_HOME=/tmp/opencode/gradle-home`**: всегда указывать при запуске `./gradlew`.
- **JMH-аргументы прибора**: `:benchmarks:jmh` при пустом `-PjmhArgs` использует
  `-f 1 -wi 2 -i 3 -tu us -bm avgt`; если передать `-PjmhArgs`, ВСЕ флаги нужно задать самому.
  Фильтр метода — last arg regex: `... avgt 'HttpNetworkBenchmark.*getUser$'`.
- **Фильтрованный прогон тестов — через `:test`**: `./gradlew :test --tests 'X'` не задевает `:benchmarks:test`.
- **`Map.copyOf`/`Set.of` не сохраняют порядок** (солт на JVM-запуск): не использовать для order-sensitive данных.
- **Подсчёт тестов:** `grep -h -o 'tests="[0-9]*"' build/test-results/test/*.xml benchmarks/build/test-results/test/*.xml | awk -F'"' '{s+=$2} END{print s}'` (сейчас **1052**).
- **Коммит-субъект: краткий, без дублирования ченджлога** (Антон); body — гейты-факты.
- **No git tags:** теги не создавать.
- **Next free ADR number:** ADR-0058.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`.
3. Версия в `build.gradle`: **0.9.0**. Тесты: **1052/1052**.
4. Warnings: main 0, test 0, javadoc 0 (под `-Werror`). Бенчмарки: есть одна
   deprecation-нота от `OkHttpEngine` (окhttp 4) — безобидная, `-Werror` там не включён.
