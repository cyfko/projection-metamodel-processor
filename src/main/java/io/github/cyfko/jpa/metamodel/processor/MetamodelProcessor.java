package io.github.cyfko.jpa.metamodel.processor;

import com.google.auto.service.AutoService;
import io.github.cyfko.jpa.metamodel.Projection;
import jakarta.persistence.Entity;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * Main annotation processor that orchestrates entity and projection processing.
 * This is the only processor registered via @AutoService.
 * 
 * <p>Processing happens in two phases:
 * <ol>
 *   <li><strong>Phase 1:</strong> Process @Entity annotations via {@link EntityProcessor}</li>
 *   <li><strong>Phase 2:</strong> Process @Projection annotations via {@link ProjectionProcessor}</li>
 * </ol>
 * </p>
 * 
 * <p>This ensures that entity metadata is available when validating projection mappings.</p>
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes({
    "jakarta.persistence.Entity",
    "io.github.cyfko.filterql.jpa.metamodel.Projection"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class MetamodelProcessor extends AbstractProcessor {

    private EntityProcessor entityProcessor;
    private ProjectionProcessor projectionProcessor;
    private boolean entitiesProcessed = false;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        
        // Initialize delegate processors
        this.entityProcessor = new EntityProcessor(processingEnv);
        this.projectionProcessor = new ProjectionProcessor(processingEnv, entityProcessor);
        
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
            "🚀 JPA Metamodel Processor initialized");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Messager messager = processingEnv.getMessager();

        // Phase 1: Process entities first (only once)
        if (!entitiesProcessed) {
            if (!roundEnv.getElementsAnnotatedWith(Entity.class).isEmpty()) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                    "═══════════════════════════════════════════════════");
                messager.printMessage(Diagnostic.Kind.NOTE,
                    "  Phase 1: Entity Processing");
                messager.printMessage(Diagnostic.Kind.NOTE,
                    "═══════════════════════════════════════════════════");
                
                entityProcessor.processEntities(roundEnv);
                entitiesProcessed = true;
            }
        }

        // Phase 2: Process projections (after entities are available)
        if (entitiesProcessed && !roundEnv.getElementsAnnotatedWith(Projection.class).isEmpty()) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "═══════════════════════════════════════════════════");
            messager.printMessage(Diagnostic.Kind.NOTE,
                "  Phase 2: Projection Processing");
            messager.printMessage(Diagnostic.Kind.NOTE,
                "═══════════════════════════════════════════════════");
            
            projectionProcessor.processProjections(roundEnv);
        }

        // Final round: Generate all implementations
        if (roundEnv.processingOver()) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "═══════════════════════════════════════════════════");
            messager.printMessage(Diagnostic.Kind.NOTE,
                "  Final Phase: Code Generation");
            messager.printMessage(Diagnostic.Kind.NOTE,
                "═══════════════════════════════════════════════════");

            // Generate entity registry
            if (!entityProcessor.getRegistry().isEmpty()) {
                entityProcessor.generateProviderImpl();
            }

            // Generate projection registry
            if (!projectionProcessor.getRegistry().isEmpty()) {
                projectionProcessor.generateProviderImpl();
            }

            messager.printMessage(Diagnostic.Kind.NOTE,
                "═══════════════════════════════════════════════════");
            messager.printMessage(Diagnostic.Kind.NOTE,
                "  ✅ Processing Complete");
            messager.printMessage(Diagnostic.Kind.NOTE,
                "     Entities: " + entityProcessor.getRegistry().size());
            messager.printMessage(Diagnostic.Kind.NOTE,
                "     Projections: " + projectionProcessor.getRegistry().size());
            messager.printMessage(Diagnostic.Kind.NOTE,
                "═══════════════════════════════════════════════════");
        }

        return true;
    }
}