package io.github.cyfko.jpa.metamodel.model.projection;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Computed field with explicit dependencies on entity fields.
 *
 * @param dtoField Name of the computed field in the DTO
 * @param dependencies Entity fields required to compute this field
 * @since 1.0.0
 */
public record ComputedField(String dtoField, String[] dependencies) {

    public ComputedField {
        Objects.requireNonNull(dtoField, "dtoField cannot be null");
        Objects.requireNonNull(dependencies, "dependencies cannot be null");

        if (dtoField.isBlank()) {
            throw new IllegalArgumentException("dtoField cannot be blank");
        }
        if (dependencies.length == 0) {
            throw new IllegalArgumentException("dependencies cannot be empty");
        }
    }

    /**
     * Checks if this computed field depends on a specific entity field.
     *
     * @param entityField the entity field path
     * @return true if dependent, false otherwise
     */
    public boolean dependsOn(String entityField) {
        return Arrays.asList(dependencies).contains(entityField);
    }

    /**
     * Gets the number of dependencies.
     *
     * @return dependency count
     */
    public int dependencyCount() {
        return dependencies.length;
    }

    /**
     * Checks if any dependency is a nested path.
     *
     * @return true if any dependency contains a dot
     */
    public boolean hasNestedDependencies() {
        return Arrays.stream(dependencies).anyMatch(d -> d.contains("."));
    }
}