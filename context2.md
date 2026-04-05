# JPA Projection Metamodel

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/jpa-projection-metamodel)](https://search.maven.org/artifact/io.github.cyfko/jpa-projection-metamodel)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)

**JPA Projection Metamodel** provides interfaces, annotations, and runtime APIs for defining and leveraging type-safe DTO projections based on the [Projection Specification](https://github.com/cyfko/projection-spec/tree/main).

## 🎯 Goals

This module provides:

- **Projection annotations**: `@Projection`, `@Projected`, `@Computed`, `@Provider`, `@Method`
- **Runtime registry APIs**: `PersistenceRegistry` and `ProjectionRegistry`
- **Metadata interfaces**: Type-safe access to entity and projection metadata
- **Computation support**: Integration of computation providers for derived fields

## 📋 Prerequisites

- **Java 21+**

## 🚀 Installation

### Maven

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>jpa-projection-metamodel</artifactId>
    <version>3.0.0</version>
</dependency>
```

> **Note:** To automatically generate metadata at compile time, also add the `jpa-metamodel-processor` module.

## 📖 Usage Guide

### 1. Define Your JPA Entities

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;
    private String lastName;
    private String email;
    private LocalDate birthDate;

    @Embedded
    private Address address;

    @ManyToOne
    private Department department;

    @OneToMany(mappedBy = "user")
    private List<Order> orders;

    // Getters and setters...
}
```

### 2. Create DTO Projections

Use the `@Projection`, `@Projected`, and `@Computed` annotations:

```java
@Projection(
    from = User.class,
    providers = {
        @Provider(value = UserComputations.class)
    }
)
public interface UserDTO {
    // Direct mapping with field renaming
    @Projected(from = "email")
    String getUserEmail();

    // Nested path to an embeddable field
    @Projected(from = "address.city")
    String getCity();

    // Nested path to a relationship
    @Projected(from = "department.name")
    String getDepartmentName();

    // Collection
    @Projected(from = "orders")
    List<OrderDTO> getOrders();

    // Computed field depending on multiple fields
    @Computed(dependsOn = {"firstName", "lastName"})
    String getFullName();

    // Computed field depending on a single field
    @Computed(dependsOn = {"birthDate"})
    Integer getAge();
}
```

**Note:**

- Only entities referenced in the `from` attribute of `@Projection` are scanned for projection purposes.
- All fields in a class annotated with `@Projection` are implicitly considered as if annotated with `@Projected`, unless explicitly annotated otherwise.
- **Empty projections:** A `@Projection` with no fields is valid and still registers the target entity in `PersistenceRegistry`. This can be useful as a base class for inheritance or to force registration of specific entities.

### 3. Define Computation Providers and External Methods

Create classes containing computation methods for your `@Computed` fields. Methods can be:

- **In a declared provider** (via `@Provider`)
- **Or in any external class** accessible via `@Method(type = ...)`, even if this class is not listed in the providers

Standard provider example:

```java
public class UserComputations {
    // Static method for fullName
    public static String toFullName(String firstName, String lastName) {
        return firstName + " " + lastName;
    }

    // Instance method for age (can be a Spring bean)
    public Integer toAge(LocalDate birthDate) {
        return Period.between(birthDate, LocalDate.now()).getYears();
    }
}
```

Advanced example: external static method not declared as a provider

```java
public class ExternalComputer {
    public static String joinNames(String first, String last) {
        return first + ":" + last;
    }
}

@Projection(from = User.class)
public interface UserDTO {
    @Computed(
        dependsOn = {"firstName", "lastName"},
        computedBy = @Method(type = ExternalComputer.class, value = "joinNames")
    )
    String getDisplayName();
}
```

**Resolution behavior:**

- If `@Method(type = ...)` is used, the method is searched in the specified class, even if it is not a provider.
- Compilation fails if the method does not exist or the signature does not match.
- Project tests validate this behavior to guarantee the expected flexibility.

**Naming convention:** Methods in providers should follow the pattern `to[FieldName]` (DTO field name with the first letter capitalized), unless an explicit method is referenced via `@Method`.

### 4. Use Registries at Runtime

The library provides two primary programmatic facades (`PersistenceRegistry` and `ProjectionRegistry`) to access the generated compile-time metadata at runtime. These registries are lazily loaded and cached for high performance.

#### `PersistenceRegistry` API

The `PersistenceRegistry` provides deep introspection into JPA entities and embeddables without reflection.

**Core Methods:**

*   **`getFieldType(Class< ?> rootEntity, String fieldPath)`:** Resolves the exact Java `Class<?>` of a given nested field path. 
    *Example:* `getFieldType(User.class, "address.city")` returns `String.class`.
*   **`getIdFields(Class<?> entityClass)`:** 
    Returns a `List<String>` containing the ID field names of the entity. Supports composite primary keys inside `@Embeddable` IDs automatically.
*   **`getMetadataFor(Class<?> entityClass)`:** 
    Returns a `Map<String, PersistenceMetadata>` detailing every persistent property, its relationship type, and collection metadata.
*   **`getFieldMetadata(Class<?> entityClass, String fieldName)`:** 
    Fetches the specific `PersistenceMetadata` for a single field.
*   **`isEntityRegistered(Class<?> clazz)`** / **`isEmbeddableRegistered(Class<?> clazz)`:** 
    Checks if the processor successfully generated metadata for the given class.

```java
import io.github.cyfko.jpametamodel.PersistenceRegistry;

if (PersistenceRegistry.isEntityRegistered(User.class)) {
    // Resolve nested types effortlessly
    Class<?> cityType = PersistenceRegistry.getFieldType(User.class, "address.city");
    
    // Retrieve Primary Key fields
    List<String> idFields = PersistenceRegistry.getIdFields(User.class);
}
```

#### `ProjectionRegistry` API

The `ProjectionRegistry` is the centerpiece for translating DTO-centric operations (like API filters or sorting) down to the underlying JPA entity schema. 

**Core Methods:**

*   **`toEntityPath(String dtoPath, Class<?> dtoClass, boolean ignoreCase)`:**
    The most critical method for filter translation. Converts a DTO field path into a valid JPA entity path. It traverses `@Projected` mappings, nested DTOs, and `@Computed` dependencies recursively. 
    *Example:* `toEntityPath("userEmail", UserDTO.class, false)` returns `"email"`.
*   **`getMetadataFor(Class<?> dtoClass)`:**
    Retrieves the `ProjectionMetadata` object. If passed a raw JPA entity class instead of a DTO, it synthesizes an implicit 1:1 projection mapping for you dynamically.
*   **`getRequiredEntityFields(Class<?> dtoClass)`:**
    Returns a comprehensive `List<String>` of all entity fields necessary to fully populate the DTO. Perfect for optimizing `SELECT` queries to only fetch exactly what is needed.
*   **`hasProjection(Class<?> dtoClass)`:**
    Checks if explicit projection metadata was generated for the DTO.

```java
import io.github.cyfko.jpametamodel.ProjectionRegistry;

// 1. Translate DTO paths to Entity paths (Used heavily by FilterQL)
String entityPath = ProjectionRegistry.toEntityPath("userEmail", UserDTO.class, false); 
// Returns: "email"

String nestedPath = ProjectionRegistry.toEntityPath("address.city", UserDTO.class, false);
// Returns: "address.cityName" (resolving @Projected annotations deeply)

// 2. Query optimization
List<String> fetchFields = ProjectionRegistry.getRequiredEntityFields(UserDTO.class);
// Build JPA Query selecting ONLY these specific properties
```

## 💡 Use Cases

- **REST API with Projections:** Dynamically build JPA queries based on fields requested by the client.
- **Filter Schema Validation:** Validate that fields used in filters exist in entities.
- **API Documentation Generation:** Automatically generate OpenAPI/Swagger documentation from metadata.
- **Query Optimization:** Build JPA queries that only load fields necessary for a given projection.

## 🔧 Advanced Features

### Collection Support

The processor automatically detects collections and extracts their metadata:

```java
@Projection(from = User.class)
public interface UserDTO {

    @Projected(from = "orders")
    List<OrderDTO> getOrders();  // Entity collection

    @Projected(from = "tags")
    Set<String> getTags();  // Element collection
}
```

### Computation Providers with Dependency Injection

You can use Spring beans for computation providers:

```java
@Projection(
    from = User.class,
    providers = {
        @Provider(value = DateFormatter.class, bean = "isoDateFormatter")
    }
)
public interface UserDTO {
    @Computed(dependsOn = {"createdAt"})
    String getFormattedDate();
}

@Service("isoDateFormatter")
public class DateFormatter {
    public String toFormattedDate(LocalDateTime createdAt) {
        return createdAt.format(DateTimeFormatter.ISO_DATE);
    }
}
```

### Complex Nested Paths

Support for deep paths in entity hierarchy:

```java
@Projected(from = "department.manager.address.city")
String getManagerCity();
```

## 📝 Annotations

Annotations described here are a reminder of the [Projection Specification](https://github.com/cyfko/projection-spec/tree/main)

### `@Projection`

Class-level annotation that declares a DTO projection.

**Parameters:**

- `from`: The source JPA entity class (required)
- `providers`: Array of computation providers (optional)

### `@Projected`

Field-level annotation to map a DTO field to an entity field.

**Parameters:**

- `from`: The path to the entity field (optional, uses DTO field name by default)
- `as`: Logical prefix for composed criterion inheritance (optional). When the field returns a `@Projection` type, its criteria are inherited under this prefix.
- `cycleBreak`: If `true`, excludes this field from criterion inheritance to break bidirectional cycles (default: `false`)

### `@Computed`

Field-level annotation to declare a computed field.

**Parameters:**

- `dependsOn`: Array of paths to entity fields required for computation
- `reducers`: Array of reducer names for collection dependencies (e.g., `"SUM"`, `"AVG"`, `"COUNT"`, `"MIN"`, `"MAX"`)
- `computedBy`: Optional `@Method` to specify the computation method
- `then`: Optional `@Method` to specify a transformation/fallback method after `computedBy`

**Transformation Pipelines with `then`:**

You can create a two-stage computation pipeline using `computedBy` and `then`. The result of the first computation (`computedBy`) is passed as the first argument to the `then` method:

```java
@Projection(from = User.class, providers = @Provider(UserComputed.class))
public interface UserDTO {
    @Computed(
        dependsOn = "birthDate",
        computedBy = @Method(value = "calculateAge"),
        then = @Method(type = FormattingUtils.class, value = "formatAge")
    )
    String getFormattedAge();
}
```

**Reducers for Collection Dependencies:**

When a dependency traverses a collection (e.g., `orders.total`), a **reducer is mandatory** to specify how to aggregate the values:

```java
@Projection(from = Company.class, providers = @Provider(CompanyComputers.class))
public interface CompanyDTO {
    // Single collection dependency with SUM reducer
    @Computed(dependsOn = {"orders.amount"}, reducers = {"SUM"})
    BigDecimal getTotalRevenue();

    // Multiple collection dependencies with different reducers
    @Computed(
        dependsOn = {"orders.amount", "orders.items.quantity"},
        reducers = {"SUM", "COUNT"}
    )
    Object getOrderStats();

    // Mixed: scalar + collection (only collection needs reducer)
    @Computed(dependsOn = {"name", "orders.amount"}, reducers = {"AVG"})
    String getSummary();
}
```

**Available Reducers:** `SUM`, `AVG`, `COUNT`, `COUNT_DISTINCT`, `MIN`, `MAX`

**Runtime API:**

```java
ComputedField field = ...;
for (ComputedField.ReducerMapping rm : field.reducers()) {
    String dependency = field.dependencies()[rm.dependencyIndex()];
    String reducer = rm.reducer();
    // "orders.amount" → "SUM"
}
```

### `@Provider`

Annotation to declare a computation provider.

**Parameters:**

- `value`: The provider class (required)
- `bean`: The bean name for dependency injection (optional)

## 🔍 Exposure Layer

### `ExposedCriterion`

Runtime metadata for a queryable criterion, declared directly via `@ExposedAs` or inherited through composed criterion inheritance.

```java
import io.github.cyfko.jpametamodel.api.ExposedCriterion;

ExposedCriterion criterion = new ExposedCriterion(
    "SOURCE_SITE__SITE_NAME",     // composed ref
    "sourceSite.name",            // entity path
    new String[]{"EQ", "MATCHES"},// supported operators
    true,                         // exposed to consumers
    true                          // composed (inherited)
);

criterion.isComposed();       // true
criterion.compositionDepth(); // 1
criterion.refSegments();      // ["SOURCE_SITE", "SITE_NAME"]
criterion.supportsOperator("EQ"); // true
```

### `ExposureMetadata`

Runtime metadata for a queryable resource declaration (`@Exposure` annotation).

```java
import io.github.cyfko.jpametamodel.api.ExposureMetadata;

ExposureMetadata exposure = new ExposureMetadata(
    "products",   // resource name
    "catalog",    // namespace
    "WINDOWED"    // strategy
);

exposure.isWindowed();    // true
exposure.hasNamespace();  // true
```

### Accessing Exposure Data via `ProjectionMetadata`

```java
import io.github.cyfko.jpametamodel.ProjectionRegistry;
import io.github.cyfko.jpametamodel.api.ProjectionMetadata;

ProjectionMetadata metadata = ProjectionRegistry.getMetadataFor(ProductDTO.class);

// Check if exposed as a queryable resource
if (metadata.isExposed()) {
    ExposureMetadata exposure = metadata.exposure();
    // exposure.value(), exposure.namespace(), exposure.strategy()
}

// Get all composed criteria
List<ExposedCriterion> composed = metadata.getComposedCriteria();

// Find a specific criterion
Optional<ExposedCriterion> criterion = metadata.getCriterion("SOURCE_SITE__SITE_NAME", false);

// Get projection-type mappings (fields returning another @Projection)
List<DirectMapping> projections = metadata.getProjectionMappings();
```

## 📦 Java Modules (JPMS) Configuration

If your project uses the Java Platform Module System (i.e., you have a `module-info.java` file), you must follow these specific steps to allow the library to discover the generated metamodel code.

> **Note:** If you are not using Java Modules (e.g., a standard Spring Boot application without `module-info.java`), you can **skip this section**.

### 1. Update `module-info.java`

You need to authorize the library to access the generated implementation via reflection. Add the following directives to your module descriptor:

```java
module com.mycompany.myproject {
    // 1. Require the library
    requires io.github.cyfko.jpametamodel;

    // 2. Open the generated package to the library
    // This allows the PersistenceRegistry to instantiate the generated provider via reflection.
    opens io.github.cyfko.jpametamodel.providers.impl to io.github.cyfko.jpametamodel;
}
```

### 2. Create a "Placeholder" Package File (Crucial)

Since the `io.github.cyfko.jpametamodel.providers.impl` package is populated only after the annotation processor runs, the Java compiler might throw a "package does not exist" error when processing the `opens` directive in step 1.

To fix this, you must explicitly create the package structure in your source tree with a placeholder file.

**Create the following file:**
`src/main/java/io/github/cyfko/jpametamodel/providers/impl/package-info.java`

With this content:

```java
/**
 * Placeholder package to ensure the package exists at compile-time
 * for JPMS 'opens' directive compatibility.
 */
package io.github.cyfko.jpametamodel.providers.impl;
```

This ensures the package technically exists before compilation starts, satisfying the strict module checks.

## 📄 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## 👤 Author

**Frank KOSSI**

- Email: frank.kossi@kunrin.com, frank.kossi@sprint-pay.com
- Organization: [Kunrin SA](https://www.kunrin.com), [Sprint-Pay SA](https://www.sprint-pay.com)

## 🔗 Links

- [GitHub Repository](https://github.com/cyfko/jpa-projection-metamodel)
- [Maven Central](https://search.maven.org/artifact/io.github.cyfko/jpa-projection-metamodel)
- [Projection Specification](https://github.com/cyfko/projection-spec)
- [jpa-metamodel-processor](https://github.com/cyfko/jpa-metamodel-processor)
