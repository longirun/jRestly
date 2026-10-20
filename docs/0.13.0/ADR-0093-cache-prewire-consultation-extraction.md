# ADR-0093: CacheDispatch Pre-wire Consultation Extraction — Unifying Cache Lookup, Freshness Classification, and Conditional Header Injection across Sync and Async Dispatch

- **Status:** Accepted
- **Date:** 2026-10-20
- **Cycle:** 0.13.0
- **Context:** Following the ADR-0081 complexity ratchet and the ongoing god-class decomposition of `ru.jrestly.http.HttpTemplate` (1,673 lines, WMC 292, 51 missed branches), we complete the cache dispatch concern initiated in ADR-0066 by extracting the RFC 9111 pre-wire consultation (`HttpTemplate.exchange()` lookup block) — entry resolution, freshness classification, conditional header injection, and cache-hit synthesis — into the existing collaborator `ru.jrestly.http.CacheDispatch`.

---

## 1. Problem & Boundaries

`CacheDispatch` was created in ADR-0066 with the post-wire half of the ADR-0063 cache semantics (`handle`: store a fresh `200`, resolve a conditional `304`, invalidate on unsafe success). The other half — deciding *before* the wire what the store says about the request — remained inline in `HttpTemplate` and was **100% duplicated between the synchronous and asynchronous legs**:

1. **Pre-wire lookup block (~16 duplicated lines in `exchange` and `exchangeAsync`):** resolve the `GET` entry from `HttpCache`, classify hit vs. stale, and compute the request actually to send.
2. **Freshness classification:** `entry.isFresh()` combined with the entry's `must-revalidate` and the request's own `Cache-Control: no-cache` — the rare corners that a naive `isFresh()` check gets wrong.
3. **Conditional header injection:** for a stale entry carrying validators, synthesize `If-None-Match` / `If-Modified-Since` for the revalidation handshake — but never override conditional headers the caller supplied (the `hasHeader` case-insensitive scan).
4. **Cache-hit synthesis (duplicated in both legs):** materialize `entry.toWireResponse()` and, for `InputStream` return types, re-wrap a byte payload as a live stream.

This pre-wire cluster added ~40 lines of branching and ~60 WMC points across the duplicated legs to `HttpTemplate`, with subtle corners (must-revalidate, request no-cache, validator selection) scattered as inline boolean conjunctions.

---

## 2. Architecture & Design Decisions

### (D1) Both halves of RFC 9111 in one collaborator
Rather than minting a new `CacheDispatchPipeline` / `HttpCacheCoordinator` class, the pre-wire consultation was **added to the existing package-private `CacheDispatch`**, so the dispatch concern and its concern owner (one row of the ADR-0081 navigation map, one test class) stay unified:
```java
final class CacheDispatch {
    record CacheLookup(WireRequest request, boolean isCacheHit, CachedResponse entry) { ... }

    static CacheLookup lookup(WireRequest req, HttpCache cache) { ... }
    static WireResponse synthesizeHit(CachedResponse entry, boolean streamBody) { ... }
    static WireResponse handle(WireRequest req, WireResponse resp, HttpCache cache,
                               boolean streamBody) { ... }   // post-wire, ADR-0066
}
```
`CacheLookup` is a plain immutable result: the (possibly enriched) request to send, whether the entry resolves to a fresh hit, and the entry itself for diagnostics.

### (D2) `lookup` behavioral contract (pinned by unit tests, no wire)
- `cache == null || req == null || req.method() != GET` → not hit, request passed through untouched (defensive; the **zero-garbage template calls `lookup` only behind an explicit `httpCache != null` guard**, so the default no-cache path stays allocation-free);
- hit ⇔ `entry != null && entry.isFresh() && !entry.mustRevalidate() && !request.noCache()`
  — a stale entry, a `must-revalidate` entry, and a request carrying `no-cache` all force upstream transmission;
- stale entry with validators → inject `If-None-Match` (ETag) and/or `If-Modified-Since` (last-modified) **only into headers the caller has not set**; a request with no cached entry or no validators is returned unchanged.

