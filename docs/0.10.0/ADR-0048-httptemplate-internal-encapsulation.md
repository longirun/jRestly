# ADR-0048: HttpTemplate Internal Encapsulation and Package-Private Engine

## Status
Accepted

## Context
В ранних версиях библиотеки класс `ru.jrestly.http.HttpTemplate` выступал промежуточным объектом для сборки параметров вызова, создавался вручную и имел более 50 `public` setter-ов, а также `public` методы `exchange()`, `handleDecompression()`, `createUri()`.

Однако в архитектуре библиотеки:
1. `HttpTemplate` является чисто внутренней деталью реализации конвейера выполнения запросов пакета `ru.jrestly.http`.
2. Внешний пользователь библиотеки никогда не должен создавать или конфигурировать `HttpTemplate` напрямую: публичным фасадом является `JRestlyClient`, а фабрикацией запроса занимается `HttpTemplateBuilder`.
3. Наличие публичного класса `HttpTemplate` с десятками мутабельных сеттеров загрязняло публичный API и Javadoc библиотеки, нарушая инкапсуляцию ядра («leaked internal»).

## Decision
1. **Инкапсуляция `HttpTemplate`:**
   - Объявить `ru.jrestly.http.HttpTemplate` как `class HttpTemplate` (package-private).
   - Сделать конструктор `HttpTemplate()` package-private.
   - Сделать все сеттеры, геттеры и внутренние методы (`exchange()`, `createUri()`, `handleDecompression()`) package-private.
   - Класс полностью исчезает из публичного API и Javadoc библиотеки.

2. **Эргономика `HttpTemplateBuilder`:**
   - Добавить публичный терминальный метод `public <T> T execute()`, который инкапсулирует `build().exchange()`.
   - Сделать метод `build()` package-private: `HttpTemplate build()`.
   - Добавить fluent-метод `public HttpTemplateBuilder logger(System.Logger logger)` для передачи контекстного логгера.

3. **Развязка `AbstractHttpClient`:**
   - `AbstractHttpClient` в пакете `ru.jrestly` использует `builder.execute()` вместо `builder.build().exchange()`.
   - Зависимость и импорт `HttpTemplate` удалены из `AbstractHttpClient`.

4. **Организация тестов:**
   - Тесты, проверяющие внутреннее устройство конвейера запросов и разрешение настроек в `HttpTemplateBuilder`/`HttpTemplate`, размещаются в тестовом пакете `ru.jrestly.http`, где действует естественная видимость package-private типов.
   - Тесты валидации контроллеров и пользовательского API (`ControllerValidator`, `JRestlyClient`) остаются в `ru.jrestly`.

## Consequences
- **Плюсы:**
  - 100% сокрытие внутренних деталей конвейера HTTP: из публичного API удалено 57 мутабельных сеттеров.
  - Нулевая возможность ошибочного использования `HttpTemplate` пользователями библиотеки.
  - Четкое разделение ответственности: `AbstractHttpClient` делегирует выполнение `HttpTemplateBuilder.execute()`.
- **Минусы / Замечания:**
  - Тесты, инспектирующие внутреннее состояние `HttpTemplate`, должны располагаться в пакете `ru.jrestly.http`.
