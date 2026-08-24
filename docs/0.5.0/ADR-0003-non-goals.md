# ADR-0003: Non-goals

> 2026-08-21 · Accepted

## Context

Declarative clients drift toward the Feign / Spring Cloud arena. Explicit non-goals keep the scope from being reopened every few months — by the author or a future AI session.

## Decision

jRestly will NOT do:

- **Android** — D8/R8 can't consume Java 21 bytecode (ceiling ~17); supporting it would fork the baseline. Modern answer to async is virtual threads, not an event loop.
- **Reactive (Reactor/RxJava)** — `CompletableFuture` return type may land post-0.5.0 on demand; a Reactor bridge, if ever, is an adapter module.
- **HTTP caching, circuit breakers, load balancing, discovery** — not in core. Retry with backoff is the only resilience feature shortlisted (integrates with `@OnError`), deferred until after 0.5.0.
- **Response wrappers** — permanently out (ADR-0004).

Reopening any of these requires a new ADR superseding this record.

## Consequences

- Focus stays on the contract-strictness axis: per-method typed errors, declarative auth capture, eager validation, strict success statuses.
