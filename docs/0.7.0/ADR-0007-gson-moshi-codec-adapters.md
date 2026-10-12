# ADR-0007: Gson and Moshi codec adapters (classpath probe chain)

> 2026-10-13 · Proposed · concept step of the "Gson / Moshi codec adapters" epic (DRAFT). Implementation follows in Steps 1–4; this ADR is the source of truth for the design decisions, the test strategy, and the Javadoc contract.

## Context

ADR-0001 settled the codec architecture: a single artifact, adapters behind an
internal SPI compiled `compileOnly`, and a resolution chain
"explicit builder setting > classpath probe (`Class.forName`, jackson first,
Gson/Moshi later) > fail fast". Today only the Jackson link exists:
`JsonCodecResolver` probes `com.fasterxml.jackson.databind.ObjectMapper` and
its fail-fast message names Jackson only. jRestly's default wire vocabulary
(wire formats, java.time contract, README wording) is therefore effectively
Jackson-shaped, and a Gson- or Moshi-only host must hand-wire a custom
`JsonCodec` although the classpath probe was promised to cover them.

Goal: make `jackson-databind` **or** `gson` **or** `moshi` on the classpath
enough to get a working client, without growing the public API or the POM.

## Decisions

### Aspect 1: Probe chain and priority

Order is fixed by ADR-0001's wording and kept **jackson → gson → moshi**:

1. Explicit `JRestlyClient.builder().jsonCodec(...)` /
   `SimpleModuleInfo.Builder.jsonCodec(...)` — always wins.
2. Classpath probe, first hit wins:
   - `com.fasterxml.jackson.databind.ObjectMapper` → `JacksonCodec.defaults()`
   - `com.google.gson.Gson` → `GsonCodec.defaults()`
   - `com.squareup.moshi.Moshi` → `MoshiCodec.defaults()`
3. Fail fast with an actionable message listing all three options (plus the
   explicit-codec escape hatch). The current message is updated, not kept
   Jackson-specific.

Rationale for the fixed order (rather than e.g. alphabetical or
user-configurable): predictability over cleverness — a host that ships both
libraries always gets Jackson, the library the whole wire contract was
designed around. No new configuration surface in 0.x; if the order ever
bites, the explicit-codec knob is already the documented exit.

### Aspect 2: Adapter shape — mirror `JacksonCodec`, no new SPI surface

Each adapter is a public class in `ru.jrestly.json`, exactly one external
type in its constructor, same contract as `JacksonCodec`:

| | `JacksonCodec` (existing) | `GsonCodec` (new) | `MoshiCodec` (new) |
|---|---|---|---|
| Constructor | `JacksonCodec(ObjectMapper)` — used as is, never reconfigured | `GsonCodec(Gson)` — used as is | `MoshiCodec(Moshi)` — used as is |
| Factory | `defaults()`, `defaults(LocalDateTimeFormat)` | `defaults()` | `defaults()` |
| Classloading gate | loaded only after the resolver's Jackson probe passed | same, after the Gson probe | same, after the Moshi probe |

The resolver stays the only place that mentions adapter class names before
the probe, so a Gson-free or Moshi-free classpath never loads
`GsonCodec`/`MoshiCodec` (no `NoClassDefFoundError`); the nested-class trick
used for jsr310 inside `JacksonCodec` is not needed at the adapter level.

`defaults()` configuration:
- **Gson:** plain `new GsonBuilder().create()` — Gson's own semantics, no
  reconfiguration (least surprise: an explicit `new Gson()` user and a
  `defaults()` user see the same output). Consequences, documented in
  Javadoc — verified against the pinned version in Step 1, pinned by
  `GsonCodecTest`: `java.util.Date` → locale-formatted string (Gson's
  default `DateFormat` style, round-trips to the same instant),
  `LocalDateTime` → the frozen reflective-style representation
  `{"date":{"year","month","day"},"time":{"hour","minute","second","nano"}}`
  (Gson 2.14 froze the pre-2.14 reflective output for compatibility;
  deliberately not ISO-8601 — hosts needing ISO bring a custom `Gson` or a
  custom `JsonCodec`).
- **Moshi:** `new Moshi.Builder().build()` plus the optional
  `moshi-adapters` probe — see Aspect 3.

No `LocalDateTimeFormat`-style knobs in this epic: Gson/Moshi both default to
ISO-8601 for `LocalDateTime`, so a jRestly-specific format knob would be
dead configuration. If a consumer wants the classic space-separated jRestly
format with Gson/Moshi, the escape hatch is a custom `JsonCodec` (the SPI
already allows it). Revisit only on demand.

### Aspect 3: java.time — optional add-on, same pattern as jsr310

- **Gson:** `java.time` support is in the core artifact (reflective-style
  on pre-2.14 versions, a native frozen adapter on 2.14+ — same wire
  format); no second probe needed.
