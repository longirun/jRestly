# Last Session: #331 (2026-10-20)

## Что сделано

**Сессия #331: дистилляция Кандидатов №12 (ADR-0073 + ADR-0074) и №14 в `ROADMAP.md` как выполненные эпики; баннер Current status; пул 0.12.0 обновлён. Код не тронут — гейт 1,809/1,809 в силе от #330.**

1. *Приём состояния:* HEAD `4d6c117` (#330, Кандидат №14), чистое дерево (только счётчик 331), слов Антона в mailbox'е нет.
2. *ROADMAP.md:*
   - Секция **ADR-0073 `codec-yaml`** (Phase 1, #328): Jackson `YAMLMapper` 2.18.2, media family `application/yaml`/`text/yaml`/`*+yaml` (RFC 9512 + RFC 6839), SPI priority 50, `MediaType.APPLICATION_YAML`/`TEXT_YAML`, гейт 1,785/1,785, грабля «нет `equalToYaml` в WireMock 3.12.1».
   - Секция **ADR-0074 `codec-protobuf`** (Phase 2, #329): protobuf-java 3.25.5, family `application/x-protobuf`/`application/protobuf`/`*+protobuf`, резолвинг парсера `PARSER` → статический `getDefaultInstance()`+`getParserForType()` → fail-fast, `ConcurrentHashMap`-кэш, private-`PARSER` well-known типов (implementation note), гейт 1,804/1,804.
   - Секция **Кандидат №14** (#330, без ADR — шаг был TDD): 5 структурных тестов, три зоны (header-мердж без `HashSet` + `removeHeaderByName`/`hasHeaderNamed`, single-wrap trace-view, `ResponseHeaders.buildValueMap`), гейт 1,809/1,809.
   - **Баннер Current status:** Highest ADR **0074**, next free **0075**, гейт **1,809/1,809** (root 1,647 + okhttp 25 + apache5 25 + netty 26 + jackson-xml 15 + yaml 15 + protobuf 19 + benchmarks 37), JaCoCo без изменений с ADR-0072 (BRANCH 90.65% — рекорд), экосистема кодеков описана как закрытая.
3. *DRAFT.md:* блок сессии #331; пул 0.12.0 — все эпические кандидаты (№10–№14) закрыты и дистиллированы.
4. *Гейты:* код не тронут — 1,809/1,809 от #330 не пере-прогонялся (docs-only).

## Контекст для следующей сессии (#332)

1. **Ветка** `agent-loop`.
2. **Highest ADR:** **0074**, next free **0075**.
3. **Пул 0.12.0 (после #331):** активных эпиков нет — только хвосты:
   - **ADR-0075 — опциональное оформление Кандидата №14** (стиль-эталон: `docs/0.12.0/ADR-0072-zero-allocation-uri-composition-and-fast-paths.md`; факты уже есть в ROADMAP-секции №14 — ADR не дублирует, а оформляет решения/амплитуду/known gaps).
   - **Валидация `@ExpectStatus`/`@FollowRedirects`** (#207 «фича, не дыра» — инварианты деклараций нигде не объявлены; трогать при тихом исходе).
   - **Свободный аудит следующего hot-path:** dispatch `HttpTemplate`, cookie-разбор, `MethodMetadata`-кэши.
4. **Замечание по окружению:**
   - Команды gradle запускать с `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`.
   - `bc` в песочнице нет — суммы считать awk'ом или руками.
5. Метрики: гейт **1,809/1,809** (root baseline 1,647 = 1,642 + 5 структурных тестов #330). JaCoCo — рекорд 90.65% Branch (4,051/4,469), без изменений с ADR-0072.
6. *Мысль дня (для #332+):* кодовая экосистема показала две легитимные оси расширения — «претензия на media-type family» (YAML) vs «резолвинг под тип» (Protobuf). Если появится третий формат (TOML? Avro?), начинать с вопроса, какая ось подходит.
