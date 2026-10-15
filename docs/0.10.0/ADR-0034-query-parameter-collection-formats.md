# ADR-0034: Query Parameter Collection Formatting Styles (CollectionFormat & @QueryCollectionFormat)

## Status
Accepted

## Context
In modern REST APIs, microservices, and web standards (OpenAPI 3.0, Swagger 2.0, RFC 6570):
1. **Diverse Collection Query Formats:** Different backend systems and API specifications expect multi-value query parameters (collections, lists, sets, and arrays) in different serialization formats:
   - **`EXPLODE` / `MULTI`** (OpenAPI 3.0 `style: form, explode: true`, Swagger 2.0 `multi`): repeated query parameters with the same name, e.g. `tag=alpha&tag=beta`. This has been jRestly's sole default behavior (ADR-0025).
   - **`CSV`** (OpenAPI 3.0 `style: form, explode: false`, Swagger 2.0 `csv`): comma-separated values, e.g. `tag=alpha,beta` (or `tag=alpha%2Cbeta`). Extremely popular in REST APIs (GitHub API, Stripe API, Spring Data `@SortDefault`, filtering endpoints).
   - **`SSV`** (Swagger 2.0 `ssv`): space-separated values, e.g. `tag=alpha%20beta`.
   - **`TSV`** (Swagger 2.0 `tsv`): tab-separated values, e.g. `tag=alpha%09beta`.
   - **`PIPES`** (Swagger 2.0 `pipes`): pipe-separated values, e.g. `tag=alpha%7Cbeta`.
   - **`BRACKETS`** (Rails, PHP, Express, and Spring array notation): repeated key-value pairs with bracket-suffixed names, e.g. `tag[]=alpha&tag[]=beta` (or `tag%5B%5D=alpha&tag%5B%5D=beta`).
2. **Current Limitations in jRestly:**
   - Prior to this ADR, all `Collection<?>` and array parameters were always serialized as repeated parameters (`EXPLODE` style).
   - Users connecting to REST endpoints expecting CSV (`?fields=id,name,email` or `?status=ACTIVE,PENDING`) or bracketed array notation (`?filter[]=a&filter[]=b`) had to manually format strings (`String.join(",", tags)`), sacrificing type safety, IDE refactoring, and clean declarative controller interfaces.
3. **Ergonomic & Architectural Goals:**
   - Maintain strict zero-dependency core (Zero-Dep Core invariant).
   - Provide a clear, type-safe enum `CollectionFormat` and declarative annotations at both parameter, method, and controller interface levels.
   - Strict priority hierarchy: `Parameter format > Method @QueryCollectionFormat > Sub-interface @QueryCollectionFormat > Super-interface @QueryCollectionFormat > EXPLODE (default)`.
   - Symmetric support across query parameters, `APPLICATION_FORM_URLENCODED` bodies, and `MULTIPART_FORM_DATA` parts.
   - Zero-allocation and null-safety: omitting empty collections and null items automatically.

## Decision
1. **Introduce `CollectionFormat` Enum:**
   - Located in `ru.jrestly.annotation.CollectionFormat`:
     - `DEFAULT`: inherits format from method, interface hierarchy, or defaults to `EXPLODE`.
     - `EXPLODE`: repeated key-value pairs (OpenAPI 3.0 `explode: true`, `tag=a&tag=b`).
     - `CSV`: comma-separated values (`tag=a,b`).
     - `SSV`: space-separated values (`tag=a%20b`).
     - `TSV`: tab-separated values (`tag=a%09b`).
     - `PIPES`: pipe-separated values (`tag=a%7Cb`).
     - `BRACKETS`: bracket notation with repeated pairs (`tag[]=a&tag[]=b`).
2. **Introduce `@QueryCollectionFormat` Annotation:**
   - Located in `ru.jrestly.annotation.QueryCollectionFormat`.
   - `@Target({ElementType.TYPE, ElementType.METHOD})`, `@Retention(RetentionPolicy.RUNTIME)`, `@Documented`.
   - Attribute `CollectionFormat value()`.
3. **Extend `@RequestParam` Annotation:**
   - Add attribute `CollectionFormat format() default CollectionFormat.DEFAULT`.
4. **Resolution Hierarchy:**
   - When serializing a parameter, `HttpTemplateBuilder` resolves effective collection format in the following order:
     1. Parameter `@RequestParam(format = ...)` if not `DEFAULT`.
     2. Method `@QueryCollectionFormat`.
     3. Interface hierarchy from child to parent interfaces (`collectInterfaceHierarchy`).
     4. Default fallback: `CollectionFormat.EXPLODE`.
5. **Handling of Values & Null-Safety:**
   - Scalar values (e.g. `String`, primitive wrappers, custom objects) are treated as single values and always serialized as `param=value`.
   - Collections (`Collection<?>`) and non-byte arrays (`Object[]`, primitive arrays `int[]`, `long[]`, etc.) are filtered for non-null elements:
     - Empty collection/array: parameter is completely omitted from query/form.
     - Formatted according to effective `CollectionFormat`.
   - Dynamic maps (`@RequestParam Map<String, Object>`): if an entry value is a `Collection<?>` or array, it is formatted using the effective format.
6. **Form & Multipart Symmetry:**
   - In `APPLICATION_FORM_URLENCODED`, parameters formatted with `CSV`/`SSV`/`TSV`/`PIPES` are serialized as single key-value form fields, and `BRACKETS` adds `[]` to the form field name.
   - In `MULTIPART_FORM_DATA`, non-file collection parts respect `BRACKETS` by naming parts with `[]`, and `CSV`/`SSV`/`TSV`/`PIPES` by concatenating values into a single part.
7. **Upfront Validation in `ControllerValidator`:**
   - Ensure `@QueryCollectionFormat` values are valid and non-null.

## Consequences
- **Positive:**
  - Full compatibility with OpenAPI 3.0 and Swagger 2.0 collection formatting conventions.
  - Seamless interop with backends expecting CSV (Spring, Django, FastAPI, Stripe, GitHub) and bracketed arrays (Rails, PHP, Express).
  - Clean declarative API with per-parameter, per-method, and per-interface configuration.
  - 100% backward compatibility: existing controllers without format specification continue to default to `EXPLODE`.
  - Zero-dependency invariant preserved.
- **Negative:**
  - None identified.