### (D3) Cache-hit synthesis deduplicated
`synthesizeHit(entry, streamBody)` is now the single materializer for both legs: `entry.toWireResponse()`, followed by the `toStreamWireResponse` re-wrap when the caller expects `InputStream` *and* the hit carries a byte body (a `null` body needs no wrapping). The two internal copies in `exchange` / `exchangeAsync` collapsed into one.

### (D4) `hasHeader` relocation
The case-insensitive header-name scan moved into `CacheDispatch` (it is the conditional-header logic this collaborator owns); `HttpTemplate` keeps a one-line delegate so the historical pins in `HttpTemplateConditionalHeaderTest` continue to hold unchanged — behavior pins, not a new public surface.

### (D5) HttpTemplate seam
In `HttpTemplate.exchange()` the duplicated block became:
```java
CacheDispatch.CacheLookup cacheLookup = CacheDispatch.lookup(effectiveRequest, httpCache);
effectiveRequest = cacheLookup.request();
cachedEntry = cacheLookup.entry();
isCacheHit = cacheLookup.isCacheHit();
```
behind the existing `if (httpCache != null)` guard; both legs synthesize hits through `CacheDispatch.synthesizeHit`. No public API changed; `HttpCache` interaction is exactly as before.

---

## 3. Verification & Metrics

- **Red-First TDD:** 14 tests appended to `CacheDispatchTest` before implementation, covering the full lookup matrix — null cache / null request / non-`GET` bypass, fresh hit, stale + validator injection (both validators, ETag-only, lastModified-only), no override of caller-supplied conditional headers, request `no-cache` still injecting conditionals while forcing a miss, stale entry without validators untouched, and `must-revalidate` never a hit — plus `synthesizeHit` (buffered, streaming re-wrap, null body no streamify). `RecordingCache` gained `getEntry`/`getCalls` for assertion.
- **Red-first catch:** the first coverage run exposed one uncovered branch — the `must-revalidate` conjunction inside the `isCacheHit` expression — closed by the dedicated test (`CacheDispatch` line 72); C11 then green.
- **Collaborator Test Suite:** 14 new tests in `CacheDispatchTest` (86 total in the class across both halves of the dispatch).
- **Collaborator Coverage (ADR-0081 C11):**
  - Branches: **72/72 (100.00%)** — **0 missed branches**;
  - Complexity: **41/41 (100.00%)** — **0 missed complexity**;
  - Lines: 46/46; Instructions: 224/224; Methods: 5/5 — all 100%.

### Scorecard Impact (ADR-0081)

| Metric | Before (#352) | After (#359) | Delta | Status |
|---|---|---|---|---|
| `HttpTemplate` Lines | 1,673 | 1,653 | −20 | Yellow/Red (Target ≤ 400) |
| `HttpTemplate` WMC | 292 | 269 | −23 | Improving |
| `HttpTemplate` Missed Branches | 51 | 40 | −11 | Improving |
| Total Library Missed Branches | 294 | **283** | −11 | **New historical all-time low!** |
| Total Library Branch Coverage | 93.29% | **93.54%** (4,096/4,379) | +0.25% | **New all-time record!** |
| Total Library Complexity Coverage | 92.11% | **92.36%** (3,445/3,730) | +0.25% | **New all-time record!** |
| Total Library Instructions | 98.54% | **98.59%** (31,112/31,556) | +0.05% | 100% green |
| Total Library Lines | 98.26% | **98.28%** (6,837/6,957) | +0.02% | 100% green |
| Total Library Classes | 162/162 | **163/163** | +1 class | 100% green |
| Total Library Methods | 1,489/1,491 | 1,493/1,495 | +4 methods | 99.87% |
| Gate Passing Tests | 2,041 / 2,041 | **2,055 / 2,055** | +14 tests | 100% green |
| Javadoc `-Werror` | Clean | Clean | 0 warnings | 100% green |

Next decomposition candidates for the remaining red `HttpTemplate` (WMC 269, 1,653 lines, 40 missed): the response-decode leg (`ResponseProcessor` / `ResponseDecoder`, streaming/SSE assembly), or the `HttpTemplateBuilder` branch debt (still the largest missed-branch class at 56).
