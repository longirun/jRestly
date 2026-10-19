# Last Session: #324 (2026-10-19)

## Что сделано

**Сессия #324: Реализация Netty Transport Engine (ADR-0070 Фаза 2), connection pooling через FixedChannelPool, streaming bridge NettyChunkedInputStream под PARANOID leak detection, 21 паритетный тест, гейт 1,736/1,736 (+21).**

1. *Приём состояния:* HEAD на #323, чистое дерево. Субмодуль `engine-netty` подготовлен, SPI зарегистрирован.
2. *Реализация `NettyHttpEngine`:*
   - Архитектура пула: `AbstractChannelPoolMap<EndpointKey, FixedChannelPool>` с per-route изоляцией `(scheme, host, port)`, `ChannelHealthChecker.ACTIVE` и keep-alive connection reuse;
   - Формирование запросов: HTTP/1.1 RFC-нормализация path + query, Host header, Cookie integration, Content-Type override priority, keep-alive headers;
   - Обработка тел запросов: byte[] с чанковой отправкой (16 KB) под `uploadProgressListener`, multipart RFC 7578 (`parts()`) и `bodyPublisherSupplier()` через credit-based `PublisherInputStream`;
   - Обработка ответов: buffered mode (`ByteArrayOutputStream`, `downloadProgressListener`, trailing headers) и streaming mode (немедленный возврат `WireResponse` с живым `NettyChunkedInputStream`).
3. *Push-to-Pull Streaming Bridge `NettyChunkedInputStream`:*
   - Связывает реактивные чанки Netty и блокирующий `InputStream` с backpressure (`channel.config().setAutoRead(false)` при 8 чанках и возобновлением при 2 чанках);
   - Гарантированное освобождение памяти `ByteBuf.retain()` / `release()` под `-Dio.netty.leakDetection.level=PARANOID` — 0 утечек;
   - Немедленная разблокировка читателя при `stream.close()` с `IOException("Stream closed")`;
   - Трейлеры становятся видны через `trailersSupplier().get()` только после полного вычитывания тела (`eof == true`).
4. *Паритетный сьют тестов (+21 тест, `NettyHttpEngineParityTest`):*
   - 21 тест против WireMock, подтверждающий полное соответствие контракту `HttpEngine` (buffered/streaming, progress listeners, chunked transfer, trailers, passive compression, call timeouts, cancel, lifecycle ownership).
5. *Гейты:* **1,736 / 1,736** green во всех 6 модулях (root 1609 + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + benchmarks 36). Javadoc чист под `-Werror`. Метрики ядра сохранены на историческом пике (BRANCH 90.64%, INSTRUCTION 98.19%, LINE 97.84%, COMPLEXITY 88.90%, METHOD/CLASS 100%).

## Контекст для следующей сессии (#325)

1. **Ветка** `agent-loop`.
2. **Highest ADR:** **0070** (`docs/0.12.0/ADR-0070-http-engine-netty-transport.md`), next free **0071**.
3. **Статус эпика Netty Transport Engine (ADR-0070):**
   - Фаза 1 — завершена (ADR-0070, субмодуль `engine-netty`, SPI, 5 тестов).
   - Фаза 2 — завершена (`NettyHttpEngine`, `FixedChannelPool`, `NettyChunkedInputStream`, 21 паритетный тест).
   - **СЛЕДУЮЩИЙ ХОД (#325, Фаза 3):** подключение `project(':engine-netty')` в `benchmarks/build.gradle`, добавление 12-го arm'а `jrestlyNetty_buffered` и `jrestlyNetty_streaming` в `FeedBenchmark`, замеры Throughput, Heap Allocations (`-prof gc`) и CPU vs Apache5, OkHttp и JDK HttpClient.
4. **Замечание по окружению:**
   - Команды gradle запускать с `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
5. Метрики — гейт **1,736/1,736**; BRANCH 90.64%, INSTRUCTION 98.19%, LINE 97.84%, COMPLEXITY 88.90%, METHOD/CLASS 100%.
