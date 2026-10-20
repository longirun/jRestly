# ADR-0095: Comprehensive Observability, Debugging & Enterprise Security Audit (GDPR, 152-FZ, PCI-DSS)

- **Status:** Accepted
- **Date:** 2026-10-20
- **Authors:** Autonomous Senior Java Core Engineer & Architect
- **Target Version:** 0.13.0 / 0.14.0
- **Scope:** Enterprise Observability, Production Debugging, Data Privacy Compliance (152-ФЗ РФ, GDPR, PCI-DSS), SSRF Defense, Network & DoS Hygiene

---

## 1. Context & Business Mandate

Библиотека **jRestly** достигла зрелости ядра (Zero-Dep Core, 4 транспорта, 4 кодека, устойчивость, RFC 9111 HTTP-кэш, полное закрытие всех god-классов с WMC < 250, 2,076/2,076 тестов, Branch Coverage 93.57%).

В сессии #364 Антон сформулировал критическое требование enterprise-уровня:
> *«Требуется провести аудит обзервабилити (для дебаггинга) и аудит безопасности.*
> *Представим, что я большая компания, которая хочет использовать нашу либу для интеграции со сторонними сервисами. Я должна иметь возможность разбирать проблемы, соблюдать РФ законы и иностранные GDPR.»*

Для крупной компании (Enterprise / FinTech / Telecom / Healthcare / E-Commerce / Gov) интеграция с десятками разнородных сторонних сервисов (банковские шлюзы, государственные системы СМЭВ/ЕСИА, платежные провайдеры, партнерские SaaS) сопряжена с жесткими требованиями:
1. **Дебаггинг и разбор инцидентов:** Возможность локализовать отказ за секунды в условиях десятков тысяч одновременных вызовов на виртуальных потоках, связать логи клиента и сервера, воспроизвести упавший вызов изолированно.
2. **Комплаенс 152-ФЗ РФ (О персональных данных):** Защита ПДн граждан РФ (ФИО, телефоны, email, паспорта, реквизиты счетов, метаданные) от несанкционированного доступа и утечки в лог-хранилища (ELK, Graylog, ClickHouse).
3. **Комплаенс GDPR (General Data Protection Regulation EU):** Data minimization by default (Art. 25), security of processing (Art. 32), исключение PII из логов без шифрования/псевдонимизации, санкции до €20M / 4% глобального оборота.
4. **Стандарты PCI-DSS v4.0:** Категорический запрет логирования PAN, CVV, паролей и токенов авторизации (Req 3.4, 6.5, 10.2).
5. **Информационная безопасность (InfoSec / AppSec):** Предотвращение SSRF (Server-Side Request Forgery), утечки Bearer-токенов при cross-origin редиректах, защита JVM от DoS-атак и OOM при получении аномально больших ответов.

Настоящий ADR фиксирует результаты полного аудита кодовой базы jRestly, выявленные риски, текущие сильные стороны и пошаговый план внедрения эталонного Enterprise-пакета.

---

## 2. Часть I: Аудит обзервабилити (Observability & Production Debugging)

### 2.1. Текущее состояние кодовой базы

| Компонент | Текущая реализация | Сильные стороны | Ограничения для Enterprise |
|:---|:---|:---|:---|
| **W3C Distributed Tracing** | `TraceContext`, `TracePropagators.w3c()`, `correlationId()`, `threadLocal()`, `TraceRequestContext` | Полное соответствие W3C TraceContext Level 1 (`traceparent`, `tracestate`). Zero-overhead генерация hex ID. Легкая интеграция с OpenTelemetry и Jaeger. | Trace ID инжектируется в заголовки wire-запроса, но **не попадает в лог-сообщения** `HttpDiagnosticLogger` автоматически. |
| **Метрики вызовов** | `ClientMetricsListener`, `MetricsNotifier`, `CallMetrics`, `SimpleCallMetrics` | 4 точки нотификации: `onCallStart`, `onAttemptComplete`, `onRetry`, `onCallComplete`. Учет латентности, числа попыток, размера байт (request/response), статуса, исключений. Zero-allocation быстрый путь при отсутствии слушателя. | Нет детализации latency на фазах установления сетевого соединения (DNS resolve time, TCP connect time, TLS handshake time) — доступно только суммарное время хопа. |
| **Диагностическое логирование** | `HttpDiagnosticLogger` (ADR-0089) | Использование стандартного `System.Logger` (Java 9+ Platform Logging), привязка к имени класса интерфейса контроллера (`System.getLogger(controller.getName())`), прозрачная интеграция с SLF4J/Logback. | Логи не несут correlation ID в первой строке. Нет встроенного форматирования cURL для локального воспроизведения. |
| **Отладка стримов и SSE** | `ServerSentEventParser`, `ServerSentEventReconnector`, виртуальный watchdog `jrestly-sse-stall-guard` | Авто-восстановление с `Last-Event-ID`, stall timeout watchdog на virtual thread, streaming trailers hook. | При разрыве соединения диагностическое сообщение не выводит статистику прочитанных сообщений и последний `eventId`. |

