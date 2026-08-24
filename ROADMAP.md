# jRestly Roadmap

## 0.5.0 — zero-dependency core

Plan derived from ADR-0001..0004 (`docs/0.5.0/`). The 0.4.0 improvement plan is complete; its history lives in git.

Gates for every step: WireMock suite green (`./gradlew test`), IDE inspections clean.

## Step 1 — Codec SPI + Jackson adapter (ADR-0001)

- [ ] Internal codec SPI (serialize / deserialize / pretty-print, media type)
- [ ] JacksonCodec adapter in the same jar, Jackson as `compileOnly`; compact wire by default, pretty printing only for response logs
- [ ] Codec resolution: explicit builder setting > classpath probe (jackson first) > fail fast with an actionable message
- [ ] Explicit codec wiring on the builder / ModuleInfo surface replaces ObjectMapper
- Gate: POM contains no Jackson; suite green

## Step 2 — Small zero-dep replacements (ADR-0001)

- [ ] Own header record replaces commons-lang3 `Pair` across the public API (breaking)
- [ ] Own `${}` path-variable resolver replaces log4j `StrSubstitutor`
- [ ] `System.Logger` replaces log4j-api; the log4j binding moves to test runtime
- [ ] commons-io and commons-lang3 removed
- Gate: runtime dependency list is empty; suite green

## Step 3 — java.net.http engine (ADR-0002)

- [ ] Internal wire model; template/builder on java.net.http; HC4 vocabulary removed
- [ ] Redirect loop: 301/302/303 → GET, 307/308 → method + body, hop counter, final result returned to the caller (bug fix); RedirectTest updated
- [ ] Own RFC 7578 multipart writer: file parts + text `@RequestParam` parts; text-part test added
- [ ] urlencoded bodies via `URLEncoder`; timeout mapping (connect / per-request); connection-request timeout dropped
- Gate: suite green including updated redirect + multipart tests

## Step 4 — Release 0.5.0-rc1

- [ ] README: single JitPack coordinate, "bring your own JSON codec — Jackson autodetected"
- [ ] Version 0.5.0-rc1; POM metadata sanity check
- [ ] Smoke test: fresh Gradle project, jrestly + jackson-databind only, GET + POST against a stub
- Gate: smoke passes; tag

## Post-0.5.0 shortlist (not scheduled — see ADR-0003)

- Retry with backoff (integrates with `@OnError` semantics)
- `CompletableFuture` return type
- Gson / Moshi codec adapters (classpath probe chain extension)
