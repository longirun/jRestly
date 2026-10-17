# ADR-0007: Gson and Moshi codec adapters (classpath probe chain)

> 2026-10-13 · Proposed · concept step of the "Gson / Moshi codec adapters" epic (DRAFT). Implementation follows in Steps 2–6 (adapters, resolver, Javadoc contract, README); this ADR is the source of truth for the design decisions, the test strategy, and the Javadoc contract.

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
- **Moshi:** plain `new Moshi.Builder().build()` — no add-on probe (the
  original `moshi-adapters` probe was a phantom, see Aspect 3, corrected
  against Moshi 1.15.2 in Step 2, 2026-10-13).

No `LocalDateTimeFormat`-style knobs in this epic: Gson/Moshi both default to
ISO-8601 for `LocalDateTime`, so a jRestly-specific format knob would be
dead configuration. If a consumer wants the classic space-separated jRestly
format with Gson/Moshi, the escape hatch is a custom `JsonCodec` (the SPI
already allows it). Revisit only on demand.

### Aspect 3: java.time — optional add-on, same pattern as jsr310

- **Gson:** `java.time` support is in the core artifact; no second probe
  needed. Refinement (Step 5, 2026-10-13, pinned against the 2.13.1 tag):
  pre-2.14 versions have **no dedicated java.time adapters at all** (no
  `java.time` anywhere in the main sources) — `LocalDateTime` & co. fall
  through to Gson's general reflective path (on modern JDKs: reflection
  into the JDK's private fields, so `--add-opens` may be needed); 2.14+
  ships a native adapter that freezes that reflective output as the wire
  format.
- **Moshi:** core has no `java.time` adapters. **Correction (Step 2,
  2026-10-13, pinned against 1.15.2):** the assumed `moshi-adapters`
  add-on (`com.squareup.moshi.adapter.JavaTime`) is a **phantom** —
  `JavaTime` never existed in `moshi-adapters` (checked the 1.0.0, 1.5.0,
  1.14.0 and 1.15.2 jars plus the upstream tree). That artifact contains
  only explicitly-registered adapters: `Rfc3339DateJsonAdapter`
  (`java.util.Date`), `EnumJsonAdapter` (1.14+),
  `PolymorphicJsonAdapterFactory` (1.14+). There is **no standard
  java.time add-on in the Moshi 1.x world at all**, so a probe for it
  would be a bet on a minority artifact; `MoshiCodec.defaults()` stays a
  plain builder, and the escape hatch for `java.time`/`java.util.Date`
  fields is an explicit `Moshi` with user-registered adapters passed to
  `MoshiCodec(Moshi)`. Second pinned reality: a plain `Moshi` instance
  rejects `java.*` platform classes for its reflective class adapter
  outright (`IllegalArgumentException`: "Platform class X requires
  explicit JsonAdapter to be registered") — `MoshiCodec` wraps that (and
  the data-exception family) in a `JsonDataException` naming the target.

### Aspect 4: `prettyPrint` per adapter

`prettyPrint` stays best-effort (return the payload unchanged on failure).
Implementation per library:
- **Gson:** a lazy-cached pretty twin (`gson.newBuilder().setPrettyPrinting()
  .create()`), built once on first use and held — `Gson`'s pretty flag is
  per-instance, so re-creating it per call would allocate on every logged
  response. Lazy (not eager) because an explicit-constructor user who never
  logs responses pays nothing.
- **Moshi:** verified against 1.15.2 (Step 2): `JsonValue` does not
  exist in Moshi core (only the `JsonValueWriter` interface), and the
  tree-based `Object` adapter widens every number to a `Double`
  (`"a": 1` would re-print as `"a": 1.0`). `MoshiCodec.prettyPrint`
  therefore re-prints **token for token** through a `JsonReader` into an
  indented `JsonWriter`: numbers pass through as their raw literal via
  `JsonWriter.value(BufferedSource)` (`value(String)` would quote them —
  another pinned gotcha), strings via `value(String)`, everything else
  through the typed accessors. No state to cache; scratch buffers are
  per-call.

### Aspect 5: Javadoc contract (the part DRAFT called out explicitly)

- **`JsonCodec`** gets a first interface-level Javadoc pinning the contract
  every adapter (current or future, including user-supplied ones) must meet:
  `serialize`/`deserialize` are synchronous and throw unchecked exceptions
  (`UncheckedIOException`-style wrapping, never a checked type);
  `deserialize` honors `java.lang.reflect.Type` (generics included);
  `prettyPrint` is best-effort; instances must be thread-safe (they are
  shared across all calls of a client).
- **Each adapter's class Javadoc** states: which external library + minimum
  version it targets (**Gson 2.8.3+** — correction, Step 5, 2026-10-13,
  pinned against the tags: the lazy pretty twin relies on
  `Gson.newBuilder()`, which landed in 2.8.3, not 2.8.0 as originally
  assumed; Moshi 1.x; Jackson 2.x per ADR-0001), what `defaults()`
  configures (and what it deliberately does not), and its date/time
  behavior. Done in Step 5 (2026-10-13): `JsonCodec` carries the
  normative interface-level Javadoc (unchecked wrapping in the adapter's
  own exception family, `Type` honored, top-level `null` → JSON `null`,
  best-effort `prettyPrint`, thread-safety) and all three adapters state
  library + minimum version, `defaults()` scope, date/time behavior and
  thread-safety.

