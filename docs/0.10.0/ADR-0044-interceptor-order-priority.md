# ADR-0044: Interceptor `@Order` Priority

**Status:** Accepted  
**Date:** 2026-10-15  
**Session:** #122

---

## Context

jRestly already supports declarative interceptors via `@WithInterceptor` (ADR-0029), with
well-defined scope ordering: Global client → Super-interface → Sub-interface → Method.

However, **within a single `@WithInterceptor(value = {...})` declaration** the execution
sequence was implicitly determined by the Java array order, without any guaranteed,
semantics-driven priority mechanism.  
This is fragile in cross-team or library-composed setups where multiple interceptors must
cooperate in a fixed sequence — e.g. authentication before logging, MDC enrichment before
any outbound call, tracing propagation before auth.

## Decision

Introduce `@ru.jrestly.annotation.Order(int value)` — a simple, targeted annotation placed
on an interceptor **class** (not on `@WithInterceptor` itself) that controls its priority
within the same `@WithInterceptor` value array.

### Semantics

| `@Order` value | Meaning |
|---|---|
| negative (`< 0`) | Higher priority — runs **earlier** |
| `0` (default) | Neutral — declared-order baseline |
| positive (`> 0`) | Lower priority — runs **later** |

- Interceptors are sorted **stably** in ascending order of their `@Order` value.
- Classes without `@Order` use `Order.DEFAULT = 0`.
- **Tie-breaking** follows the original declaration order (stable sort via `Arrays.sort`).
- The inter-level ordering guarantee (Global → Super → Sub → Method) is **not affected**.
- `@Order` is a `@Target(ElementType.TYPE)` annotation — it lives on the interceptor class,
  not on the call site, making it a property of the interceptor itself.

### Why class-level, not call-site?

- An interceptor's intended priority is an intrinsic property of its design (e.g.
  "auth always first"), not something callers should re-specify on every interface.
- Keeps `@WithInterceptor` lean; avoids parallel arrays or complex `@Order`-per-entry syntax.
- If a caller needs different ordering, they can register multiple interceptors in separate
  `@WithInterceptor` annotations (preserving declaration order between annotations).

## Implementation

### New files

- `ru.jrestly.annotation.Order` — runtime-retained, `@Target(ElementType.TYPE)`, `int value() default 0`.

### Modified files

- `ru.jrestly.annotation.WithInterceptor` — Javadoc updated to document `@Order` semantics
  and link to the new annotation.
- `ru.jrestly.http.HttpTemplateBuilder`:
  - Import `ru.jrestly.annotation.Order`.
  - `sortedByOrder(Class<?>[] classes)` — clones and stably sorts the array by `orderOf()`.
  - `orderOf(Class<?> clazz)` — reads `@Order` or returns `Order.DEFAULT`.
  - `resolveInterceptors()` — calls `sortedByOrder(ann.value())` before iterating each
    `@WithInterceptor` value array (both interface-level and method-level scopes).

### Tests added (3 new, in `InterceptorAnnotationTest`)

1. **`orderAnnotationSortsInterceptorsAscending`** — declares three interceptors in
   `@WithInterceptor({Low(+10), High(-10), Neutral(0)})` order; asserts resolved list is
   `[High, Neutral, Low]`.
2. **`orderAnnotationStableSortOnTie`** — verifies same result reproducibly (stable sort).
3. **`noOrderAnnotationUsesDefault`** — interceptors without `@Order` still resolve
   correctly (backward-compatible path).

## Gates

- `./gradlew test`: **927/927** tests (112 classes, +3 tests to 924) — green.
- `./gradlew compileJava compileTestJava javadoc`: 0 warnings under `-Xlint:all -Werror`
  and `-Xdoclint:all -Werror`.
- Zero-dep core preserved: 0 `<dependency>` in Maven POM.

## Consequences

- **Positive:** Deterministic, semantics-driven interceptor ordering without breaking any
  existing API or test.
- **Positive:** `@Order` is familiar to Spring/Jakarta EE developers — low learning curve.
- **Positive:** Zero allocation cost on hot paths: sorting happens once at build time in
  `HttpTemplateBuilder.build()`, not per-request.
- **Neutral:** Only sorts within a single `@WithInterceptor` value array. Cross-annotation
  ordering still follows declaration order (consistent with existing behaviour).
- **Next free ADR number:** 0045.

## Amendment #189

Аудит «ADR против кода» (сессия #189) выявил утечку скопа сортировки:
`MethodMetadata.parseInterceptors` (переехавшая из `HttpTemplateBuilder` в ADR-0051)
выполняла **финальную глобальную стабильную сортировку** всего разрешённого
списка по `@Order` — поверх всех аннотаций и всех уровней скопа
(super → sub → method). Это противоречило прямому тексту ADR-0044
(«Only sorts within a single `@WithInterceptor` value array», «The inter-level
ordering guarantee … is not affected») и пирамиде ADR-0029. Поведение:
method/sub-интерцептор с `@Order(-10)` **опережал** super-интерцептор с
`@Order(0)` — инверсия «Super → Sub → Method»; второй `@WithInterceptor` с
отрицательным `@Order` выдвигался перед первым, вопреки «cross-annotation
ordering follows declaration order». Тихий: при равных значениях (0,
без `@Order`) стабильная сортировка — no-op, поэтому расхождение проявлялось
только когда `@Order` реально использовался на разных уровнях. Фикс: финальная
глобальная сортировка удалена — каждый value-массив уже отсортирован
`sortedByOrder()` внутри декларации; публичный API не тронут. Javadoc
`@WithInterceptor` уточнён («within each declaration», а не «within each
scope level»). TDD red-first: 2 красных теста в `InterceptorResolutionTest`
(cross-level и cross-annotation), зелёные после; все предсуществующие
порядковые тесты (в т.ч. ADR-0044 и `pipelineOrderingHierarchy`) не тронуты.
