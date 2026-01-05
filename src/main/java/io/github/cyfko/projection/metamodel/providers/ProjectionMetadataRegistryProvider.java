package io.github.cyfko.projection.metamodel.providers;

import io.github.cyfko.projection.metamodel.model.projection.ProjectionMetadata;
import java.util.Map;

/**
 * Provider interface for projection metadata registry.
 * Implementations are generated at compile-time by the annotation processor.
 */
public interface ProjectionMetadataRegistryProvider {
    /**
     * Returns the projection metadata registry.
     * @return immutable map of DTO class to ProjectionMetadata
     */
    Map<Class<?>, ProjectionMetadata> getProjectionMetadataRegistry();
}