### Aspect 6: Test strategy (and the one real wrinkle)

- `build.gradle`: `compileOnly` for `com.google.code.gson:gson` and
  `com.squareup.moshi:moshi` (verify the POM still ships zero dependencies —
  the 0.5.0 gate); `testImplementation` for both. `moshi-adapters` is
  **not** a dependency: its probe was dropped in Step 2 (Aspect 3
  correction), and the explicit-Moshi test paths use hand-rolled adapters
  instead. Versions: latest stable at implementation time (Gson pinned at
  2.14.0 in Step 1; Moshi pinned at 1.15.2 in Step 2, 2026-10-13).
- New `GsonCodecTest` and `MoshiCodecTest` mirror the scope of
  `JacksonCodecDefaultsTest`: serialize/deserialize round-trips incl.
  generics, date/time behavior pins, null handling, `prettyPrint`
  (best-effort: valid input → indented output; garbage → unchanged).
  `MoshiCodecTest` (13 tests, Step 2) additionally pins the plain-Moshi
  platform-class rejection for `java.time`/`java.util.Date` and the
  number-literal fidelity of the token-based `prettyPrint`.
- The existing `JsonCodecResolverTest` keeps its Jackson pin — with all three
  libraries on the test classpath, the chain head (Jackson) must win. That
  test's assertions are the "priority" spec.
- **Wrinkle: testing "gson wins when jackson is absent" while Jackson is
  permanently on the test classpath.** Decision: a package-private test seam —
  `resolve()` delegates to a package-private overload taking a
  `Predicate<String>`-style classpath probe, so tests can simulate each
  combination (gson-only, moshi-only, all-absent → fail-fast) with no
  `URLClassLoader` machinery. No public API grows; the seam stays
  package-private and is covered by the resolver tests. Step 3 (2026-10-13):
  the seam is exercised by `ru.jrestly.json.JsonCodecResolverChainTest`
  (the full 8-way classpath truth table, including the functional pins that
  the resolved codec is a working `defaults()` instance).

## Consequences

- Hosts with Gson or Moshi (and nothing else) get a working client with zero
  configuration — the promise of ADR-0001's probe chain is finally complete.
- +2 `compileOnly` artifacts; +2 `testImplementation` artifacts (the
  `moshi-adapters` test dependency was dropped in Step 2 — Aspect 3); POM
  must stay dependency-free (re-verified at each step's gate).
- README: "Jackson 2 is autodetected" becomes "Jackson, Gson, or Moshi is
  autodetected" (status line, the three install-snippet comments, and the
  SPI note in one place) — due in Step 6 (the concept stage estimated the
  epic as "Steps 1–4"; the step list grew), still referencing the last tag
  per the "no tags" agreement.
- Date behavior intentionally differs across adapters (Jackson default
  space-separated vs Gson frozen reflective-style objects vs Moshi: no
  date/time support out of the box — `java.*` platform classes are
  rejected until an adapter is registered): each adapter's Javadoc is the
  contract, and a mixed-classpath host always gets Jackson anyway
  (Aspect 1).
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
- `java.time`/`java.util.Date` are not first-class in `MoshiCodec.defaults()`
  — there is no standard Moshi 1.x add-on to probe (the `moshi-adapters`
  probe was a phantom, Aspect 3). A Moshi host that needs them brings an
  explicit `Moshi` with user-registered adapters — accepted, and strictly
  more honest than a probe for a minority artifact.
- `MoshiCodec.defaults()` cannot serialize concrete `java.util` collection
  values: Moshi's reflective path accepts collection *interfaces* only, and
  the codec resolves the runtime class, so a `Map.of(...)`/`HashMap` value
  fails with a wrapped "No JsonAdapter for class ... (Moshi only supports the
  collection interfaces by default)" (pinned against 1.15.2 in Step 3,
  2026-10-13). A Moshi host that serializes collection values brings an
  explicit `Moshi` with an adapter registered for the concrete type.

---

## Amendment #245 (2026-10-17) — 32nd Silent Finding: `MoshiCodec.copyValue` OOM on null tokens

- **Context & Defect:**
  In `MoshiCodec.copyValue(JsonReader, JsonWriter)` (used for token-by-token formatting in `prettyPrint`),
  the `case NULL` branch called `writer.nullValue()` but failed to consume the token from the underlying reader
  (`reader.nextNull()` was omitted). When formatting JSON arrays or objects containing `null` values
  (e.g., `[1, null, 2]`), `reader.peek()` remained stuck on `NULL`, resulting in an infinite loop that repeatedly
  allocated buffer segments until terminating with `java.lang.OutOfMemoryError: Java heap space`.
- **Resolution:**
  Updated `case NULL` in `MoshiCodec.copyValue` to invoke `reader.nextNull()` before `writer.nullValue()`.
- **Verification:**
  Covered by `MoshiCodecTest.prettyPrintTokensHandling` verifying arrays, booleans, and nulls.
