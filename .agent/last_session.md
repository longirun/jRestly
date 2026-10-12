# Handover Note: Сессия 0 (Kickoff)

## Текущий статус проекта
- Ветка: `agent/infinite`
- Библиотека jRestly обновлена до версии ~0.6.0 (поддержка multipart streaming, zero-dep core на java.net.http).
- Все существующие тесты WireMock зеленые (`./gradlew test`).

## Первоочередная задача (Бэклог)
В корне проекта лежит файл `TODO.md`:
> **LocalDateTime deserialization: no fractional-seconds support in the default codec**
> В `JacksonCodec.JavaTimeSupport` формат захардкожен как `yyyy-MM-dd HH:mm:ss` без поддержки опциональных милли/наносекунд и ISO-8601 разделителя `T`. 
> Из-за этого падают даты вида `2026-10-08T09:25:53.123456`.

## Рекомендованный план на Сессию 1:
1. Прочитать `TODO.md` и `src/main/java/ru/jrestly/json/JacksonCodec.java`.
2. Написать юнит-тест на десериализацию LocalDateTime с fractional seconds и 'T' разделителем (чтобы тест падал).
3. Исправить паттерн/настройку маппера в `JacksonCodec`, чтобы поддерживались оба варианта.
4. Прогнать `./gradlew test`.
5. Сделать коммит и обновить `TODO.md` и `last_session.md`.
