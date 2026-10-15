# Last Session: #149 (2026-10-16)

## Что сделано

**Кандидат №3 (развилка Антона №1) — ADR-0058, фаза 1 (ядро) закрыта.**

1. `HttpEngineProvider` (SPI, ADR-0052) получил `default int priority() { return 0; }`
   — бинарно-совместимое расширение: провайдер без переопределения ведёт себя
   как раньше (приоритет 0). Javadoc: «выше — раньше; равенство → порядок
   ServiceLoader».
2. `HttpEngineResolver.resolveFromSpi`: кандидаты собираются в `ArrayList`,
   сортируются `Comparator.comparingInt(HttpEngineProvider::priority).reversed()`,
   затем обход «первый available с не-null движком». `List.sort` гарантированно
   стабилен (TimSort) → при равенстве приоритетов сохраняется порядок
   `ServiceLoader`. Аллокации не греют горячий путь: резолвится один раз на
   билд клиента, не на запрос.
3. Class-Javadoc `HttpEngineResolver` обновлён (стратегия с приоритетами).
4. TDD red-first в `HttpEngineResolverTest` (+5 тестов, всего 11):
   - КРАСНЫЕ до фикса: `higherPriorityProviderWinsOverIterationOrder`
     (приоритет 50 после 10 в итерации — раньше брался первый available),
     `legacyDefaultPriorityLosesToExplicitPriority` (дефолтный 0 проигрывает 1).
   - Зелёные предохранители: `equalPriorityKeepsIterationOrder` (стабильность),
     `unavailableHighPriorityProviderSkipped` (availability выше приоритета),
     `highPriorityNullEngineFallsThrough` (null-движок → следующий по приоритету).
   - Хелпер `TestProvider` с `priority()` **без** `@Override` — класс тестов
     компилируется и до появления default-метода (чистый assertion-red вместо
     compile-red; при этом после фикса метод тихо переопределяет default).

## Грабли (новые, в дополнении к старому набору)

- **`@Override` на переопределении ещё не существующего default-метода** —
  compile-red для всего класса тестов (все 11 тестов «красные» по компиляции,
  не по ассертам). Для assertion-red в TDD-helper'ах SPI-метод объявлять
  без `@Override` (и прокомментировать почему).
- Семантика fallthrough сохранена: «available, но `createEngine` вернул null»
  больше не останавливает обход — резолвер переходит к следующему по
  приоритету (проверено тестом).

## Гейты

- **1063/1063** (ядро 1048 + benchmarks 15; +5 новых), 0 failures, 0 warnings.
- Изменены: `HttpEngineProvider.java`, `HttpEngineResolver.java`,
  `HttpEngineResolverTest.java`, DRAFT.md, дневник 2026-10-16 (сессия #149),
  mailbox (+1 сообщение), last_session.md.
- Ядро по-прежнему zero-dep (новые импорты — `java.util`).

## Контекст для следующей сессии (#150)

### Следующий шаг — ADR-0058, фаза 2 (субмодули; крупный, можно 2 сессии)
1. `settings.gradle`: `include 'engine-okhttp'`, `include 'engine-apache5'` +
   их `build.gradle` (`api 'com.squareup.okhttp3:okhttp:4.12.0'` /
   `api 'org.apache.httpcomponents.client5:httpclient5:5.4.1'`, maven-publish
   как у ядра, версия 0.9.0/0.10.0-dev).
2. Перенос прототипов `benchmarks/.../ru/jrestly/benchmark/engine/{OkHttpEngine,
   Apache5HttpEngine}` → пакеты `ru.jrestly.engine.okhttp` /
   `ru.jrestly.engine.apache` + провайдеры `OkHttpEngineProvider` /
   `Apache5HttpEngineProvider` (`isAvailable()` через `Class.forName`,
   `priority() = 50`) + `META-INF/services/ru.jrestly.engine.HttpEngineProvider`.
3. Доведение до контракта паритета (чек-лист ADR-0058 §Decision.4): timeout,
   streamResponse, version (best-effort), прогресс-листнеры (финал total/total,
   нулевая 0/0), trailers, `close()` с уважением `ownsClient`.
4. `benchmarks` → `implementation project(':engine-*')`, удаление локальных
   копий прототипов. WireMock-тесты паритета в субмодулях.
5. **Потом**: публичная документация по бенчмаркам (методология raw-TCP +
   TCP_NODELAY, таблица #147, ограничения variance). Опционально перед ней —
   повторный прогон JMH `-f 2 -i 5` для публичных цифр.

### Среда
1. `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...` (тёплый кэш).
2. Ветка `agent-loop`. Версия в `build.gradle`: **0.9.0**. Тесты: **1063/1063**.
3. **Next free ADR: 0059.** Пропуски в нумерации сессий (144, 146) —
   сверяться по `git log`.
4. Часы контейнера — UTC; таймстампы mailbox вести локальным временем
   (≈ UTC+9:30): 01:06 UTC ≈ 10:36 local.
