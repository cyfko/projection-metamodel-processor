package io.github.cyfko.jpa.metamodel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a DTO field as directly projected from a corresponding entity field.
 *
 * <p>This annotation establishes a straightforward mapping between an entity field and
 * a DTO field, optionally allowing the DTO field to have a different name than its
 * source entity field.</p>
 *
 * <h2>Mapping Behavior</h2>
 *
 * <h3>Automatic Name Matching</h3>
 * <p>When {@link #from()} is not specified (empty string), the system assumes the DTO
 * field name exactly matches the entity field name:</p>
 * <pre>{@code
 * @Entity
 * public class User {
 *     private String email;
 *     private String phoneNumber;
 * }
 *
 * @Projection(entity = User.class)
 * public class UserDTO {
 *     @Projected  // Maps to User.email (same name)
 *     private String email;
 *
 *     @Projected  // Maps to User.phoneNumber (same name)
 *     private String phoneNumber;
 * }
 * }</pre>
 *
 * <h3>Explicit Field Mapping</h3>
 * <p>When {@link #from()} is specified, the DTO field is mapped from the explicitly
 * named entity field, enabling field renaming:</p>
 * <pre>{@code
 * @Projection(entity = User.class)
 * public class UserDTO {
 *     @Projected(from = "email")      // DTO field has different name
 *     private String emailAddress;
 *
 *     @Projected(from = "createdAt")  // DTO field has different name
 *     private LocalDateTime registrationDate;
 * }
 * }</pre>
 *
 * <h3>Nested Field Access</h3>
 * <p>The {@link #from()} path supports dot notation for accessing nested entity relationships:</p>
 * <pre>{@code
 * @Entity
 * public class User {
 *     @ManyToOne
 *     private Address address;
 * }
 *
 * @Entity
 * public class Address {
 *     private String city;
 *     private String country;
 * }
 *
 * @Projection(entity = User.class)
 * public class UserDTO {
 *     @Projected(from = "address.city")
 *     private String city;
 *
 *     @Projected(from = "address.country")
 *     private String country;
 * }
 * }</pre>
 *
 * <h2>Comparison with @Computed</h2>
 * <table border="1">
 *   <tr>
 *     <th>Aspect</th>
 *     <th>@Projected</th>
 *     <th>@Computed</th>
 *   </tr>
 *   <tr>
 *     <td>Purpose</td>
 *     <td>Direct entity-to-DTO field mapping</td>
 *     <td>Derived value through computation</td>
 *   </tr>
 *   <tr>
 *     <td>Processing</td>
 *     <td>Simple field copy/type conversion</td>
 *     <td>Invokes computer method with dependencies</td>
 *   </tr>
 *   <tr>
 *     <td>Dependencies</td>
 *     <td>Single entity field (or path)</td>
 *     <td>Multiple entity fields as inputs</td>
 *   </tr>
 *   <tr>
 *     <td>Use Case</td>
 *     <td>Exposing entity data as-is or renamed</td>
 *     <td>Formatting, concatenation, calculations</td>
 *   </tr>
 * </table>
 *
 * <h2>Type Safety</h2>
 * <p>The annotation processor validates that:</p>
 * <ul>
 *   <li>The entity field specified in {@link #from()} exists</li>
 *   <li>The entity field type is compatible with the DTO field type</li>
 *   <li>For nested paths, each segment in the path is a valid field or relationship</li>
 * </ul>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Selective Field Exposure</h3>
 * <pre>{@code
 * @Entity
 * public class User {
 *     private Long id;
 *     private String email;
 *     private String passwordHash;  // Sensitive field
 * }
 *
 * @Projection(entity = User.class)
 * public class UserDTO {
 *     @Projected
 *     private Long id;
 *
 *     @Projected
 *     private String email;
 *
 *     // passwordHash is intentionally NOT projected
 * }
 * }</pre>
 *
 * <h3>API Contract Alignment</h3>
 * <pre>{@code
 * @Projection(entity = Product.class)
 * public class ProductDTO {
 *     @Projected(from = "sku")
 *     private String productCode;  // API uses different terminology
 *
 *     @Projected(from = "name")
 *     private String displayName;
 * }
 * }</pre>
 *
 * @since 1.0.0
 * @author Frank KOSSI
 * @see Projection
 * @see Computed
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface Projected {

    /**
     * The path to the source field in the JPA entity.
     *
     * <p>This path can be:</p>
     * <ul>
     *   <li><b>Simple field name:</b> {@code "email"} maps to {@code entity.getEmail()}</li>
     *   <li><b>Nested path:</b> {@code "address.city"} maps to {@code entity.getAddress().getCity()}</li>
     *   <li><b>Empty string (default):</b> Uses the DTO field name as the entity field name</li>
     * </ul>
     *
     * <h3>Path Resolution</h3>
     * <p>Paths are resolved left-to-right, validating each segment:</p>
     * <pre>{@code
     * @Projected(from = "department.manager.email")
     * private String managerEmail;
     *
     * // Resolves as:
     * // 1. entity.getDepartment()        → Department
     * // 2. department.getManager()       → Employee
     * // 3. employee.getEmail()           → String
     * }</pre>
     *
     * <h3>Null Safety Considerations</h3>
     * <p>For nested paths, intermediate values may be null. The generated projection code
     * should handle null navigation appropriately (implementation-dependent).</p>
     *
     * <h3>When to Use vs. Default Behavior</h3>
     * <pre>{@code
     * // Use default (empty string) when names match
     * @Projected
     * private String email;  // Assumes entity also has 'email' field
     *
     * // Specify 'from' when:
     * // 1. Renaming for clarity
     * @Projected(from = "createdAt")
     * private LocalDateTime registeredOn;
     *
     * // 2. Navigating relationships
     * @Projected(from = "profile.bio")
     * private String biography;
     *
     * // 3. Avoiding naming conflicts
     * @Projected(from = "internalCode")
     * private String code;  // 'code' might mean something else in DTO context
     * }</pre>
     *
     * @return the entity field path, or empty string to use the DTO field name
     */
    String from() default "";
}