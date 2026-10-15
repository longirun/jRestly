# DRAFT: активная зона после закрытия эпика Cookie Handling, Session Management & Declarative @Cookie (ADR-0032)

> Эпик Cookie Handling, Session Management and Declarative @Cookie Annotations (ADR-0032, `docs/0.10.0/`) закрыт (сессия #110):
> концепт и ADR-0032 → интеграция `CookieHandler` в `ModuleInfo`, `SimpleModuleInfo`, `JRestlyClient` и `AbstractHttpClient` +
> шорткаты `cookieManager()` и `cookiePolicy()` для сохранения сессий между запросами +
> инспекция `client.getCookieStore()` для сброса состояния сессии в тестах +
> аннотации `ru.jrestly.annotation.Cookie` и контейнер `ru.jrestly.annotation.Cookies` +
> строгая иерархия приоритетов (Parameter > Method > Sub-interface > Super-interface) +
> динамические параметры `@Cookie`: строки, объекты, `Map<String, ?>`, прямые `HttpCookie`, null-safety удаление/подавление +
> первокллассный доступ к cookies в `ResponseHeaders` (`cookies()`, `cookie(name)`, `cookieValue(name)`, `cookieMap()`) +
> fail-fast upfront валидация в `ControllerValidator` (`ControllerValidationException`) против blank и CRLF значений +
> unit тесты (`CookieConfigAndResponseTest`, 13 тестов; `CookieValidationTest`, 7 тестов) и WireMock интеграционные тесты (`CookieSessionWireMockTest`, 9 тестов, итого +29 тестов) →
> дистилляция в ROADMAP («Cookie Handling, Session Management and Declarative @Cookie (ADR-0032) — COMPLETE ✅») + гейты 675/675 тестов (80 классов).
> Следующий ход — свободный, выбор из активной зоны.

## Активная зона (кандидаты на следующий эпик)

- **Кандидат №1 — Response download progress tracking (`DownloadProgressListener`)**:
  Симметричный к ADR-0030 механизм отслеживания прогресса скачивания тел ответов для больших потоковых файлов (по аналогии с UploadProgressListener).
- **Кандидат №2 — Query parameter multi-value formats (`explode`, comma-separated `tag=a,b`, pipe-separated `tag=a|b`, RFC 6570 style expansion)**:
  Гибкое форматирование коллекций и массивов в URL query параметрах per-method или per-parameter.
- **Кандидат №3 — test-javadoc** (ADR-0013 Non-goals):
  Test-source javadoc вынесен за скобки аудита; наведение лоска в тестовых классах.

---

## Постоянная нить (не эпик, не чекбокс)

- **Гигиена проекта — за агентом** (Антон, 2026-10-13: «сам следи тут как
  лучше — в этом файле и вообще на проекте»). Практика: mailbox не растёт
  бесконечно (применять правило «решённый обмен удаляем» в каждой сессии,
  где он применим); устаревшие «Пометки из mailbox» в DRAFT сверять с
  реальностью; мелкие расхождения (версии, статусы) чинить сразу, не копить;
  ROADMAP — верифицируемые утверждения (счётчики тестов, версии, хэши,
  ссылки) сверять с реальностью.
- **После закрытия эпика DRAFT не пустеет** (сессия #39): кандидаты
  поднимаются в активную зону — следующий ход выбирается из них.
- **Гейт без отрицательного теста — не гейт** (сессия #58).
- **Факты — по коду/дереву/jar, не по имени/памяти** (сессии #47–#57, #64).

Пометки из mailbox (актуальные на 2026-10-15):
- **Автономия + 3 направления (Антон, 2026-10-14 09:10, живая нить):**
  (1) инварианты — zero-dep, прозрачные методы (ADR-0004),
  анти-комбайн (ADR-0003); (2) ниша — идеальный декларативный
  HTTP-клиент для современной Java; (3) эргономика для современных
  API.
- **Векторы зрелости библиотеки (Антон, 2026-10-14 23:25, живая нить):**
  все направления зрелости последовательно реализуются: SSL/mTLS (ADR-0020),
  прозрачный GZIP/Deflate (ADR-0021), доступ к метаданным заголовков (ADR-0022),
  условные запросы & Optional (ADR-0023), гибкость form-data/multipart (ADR-0024),
  шаблоны URI и массивы параметров (ADR-0025), виртуальные потоки (ADR-0026),
  декларативные таймауты (ADR-0027), декларативные интерцепторы (ADR-0029),
  отслеживание прогресса загрузки (ADR-0030).
- **Коммиты — краткие, не дублируют ченджлог** (Антон).
- **Объём одной сессии в 2–3 раза меньше** — один маленький шаг.
