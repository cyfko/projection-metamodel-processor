# Projection Spec

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/projection-spec.svg)](https://search.maven.org/artifact/io.github.cyfko/projection-spec)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://openjdk.org/)

**A framework-agnostic annotation specification for declarative DTO mapping, computed fields, and queryable resource exposure in Java.**

---

## Table of Contents

- [Introduction](#introduction)
- [Design Principles](#design-principles)
- [Conceptual Model](#conceptual-model)
- [Specification — Structure Layer](#specification--structure-layer)
  - [@Projection](#projection)
  - [@Provider](#provider)
  - [@Method](#method)
- [Specification — Mapping Layer](#specification--mapping-layer)
  - [@Projected](#projected)
  - [@Computed](#computed)
- [Specification — Exposure Layer](#specification--exposure-layer)
  - [StandardOp](#standardop)
  - [@ExposedAs](#exposedas)
  - [@Exposure](#exposure)
- [Composed Criterion Inheritance](#composed-criterion-inheritance)
- [Compile-Time Validation Rules](#compile-time-validation-rules)
- [Quick Start](#quick-start)
- [Best Practices](#best-practices)
- [Installation](#installation)
- [Contributing](#contributing)
- [License](#license)
- [Author](#author)

---

## Introduction

Every non-trivial Java application maps objects from one shape to another. Entities become DTOs; DTOs become view models; domain objects become API responses. The mapping code that results is repetitive, fragile, and deeply coupled to whichever framework happens to generate it.

**Projection Spec** addresses this by defining a *specification*, not a library. It is a set of annotation types with `SOURCE` retention that describe, declaratively and completely, how a DTO relates to its source object. The annotations carry no runtime footprint — they are consumed entirely by annotation processors at compile time and discarded before execution.

The specification is intentionally **framework-agnostic**. It imports nothing from Spring, Jakarta, Hibernate, or any other framework. A source class can be a JPA entity, a MongoDB document, a protobuf message, a plain POJO, or even another DTO. The implementation that processes these annotations may target any framework, but the specification itself remains neutral.

This document serves as the authoritative reference for the specification. An implementer should be able to build a fully compliant annotation processor by reading this document alone.

> **Note:** This library provides only annotations with `SOURCE` retention. You need an annotation processor implementation (such as [filterql-spring](https://github.com/cyfko/filterql)) to generate actual projection code.

---

## Design Principles

The specification is governed by a small set of architectural invariants. Understanding them first makes every subsequent design decision self-evident.

### Source Retention

All annotations use `RetentionPolicy.SOURCE`. They exist only in source code, are consumed by the annotation processor during compilation, and are absent from the compiled bytecode. This means:

- Zero runtime reflection
- Zero annotation-scanning overhead
- No transitive dependency on this library at runtime

### Declarative Completeness

The DTO itself is the single source of truth for all mapping, computation, and exposure concerns. No external configuration file, no XML, no separate mapper class. A reader looking at a DTO interface can understand its full behavior without consulting any other artifact.

### Source-Only Dependencies

Computed fields may depend only on source fields — never on other computed fields. This eliminates dependency graph resolution, prevents circular dependencies, and ensures every field can be computed independently and in any order. If two computed fields need overlapping intermediate results, each declares its own dependencies from the source, and the provider method handles the internal logic.

### First-Match-Wins Resolution

When resolving a computation method, providers are searched in declaration order and the first matching method is used. This makes resolution deterministic, easy to reason about, and enables intentional override patterns (specific providers before generic ones).

### Immutability by Design

DTOs are declared as interfaces. An interface cannot have mutable state — it exposes only getters. This makes projected objects thread-safe by default, cacheable, and free from accidental mutation.

### Framework Agnosticism

The specification imports no framework types. Operator values are plain `String` constants, not enum members tied to a query language. Exposure attributes use abstract concepts (namespace, cardinality) rather than protocol-specific terms (URI, pagination). Implementations translate these concepts into whatever is meaningful in their context.

---

## Conceptual Model

The specification defines eight annotation types organized in three conceptual layers. Each layer builds on the one below it.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                   LAYER 3 — EXPOSURE                                        │
│                                                                                             │
│   @ExposedAs              @Exposure                    StandardOp                           │
│   (queryable fields)      (resource declaration)       (standard operators)                 │
├─────────────────────────────────────────────────────────────────────────────────────────────┤
│                                   LAYER 2 — MAPPING                                         │
│                                                                                             │
│   @Projected                                      @Computed                                 │
│   (direct source-to-DTO field mapping)            (derived values via two-stage pipeline)   │
├─────────────────────────────────────────────────────────────────────────────────────────────┤
│                                   LAYER 1 — STRUCTURE                                       │
│                                                                                             │
│   @Projection                          @Provider                @Method                     │
│   (entry point: source + providers)    (computation engine)     (method reference)          │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Layer 1 (Structure)** establishes the foundation: which source class is being projected, which classes provide computation logic, and how individual methods are referenced.

**Layer 2 (Mapping)** defines how each DTO field obtains its value — either by direct mapping from a source field, or by computation through a two-stage pipeline.

**Layer 3 (Exposure)** declares which fields are queryable by consumers, what operators they support, and how the projection is exposed as a queryable resource with cardinality semantics and transformation pipelines.

Layers 1 and 2 are sufficient for pure DTO mapping. Layer 3 is required only when the DTO participates in dynamic querying or filtering scenarios.

---

## Specification — Structure Layer

### @Projection

`@Projection` is the entry point of the specification. It is placed on a DTO interface or class and declares two things: the source class being projected, and the provider classes that supply computation logic.

```java
@Projection(
    from = User.class,
    providers = {
        @Provider(UserComputations.class),
        @Provider(value = TaxService.class, bean = "taxService")
    }
)
public interface UserDTO {
    // Fields declared here
}
```

| Attribute   | Type         | Required | Description |
|-------------|--------------|----------|-------------|
| `from`      | `Class<?>`   | Yes      | The source class to project from |
| `providers` | `Provider[]` | No       | Provider classes for computation and transformation methods |

**Rationale — why `from` is a class reference, not a string:**
A class reference enables compile-time validation. The annotation processor can verify that field paths in `@Projected(from = "...")` and `@Computed(dependsOn = {...})` actually exist on the source class, catching typos and structural mismatches before runtime.

**Rationale — why providers are declared here, not discovered automatically:**
Explicit declaration makes the resolution scope visible and deterministic. A reader sees exactly which classes participate in computation, and the declaration order defines the search priority.

---

### @Provider

A `@Provider` registers a class containing computation and transformation methods. Providers are a convenience mechanism for grouping related methods and enabling convention-based resolution (`to[FieldName]`). They are **not required** when `@Method` references specify `type` explicitly — in that case, the processor resolves the method directly from the specified class.

```java
// Static provider — pure functions, no IoC
@Provider(MathUtils.class)

// IoC-managed provider — resolved by type
@Provider(TaxService.class)

// IoC-managed provider — resolved by bean name
@Provider(value = PriceCalculator.class, bean = "premiumPricing")
```

| Attribute | Type       | Required | Description |
|-----------|------------|----------|-------------|
| `value`   | `Class<?>` | Yes      | The provider class |
| `bean`    | `String`   | No       | Bean name for IoC container lookup (empty = resolve by type) |

#### Invocation Strategy

The key design insight is that the *method signature*, not the provider declaration, determines the invocation strategy:

| `bean` attribute | Method type | Resolution |
|------------------|-------------|------------|
| Empty (default)  | `static`    | Direct invocation — no IoC lookup |
| Empty (default)  | Instance    | Resolve provider from IoC by **type** |
| Specified        | `static`    | Direct invocation — bean name ignored |
| Specified        | Instance    | Resolve provider from IoC by **name** |

**Rationale — why static methods bypass IoC entirely:**
A static method is a pure function. It needs no instance, no lifecycle management, and no dependency injection. Requiring IoC for such methods would impose unnecessary framework coupling and runtime overhead.

#### Search Order

When resolving a method for a `@Computed` field, the system searches in this order:

1. The DTO interface itself (static methods only)
2. All providers from `@Projection.providers()`, in declaration order
3. First matching method wins

```java
@Projection(
    from = User.class,
    providers = {
        @Provider(CustomComputations.class),   // Searched first
        @Provider(CommonUtils.class)           // Searched second
    }
)
```

**Rationale — why first-match-wins instead of most-specific-match:**
First-match is trivially deterministic. There is no ambiguity, no scoring, no surprising resolution. If you need to override a method, place the overriding provider first.

---

### @Method

`@Method` is a lightweight reference to a method. It appears inside `@Computed`, `@Exposure`, and other annotations that need to point to executable logic.

When `type` is specified, `@Method` is a **direct reference** to a method in a specific class — no providers are needed. When `type` is omitted, resolution falls back to the providers declared in `@Projection.providers()`.

```java
@Method()                                              // Convention: to[FieldName]
@Method("buildFullName")                               // Named method, search all providers
@Method(type = StringUtils.class)                      // Convention name, specific class
@Method(type = StringUtils.class, value = "uppercase") // Fully qualified
```

| Attribute | Type       | Required | Description |
|-----------|------------|----------|-------------|
| `type`    | `Class<?>` | No       | Direct reference to the class containing the method. When specified, the processor looks in this class only, bypassing all providers. Default (`void.class`): fall back to provider-based resolution. |
| `value`   | `String`   | No       | Method name (default: convention `to[FieldName]`) |

#### Resolution Matrix

| `type`  | `value` | Resolution |
|---------|---------|------------|
| Not set | Not set | Search DTO, then all declared providers for `to[FieldName]` |
| Not set | Set     | Search DTO, then all declared providers for the specified method |
| Set     | Not set | Look in the specified class only for `to[FieldName]` (providers bypassed) |
| Set     | Set     | Look in the specified class only for that exact method (providers bypassed) |

**Rationale — the `to[FieldName]` convention:**
Convention-over-configuration reduces annotation noise. For a field `getFullName()`, the processor looks for `toFullName(...)` — a predictable, readable pattern. The convention can always be overridden with an explicit `value`.

---

## Specification — Mapping Layer

### @Projected

`@Projected` maps a DTO field directly from a source field. It handles three common scenarios: renaming, nested path traversal, and composed projection inheritance.

```java
@Projection(from = User.class)
public interface UserDTO {

    // Rename: source field "createdAt" → DTO field "registrationDate"
    @Projected(from = "createdAt")
    LocalDateTime getRegistrationDate();

    // Nested path: traverse object graph
    @Projected(from = "department.manager.email")
    String getManagerEmail();

    // Composed projection: returns another @Projection type
    @Projected(from = "department", as = "DEPT")
    DepartmentDTO getDepartment();
}
```

| Attribute    | Type      | Required | Default | Description |
|--------------|-----------|----------|---------|-------------|
| `from`       | `String`  | Yes      | —       | Source field path (supports dot notation) |
| `as`         | `String`  | No       | `""`    | Logical prefix for composed criterion inheritance |
| `cycleBreak` | `boolean` | No       | `false` | Excludes this field from criterion inheritance to break cycles |

**Rationale — why `from` has no default value:**
An empty default would mean "use the method name as the field name" — but that is already the behavior of *unannotated* methods. If a developer writes `@Projected`, they are signaling that the source path differs from the method name. Making them state it explicitly avoids ambiguity.

**Rationale — why dot notation instead of nested annotations:**
Dot notation (`"department.manager.email"`) is concise, readable, and mirrors how developers mentally navigate object graphs. Nested annotations would add verbosity without adding expressiveness.

#### Implicit Mapping (No Annotation)

When a getter has no annotation at all, the system maps it implicitly by matching the method name to a source field name:

```java
@Projection(from = User.class)
public interface UserDTO {
    Long getId();          // Automatically maps to User.id
    String getEmail();     // Automatically maps to User.email
}
```

This is not a distinct annotation — it is the absence of one. The specification defines it as: *if a method on a `@Projection` type bears no mapping annotation, the processor infers a direct mapping from a source field whose name matches the getter's property name.*

---

### @Computed

`@Computed` declares a DTO field whose value is derived from one or more source fields through a **two-stage computation pipeline**:

```
Source Fields → [computedBy] → Intermediate Result → [then] → Final Value
                (business logic)                     (type conversion)
```

```java
@Projection(
    from = User.class,
    providers = {
        @Provider(UserComputations.class),
        @Provider(Formatters.class)
    }
)
public interface UserDTO {

    @Computed(
        dependsOn = {"firstName", "lastName"},
        computedBy = @Method("buildDisplayName")
    )
    String getDisplayName();

    @Computed(
        dependsOn = "createdAt",
        computedBy = @Method("toInstant"),
        then = @Method("uppercase")
    )
    String getFormattedDate();
}
```

| Attribute    | Type       | Required | Description |
|--------------|------------|----------|-------------|
| `dependsOn`  | `String[]` | Yes      | Source fields this computation requires (with optional `:REDUCER` suffix for collections) |
| `computedBy` | `Method`   | No       | Business logic method (default: convention `to[FieldName]`) |
| `then`       | `Method`   | No       | Pure transformation applied to `computedBy` result |

#### Stage 1: Business Logic (`computedBy`)

The `computedBy` method receives the source field values (in declaration order) and returns an intermediate or final result. It can be a static method or an IoC-managed instance method.

```java
// Provider
public class UserComputations {
    public static String toFullName(String firstName, String lastName) {
        return firstName + " " + lastName;
    }
}

// DTO — uses convention (toFullName matches getFullName)
@Computed(dependsOn = {"firstName", "lastName"})
String getFullName();
```

#### Stage 2: Transformation (`then`)

The `then` method applies a pure type conversion to the result of `computedBy`. It **must** be `static` — no IoC, no side effects.

```java
// Utilities:
public class DateUtils {
    public static Instant toInstant(LocalDateTime dt) {
        return dt.toInstant(ZoneOffset.UTC);
    }
}

public class Formatters {
    public static String formatIso(Instant instant) {
        return instant.toString();
    }
}

// DTO:
@Computed(
    dependsOn = "createdAt",
    computedBy = @Method(type = DateUtils.class, value = "toInstant"),
    then = @Method(type = Formatters.class, value = "formatIso")
)
String getFormattedDate();
```

**Rationale — why `then` must be static:**
Transformations are pure functions: same input, same output, no side effects. Statelessness guarantees predictability, testability, composability, and zero IoC overhead. If a transformation needs external state, it belongs in `computedBy`, not `then`.

**Rationale — why source-only dependencies:**
Allowing computed fields to depend on other computed fields would introduce dependency graphs, ordering constraints, and potential cycles. By requiring all dependencies to come from the source, every field is independently computable, and the processor never needs topological sorting.

#### Collection Aggregation (Inline Reducers)

When a dependency path traverses a collection, a reducer is specified **inline** using the `:REDUCER` suffix directly in the path:

```java
@Computed(
    dependsOn = {"id", "orders.total:SUM", "refunds.amount:SUM"}
)
String getFinancialReport();
```

Each dependency is self-describing — the reducer is co-located with the field it applies to. No separate array, no positional mapping, no risk of silent misalignment.

Standard reducers are defined in `Computed.Reduce`:

| Reducer          | Syntax Example                        | Description |
|------------------|---------------------------------------|-------------|
| `SUM`            | `"orders.total:SUM"`                  | Sum of all values |
| `AVG`            | `"employees.salary:AVG"`              | Arithmetic mean |
| `COUNT`          | `"orders.id:COUNT"`                   | Count of elements |
| `MIN`            | `"bids.amount:MIN"`                   | Minimum value |
| `MAX`            | `"bids.amount:MAX"`                   | Maximum value |
| `COUNT_DISTINCT` | `"orders.status:COUNT_DISTINCT"`      | Count of distinct values |

**Rules:**
- Collection paths must include a `:REDUCER` suffix (`"orders.total:SUM"` ✅, `"orders.total"` ❌)
- Collection paths must end with a field before the reducer (`"orders.total:SUM"` ✅, `"orders:SUM"` ❌)
- Scalar paths must NOT include a reducer (`"address.city"` ✅, `"address.city:SUM"` ❌)
- Whether a path traverses a collection depends on the source model and is determined by the implementation

**Rationale — why inline instead of a separate `reducers` array:**
A separate array requires positional correspondence between two parallel lists. Adding, removing, or reordering a dependency silently shifts the reducer assignments. Inline syntax makes each dependency self-contained and eliminates this entire class of bugs.

**Rationale — why reducers are mandatory on collection paths:**

A collection may contain thousands to millions of elements. The reducer is a **declarative hint** that allows the implementation to delegate the aggregation to the data source (SQL `SUM()`, MongoDB `$sum`, etc.) rather than loading the entire collection into Java memory. Without a reducer, the implementation would need to pass a potentially unbounded `List<T>` to the provider method — a risk the specification refuses to take.

For **custom aggregation** logic that exceeds standard reducers (weighted averages, conditional sums, etc.), the recommended approach is an IoC-managed provider with direct access to the data source:

```java
// Custom aggregation — provider handles the query directly
@Computed(dependsOn = "id")
BigDecimal getWeightedAverage();

@Service
public class OrderAnalytics {
    @Inject
    private OrderRepository orderRepo;

    public BigDecimal toWeightedAverage(Long userId) {
        // Custom query — fully controlled, memory-safe
        return orderRepo.calculateWeightedAverage(userId);
    }
}
```

This separation gives the best of both worlds:
- **Standard aggregations** (SUM, AVG, COUNT...) → inline reducer, optimized at the data source
- **Custom aggregations** (weighted averages, conditional logic...) → IoC provider with repository access, fully controlled

---

## Specification — Exposure Layer

The exposure layer is new in version 3.0. It enables a projection to participate in criteria-based querying — declaring which fields consumers can query against, which operators they may use, and how the projection is exposed as a queryable resource.

This layer is **entirely agnostic** of transport protocol, delivery mechanism, and response format. The concepts it defines — operators, queryable fields, resource identity, cardinality — are abstract. Each implementation translates them into whatever is meaningful in its own context.

### StandardOp

`StandardOp` is an interface containing `String` constants for the fourteen standard operators that **every compliant implementation must support**.

```java
public interface StandardOp {
    String EQ          = "EQ";           // field = value
    String NE          = "NE";           // field != value
    String GT          = "GT";           // field > value
    String GTE         = "GTE";          // field >= value
    String LT          = "LT";           // field < value
    String LTE         = "LTE";          // field <= value
    String RANGE       = "RANGE";        // field BETWEEN min AND max
    String NOT_RANGE   = "NOT_RANGE";    // field NOT BETWEEN min AND max
    String MATCHES     = "MATCHES";      // case-insensitive contains
    String NOT_MATCHES = "NOT_MATCHES";  // case-insensitive does not contain
    String IN          = "IN";           // field IN (v1, v2, ...)
    String NOT_IN      = "NOT_IN";       // field NOT IN (v1, v2, ...)
    String IS_NULL     = "IS_NULL";      // field IS NULL
    String NOT_NULL    = "NOT_NULL";     // field IS NOT NULL
}
```

Implementations may support additional custom operators beyond this standard set. Custom operators are documented by the implementation and handled through its own extension mechanism.

**Rationale — why `String` constants instead of an `enum`:**
An `enum` is a closed set. Adding a custom operator would require subclassing or wrapper types — neither of which is natural in Java. `String` constants are open by design: any implementation can define `"GEO_WITHIN"` or `"FULL_TEXT"` alongside the standard set, and they flow through `@ExposedAs(operators = {...})` without friction.

---

### @ExposedAs

`@ExposedAs` declares a DTO field as queryable — meaning consumers can submit selection criteria against it using the specified operators.

```java
@ExposedAs(value = "NAME", operators = {StandardOp.EQ, StandardOp.MATCHES})
String getName();

@ExposedAs(value = "LOCATION", operators = {StandardOp.EQ, "GEO_WITHIN"})
String getLocation();

@ExposedAs(value = "TENANT_ID", operators = {StandardOp.EQ}, exposed = false)
String getTenantId();
```

| Attribute   | Type       | Required | Default | Description |
|-------------|------------|----------|---------|-------------|
| `value`     | `String`   | No       | `""`    | Symbolic name in criteria (default: logical name derived from method name, casing is implementation-defined) |
| `operators` | `String[]` | No       | `{}`    | Supported operators (use `StandardOp` constants or custom strings) |
| `exposed`   | `boolean`  | No       | `true`  | If `false`, field is internal-only (not visible to consumers) |

#### Valid Targets

- A getter method on a `@Projection` interface returning a **scalar-compatible type** (primitives, String, temporal types, enums, and other value types with no nested queryable structure)
- A method in a `@Provider` class defining a **virtual queryable field** — a selection criterion with custom resolution logic that does not map directly to a single source field

#### Forbidden Target

A method returning a type annotated with `@Projection`. Such a type participates in [composed criterion inheritance](#composed-criterion-inheritance). Applying `@ExposedAs` directly on it is a compile-time error — the queryable properties of the nested projection are inherited automatically under the field's logical prefix.

**Rationale — the `exposed` attribute:**
Some fields must be queryable by the system (e.g., tenant isolation in pipes) but should not be visible to external consumers. Setting `exposed = false` makes the field available to the implementation's internal machinery without advertising it in the public API.

---

### @Exposure

`@Exposure` declares that a `@Projection` interface should be exposed as a queryable resource, enabling criteria-based access to its data. It is placed at the type level alongside `@Projection`.

```java
@Projection(from = Product.class)
@Exposure(value = "products", namespace = "catalog")
public interface ProductDTO {
    @ExposedAs(operators = {StandardOp.EQ, StandardOp.MATCHES})
    String getName();

    @ExposedAs(operators = {StandardOp.GTE, StandardOp.LTE})
    BigDecimal getPrice();
}
```

| Attribute   | Type       | Required | Default              | Description |
|-------------|------------|----------|----------------------|-------------|
| `value`     | `String`   | No       | `""`                 | Logical resource name |
| `namespace` | `String`   | No       | `""`                 | Logical namespace grouping related resources |
| `strategy`  | `Strategy` | No       | `Strategy.WINDOWED`  | Result cardinality strategy |
| `pipes`     | `Method[]` | No       | `{}`                 | Ordered query transformation pipeline |
| `handler`   | `Method`   | No       | `@Method()`          | Custom handler producing the resource result |

Has no effect if used without `@Projection`.

#### `value` — Resource Identity

The logical name identifying the resource. Implementations derive a concrete identifier from it:

- A REST implementation may map `"users"` to `/users/search`
- A GraphQL implementation may generate a `users` query type
- A messaging implementation may publish to a `users.query` topic

#### `namespace` — Logical Grouping

An intentionally abstract grouping concept:

- A REST implementation may use it as a URI prefix: `"admin"` → `/admin/users`
- A GraphQL implementation may use it as a schema module
- A messaging implementation may prepend it to topic names: `"billing"` → `billing.invoices`
- A versioned API may use it as a version scope: `"v2"`

#### `strategy` — Result Cardinality

Defines the intent regarding *how much* of the matching result set is returned:

| Strategy   | Intent | Typical Use Cases |
|------------|--------|-------------------|
| `WINDOWED` | Consumer controls a bounded view over the result set | General-purpose data access, large collections |
| `FULL`     | All matching results returned at once | Reference data lookups, configuration entries, exports |
| `CUSTOM`   | Handler defines the result shape entirely | Dashboards, reports, aggregated statistics |

How `WINDOWED` is concretely implemented — offset/limit, cursor-based, Relay connections, chunked streaming — is entirely up to the implementation.

#### `pipes` — Transformation Pipeline

An ordered chain of pure transformation units applied to the query context before the handler:

```
QueryContext → Pipe1 → Pipe2 → ... → Handler
```

```java
@Exposure(
    value = "invoices",
    namespace = "billing",
    pipes = {
        @Method("enforceCurrentTenant"),
        @Method("applyDateRange")
    }
)
```

Typical use cases for pipes:
- **Tenant isolation:** inject a mandatory tenant criterion
- **Access control:** restrict results based on user permissions
- **Input enrichment:** add default sorting, impose result limits
- **Constraint enforcement:** require a date range, reject overly broad queries

#### `handler` — Custom Execution

When specified, the implementation delegates execution to this method instead of generating a default handler. Common scenarios:
- Querying a search engine or cache layer instead of the primary data source
- Combining data from multiple sources
- Triggering auditing or metrics alongside query execution

**Rationale — why no HTTP, REST, or pagination vocabulary:**
The specification must outlive any single protocol. By using abstract concepts (namespace, cardinality, pipes, handler), the same `@Exposure` annotation works whether the implementation generates a REST controller, a GraphQL resolver, a gRPC service, or a Kafka consumer. The implementation translates; the specification describes intent.

---

## Composed Criterion Inheritance

When a `@Projected` field returns a type that is itself annotated with `@Projection`, a powerful composition mechanism activates: the queryable properties of the nested projection are **automatically inherited** by the parent DTO under a logical prefix.

### How It Works

The double underscore (`__`) is the **reserved composition separator**. It marks the boundary between the prefix and the inherited criterion name:

```java
@Projection(from = Customer.class)
@Exposure("customers")
public interface CustomerDTO {

    @ExposedAs(operators = {StandardOp.EQ, StandardOp.MATCHES})
    String getName();
    // Exposed as: NAME

    @Projected(from = "address", as = "ADDR")
    AddressDTO getAddress();
    // AddressDTO's criteria are inherited under prefix ADDR__
}

@Projection(from = Address.class)
public interface AddressDTO {

    @ExposedAs(operators = {StandardOp.EQ})
    String getCity();
    // Inherited as: ADDR__CITY

    @ExposedAs(operators = {StandardOp.EQ})
    String getCountry();
    // Inherited as: ADDR__COUNTRY
}
```

The result: `CustomerDTO` exposes criteria `NAME`, `ADDR__CITY`, and `ADDR__COUNTRY` — without the developer manually redeclaring the nested fields.

The `as` attribute controls the prefix. If omitted, it defaults to a logical name derived from the method name (e.g., `getAddress` → implementation-defined casing of `address`).

### Naming Convention

| Symbol | Meaning | Example |
|--------|---------|--------|
| `_` (single) | Word separator within a name | `SITE_NAME`, `SOURCE_SITE` |
| `__` (double) | Composition level boundary | `SOURCE_SITE__SITE_NAME` |

To prevent ambiguity, `@ExposedAs(value = "...")` enforces these rules at compile time:
- **No double underscores** (`__`) in the value — reserved for composition
- **No leading underscore** (`_NAME`) — would create ambiguity at the junction
- **No trailing underscore** (`NAME_`) — same reason

### Cycle Prevention

Bidirectional relationships create cycles:

```
DepartmentDTO → manager: EmployeeDTO → department: DepartmentDTO → ...
```

The `cycleBreak` attribute breaks the cycle on one side:

```java
@Projection(from = Employee.class)
public interface EmployeeDTO {

    @Projected(from = "department", cycleBreak = true)
    DepartmentDTO getDepartment();
    // DepartmentDTO remains available for projection (read),
    // but its criteria are NOT inherited here.
}
```

**Rationale — why `cycleBreak` rather than automatic cycle detection:**
Automatic detection would require the processor to silently drop criteria from one side. By making it explicit, the developer controls *which side* loses inheritance — a deliberate architectural decision, not a silent default.

---

## Compile-Time Validation Rules

A compliant annotation processor should enforce these rules and emit diagnostics:

| Rule | Severity | Description |
|------|----------|-------------|
| Missing `from` on `@Projected` | Error | `from` is mandatory — no default |
| Invalid source path | Error | `@Projected(from = "x.y.z")` must resolve to an existing field chain on the source class |
| `@ExposedAs` on `@Projection` return type | Error | Use composed criterion inheritance instead |
| `@ExposedAs` value contains `__` | Error | Double underscore is reserved as the composition level separator |
| `@ExposedAs` value starts with `_` | Error | Leading underscore creates ambiguity with composition boundaries |
| `@ExposedAs` value ends with `_` | Error | Trailing underscore creates ambiguity with composition boundaries |
| `@Projected` `as` contains `__` | Error | Same rule — prefix participates in the same namespace |
| `@Projected` `as` starts with `_` | Error | Same rule — leading underscore at junction |
| `@Projected` `as` ends with `_` | Error | Same rule — trailing underscore at junction |
| `dependsOn` references computed field | Error | Dependencies must be source fields only |
| `then` method is not static | Error | Transformation methods must be pure functions |
| Collection path without reducer | Error | A collection-traversing dependency must include `:REDUCER` suffix |
| Scalar path with reducer | Error | Non-collection paths must not include `:REDUCER` suffix |
| Collection path not ending with field | Error | `"orders:SUM"` ❌ — must be `"orders.total:SUM"` |
| `@Exposure` without `@Projection` | Warning | Has no effect |
| Bidirectional projection cycle without `cycleBreak` | Error | One side must set `cycleBreak = true` |
| No matching provider method | Error | No method matching the signature was found in any provider |

---

## Quick Start

### 1. Define Your Source Class

```java
public class User {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private LocalDateTime createdAt;
    private Department department;
    private List<Order> orders;
}
```

### 2. Create a DTO with Projections

```java
@Projection(
    from = User.class,
    providers = {
        @Provider(UserComputations.class),
        @Provider(Formatters.class)
    }
)
@Exposure(value = "users", namespace = "api")
public interface UserDTO {

    Long getId();

    String getEmail();

    @Projected(from = "createdAt")
    LocalDateTime getRegistrationDate();

    @Projected(from = "department.name")
    String getDepartmentName();

    @Computed(dependsOn = {"firstName", "lastName"})
    String getFullName();

    @Computed(
        dependsOn = "createdAt",
        computedBy = @Method("toInstant"),
        then = @Method("formatIso")
    )
    String getFormattedDate();

    @Computed(dependsOn = "orders.total:SUM")
    BigDecimal getTotalOrderValue();

    @ExposedAs(value = "EMAIL", operators = {StandardOp.EQ, StandardOp.MATCHES})
    String getEmail();

    @ExposedAs(value = "FULL_NAME", operators = {StandardOp.MATCHES})
    String getFullName();
}
```

### 3. Implement Provider Logic

```java
public class UserComputations {

    public static String toFullName(String firstName, String lastName) {
        if (firstName == null && lastName == null) return null;
        return String.format("%s %s",
            firstName != null ? firstName : "",
            lastName != null ? lastName : ""
        ).trim();
    }

    public static Instant toInstant(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}

public class Formatters {

    public static String formatIso(Instant instant) {
        return instant.toString();
    }
}
```

### 4. Use Your DTO

The annotation processor generates the implementation. Usage depends on your implementation:

```java
UserDTO dto = projectionMapper.map(userEntity, UserDTO.class);
```

---

## Best Practices

### Use Interfaces for Read-Only DTOs

```java
// Preferred: interface enforces immutability
@Projection(from = User.class)
public interface UserDTO {
    Long getId();
    String getName();
}
```

Interfaces cannot have mutable state. This makes DTOs thread-safe, cacheable, and impossible to mutate accidentally.

### Order Providers by Specificity

```java
@Projection(
    from = Order.class,
    providers = {
        @Provider(OrderSpecificLogic.class),    // Domain-specific — checked first
        @Provider(CommonCalculations.class),    // Generic fallbacks — checked last
    }
)
```

Since resolution is first-match-wins, placing specific providers before generic ones ensures intentional overrides work correctly.

### Keep Transformations Pure and Static

```java
// Good: pure function, no state, no side effects
public class Formatters {
    public static String formatCurrency(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance().format(amount);
    }
}
```

Purity guarantees predictability, testability, and zero IoC overhead. If a transformation needs external state, it belongs in `computedBy` (which supports IoC), not in `then`.

### Use Explicit @Method for Disambiguation

```java
@Computed(
    dependsOn = "birthDate",
    computedBy = @Method(type = ModernDateUtils.class, value = "toAge")
)
Integer getAge();
```

When multiple providers have methods with overlapping names, explicit references eliminate ambiguity and make the code self-documenting.

### Separate Read and Write DTOs

```java
// Read — projection
@Projection(from = User.class)
public interface UserReadDTO {
    Long getId();
    @Computed(dependsOn = {"firstName", "lastName"})
    String getFullName();
}

// Write — plain class with validation
public class UserCreateRequest {
    @NotBlank private String firstName;
    @NotBlank private String lastName;
    @Email private String email;
}
```

Projections are read-only by design. Write-side DTOs belong outside the projection system.

---

## Installation

**Maven:**
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>projection-spec</artifactId>
    <version>3.0.0</version>
</dependency>
```

**Gradle (Kotlin DSL):**
```kotlin
implementation("io.github.cyfko:projection-spec:3.0.0")
```

**Gradle (Groovy):**
```groovy
implementation 'io.github.cyfko:projection-spec:3.0.0'
```

> **Important:** This library provides only annotations with `SOURCE` retention. You need an annotation processor implementation to generate actual projection code.

---

## Contributing

Contributions are welcome. Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/descriptive-name`)
3. Commit your changes (`git commit -m 'Add feature'`)
4. Push to the branch (`git push origin feature/descriptive-name`)
5. Open a Pull Request

For major changes, please open an issue first to discuss what you would like to change.

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

---

## Author

**Frank KOSSI**

- Email: frank.kossi@kunrin.com, frank.kossi@sprint-pay.com
- Organization: [Kunrin SA](https://www.kunrin.com), [Sprint-Pay SA](https://www.sprint-pay.com)

---

## Links

- [GitHub Repository](https://github.com/cyfko/projection-spec)
- [Issue Tracker](https://github.com/cyfko/projection-spec/issues)
- [Maven Central](https://search.maven.org/artifact/io.github.cyfko/projection-spec)
- [Full Javadoc](https://javadoc.io/doc/io.github.cyfko/projection-spec)
