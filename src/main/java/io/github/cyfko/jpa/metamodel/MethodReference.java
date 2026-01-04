package io.github.cyfko.jpa.metamodel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Reference to a method for a specific purpose.
 * 
 * <p>Both {@code type} and {@code method} are optional:</p>
 * <ul>
 *   <li>If both are omitted: looks for the conventional <strong>expected method name</strong> in the annotated class</li>
 *   <li>If only {@code method} is set: looks for that method in the annotated class</li>
 *   <li>If only {@code type} is set: looks for the conventional <strong>expected method name</strong> in that class</li>
 *   <li>If both are set: looks for the specified method in the specified class</li>
 * </ul>
 */
@Retention(RetentionPolicy.SOURCE)
public @interface MethodReference {
    
    /**
     * The class containing the template method.
     * If not specified (default), searches in the class of the current context.
     */
    Class<?> type() default void.class;
    
    /**
     * The name of the method of interest.
     * If not specified (default), uses the <strong>conventional name</strong>.
     * <p>
     * The method must meet the expected signature fixed by the external semantic.
     * </p>
     */
    String method() default "";
    
}