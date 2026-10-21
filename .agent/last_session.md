# Last Session: #371 (2026-10-21)

## Что сделано

**Сессия #371: JdkHttpEngine Extensibility (100% Branches) & ZeroDepJsonParser Polish (Branch 95.60%, missed branches 199).**
1. Открытость и расширяемость `JdkHttpEngine` (пожелание Антона):
   - методы `toHttpRequest`, `createBodyPublisher`, `toWireResponse`, `toHeaders`, `notifyZeroUploadIfPending` переведены из `private` в `protected` (и `protected static`), снабжены исчерпывающим Javadoc. Пользователи теперь могут расширять и кастомизировать генерацию `HttpRequest` и маппинг ответов;
   - устранена синтетическая проверка `&& httpRequest != null` в `notifyZeroUploadIfPending`.
2. Покрытие пограничных сценариев в `JdkHttpEngineTest` (+7 тестов):
   - `send` и `sendAsync` с `options == null`;
   - асинхронный стриминг `sendAsync` со `streamResponse(true)` и `downloadProgressListener`;
   - `bodyPublisherSupplier` возвращает `null` (fallback на `noBody()`);
   - HTTP GET с телом (`body`) и частями (`parts`);
   - `toHeaders(null)` и headers с множественными значениями одного имени;
   - `ModuleInfo` с обычным `Executor`, нулевым тайм-аутом, прокси, аутентификатором, закрытие управляемого пула виртуальных потоков.
   - **`JdkHttpEngine` достиг 100% покрытия ветвей (94/94 branches, 0 missed branches!)**.
3. Очистка и покрытие `ProblemDetail$ZeroDepJsonParser`:
   - удалены мертвые проверки `if (json == null || json.isEmpty() || json.charAt(0) != '{')` в приватном `parseObject` (делегирование из `fromJson`);
   - ликвидирована недостижимая ветка `else if (i < len)` в разделителях полей объекта;
   - в `ProblemDetailTest` добавлены тесты на отрицательный Long `< Integer.MIN_VALUE`, незакрытые массивы без элементов и с элементами, неожиданный конец JSON после `:`, минус в самом конце строки, экранированные кавычки и скобки внутри объектов, незакрытый объект.
4. Новые исторические рекорды библиотеки:
   - Missed branches всей библиотеки впервые в истории опустились ниже 200: **ровно 199 missed branches** (минус 19 за сессию — абсолютный исторический минимум!);
   - Полный гейт: **2,145 / 2,145** тестов green во всех 8 модулях (+7 новых тестов, 0 failures, 0 errors);
   - JaCoCo: Branch **95.60%** (4,322 / 4,521 covered — новый исторический рекорд библиотеки!);
   - JaCoCo: Complexity **94.77%** (3,658 / 3,860 covered — новый рекорд!);
   - JaCoCo: Instruction **98.92%**, Line **98.68%**, Classes **100%** (168/168), Methods **99.81%** (1,553/1,556);
   - Javadoc: абсолютно чист во всех 8 модулях под `-Werror`.

## Контекст для следующей сессии (#372)

1. **Ветка:** `agent-loop`.
2. **Статус:** Все задачи из бэклога и запросов Антона выполнены на 100%: архитектурная декомпозиция god-классов (ADR-0080 — ADR-0094), Enterprise Observability & Compliance (ADR-0095), TraceId Correlation Logging & Raw Mode (ADR-0096), Typed Server-Sent Events (ADR-0097), SSE Resilience Polish, ProblemDetail Zero-Dep Resilience, HttpTemplateBuilder Branch Pruning, HttpTemplate Resilience Polish и JdkHttpEngine Extensibility (100% branches, missed branches 199).
3. **Метрики:** Полный гейт **2,145 / 2,145**; Branch coverage **95.60%**; Complexity **94.77%**; Missed branches: 199; Javadoc clean под `-Werror`; Highest ADR **0097**, next free **0098**.
4. **Дальше:** Перенос фич из `agent-loop` в `main` Антоном или релизные транши.