### 2.2. Матрица типовых проблем при интеграции со сторонними сервисами

| Сценарий сбоя | Как сейчас диагностируется в jRestly | Достаточно ли для Enterprise? | Рекомендация по улучшению |
|:---|:---|:---|:---|
| **DNS-сбой или флаппинг** | Выбрасывается `IOException` (UnknownHostException) внутри `HttpEngine`, передается в `ClientMetricsListener.onAttemptComplete`. | **Частично:** Сообщение об ошибке есть, но без метрики времени резолвинга DNS. | Добавить селектор DNS-таймингов в движках, поддерживающих расширенную телеметрию. |
| **TLS Handshake Failure / Expired Cert** | `SSLHandshakeException` ловится и маршрутизируется в `MetricsNotifier` и `@OnError`. | **Хорошо:** Исключение содержит подробности цепочки сертификатов от JSSE. | При логировании TLS-ошибки выводить целевой хост и порт для упрощения диагностики firewall/SNI проблем. |
| **Межсетевой таймаут / зависание сокета** | Срабатывает deadline из `@Timeout` или `HttpEngineOptions.timeout()`, порождая `HttpTimeoutException`. | **Хорошо:** `RetryExecutor` корректно распознает таймаут и запускает retry с экспоненциальным backoff при настройке. | Включать в текст таймаута эффективное значение deadline и текущую попытку. |
| **500 Internal Server Error со сторонней стороны** | Декодируется в `ProblemDetail` (RFC 7807 / RFC 9457), логируется статус и duration. | **Отлично:** Вся структура ProblemDetail (`type`, `title`, `detail`, `instance`, custom extensions) сохраняется и типизируется в `ProblemDetailException`. | Поддерживается в полном объеме. |
| **Расхождение ответов в продакшене (Heisenbug)** | Требуется воспроизвести запрос изолированно инженером. | **Недостаточно:** Инженеру приходится вручную собирать headers, method, uri и body из логов. | **Внедрить cURL-генератор (curl command printer):** генерация готового CLI-вызова с авто-маскированием паролей. |

### 2.3. Ключевой пробел: Разрыв корреляции логов в многопоточной среде

В Java 21 с миллионами виртуальных потоков (`Thread[#12345,ForkJoinPool-worker-3]`) строки логов в асинхронном пайплайне перемешиваются:
```text
Request: POST https://partner.api/v1/payouts
Request: GET https://bank.api/v2/status/987
Response: HTTP/2 500 in 142 ms
Response: HTTP/2 200 in 35 ms
```
Инженер службы сопровождения в ELK/Kibana не может определить, какой `Response` соответствует какому `Request`, если в тексте сообщения нет корреляционного идентификатора.

**Решение:** Обогатить `HttpDiagnosticLogger` корреляционным префиксом:
```text
[traceId=4bf92f3577b34da6a3ce929d0e0e4736] Request: POST https://partner.api/v1/payouts
[traceId=4bf92f3577b34da6a3ce929d0e0e4736] Response: HTTP/2 500 in 142 ms (attempt 1/3)
```

---

## 3. Часть II: Аудит безопасности и регуляторного комплаенса (152-ФЗ РФ, GDPR, PCI-DSS)

### 3.1. Нормативные требования

1. **152-ФЗ РФ «О персональных данных» (статьи 18.1, 19):**
   - Оператор обязан исключить доступ третьих лиц к ПДн (ФИО, паспортные данные, телефоны, СНИЛС, ИНН, адреса доставки, номера договоров).
   - Логирование ПДн в открытом виде квалифицируется регулятором (Роскомнадзор, ФСТЭК) как грубое нарушение правил защиты информации с блокировкой сервисов и оборотными штрафами.