- **Moshi:** core has no `java.time` adapters; they live in the separate
  `moshi-adapters` artifact (`com.squareup.moshi.adapter.JavaTime`).
  `MoshiCodec.defaults()` probes for it and registers `JavaTime` only when
  present — byte-for-byte the same pattern as
  `JacksonCodec.JavaTimeSupport` and the jsr310 probe. The Javadoc contract
  says: "a Moshi-only host without `moshi-adapters` works, but `java.time`
  fields are not first-class".

### Aspect 4: `prettyPrint` per adapter

`prettyPrint` stays best-effort (return the payload unchanged on failure).
Implementation per library:
- **Gson:** a lazy-cached pretty twin (`gson.newBuilder().setPrettyPrinting()
  .create()`), built once on first use and held — `Gson`'s pretty flag is
  per-instance, so re-creating it per call would allocate on every logged
  response. Lazy (not eager) because an explicit-constructor user who never
  logs responses pays nothing.
- **Moshi:** re-print through `JsonValue` + an indented `JsonWriter`
  (verify the exact idiom — `JsonUtils` or a manual writer — against the
  pinned version in Step 2; no extra instance to cache beyond the writer
  configuration).

### Aspect 5: Javadoc contract (the part DRAFT called out explicitly)

- **`JsonCodec`** gets a first interface-level Javadoc pinning the contract
  every adapter (current or future, including user-supplied ones) must meet:
  `serialize`/`deserialize` are synchronous and throw unchecked exceptions
  (`UncheckedIOException`-style wrapping, never a checked type);
  `deserialize` honors `java.lang.reflect.Type` (generics included);
  `prettyPrint` is best-effort; instances must be thread-safe (they are
  shared across all calls of a client).
- **Each adapter's class Javadoc** states: which external library + minimum
  version it targets (Gson 2.8+; Moshi 1.x), what `defaults()` configures
  (and what it deliberately does not), and its date/time behavior.

### Aspect 6: Test strategy (and the one real wrinkle)

- `build.gradle`: `compileOnly` for `com.google.code.gson:gson` and
  `com.squareup.moshi:moshi` (verify the POM still ships zero dependencies —
  the 0.5.0 gate); `testImplementation` for both **plus**
  `com.squareup.moshi:moshi-adapters` so the optional-probe path is
  exercised. Versions: latest stable at implementation time
  (Gson pinned at 2.14.0 in Step 1, 2026-10-13; Moshi pinned in Step 2).
- New `GsonCodecTest` and `MoshiCodecTest` mirror the scope of
  `JacksonCodecDefaultsTest`: serialize/deserialize round-trips incl.
  generics, `LocalDateTime` behavior pins, null handling, `prettyPrint`
  (best-effort: valid input → indented output; garbage → unchanged).
- The existing `JsonCodecResolverTest` keeps its Jackson pin — with all three
  libraries on the test classpath, the chain head (Jackson) must win. That
  test's assertions are the "priority" spec.
- **Wrinkle: testing "gson wins when jackson is absent" while Jackson is
  permanently on the test classpath.** Decision: a package-private test seam —
  `resolve()` delegates to a package-private overload taking a
  `Predicate<String>`-style classpath probe, so tests can simulate each
  combination (gson-only, moshi-only, all-absent → fail-fast) with no
  `URLClassLoader` machinery. No public API grows; the seam stays
  package-private and is covered by the resolver tests.

## Consequences

- Hosts with Gson or Moshi (and nothing else) get a working client with zero
  configuration — the promise of ADR-0001's probe chain is finally complete.
- +2 `compileOnly` artifacts; +3 `testImplementation` artifacts; POM must
  stay dependency-free (re-verified at each step's gate).
- README: "Jackson 2 is autodetected" becomes "Jackson, Gson, or Moshi is
  autodetected" (status line, the three install-snippet comments, and the
  SPI note in one place) — done in Step 4, still referencing the last tag
  per the "no tags" agreement.
- Date behavior intentionally differs across adapters (Jackson default
  space-separated vs Gson frozen reflective-style objects vs Moshi
  ISO-8601): each adapter's Javadoc is the contract, and a mixed-classpath
  host always gets Jackson anyway (Aspect 1).
- `JsonCodec` interface Javadoc is a new normative surface: user-supplied
  custom codecs are now *documented* to need thread-safety (they should
  already, but this makes it a promise the library relies on).

## Known gaps (accepted)

- No adapters for other JSON libraries (fastjson, kotlinx-serialization,
  …): the chain is a fixed, ordered list for 0.x — extensibility comes via
  the explicit-codec knob, not via configuration.
- Gson's built-in `java.util.Date` → locale-string output is Gson's
  semantics, not a jRestly choice; documented, not fought. Same for the
  frozen non-ISO `java.time` representation — a Gson 2.14 compatibility
  decision.
- `moshi-adapters` is a second optional artifact for a Moshi host to get
  first-class `java.time` — mirrors jsr310, accepted.
