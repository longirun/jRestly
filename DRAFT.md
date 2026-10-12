# DRAFT: LocalDateTime Support in JacksonCodec

- [x] Написать юнит-тест на десериализацию LocalDateTime с fractional seconds и ISO-разделителем 'T' (например, `2026-10-08T09:25:53.123456`)
- [x] Доработать JacksonCodec, чтобы поддерживать как классический формат `yyyy-MM-dd HH:mm:ss`, так и ISO-8601 с наносекундами
- [x] Запустить `./gradlew test` и убедиться, что все тесты зелёные
- [x] Зафиксировать результат в коммите и обновить .agent/last_session.md

## Статус: ГОТОВО (сессия #3, 2026-10-12)

Решение: два форматтера в `JacksonCodec.JavaTimeSupport`.
- `WIRE` (serializer): `yyyy-MM-dd HH:mm:ss` + опциональный хвост `.SSSSSSSSS`
  (`appendFraction(NANO_OF_SECOND, 0, 9, true)` внутри `optionalStart/End`).
  Значения с секундной точностью сериализуются **байт-в-байт как раньше**;
  наносекунды больше не теряются.
- `LENIENT` (deserializer): разделитель `'T'` или `' '` (каждый опционален),
  время `HH:mm:ss`, опциональная дробная часть 1–9 цифр.
  Принимаются: `2026-10-08T09:25:53.123456`, `2026-10-08 09:25:53.123`,
  `2026-10-08T09:25:53`, `2026-10-08 09:25:53`. Отклоняются: дата без времени,
  строчная `t`, отсутствие секунд.

Изменены тесты, закреплявшие старый баг:
- `JacksonCodecDefaultsTest`: `defaultsTruncateNanoseconds` →
  `defaultsPreserveFractionalSecondsWhenPresent`; «ISO 'T' is rejected» →
  «space separator is the wire default»; +3 новых теста (ISO-десериализация,
  DTO с ISO-печатью сервера, формы вне контракта).
- `JsonCodecWireTest`: + `isoTimestampWithFractionalSecondsIsDeserializedFromResponse`
  (энд-ту-энд через WireMock: сервер шлёт ISO с дробью, request остаётся
  классическим).

Итог: 135 тестов, все зелёные. Дистилляция в ROADMAP — следующая сессия.
