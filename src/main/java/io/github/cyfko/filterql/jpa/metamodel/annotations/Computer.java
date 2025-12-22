package io.github.cyfko.filterql.jpa.metamodel.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a computer class responsible for calculating computed fields in a projection.
 * 
 * <p>This annotation is exclusively used within {@link Projection#computers()} to register
 * classes that contain computation methods. It supports both static utility methods and
 * instance methods from IoC-managed beans.</p>
 * 
 * <h2>Method Resolution Rules</h2>
 * <p>For a computed field to be resolved, the computer class must provide a method that:</p>
 * <ul>
 *   <li>Follows the naming convention: {@code get[FieldName](...)}
 *       <br><i>Example:</i> For field {@code fullName}, method must be named {@code getFullName}</li>
 *   <li>Accepts parameters matching the types of fields declared in {@link Computed#dependsOn()}
 *       <br><i>Order matters:</i> Parameters must appear in the same order as {@code dependsOn}</li>
 *   <li>Returns a type compatible with the computed field's type</li>
 *   <li>Is {@code static} if {@link #bean()} is not specified</li>
 *   <li>Is an instance method if {@link #bean()} is specified</li>
 * </ul>
 * 
 * <h2>Static vs Instance Methods</h2>
 * <table border="1">
 *   <tr>
 *     <th>Condition</th>
 *     <th>Required Method Type</th>
 *     <th>Resolution Mechanism</th>
 *   </tr>
 *   <tr>
 *     <td>{@code bean} is empty</td>
 *     <td>Static method</td>
 *     <td>Direct static invocation</td>
 *   </tr>
 *   <tr>
 *     <td>{@code bean} is specified</td>
 *     <td>Instance method</td>
 *     <td>Bean lookup from IoC container by name or type</td>
 *   </tr>
 * </table>
 * 
 * <h2>Usage Examples</h2>
 * 
 * <h3>Static Computer</h3>
 * <pre>{@code
 * @Projection(
 *     entity = User.class,
 *     computers = {@Computer(StringUtils.class)}
 * )
 * public class UserDTO {
 *     @Computed(dependsOn = {"firstName", "lastName"})
 *     private String fullName;
 * }
 * 
 * public class StringUtils {
 *     public static String getFullName(String firstName, String lastName) {
 *         return firstName + " " + lastName;
 *     }
 * }
 * }</pre>
 * 
 * <h3>Bean-Based Computer (Spring)</h3>
 * <pre>{@code
 * @Projection(
 *     entity = Order.class,
 *     computers = {@Computer(value = PriceCalculator.class, bean = "discountCalculator")}
 * )
 * public class OrderDTO {
 *     @Computed(dependsOn = {"basePrice", "discountPercent"})
 *     private BigDecimal finalPrice;
 * }
 * 
 * @Service("discountCalculator")
 * public class PriceCalculator {
 *     @Autowired
 *     private TaxService taxService; // Can inject external services!
 *     
 *     public BigDecimal getFinalPrice(BigDecimal basePrice, Integer discountPercent) {
 *         BigDecimal discounted = applyDiscount(basePrice, discountPercent);
 *         return taxService.applyTax(discounted);
 *     }
 * }
 * }</pre>
 * 
 * <h2>Compilation Errors</h2>
 * <p>The annotation processor will generate compilation errors in these scenarios:</p>
 * <ul>
 *   <li>If {@code bean} is specified but no instance method matches</li>
 *   <li>If {@code bean} is empty but no static method matches</li>
 *   <li>If {@code bean} is specified but no IoC framework is detected in the classpath</li>
 *   <li>If method parameters don't match {@link Computed#dependsOn()} field types</li>
 * </ul>
 * 
 * @since 1.0.0
 * @author Frank KOSSI
 * @see Projection
 * @see Computed
 */
@Retention(RetentionPolicy.SOURCE)
@Target({}) // Only usable within @Projection
public @interface Computer {
    /**
     * The class containing computation methods for computed fields.
     * 
     * <p>This class will be searched for methods matching the naming convention
     * {@code get[FieldName](...)} with parameters corresponding to the field's dependencies.</p>
     * 
     * <p><b>Important:</b> Methods must be static if {@link #bean()} is not specified,
     * or instance methods if {@link #bean()} is specified.</p>
     *
     * @return the computer class
     */
    Class<?> value();

    /**
     * The bean name for IoC container lookup (optional).
     * 
     * <p>When specified, the computer class will be resolved as a managed bean from the
     * IoC container (Spring, CDI, Quarkus, etc.). This enables:</p>
     * <ul>
     *   <li>Dependency injection into the computer class</li>
     *   <li>Access to external services during computation</li>
     *   <li>Lifecycle management by the IoC container</li>
     * </ul>
     * 
     * <p><b>Disambiguation:</b> If multiple beans of the same type exist, this name is used
     * to uniquely identify the correct bean.</p>
     * 
     * <p><b>Examples:</b></p>
     * <pre>{@code
     * // Lookup by type only
     * @Computer(DateFormatter.class)
     * 
     * // Lookup by name (when multiple DateFormatter beans exist)
     * @Computer(value = DateFormatter.class, bean = "isoDateFormatter")
     * }</pre>
     * 
     * <p><b>Default behavior:</b> When empty, the system expects static methods in the
     * computer class. No IoC lookup is performed.</p>
     *
     * @return the bean name for IoC lookup, or empty string for static method resolution
     */
    String bean() default "";
}