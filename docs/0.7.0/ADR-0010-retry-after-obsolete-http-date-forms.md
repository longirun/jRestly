# ADR-0010: Retry-After — accept the obsolete HTTP-date forms (RFC 850 + asctime(3))

- **Status:** accepted
- **Date:** 2026-10-13 (session #28)
- **Scope:** 0.x, unreleased (0.6.2)
- **Supersedes:** the "asctime deliberately not accepted" bullet of ADR-0009
  candidate 3 (reopened per the ADR-0003 rule "reopening a known gap = a new
  ADR")

## Context

ADR-0009 candidate 3 closed the live form of `Retry-After` (delta-seconds +
IMF-fixdate) and recorded **"asctime (obsolete per RFC 9110, no zone) is
deliberately not parsed"** as a micro-gap, the main argument being "a custom
formatter plus a zone assumption for a dying form".

A reality check of the actual parser (a `jshell` probe against
`DateTimeFormatter.RFC_1123_DATE_TIME`, session #28) found the micro-gap was
broader than its wording: **`RFC_1123_DATE_TIME` accepts IMF-fixdate only**,
so *both* obsolete RFC 9110 date forms were unparsed — the RFC 850 form
(`Sunday, 06-Nov-94 08:49:37 GMT`) and the C `asctime(3)` form
(`Sun Nov  6 08:49:37 1994`).

And RFC 9110 is stronger than the earlier wording suggested:

- `Retry-After = HTTP-date / delay-seconds` (10.2.3), and
  `HTTP-date = IMF-fixdate / obs-date`, `obs-date = rfc850-date /
  asctime-date` (5.6.7, Appendix A) — so the obsolete forms are in the
  `Retry-After` grammar.
- **"A recipient that parses a timestamp value in an HTTP field MUST accept
  all three HTTP-date formats"** (5.6.7). Senders MUST use IMF-fixdate;
  recipients must take all three.
- "The first two formats indicate UTC by ... `GMT` ...; **values in the
  asctime format are assumed to be in UTC**" (5.6.7) — the asctime zone
  assumption is prescribed by the RFC, not our guess. This removes the main
  argument of ADR-0009.

## Decision

ACCEPT both obsolete forms: `parseRetryAfter` now tries, after the
delta-seconds form,

1. **IMF-fixdate** — `DateTimeFormatter.RFC_1123_DATE_TIME` (as before);
2. **RFC 850** — the full weekday name is validated against a `static
   final` set and then parsed off; the rest parses as
   `ofPattern("dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH)`. Two details
   found by the tests:
   - as a *parsed* `EEEE` field, java.time cross-checks the weekday against
     the derived date and trips on the two-digit-year mapping (a parse
     conflict) before any fix-up can run — so the weekday is validated,
     not parsed;
   - java.time maps a two-digit year to the **current century** (`94` →
     2094); the classic pivot used by legacy parsers (`80–99` → previous
     century) is applied after parsing, so `94` reads as 1994.
3. **asctime(3)** — `ofPattern("EEE MMM d HH:mm:ss yyyy",
   Locale.ENGLISH).withZone(ZoneOffset.UTC)` — the zone is the RFC's
   assumption. asctime may carry a double space before a one-digit day (the
   RFC's own example does) — runs of spaces are collapsed for this attempt
   only.

All formatters/sets are `private static final` (no per-request allocation).
The three grammars are mutually exclusive by structure (comma placement,
zone token, field order), so the linear try order is just the RFC's
presentation order. Semantics are unchanged from the live form: `max(0,
date − now)`, a past date floored at 0, a deterministic lower bound via
`nextDelayMillis`.

**Rejected alternative:** consciously close the micro-gap as a permanent
known gap — a documented deviation from the RFC's `MUST`, reopen on a
real-world report. Cheaper today, but the conformance note would never go
away, and the cost turned out to be bounded (two static formatters + a
weekday set + one whitespace collapse, no new dependency).

## Consequences

- **ADR-0008 Known gaps:** the asctime line is closed (struck through,
  pointer here); its scope is corrected — the micro-gap covered *both*
  obsolete forms, not asctime only.
- **ADR-0009:** candidate 3's asctime bullet and the Known-gaps line gain
  the scope correction + pointer here (the rest of the decision stands).
- **`HttpTemplate`:** the parser comment and the seam note now read "all
  three RFC 9110 HTTP-date grammars"; the RFC 850 two-digit-year pivot and
  the asctime whitespace collapse are pinned by comments.
- **Behavior:** an obsolete-form `Retry-After` on a retried status now sets
  a deterministic lower bound instead of pure jitter — only for users who
  opted into retry (the parser is unreachable otherwise), in the direction
  of the server's intent ("do not come back before X").
- **Tests:** `HttpTemplateRetryAfterTest` — the asctime-is-not-parsed pin
  is replaced by four pins (both forms: past → 0 and future → positive
  delta); the future-delta bound is widened to `[58_000, 60_000]` ms
  (second-granularity truncation of the wire form — the existing
  IMF-fixdate test's `[59_000, 60_000]` bound was a latent flake of the
  same class, widened as well). Suite 239 → 242.
