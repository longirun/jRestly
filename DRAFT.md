# DRAFT: LocalDateTime Support in JacksonCodec

- [ ] Написать юнит-тест на десериализацию LocalDateTime с fractional seconds и ISO-разделителем 'T' (например, `2026-10-08T09:25:53.123456`)
- [ ] Доработать JacksonCodec, чтобы поддерживать как классический формат `yyyy-MM-dd HH:mm:ss`, так и ISO-8601 с наносекундами
- [ ] Запустить `./gradlew test` и убедиться, что все тесты зелёные
- [ ] Зафиксировать результат в коммите и обновить .agent/last_session.md
