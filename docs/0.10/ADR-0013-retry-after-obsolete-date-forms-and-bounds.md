# ADR-0013: Retry-After — Obsolete HTTP-Date Forms & Wait Bounds

- **Status:** accepted
- **Date:** 2026-11-07
- **Scope:** FEAT-10 of the 0.10.0 cycle (TODO.md §2), step 2 of the retry contract
- **Provenance:** ported from `jrestly-infinite` (`ba68f1f`) ADR-0010 (obsolete date forms) + ADR-0040 (bounds only — see Deviations), with the reference's Amendments #184 and #207 applied directly

## Context

ADR-0012 (FEAT-09) landed the retry loop with the live `Retry-After` surface: the `max(bound, retryAfter)` rule, delta-seconds, and IMF-fixdate. Two gaps remain:

1. **Parsing.** RFC 9110 §5.6.7 is explicit: `HTTP-date = IMF-fixdate / obs-date`, `obs-date = rfc850-date / asctime-date`, and *"a recipient that parses a timestamp value in an HTTP field MUST accept all three HTTP-date formats"*. The FEAT-09 parser accepted IMF-fixdate only (`RFC_1123_DATE_TIME`) — both obsolete forms (`Sunday, 06-Nov-94 08:49:37 GMT` and `Sun Nov  6 08:49:37 1994`) were unparsed and treated as absent (pure jitter).
2. **Bounds.** The `max(bound, retryAfter)` rule honors the header unconditionally: a server (or a misbehaving intermediary proxy) returning `Retry-After: 86400` parks the caller for 24 hours.

## Decisions

### 1. Accept both obsolete forms

`retryAfterDelayMillis` tries, after delta-seconds:

1. **IMF-fixdate** — `DateTimeFormatter.RFC_1123_DATE_TIME` (as before);
2. **RFC 850** — the full weekday name is validated against a `static final` set and stripped; the rest parses as `dd-MMM-yy HH:mm:ss zzz` (`Locale.ENGLISH`). Two details pinned by tests:
   - the weekday is **validated, not parsed**: as a *parsed* `EEEE` field, java.time cross-checks the weekday against the derived date and trips on the two-digit-year mapping (a parse conflict) before any fix-up can run. Validation = membership in the set, not agreement with the date;
   - java.time maps a two-digit year to the **current century** (`94` → 2094); the classic pivot used by legacy parsers (`80–99` → previous century) is applied after parsing (`pivotRfc850Year`), so `94` reads as 1994.
3. **asctime(3)** — `ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)` — the zone is the RFC's own assumption, not ours. asctime may carry a double space before a one-digit day (the RFC's own example does) — runs of spaces are collapsed (`\s{2,}` → single) for this attempt only.

All formatters/sets are `private static final` on `HttpTemplate` (no per-request allocation). The three grammars are mutually exclusive by structure (comma placement, zone token, field order), so the linear try order is just the RFC's presentation order. A past date floors at 0; an unparseable value is `null` (pure jitter).

### 2. Bounds: `maxRetryAfter` cap + `respectRetryAfter` opt-out

- `RetryPolicy.Builder.maxRetryAfter(Duration)` — the ceiling for the header's demand; `null` (the default) means the **effective cap falls back to `maxBackoff`**. The wait becomes `max(bound, min(cap, max(0, retryAfter)))`. A server can raise the wait above the exponential bound, but never past the policy's own ceiling — `Retry-After: 86400` against a 5 s `maxBackoff` waits 5 s, not a day.
- `RetryPolicy.Builder.respectRetryAfter(boolean)` (default `true`) — the opt-out for untrusted or misbehaving proxies: the header is ignored, pure jitter applies.
- `@Retry` gains the mirroring attributes: `respectRetryAfter()` (default `true`) and `maxRetryAfterMs()` (default `-1`, the "unset" sentinel bound by `maxBackoffMs`), mapped in `HttpTemplateBuilder.resolveRetryPolicy`.
- Negative `maxRetryAfterMs` on the annotation is deliberately **not** range-checked by `ControllerValidator.validateRetry` — any negative follows the `-1` "unset" convention. The builder still rejects a negative `maxRetryAfter` `Duration`.

## Deviations from the reference

1. **ADR-0040's jitter/multiplier surface is not ported** — the `Jitter` enum (FULL/EQUAL/DECORRELATED/NONE) and `backoffMultiplier` stay out of 0.10 (ADR-0012, deviation 1); only the bounds axis lands here, matching the FEAT-10 inventory line ("with cap").
2. **Amendment #184 is applied directly.** The reference's original implementation applied the cap only when `maxRetryAfter` was explicitly set (an unbounded default — the bug the amendment fixed, red-first). `main` lands the fallback-to-`maxBackoff` rule from day one; `RetryPolicyTest.defaultRetryAfterCapFallsBackToMaxBackoff` pins it (24 h header → capped to 5 s).
3. **The parser stays a `HttpTemplate` seam.** The reference later extracted `RetryAfter` as a class (its ADR-0066 god-class decomposition); `main` keeps the package-private method per ADR-0012's shape.

## Verification

1. `HttpTemplateRetryAfterTest` (unit, no WireMock): null/missing/blank → `null`; garbage → `null`; delta-seconds (`120` → 120 000 ms, `0` → 0); IMF-fixdate past → 0, future → `[58, 60] s` (the widened bound avoids the second-granularity truncation flake); RFC 850 past (94 → 1994 pivot) → 0, future → positive delta, a non-weekday prefix rejected; asctime past → 0, future → positive delta, the RFC example's double-space day field parses (runs of spaces collapse).
2. `RetryPolicyTest` additions: the default cap falls back to `maxBackoff` (24 h header → 5 s, deterministic — independent of the jitter draw); an explicit `maxRetryAfter` bounds the header while a smaller header is honored in full; `respectRetryAfter = false` ignores the header (pure jitter); a negative `maxRetryAfter` rejected, `null` is the unset default with `respectRetryAfter = true`.
3. `RetryTest`: the two live-form wall-clock scenarios pin an explicit `maxRetryAfter(10 s)` so the default cap cannot bite their lower-bound asserts; the RFC 850 wall-clock scenario raises the wait like the live forms.
4. Annotation-level wire pins: `respectRetryAfter = false` ignores a 1 s header demand on the wire (elapsed well under 1 s); `maxRetryAfterMs = 100` caps the same demand at ~100 ms (elapsed ≥ 100 ms and well under 1 s) — the `RetryController` fixture drives both through the proxy, closing the annotation-to-policy mapping loop end to end.
5. Gates: full suite 331 tests green (`./gradlew test`), IDE inspections clean on all touched files, POM with zero runtime `<dependency>`.
