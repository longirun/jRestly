# Last Session: #157 (2026-10-16)

## Что сделано

**ADR-0058, фаза 2b: субмодуль `:engine-apache5` создан и полностью зелёный (23/23).**

1. Скаффолд по образцу `engine-okhttp`: `engine-apache5/build.gradle`
   (`api httpclient5:5.4.1`, maven-publish, javadoc-гейт, `-Xlint:all -Werror`),
   `Apache5HttpEngineProvider` (priority 50, ServiceLoader-регистрация),
   `settings.gradle`, `benchmarks` на `project(':engine-apache5')`,
   прототип `benchmarks/.../engine/Apache5HttpEngine` удалён.
2. Движок `Apache5HttpEngine` — полный контракт паритета:
   - timeout: per-request `RequestConfig.responseTimeout/connectionRequestTimeout`;
   - version: репорт согласованного протокола через `HttpClientContext.getProtocolVersion()`
     (classic API не умеет pinning — задокументировано);
   - streaming: `executeOpen` + `ResponseReleasingInputStream` (close стрима →
     response.close → соединение в пул);
   - progress: общий `CountingInputStream` (upload + download), zero-length `(0,0)` ADR-0030/0033;
   - trailers: буферный режим через `entity.getTrailers()` (best-effort try/catch);
   - `sendAsync`: выделенный executor (virtual-thread per engine, или из ModuleInfo),
     НЕ общий ForkJoinPool (грабля прототипа ADR-0058);
   - `close()`: client — только если owned; executor — если создан движком.
   - Маппинг ModuleInfo: TLS (`DefaultClientTlsStrategy`), cookies
     (`JdkCookieStoreAdapter` → Apache CookieStore, best-effort), proxy
     (`ProxySelector` → per-request HttpHost), authenticator
     (`JdkAuthenticatorCredentialsProvider` → JDK Authenticator Basic best-effort).
3. Пассивный транспорт: `disableContentCompression()` — ни `Accept-Encoding`, ни
   прозрачной распаковки (урок #156 применён заранее). Тесты пассивности зелёные
   с первого прогона.
4. Тесты: `Apache5HttpEngineParityTest` — порт okhttp-сьюта (19 тестов, стабы
   прогресса с явным Content-Length), `Apache5HttpEngineProviderTest` (4). Одно
   падение в собственном тесте: после close owned-клиента Apache кидает
   `IllegalStateException`, не `IOException` — тест поправлен.

## Грабли (в копилку)

- **HttpClient 5.4.1: массовые deprecations.** `-Werror` заставляет мигрировать:
  `SSLConnectionSocketFactory`/`setSSLSocketFactory` → `DefaultClientTlsStrategy`/
  `setTlsSocketStrategy`; `RequestConfig.setConnectTimeout` → `ConnectionConfig`
  на connection manager (`setDefaultConnectionConfig`); Date-API куки →
  Instant-варианты; `RequestConfig.setProxy(HttpHost)` — deprecated без замены,
  локальный `@SuppressWarnings("deprecation")`.
- `InputStreamEntity(InputStream, long)` в 5.4.1 НЕТ (только с `ContentType`):
  `new InputStreamEntity(in, len, (ContentType) null)`.

## Гейты (честный статус)

- Полный `./gradlew test`: **1109/1109** (core 1048 + engine-okhttp 23 +
  engine-apache5 23 + benchmarks 15), 0 failures, ~9 с. 0 warnings.
- javadoc-гейт субмодуля настроен; ADR-0058 фаза 2b закрыта по коду.

## Контекст для следующей сессии (#158)

1. **Завершение ADR-0058 — публичная бенчмарк-документация** (развилка Антона):
   - опционально перепрогон матрицы JMH `-f 2 -i 5` (снизить variance), или брать
     честную таблицу #147;
   - оформить `docs/` страницу: методология (raw-TCP стенд + TCP_NODELAY,
     10 клиентов), таблица µs/op, честные ограничения (диспропорция JDK
     транспорта — не декларативного слоя; DispatchOverhead 5–9 µs);
   - сверить ADR-0058 (Status: Proposed → Accepted?) и DRAFT после закрытия.
2. Возможные кандидаты дальше: test-javadoc (ADR-0013), Trailers & streaming
   ergonomics.
3. **Среда:** `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`,
   всегда внешний `timeout`; ветка `agent-loop`; версии 0.9.0, next ADR — 0059.
   WireMock без явного Content-Length отвечает chunked (урок #156).