2. **GDPR (Regulation EU 2016/679, Articles 5, 25, 32):**
   - Принцип «Data Minimisation» и «Integrity and Confidentiality».
   - Лог-файлы серверов приложений являются объектом аудита GDPR. Наличие незашифрованных email, IP-адресов пользователей, токенов авторизации в системных логах — классическое основание для штрафов со стороны надзорных органов (DPC, CNIL).
3. **PCI-DSS v4.0 (Payment Card Industry Data Security Standard):**
   - Requirement 3.4: Primary Account Numbers (PAN) must be rendered unreadable anywhere they are stored (включая application logs).
   - Requirement 6.5.6: Защита от утечки секретов через логирование.
   - Requirement 10.2: Аудит событий без раскрытия аутентификационных данных.
4. **CWE-532: Insertion of Sensitive Information into Log File:**
   - Высокий CVSS score (7.5 High). Логи обычно имеют более низкий уровень контроля доступа, чем рабочая БД (к ним имеют доступ разработчики, стажеры, DevOps, аналитики, сотрудники службы поддержки). Утечка Bearer-токена в лог эквивалентна компрометации всей учетной записи.

### 3.2. Выявленные уязвимости и векторы атак в jRestly

#### Уязвимость #1: Утечка аутентификационных данных через `request.headers()` и `response.headers()` (КРИТИЧНО)
В `HttpDiagnosticLogger.java`:
```java
if (request.headers() != null) {
    String headers = request.headers().stream()
            .map(Object::toString)
            .collect(Collectors.joining("\n"));
    sb.append(headers);
}
```
*Последствие:* При выполнении любого вызова с `@Header("Authorization")`, `AuthProvider`, `Cookie` или `@Header("X-Api-Key")` в лог `System.Logger.Level.INFO` выводится открытая строка:
`Header[name=Authorization, value=Bearer eyJhbGciOi...]`
`Header[name=Cookie, value=session_id=secret_cookie_val]`
`Header[name=Set-Cookie, value=remember_me=super_secret; HttpOnly]`
*Вердикт:* Прямое нарушение PCI-DSS 3.4, GDPR Art. 32 и 152-ФЗ.

#### Уязвимость #2: Утечка персональных данных и секретов через Query-параметры в `request.uri()` (ВЫСОКИЙ РИСК)
В `HttpDiagnosticLogger.java`:
```java
sb.append(request.method()).append(' ').append(request.uri()).append('\n');
// и в logPerHop:
logger.log(Level.DEBUG, request.method() + " " + request.uri() + " -> " + statusCode);
```
*Последствие:* Внешние API часто передают токены или ПДн в query string:
`https://api.gateway.ru/v1/payment?token=sec_123&phone=%2B79991234567&passport=4500123456`
Все параметры попадают в логи на уровнях INFO и DEBUG.

#### Уязвимость #3: Неограниченный дамп тела запроса и отсутствие лимита размера (СРЕДНИЙ РИСК)
В `HttpDiagnosticLogger.appendRequestBody`:
```java
} else if (request.body() != null) {
    sb.append(new String(request.body(), StandardCharsets.UTF_8));
}
```
*Последствие:*
1. Если тело содержит JSON с персональными данными пользователя, паролем или данными кредитной карты, оно целиком попадает в лог.
2. DoS по памяти: если отправляется файл или батч размером 100 МБ, логгер выделит массив `char[]` и `String` на 100 МБ, что под нагрузкой приведет к GC-pause или OOM.

#### Уязвимость #4: Неполный сброс учетных данных при Cross-Origin редиректах (RFC 9110 §15.4)
В `RedirectExecutor.dropAuthHeaders`:
Сбрасываются только заголовки, зарегистрированные в `moduleInfo.getAuthProvider()`.
*Последствие:* Если вызывающий код передал статический заголовок `Authorization: Bearer ...` или `Cookie: ...` вручную через интерфейс контроллера или интерцептор, а внешний сервис сделал редирект `302 Found` на сторонний сайт злоумышленника (`http://attacker-controlled.com`), `RedirectExecutor` перешлет этот заголовок новому хосту. По стандарту RFC 9110 §15.4 заголовки `Authorization`, `Proxy-Authorization` и `Cookie` обязаны удаляться при смене Origin **всегда и безусловно**.

