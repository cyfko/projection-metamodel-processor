# JPA Metamodel Processor

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/jpa-metamodel-processor)](https://search.maven.org/artifact/io.github.cyfko/jpa-metamodel-processor)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)

**JPA Metamodel Processor** is a Java annotation processor that automatically generates type-safe metadata registries for JPA entities and their DTO projections, in accordance with the [Projection Specification](https://github.com/cyfko/projection-spec/tree/main).

## 🎯 Goals

This processor provides:

- **Automatic JPA metadata extraction**: Analyzes entities and embeddables to build a compile-time metadata registry
- **DTO projection management**: Declarative mapping definitions between JPA entities and DTOs with compile-time validation
- **Computed field support**: Integration of computation providers for derived fields
- **Type-safe registries**: Generation of immutable registries accessible at runtime via simple APIs

## 📋 Prerequisites

- **Java 21+**
- **Maven 3.6+**
- **Jakarta Persistence API 3.1.0+**
- **jpa-projection-metamodel** (required dependency)

## 🚀 Installation

### Maven

Add the following dependency to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.github.cyfko</groupId>
                        <artifactId>jpa-metamodel-processor</artifactId>
                        <version>1.0.5</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

The annotation processor will be automatically detected and executed during compilation thanks to `auto-service`.

> **Note:** This module requires `jpa-projection-metamodel` as a dependency. Make sure you have it in your dependencies as well.

## 🏗️ Architecture

The processor works in two phases:

### Phase 1 - Entity Processing

- Analyzes all `@Entity` and `@Embeddable` classes referenced by the `from` attribute of your `@Projection` annotations (and their related embeddables)
- Extracts their metadata (fields, identifiers, relationships, collections)
- Generates `PersistenceMetadataRegistryProviderImpl`

### Phase 2 - Projection Processing

- Analyzes all `@Projection` classes
- Validates mappings against entity metadata
- Resolves computation methods
- Generates `ProjectionMetadataRegistryProviderImpl`

The generated registries are immutable and thread-safe, accessible via the utility classes `PersistenceRegistry` and `ProjectionRegistry`.

## 🔍 Detailed Features

### Automatic JPA Metadata Detection

The processor automatically detects and extracts:

- **Identifiers**: Fields annotated with `@Id` (simple or composite with `@IdClass` or `@EmbeddedId`)
- **Embeddable fields**: `@Embeddable` classes and their fields
- **Relationships**: `@ManyToOne`, `@OneToOne`, `@OneToMany`, `@ManyToMany`
- **Collections**: `@ElementCollection` and entity collections
- **Exclusions**: `@Transient` fields are automatically excluded from analysis

### Strict Compile-Time Validation

The processor performs comprehensive validation:

- **Field existence**: Verifies that all paths specified in `@Projected(from = "...")` exist in the entity
- **Type consistency**: Ensures that types match between DTO fields and entity fields
- **Computation methods**: Checks the existence and signature of referenced methods
- **Collection reducers**: Validates the presence of reducers for any dependency traversing a collection
- **Fail fast**: Compilation fails immediately on error, avoiding runtime errors

### Flexible Computation Method Resolution

The processor supports multiple resolution modes:

#### 1. Naming Convention in Providers

```java
@Projection(from = User.class, providers = @Provider(UserComputations.class))
public interface UserDTO {
    @Computed(dependsOn = {"firstName", "lastName"})
    String getFullName();
}

public class UserComputations {
    // Convention: get[FieldName]
    public static String getFullName(String firstName, String lastName) {
        return firstName + " " + lastName;
    }
}
```

#### 2. Explicit Reference via @Method

```java
@Projection(from = User.class)
public interface UserDTO {
    @Computed(
        dependsOn = {"firstName", "lastName"}, 
        computedBy = @Method(type = ExternalComputer.class, value = "joinNames")
    )
    String getDisplayName();
}

public class ExternalComputer {
    public static String joinNames(String first, String last) {
        return first + ":" + last;
    }
}
```

#### 3. Static and Instance Methods

The processor supports both types of methods:

```java
public class Computations {
    // Static method
    public static String staticMethod(String arg) { ... }
    
    // Instance method (can be a Spring bean)
    public String instanceMethod(String arg) { ... }
}
```

#### Strict Validation

- Compilation **fails** if the method does not exist
- Compilation **fails** if the signature does not match (different number or types of parameters than `dependsOn`)
- Compilation **fails** if the referenced class is not accessible

### Collection Reducer Support

When a dependency traverses a collection, an inline `:REDUCER` suffix is required:

```java
@Projection(from = Company.class, providers = @Provider(CompanyComputers.class))
public interface CompanyDTO {
    // Collection dependency → reducer required via :SUFFIX format
    @Computed(dependsOn = {"orders.amount:SUM"})
    BigDecimal getTotalRevenue();
}
```

The processor validates:
- That a reducer is defined correctly via the `path:REDUCER` syntax
- That the number of reducers matches the number of paths traversing a collection constraint
- That reducers use valid values (`:SUM`, `:AVG`, `:COUNT`, etc.)

### 🌐 Layer 3: Projection Exposure

The processor fully supports the unified **Exposure Layer v3.0.0**:

- **Scalar Variables**: Opt-in queryability via `@ExposedAs("SCREAMING_SNAKE")` safely restricting lookup dictionaries.
- **Deep Composition**: Through `@Projected(as = "PREFIX")`, child representations logically join contexts creating seamless paths without redundancy.
- **Cycle Break & Depth DFS**: Bi-directional resolution cycles stop gracefully at compile-time forcing explicitness and preventing runaway dependencies.

## 📁 Generated Files

Generated classes are located in `target/generated-sources/annotations/`:

```
target/generated-sources/annotations/
└── io/github/cyfko/jpametamodel/providers/impl/
    ├── PersistenceMetadataRegistryProviderImpl.java
    └── ProjectionMetadataRegistryProviderImpl.java
```

These classes are automatically compiled and packaged with your application.

## ⚙️ Configuration and Customization

### Disable the Processor

If necessary, you can disable the processor:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <proc>none</proc>
    </configuration>
</plugin>
```

### Enable Debug Logging

To see processing details:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>-Averbose=true</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

## 🔧 Usage Examples

### Complete Example

```java
// 1. JPA Entity
@Entity
public class User {
    @Id
    private Long id;
    private String firstName;
    private String lastName;
    private LocalDate birthDate;
    
    @Embedded
    private Address address;
    
    @OneToMany(mappedBy = "user")
    private List<Order> orders;
}

// 2. Computation Provider
public class UserComputations {
    public static String getFullName(String firstName, String lastName) {
        return firstName + " " + lastName;
    }
    
    public Integer getAge(LocalDate birthDate) {
        return Period.between(birthDate, LocalDate.now()).getYears();
    }
}

// 3. DTO Projection
@Projection(from = User.class, providers = @Provider(UserComputations.class))
@Exposure(value = "users", namespace = "api") // Enables Layer 3 mapping integration
public interface UserDTO {
    
    @ExposedAs(value = "FIRST_NAME", operators = {"EQ", "IN"})
    String getFirstName();
    
    @Projected(from = "address.city")
    String getCity();
    
    @Computed(dependsOn = {"firstName", "lastName"})
    String getFullName();
    
    @Computed(dependsOn = {"birthDate"})
    Integer getAge();
    
    @Computed(dependsOn = {"orders.total:SUM"})
    BigDecimal getTotalSpent();
}
```

### Runtime Usage

```java
// Registries are automatically available
ProjectionMetadata meta = ProjectionRegistry.getMetadataFor(UserDTO.class);
List<String> requiredFields = ProjectionRegistry.getRequiredEntityFields(UserDTO.class);
// ["firstName", "lastName", "birthDate", "address.city", "orders.total"]
```

## ⚠️ Limitations

- **Visibility**: Entity classes must be public
- **Transient fields**: `@Transient` fields are excluded from analysis
- **Naming convention**: Providers must follow the `get[FieldName]` convention (except with `@Method`)
- **Processing scope**: Only entities referenced in the `from` attribute of `@Projection` are analyzed

## 🔧 Troubleshooting

### The Processor Doesn't Run

**Check that:**
- `jpa-projection-metamodel` is in your dependencies (scope `compile`)
- `jpa-metamodel-processor` is in your dependencies (scope `provided` or `compile`)
- You haven't disabled processors with `<proc>none</proc>`
- The JAR file contains the `META-INF/services/javax.annotation.processing.Processor` file

**Diagnosis:**
```bash
mvn clean compile -X | grep "Annotation processor"
```

### "Package does not exist" Error (JPMS)

If you are using Java Modules (`module-info.java`), create the placeholder file:

`src/main/java/io/github/cyfko/jpametamodel/providers/impl/package-info.java`

See the JPMS section in the `jpa-projection-metamodel` README.

### "Method not found for computed field" Error

**Check that:**
- The method name follows `get[FieldName]` OR is explicitly referenced via `@Method`
- The signature matches: the number and types of parameters exactly match `dependsOn`
- The provider class is accessible (public and in the compilation classpath)
- If you use `@Method`, verify that `type` and `value` are correct

**Common error example:**
```java
@Computed(dependsOn = {"firstName", "lastName"})  // 2 String parameters
private String fullName;

// ❌ ERROR - wrong signature (only 1 parameter)
public static String getFullName(String name) { ... }

// ✅ CORRECT
public static String getFullName(String firstName, String lastName) { ... }
```

### "Missing reducer for collection dependency" Error

When a dependency traverses a collection, a reducer is mandatory:

```java
// ❌ ERROR - no reducer for "orders.amount"
@Computed(dependsOn = {"orders.amount"})
private BigDecimal total;

// ✅ CORRECT
@Computed(dependsOn = {"orders.amount:SUM"})
private BigDecimal total;
```

### Generated Classes Not Found

**Check that:**
- The `target/generated-sources/annotations/` folder is marked as source in your IDE
- You have performed a complete compilation (`mvn clean compile`)
- Generated classes are present in the final JAR

**For IntelliJ IDEA:**
1. File → Project Structure → Modules
2. Verify that `target/generated-sources/annotations` is marked as "Sources"

**For Eclipse:**
1. Project Properties → Java Build Path → Source
2. Add `target/generated-sources/annotations` if necessary

## 🤝 Contributing

Contributions are welcome! Please open an issue to discuss major changes before submitting a pull request.

### Local Development

```bash
# Clone the repository
git clone https://github.com/cyfko/jpa-metamodel-processor.git
cd jpa-metamodel-processor

# Build and test
mvn clean install

# Run tests
mvn test
```

## 📄 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## 👤 Author

**Frank KOSSI**

- Email: frank.kossi@kunrin.com, frank.kossi@sprint-pay.com
- Organization: [Kunrin SA](https://www.kunrin.com), [Sprint-Pay SA](https://www.sprint-pay.com)

## 🔗 Links

- [GitHub Repository](https://github.com/cyfko/jpa-metamodel-processor)
- [Issue Tracker](https://github.com/cyfko/jpa-metamodel-processor/issues)
- [Maven Central](https://search.maven.org/artifact/io.github.cyfko/jpa-metamodel-processor)
- [jpa-projection-metamodel](https://github.com/cyfko/jpa-projection-metamodel)
- [Projection Specification](https://github.com/cyfko/projection-spec)