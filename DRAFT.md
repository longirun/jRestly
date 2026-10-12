# DRAFT: (рабочее место для следующего эпика)

> Эпик «LocalDateTime Support in JacksonCodec» (2026-10-12, сессия #3) завершён:
> дистиллирован в ROADMAP.md → **Cycle 0.6.1 — java.time wire contract**,
> коммит `f7a4c47`, тег `v0.6.1`. Детали решения — там.

## Кандидаты (следующая сессия выбирает или предлагает свой)

- [ ] **ADR-0006 — java.time wire contract** (если Антон подтвердит в mailbox;
      всё исходное сырьё — в ROADMAP cycle 0.6.1 и Javadoc `JacksonCodec`)
- [ ] Retry with backoff (Post-0.5.0 shortlist, ADR-0003) — концепт + `@OnError`-семантика
- [ ] `CompletableFuture` return type (Post-0.5.0 shortlist)
- [ ] Gson / Moshi codec adapters — расширение цепочки classpath-probe (ADR-0001)

Правила работы: один эпик в DRAFT.md, шаги — чекбоксы `[ ]`; закрыл →
дистиллируй в ROADMAP.md и зачисти это место.