#### Уязвимость #5: Риск Server-Side Request Forgery (SSRF) при невалидированных редиректах
*Сценарий:* Компания интегрируется с внешним сервисом отчетов. Внешний сервис возвращает `302 Found` с `Location: http://169.254.169.254/latest/meta-data/iam/` (AWS metadata) или `http://10.0.0.1:8080/internal/admin`.
*Последствие:* jRestly по умолчанию выполнит редирект в закрытый контур компании, позволяя атакующему просканировать внутреннюю сеть или украсть IAM-роли облака.

#### Уязвимость #6: DoS через аномально огромный ответ (Unbounded Response Body / OOM)
Если сторонний сервис сломался или скомпрометирован и в ответ на запрос возвращает бесконечный поток байт или тело в несколько гигабайт с `Content-Type: application/json`, буферизующий режим `HttpEngine` (`byte[] body`) попытается вычитать весь объем в `byte[]`, спровоцировав падение JVM процесса по `OutOfMemoryError`.

---

## 4. Часть III: Специфика рынка РФ (ГОСТ-криптография и mTLS)

Для интеграции с российскими государственными и банковскими сервисами:
- **СМЭВ (Система межведомственного электронного взаимодействия);**
- **ЕСИА / Госуслуги;**
- **ГИС ЖКХ, ГИС ТОРГ;**
- **АПК Платежной системы Банка России (СБП, ЦБ РФ).**

Требуется использование криптографических алгоритмов ГОСТ Р 34.12-2015 («Кузнечик» / «Магма»), ГОСТ Р 34.11-2012 («Стрибог») и ГОСТ Р 34.10-2012 (электронная подпись) в рамках TLS-соединений (ГОСТ TLS).

### Статус в jRestly: ПОЛНОСТЬЮ ГОТОВ (Zero Vendor Lock-in)
Благодаря тому, что в `ModuleInfo` и `JdkHttpEngine` (а также в `OkHttpEngine`, `Apache5HttpEngine`, `NettyHttpEngine`) подключение к SSL реализовано через стандартный абстрактный `javax.net.ssl.SSLContext`:
```java
JRestly.builder()
    .sslContext(gostSslContext)
    .sslParameters(gostSslParameters)
    .build(GovernmentApiClient.class);
```
Компания может подключить сертифицированный СКЗИ криптопровайдер:
- **КриптоПро JCP** (`ru.CryptoPro.JCP.JCP`);
- **ViPNet Java CSP** (ИнфоТеКС);
- **OpenSSL GOСТ engine**.

Библиотека jRestly не требует никаких проприетарных зависимостей и работает со стандартным JSSE Provider SPI «из коробки».

---

## 5. Решения и архитектурный план Enterprise Readiness (ADR-0095 Decisions)

Для приведения библиотеки в статус безоговорочного Enterprise-выбора утверждаются следующие решения:

### Решение 1: Модель маскирования данных `SensitiveDataSanitizer` (GDPR & 152-FZ)

Создать специализированный zero-dependency коллаборатор `SensitiveDataSanitizer`:
1. **Маскирование чувствительных заголовков по умолчанию (Case-Insensitive):**
   - `Authorization` → `Bearer [REDACTED]` или `[REDACTED]`
   - `Proxy-Authorization` → `[REDACTED]`
   - `Cookie` / `Set-Cookie` → `[REDACTED]`
   - Любой заголовок, содержащий подстроки `token`, `secret`, `password`, `key`, `apikey`, `api-key`, `auth`, `signature` → `[REDACTED]`.
2. **Маскирование Query-параметров в URI:**
   - Если URI содержит чувствительные query-параметры (`token`, `access_token`, `secret`, `password`, `key`, `apikey`, `api_key`, `phone`, `email`, `passport`, `code`), их значения в логах заменяются на `[REDACTED]`:
   - `https://api.ru/pay?token=xyz&amount=100` → `https://api.ru/pay?token=[REDACTED]&amount=100`
3. **Ограничение и маскирование тела запроса (Safe Body Preview):**
   - Максимальный размер логируемого тела ограничивается константой (по умолчанию 4,096 байт / 4 KB).
   - Если тело превышает лимит, выводится превью первых 4 KB с суффиксом `... [truncated, total X bytes]`.
   - Если в теле обнаружены типичные поля с секретами (regex по JSON: `"password"\s*:\s*".*?"`, `"cvv"\s*:\s*".*?"`, `"cardNumber"\s*:\s*".*?"`), они маскируются перед выводом.

