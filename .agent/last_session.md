# Last Session: #323 (2026-10-19)

## Что сделано

**Сессия #323: Открытие цикла 0.12.0, архитектурный дизайн Netty Transport Engine (ADR-0070 Фаза 1), субмодуль `engine-netty`, регистрация SPI, гейт 1,715/1,715.**

1. *Приём состояния:* HEAD на #322, чистое дерево. Релиз 0.11.0 упакован и проверен.
2. *ADR-0070 оформлен (`docs/0.12.0/ADR-0070-http-engine-netty-transport.md`, Proposed):*
   - Ответ на архитектурный вопрос Антона по Netty (mailbox 2026-10-19 18:45): анализ non-blocking EventLoop I/O и Pooled off-heap ByteBuf (`PooledByteBufAllocator`).
   - Инварианты: 100% zero-dependency ядро, изоляция Netty в подключаемом субмодуле `jrestly-engine-netty` (`engine-netty/`), реализация SPI `HttpEngine` и `HttpEngineProvider` (priority = 60).
   - Архитектурный дизайн: `AbstractChannelPoolMap` (пул соединений с keep-alive), push-to-pull streaming мост `NettyChunkedInputStream` с контролем backpressure через `ChannelConfig.setAutoRead` и строгим контролем освобождения `ByteBuf.release()` (`-Dio.netty.leakDetection.level=PARANOID`), нативный `sendAsync`.
3. *Создание субмодуля `engine-netty` (Фаза 1):*
   - `settings.gradle`: добавлен `include 'engine-netty'`.
   - `engine-netty/build.gradle`: настроен по стандартам проекта (Java 21, `-parameters`, `-Werror`, `javadoc -Werror`, `maven-publish`, Netty 4.1.118.Final, WireMock).
   - `package-info.java`, каркас `NettyHttpEngine` и `NettyHttpEngineProvider` (priority = 60).
   - Регистрация в `META-INF/services/ru.jrestly.engine.HttpEngineProvider`.
4. *Тесты (+5, `NettyHttpEngineProviderTest`):*
   - `providerReportsAvailableAndPriority` (isAvailable true, priority 60);
   - `serviceLoaderDiscoversProvider` (ServiceLoader находит NettyHttpEngineProvider);
   - `resolverPicksNettyEngine` (HttpEngineResolver выбирает Netty по приоритету);
   - `createEngineMapsModuleInfo` (создание из ModuleInfo);
   - `engineLifecycleAndClose` (graceful shutdown EventLoopGroup).
5. *Гейты:* **1,715 / 1,715** green во всех 6 модулях (root 1609 + engine-okhttp 25 + engine-apache5 25 + engine-netty 5 + codec-jackson-xml 15 + benchmarks 36). Javadoc чист под `-Werror`. JaCoCo ядра: BRANCH 90.64%, INSTRUCTION 98.19%, LINE 97.84%, COMPLEXITY 88.90%, METHOD/CLASS 100%.

## Контекст для следующей сессии (#324)

1. **Ветка** `agent-loop`.
2. **Highest ADR:** **0070** (`docs/0.12.0/ADR-0070-http-engine-netty-transport.md`), next free **0071**.
3. **Статус эпика Netty Transport Engine (ADR-0070):**
   - Фаза 1 — завершена (ADR-0070, субмодуль `engine-netty`, SPI, 5 тестов).
   - **СЛЕДУЮЩИЙ ХОД (#324, Фаза 2):** реализация полного пайплайна в `NettyHttpEngine` (кодеки HTTP/1.1, `AbstractChannelPoolMap` для keep-alive соединений, push-to-pull `NettyChunkedInputStream` со строгим `release()` для `@Get InputStream`).
4. **Замечание по окружению:**
   - Команды gradle запускать с `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
5. Метрики — гейт **1,715/1,715**; BRANCH 90.64%, INSTRUCTION 98.19%, LINE 97.84%, COMPLEXITY 88.90%, METHOD/CLASS 100%.