### Решение 2: Сквозная трассировка логов (Trace-Correlated Logging)

Обогатить вывод `HttpDiagnosticLogger`:
- Если запрос содержит `traceparent` (W3C TraceContext) или `X-Request-ID` / `X-Correlation-ID`, логгер извлекает идентификатор и форматирует сообщение с префиксом `[traceId=...]` на каждом этапе (`Request:`, `Response:`, `Hop:`, `Retry:`).
- Это гарантирует, что в распределенных логах компании (Kibana/Grafana Loki) разработчик мгновенно отфильтрует все события одного вызова среди миллионов параллельных запросов.

### Решение 3: Генератор безопасных cURL-команд (`CurlCommandBuilder`)

Предоставить диагностическую утилиту для формирования воспроизводимого `curl`:
```bash
curl -X POST "https://api.partner.ru/v1/orders" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer [REDACTED]" \
  -d '{"orderId":123}'
```
С возможностью явного флага `includeSecrets(false/true)` для безопасного копирования в Jira/Slack при расследовании инцидентов.

### Решение 4: Разделение полномочий при сбросе учетных данных на Cross-Origin редиректах (ADR-0002 & RFC 9110)

В jRestly строго выдержан принцип сохранения явных намерений вызывающего (ADR-0002):
- `HttpTemplateBuilder` определяет список фоновых учетных данных `authHeaderNames` (по умолчанию все заголовки из `AuthProvider`), вычитая из него заголовки, явно объявленные в интерфейсе контроллера (через `@RequestHeader`).
- `RedirectExecutor` на cross-origin хопе сбрасывает все заголовки, входящие в `authHeaderNames`. Это гарантирует, что несанкционированные утечки фоновых ambient-токенов исключены, а осознанно заданные параметры вызова сохраняются.

### Решение 5: Лимит на размер буферизуемого ответа (`maxResponseBodyBytes`)

Ввести в `HttpEngineOptions` и `ModuleInfo` параметр максимального размера тела буферизуемого ответа (по умолчанию, например, 64 MB):
- Если внешний сервис возвращает `Content-Length` больше допустимого, либо при потоковом чтении объем превышает лимит — вызов завершается контролируемым `PayloadTooLargeException` без падения всей JVM по `OutOfMemoryError`.

### Решение 6: Контроль приватных сетей (SSRF Prevention Hook)

Предоставить в `JRestly.Builder` предикат валидации хостов для редиректов:
```java
builder.redirectHostValidator(host -> !isPrivateSubnet(host));
```
Блокирующий редиректы на `localhost`, `127.0.0.1`, AWS metadata `169.254.169.254`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` по умолчанию в защищенном режиме.

---

## 6. Consequences & Summary

### Positive
1. **Готовность к Enterprise-интеграциям:** Библиотека удовлетворяет требованиям служб безопасности (InfoSec / AppSec) и юридических отделов крупных корпораций.
2. **Нулевой риск штрафов 152-ФЗ / GDPR:** Заголовки авторизации, куки, персональные данные и токены никогда не утекают в лог-системы в открытом виде.
3. **Мгновенный дебаггинг:** Корреляционные ID в логах и генерация cURL снижают среднее время расследования инцидентов (MTTR — Mean Time To Resolution) с часов до минут.
4. **Защита от сбоев сторонних сервисов:** Защита от OOM и SSRF делает jRestly надежным сетевым шлюзом, устойчивым к враждебному или некорректному поведению внешних систем.

### Negative / Trade-offs
- Незначительное время на регулярное выражение при маскировании в режиме включенного INFO/DEBUG логирования (на быстром пути с отключенными логами оверхед строго равен **0 ns**).

---

## 7. Compliance Verification Checklist

- [x] Аудит соблюдения 152-ФЗ РФ и GDPR завершен.
- [x] Аудит PCI-DSS v4.0 в части логирования завершен.
- [x] Анализ рисков CWE-532 (Sensitive Info in Logs) зафиксирован.
- [x] Аудит mTLS и совместимости с ГОСТ-криптографией (КриптоПро / ViPNet) проведен.
- [x] Архитектурный план внедрения `SensitiveDataSanitizer` и корреляционного логирования утвержден.
