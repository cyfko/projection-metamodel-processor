package io.github.cyfko.jpametamodel.processor;

import io.github.cyfko.jpametamodel.providers.ProjectionRegistryProvider;
import io.github.cyfko.projection.Computed;
import io.github.cyfko.projection.ExposedAs;
import io.github.cyfko.projection.Exposure;
import io.github.cyfko.projection.Projected;
import io.github.cyfko.projection.Projection;
import io.github.cyfko.jpametamodel.api.CollectionKind;
import io.github.cyfko.jpametamodel.api.CollectionType;
import io.github.cyfko.jpametamodel.api.ComputedField;
import io.github.cyfko.jpametamodel.api.DirectMapping;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.github.cyfko.jpametamodel.processor.AnnotationProcessorUtils.BASIC_JPA_TYPES;
import static io.github.cyfko.jpametamodel.processor.StringUtils.hasJavaNamingConvention;
import static io.github.cyfko.jpametamodel.processor.StringUtils.toJavaNamingAwareFieldName;

/**
 * Processor for @Projection annotated DTOs that generates projection metadata.
 * NOT a standalone annotation processor - used by MetamodelProcessor.
 */
public class ProjectionProcessor {
    private final ProcessingEnvironment processingEnv;
    private final Elements elementUtils;
    private final Types typeUtils;

    private final EntityProcessor entityProcessor;
    private final Map<String, SimpleProjectionMetadata> projectionRegistry = new LinkedHashMap<>();
    private final List<TypeElement> referencedProjections = new ArrayList<>();


    public ProjectionProcessor(ProcessingEnvironment processingEnv, EntityProcessor entityProcessor) {
        this.processingEnv = processingEnv;
        this.elementUtils = processingEnv.getElementUtils();
        this.entityProcessor = entityProcessor;
        this.typeUtils = processingEnv.getTypeUtils();
    }

    /**
     * Processes all {@link Projection}
     * annotated classes discovered in the current round.
     * <p>
     * For each DTO:
     * </p>
     * <ul>
     * <li>Resolves the target entity type from the {@code @Projection}
     * annotation.</li>
     * <li>Validates that the entity has been registered by
     * {@link EntityProcessor}.</li>
     * <li>Collects direct field mappings annotated with {@code @Projected}.</li>
     * <li>Collects computed fields annotated with {@code @Computed} and validates
     * their computation providers.</li>
     * <li>Stores the resulting metadata in an internal registry keyed by DTO
     * type.</li>
     * </ul>
     *
     */
    public void processProjections() {
        Messager messager = processingEnv.getMessager();

        for (TypeElement dtoClass : referencedProjections) {
            try {
                processProjection(dtoClass, messager);
                messager.printNote("✅ Processed " + projectionRegistry.size() + " projections");
            } catch (Exception e) {
                messager.printError(e.getMessage(), dtoClass);
            }
        }
    }

    /**
     * Returns an unmodifiable view of the collected projection metadata registry.
     *
     * @return an unmodifiable map where keys are fully qualified DTO class names
     *         and values are {@link SimpleProjectionMetadata} instances
     */
    public Map<String, SimpleProjectionMetadata> getRegistry() {
        return Collections.unmodifiableMap(projectionRegistry);
    }

    /**
     * Generates the {@code ProjectionRegistryProviderImpl} class
     * implementing
     * {@code ProjectionRegistryProvider} and exposing all collected
     * projection metadata.
     * <p>
     * The generated class is written via the
     * {@link Filer} and contains
     * a static unmodifiable registry initialized at class-load time. It is safe to
     * invoke this
     * method only after all relevant {@code @Projection} DTOs have been processed.
     * </p>
     */
    public void generateProviderImpl() {
        Messager messager = processingEnv.getMessager();
        messager.printMessage(Diagnostic.Kind.NOTE,
                "🛠️ Generating ProjectionRegistryProvider implementation...");

        try {
            JavaFileObject file = processingEnv.getFiler()
                    .createSourceFile("io.github.cyfko.jpametamodel.providers.impl.ProjectionRegistryProviderImpl");

            try (Writer writer = file.openWriter()) {
                writeProjectionRegistry(writer);
            }

            messager.printMessage(Diagnostic.Kind.NOTE,
                    "✅ ProjectionRegistryProviderImpl generated with " + projectionRegistry.size() + " projections");

        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate ProjectionRegistryProviderImpl: " + e.getMessage());
        }

        Helper.generateServiceProviderInfo(
                processingEnv,
                ProjectionRegistryProvider.class,
                "io.github.cyfko.jpametamodel.providers.impl.ProjectionRegistryProviderImpl");
    }

    /**
     * Processes a single {@code @Projection}-annotated DTO class and records its
     * metadata.
     * <p>
     * This includes:
     * </p>
     * <ul>
     * <li>Resolving the targeted JPA entity.</li>
     * <li>Validating that each {@code @Projected} path exists in the
     * entity/embeddable graph.</li>
     * <li>Analyzing collection fields to determine collection kind and type.</li>
     * <li>Resolving {@code @Computed} fields and ensuring corresponding computation
     * provider
     * methods exist with compatible signatures.</li>
     * </ul>
     *
     * @param dtoClass the DTO type element annotated with {@code @Projection}
     * @param messager the messager used to report notes and errors
     */
    private void processProjection(TypeElement dtoClass, Messager messager) {
        // Get entity class from annotation
        TypeMirror entityTypeMirror = getEntityClass(dtoClass);
        if (entityTypeMirror == null) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Cannot determine entity class from @Projection", dtoClass);
            return;
        }

        TypeElement entityClass = (TypeElement) ((DeclaredType) entityTypeMirror).asElement();
        String entityClassName = entityClass.getQualifiedName().toString();

        messager.printMessage(Diagnostic.Kind.NOTE,
                "🔍 Processing projection: " + dtoClass.getSimpleName() + " → " + entityClass.getSimpleName());

        // Validate entity has metadata (using EntityRegistryProcessor)
        if (!entityProcessor.hasEntityMetadata(entityClassName)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    String.format(
                            "Entity %s has no metadata. Ensure it is annotated with @Entity or @Embeddable and is public.",
                            entityClassName),
                    dtoClass);
            return;
        }

        List<SimpleDirectMapping> directMappings = new ArrayList<>();
        List<SimpleComputedField> computedFields = new ArrayList<>();
        List<SimpleComputationProvider> computers = new ArrayList<>();

        // Process computation providers
        AnnotationProcessorUtils.processExplicitFields(dtoClass,
                Projection.class.getName(),
                params -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> computersList = (List<Map<String, Object>>) params.get("providers");
                    if (computersList == null)
                        return;

                    computersList.forEach(com -> computers.add(
                            new SimpleComputationProvider((String) com.get("value"), (String) com.get("bean"))));
                },
                null);

        // Process fields
        final String dtoFqcn = dtoClass.getQualifiedName().toString();
        for (Element enclosedElement : dtoClass.getEnclosedElements()) {
            if (enclosedElement.getKind() != ElementKind.METHOD)
                continue;
            Set<Modifier> modifiers = enclosedElement.getModifiers();
            if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE))
                continue; // Skip static and private element

            // Process direct mappings
            final ExecutableElement methodElement = (ExecutableElement) enclosedElement;
            AnnotationProcessorUtils.processExplicitFields(
                    methodElement,
                    Projected.class.getName(),
                    params -> {
                        // validate entity field path
                        String entityField = params.get("from").toString();
                        // Read new v3 attributes
                        String asValue = params.containsKey("as") ? params.get("as").toString() : "";
                        boolean cycleBreak = params.containsKey("cycleBreak") && Boolean.TRUE.equals(params.get("cycleBreak"));
                        insertDirectMapping(methodElement, entityClassName, entityField, asValue, cycleBreak, directMappings);
                    },
                    () -> {
                        // Automatically consider this method if it is not a @Computed field and start
                        // by getXxxx
                        // as Java Naming Convention.
                        String name = methodElement.getSimpleName().toString();
                        if (methodElement.getAnnotation(Computed.class) != null || !hasJavaNamingConvention(name)) {
                            return;
                        }
                        name = toJavaNamingAwareFieldName(methodElement);
                        insertDirectMapping(methodElement, entityClassName, name, "", false, directMappings);
                    });

            // Process computed fields
            AnnotationProcessorUtils.processExplicitFields(methodElement,
                    Computed.class.getName(),
                    params -> {
                        String dtoField = toJavaNamingAwareFieldName(methodElement);

                        @SuppressWarnings("unchecked")
                        List<String> rawDependsOn = (List<String>) params.get("dependsOn");

                        // Validate DTO field exists
                        if (rawDependsOn.isEmpty()) {
                            messager.printMessage(Diagnostic.Kind.ERROR, String.format(
                                    "@Computed method '%s' does not declare any dependency in %s",
                                    dtoClass.getSimpleName(), dtoClass));
                            return;
                        }

                        // Parse inline :REDUCER syntax (v3.0.0)
                        List<String> dependencies = new ArrayList<>();
                        List<int[]> inlineReducers = new ArrayList<>(); // [index, -1] placeholder
                        List<String> inlineReducerNames = new ArrayList<>();
                        for (int ri = 0; ri < rawDependsOn.size(); ri++) {
                            String raw = rawDependsOn.get(ri);
                            int colonIdx = raw.indexOf(':');
                            if (colonIdx > 0) {
                                String path = raw.substring(0, colonIdx);
                                String reducer = raw.substring(colonIdx + 1);
                                dependencies.add(path);
                                inlineReducers.add(new int[]{dependencies.size() - 1});
                                inlineReducerNames.add(reducer);
                            } else {
                                dependencies.add(raw);
                            }
                        }

                        // Validate all dependencies exist in entity
                        final Map<String, TypeMirror> depsToTypes = new HashMap<>();
                        for (String dependency : dependencies) {
                            String errorMessage = validateEntityFieldPath(entityClassName, dependency, type -> depsToTypes.put(dependency, type));
                            if (errorMessage != null) {
                                messager.printError(String.format("Computed field '%s': %s", dtoField, errorMessage), dtoClass);
                                return;
                            }
                        }

                        // Extraction de @Method (computedBy)
                        @SuppressWarnings("unchecked")
                        Map<String, Object> computedBy = (Map<String, Object>) params.get("computedBy");
                        String computedByClass = computedBy != null ? (String) computedBy.get("type") : null;
                        String computedByMethod = computedBy != null ? (String) computedBy.get("value") : null;

                        @SuppressWarnings("unchecked")
                        Map<String, Object> thenProp = (Map<String, Object>) params.get("then");
                        String thenClass = thenProp != null ? (String) thenProp.get("type") : null;
                        String thenMethod = thenProp != null ? (String) thenProp.get("value") : null;

                        // Build reducers from inline parsing
                        var reducerNames = inlineReducerNames.toArray(new String[0]);
                        int[] reducerIndices = new int[inlineReducers.size()];
                        for (int i = 0; i < inlineReducers.size(); i++) {
                            reducerIndices[i] = inlineReducers.get(i)[0];
                        }

                        // Validate reducers: each collection dependency MUST have a reducer
                        List<String> collectionDeps = findCollectionDependencies(entityClassName, dependencies);
                        if (!collectionDeps.isEmpty() && reducerNames.length != collectionDeps.size()) {
                            messager.printMessage(Diagnostic.Kind.ERROR,
                                    String.format("Computed field '%s': reducers count (%d) must match " +
                                            "collection dependency count (%d). Collection dependencies: %s",
                                            dtoField, reducerNames.length, collectionDeps.size(), collectionDeps),
                                    dtoClass);
                            return;
                        }

                        // Validate that compute method exist in any of provided computation providers
                        SimpleComputedField field = new SimpleComputedField(
                                dtoField,
                                dependencies.toArray(new String[0]),
                                reducerIndices,
                                reducerNames,
                                computedByClass,
                                computedByMethod,
                                thenClass,
                                thenMethod
                        );

                        try {
                            field = validateComputeMethodWithTransformation(field, methodElement, computers, depsToTypes);
                        } catch (Exception e) {
                            printComputationMethodError(dtoClass, methodElement, field, e.getMessage(), computers, depsToTypes);
                            return;
                        }

                        // Everything OK ! then record this compute field!
                        computedFields.add(field);
                        messager.printMessage(Diagnostic.Kind.NOTE,
                                "  🧮 " + dtoField + " ← [" + String.join(", ", dependencies) + "]" +
                                        (reducerNames.length > 0 ? " ⬇ [" + String.join(", ", reducerNames) + "]"
                                                : ""));
                    },
                    null);
        }

        // === Collect @ExposedAs criteria and composed criteria ===
        List<SimpleExposedCriterion> allCriteria = collectCriteria(dtoClass, "", "", new LinkedHashSet<>());

        // === Read @Exposure on the DTO ===
        final SimpleExposureMetadata[] exposureHolder = {null};
        AnnotationProcessorUtils.processExplicitFields(dtoClass,
                Exposure.class.getName(),
                expFields -> {
                    String expValue = expFields.containsKey("value") ? expFields.get("value").toString() : "";
                    String expNamespace = expFields.containsKey("namespace") ? expFields.get("namespace").toString() : "";
                    String expStrategy = expFields.containsKey("strategy") ? expFields.get("strategy").toString() : "WINDOWED";
                    exposureHolder[0] = new SimpleExposureMetadata(expValue, expNamespace, expStrategy);
                },
                null);

        // Store metadata
        SimpleProjectionMetadata metadata = new SimpleProjectionMetadata(
                entityClassName,
                directMappings,
                computedFields,
                computers.toArray(SimpleComputationProvider[]::new),
                allCriteria,
                exposureHolder[0]);

        projectionRegistry.put(dtoFqcn, metadata);
    }

    /**
     * Validates and records a direct mapping between a DTO method and an entity
     * field.
     *
     * @param dtoMethod       the DTO method element (getter)
     * @param entityClassName the fully qualified name of the entity
     * @param entityField     the path to the entity field (e.g. "address.city")
     * @param directMappings  the list to append the new mapping to
     */
    private void insertDirectMapping(ExecutableElement dtoMethod,
            String entityClassName,
            String entityField,
            String asValue,
            boolean cycleBreak,
            List<SimpleDirectMapping> directMappings) {
        Messager messager = this.processingEnv.getMessager();
        if (!dtoMethod.getParameters().isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR, "@Projected methods can not have parameters.", dtoMethod);
            return;
        }

        String errorMessage = validateEntityFieldPath(entityClassName, entityField, null);
        if (errorMessage != null) {
            messager.printMessage(Diagnostic.Kind.ERROR, errorMessage, dtoMethod);
            return;
        }

        TypeMirror dtoType = dtoMethod.getReturnType();
        String dtoName = toJavaNamingAwareFieldName(dtoMethod);
        boolean isCollection = isCollection(dtoType);
        TypeElement itemType = resolveRelatedType(dtoType, isCollection);

        // Determine logicalPrefix for composed criterion inheritance
        Optional<String> logicalPrefix = Optional.empty();
        String dtoFieldTypeFqcn;

        if (isCollection) {
            dtoFieldTypeFqcn = itemType.asType().toString();
        } else {
            dtoFieldTypeFqcn = AnnotationProcessorUtils.getTypeNameWithoutAnnotations(dtoType);
        }

        // Check if the return type is itself a @Projection type
        TypeElement returnTypeElement = elementUtils.getTypeElement(dtoFieldTypeFqcn);
        boolean isProjectionType = returnTypeElement != null &&
                AnnotationProcessorUtils.hasAnnotation(returnTypeElement, Projection.class.getName());

        if (isProjectionType) {
            if (asValue != null && !asValue.isBlank()) {
                logicalPrefix = Optional.of(asValue);
            } else {
                logicalPrefix = Optional.of(StringUtils.toScreamingSnakeCase(dtoMethod.getSimpleName().toString()));
            }
        }

        if (isCollection) {
            DirectMapping.CollectionMetadata collectionMetadata = analyzeCollection(dtoType, itemType);
            directMappings.add(new SimpleDirectMapping(
                    dtoName,
                    entityField,
                    dtoFieldTypeFqcn,
                    Optional.of(collectionMetadata),
                    logicalPrefix,
                    cycleBreak));
        } else {
            directMappings.add(new SimpleDirectMapping(
                    dtoName,
                    entityField,
                    dtoFieldTypeFqcn,
                    Optional.empty(),
                    logicalPrefix,
                    cycleBreak));
        }

        messager.printNote("  ✅ " + dtoName + " → " + entityField);
    }

    /**
     * Prints a detailed error message when a computation method for a computed
     * field
     * cannot be resolved or does not match the expected signature.
     * <p>
     * The message contains:
     * </p>
     * <ul>
     * <li>The high-level error description.</li>
     * <li>The DTO source type.</li>
     * <li>The expected method signature, including parameter names and types.</li>
     * <li>The list of available computation provider classes.</li>
     * </ul>
     *
     * @param dtoClass          the DTO type declaring the computed field
     * @param methodElement     method element representing the computed property
     * @param field             the computed field metadata
     * @param errMessage        the base error message describing the mismatch
     * @param computers         the list of configured computation providers
     * @param depsToTypes a mapping of dependency paths to their fully
     *                          qualified types
     */
    private void printComputationMethodError(
            TypeElement dtoClass,
            ExecutableElement methodElement,
            SimpleComputedField field,
            String errMessage,
            List<SimpleComputationProvider> computers,
            Map<String, TypeMirror> depsToTypes) {

        String expectedMethodSignature = String.format(
                "public %s %s(%s);",
                methodElement.getReturnType().toString(),
                field.computedByMethod != null ? field.computedByMethod : "to" + StringUtils.capitalize(field.dtoField()),
                String.join(", ", Arrays.stream(field.dependencies())
                        .map(d -> depsToTypes.get(d) + " " + getLastSegment(d, "\\."))
                        .toList()));

        if (field.computedByClass != null) {
            computers = List.of(new SimpleComputationProvider(field.computedByClass, null));
        }

        String computationProviders = computers.stream().map(SimpleComputationProvider::className)
                .collect(Collectors.joining(", "));
        String msg = String.format("%s \n- Source: %s \n- Providers: %s \n- Expected computing method: %s ",
                errMessage,
                dtoClass.getQualifiedName(),
                computers.isEmpty() ? "<error: undefined provider>" : computationProviders,
                expectedMethodSignature);

        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, dtoClass);
    }

    /**
     * Returns the last segment of a dotted or delimited string.
     *
     * @param content the full content string
     * @param delim   the regular expression delimiter used to split the content
     * @return the last segment after splitting, or the original content if no
     *         delimiter is found
     */
    private static String getLastSegment(String content, String delim) {
        String[] segments = content.split(delim);
        return segments[segments.length - 1];
    }

    /**
     * Result of method validation, containing both computedBy and optional then methods.
     */
    record ValidationResult(
            ExecutableElement computedByMethod,
            ExecutableElement thenMethod,  // null if no transformation
            String errorMessage
    ) {
        boolean isValid() {
            return errorMessage == null;
        }
    }

    /**
     * Validates that compute and transformation methods exist for the given computed field.
     * <p>
     * Validation covers two stages:
     * </p>
     * <ol>
     *   <li><b>computedBy method:</b>
     *     <ul>
     *       <li>Method name convention: {@code to[FieldName]} or explicit</li>
     *       <li>Parameter count matches dependencies</li>
     *       <li>Parameter types match dependency types</li>
     *       <li>Return type matches field type OR then parameter type (if then is specified)</li>
     *     </ul>
     *   </li>
     *   <li><b>then method (if specified):</b>
     *     <ul>
     *       <li>Method name must be explicit (no convention)</li>
     *       <li>Must be static (pure function)</li>
     *       <li>Must accept exactly one parameter (computedBy return type)</li>
     *       <li>Return type must match field type</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param field             the computed field descriptor
     * @param informativeMethod the @Computed annotated method
     * @param providers         the list of available computation providers
     * @param depsToTypes mapping from dependency paths to their types
     * @return ValidationResult containing resolved methods or error message
     */
    private SimpleComputedField validateComputeMethodWithTransformation(
            SimpleComputedField field,
            ExecutableElement informativeMethod,
            List<SimpleComputationProvider> providers,
            Map<String, TypeMirror> depsToTypes) {

        Elements elements = processingEnv.getElementUtils();
        Types types = processingEnv.getTypeUtils();

        TypeMirror fieldType = informativeMethod.getReturnType();

        // === STAGE 1: Resolve computedBy method ===

        final String computedByMethodName = (field.computedByMethod() != null && !field.computedByMethod().isBlank())
                ? field.computedByMethod()
                : "to" + StringUtils.capitalize(field.dtoField());

        List<SimpleComputationProvider> computedByProviders = providers;
        if (field.computedByClass() != null) {
            computedByProviders = List.of(new SimpleComputationProvider(field.computedByClass(), null));
        }

        ExecutableElement computedByMethod = null;
        String computedByProviderClass = null;

        for (SimpleComputationProvider provider : computedByProviders) {
            TypeElement providerElement = elements.getTypeElement(provider.className());
            if (providerElement == null) continue;

            for (Element enclosed : providerElement.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) continue;

                ExecutableElement method = (ExecutableElement) enclosed;
                if (!computedByMethodName.equals(method.getSimpleName().toString())) continue;

                // Verify parameter count
                List<? extends VariableElement> parameters = method.getParameters();
                if (parameters.size() != field.dependencies().length) {
                    throw new IllegalStateException(String.format("Method %s.%s has incompatible parameters count. Required: %s, Found: %s.",
                            provider.className,
                            computedByMethodName,
                            field.dependencies().length,
                            parameters.size())
                    );
                }

                // Verify parameter types WITH autoboxing/unboxing and numeric promotions support
                for (int i = 0; i < parameters.size(); i++) {
                    TypeMirror methodParamType = parameters.get(i).asType();
                    TypeMirror dependencyType = depsToTypes.get(field.dependencies()[i]);

                    if (!AnnotationProcessorUtils.areTypesCompatible(dependencyType, methodParamType, types)) {
                        throw new IllegalStateException( String.format(
                                "Method %s.%s has incompatible type on parameter[%d]. Required: %s, Found: %s.",
                                provider.className,
                                computedByMethodName,
                                i,
                                dependencyType,
                                methodParamType)
                        );
                    }
                }

                // Found matching computedBy method
                computedByMethod = method;
                computedByProviderClass = provider.className();
                break;
            }

            if (computedByMethod != null) break;
        }

        if (computedByMethod == null) {
            throw new IllegalStateException( String.format("No matching resolution of computing method '%s' found for @Computed field '%s'.",
                    computedByMethodName,
                    informativeMethod.getSimpleName()
            ));
        }

        // === STAGE 2: Resolve then method (if specified) ===

        if (field.thenMethod() == null || field.thenMethod().isBlank()) {
            // No transformation - validate computedBy return type matches field type
            TypeMirror computedByReturnType = computedByMethod.getReturnType();
            if (!types.isSameType(computedByReturnType, fieldType)) {
                throw new IllegalStateException( String.format(
                        "Method %s.%s has incompatible return type. Required: %s, Found: %s.",
                        computedByProviderClass,
                        computedByMethodName,
                        fieldType.toString(),
                        computedByReturnType.toString()
                ));
            }

            // Valid without transformation
            return new SimpleComputedField(field.dtoField,
                    field.dependencies,
                    field.reducerIndices,
                    field.reducerNames,
                    computedByProviderClass,
                    computedByMethodName,
                    null,
                    null
            );
        }

        // Then method is specified - must be explicit
        final String thenMethodName = field.thenMethod();

        List<SimpleComputationProvider> thenProviders = providers;
        if (field.thenClass() != null) {
            thenProviders = List.of(new SimpleComputationProvider(field.thenClass(), null));
        } else {
            thenProviders.addFirst(new SimpleComputationProvider(informativeMethod.getEnclosingElement().toString(), null));
        }

        ExecutableElement thenMethod = null;
        String thenProviderClass = null;
        TypeMirror computedByReturnType = computedByMethod.getReturnType();

        for (SimpleComputationProvider provider : thenProviders) {
            TypeElement providerElement = elements.getTypeElement(provider.className());
            if (providerElement == null) continue;

            for (Element enclosed : providerElement.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) continue;

                ExecutableElement method = (ExecutableElement) enclosed;
                if (!thenMethodName.equals(method.getSimpleName().toString())) continue;

                // === CRITICAL VALIDATIONS FOR TRANSFORMATION METHODS ===

                // 1. Must be static (pure function)
                if (!method.getModifiers().contains(Modifier.STATIC)) {
                    throw new IllegalStateException( String.format(
                            "Transformation method '%s.%s' must be static. " +
                                    "Transformation functions must be pure (no state, no side effects). " +
                                    "IoC-managed beans are not allowed for transformations.",
                            provider.className(),
                            thenMethodName
                    ));
                }

                // 2. Must accept exactly one parameter
                List<? extends VariableElement> parameters = method.getParameters();
                if (parameters.size() != 1) {
                    throw new IllegalStateException( String.format(
                            "Transformation method '%s.%s' must accept exactly one parameter " +
                                    "(the output of computedBy method). Found: %d parameters.",
                            provider.className(),
                            thenMethodName,
                            parameters.size()
                    ));
                }

                // 3. Parameter type must match computedBy return type
                TypeMirror thenParameterType = parameters.getFirst().asType();
                if (!types.isSameType(thenParameterType, computedByReturnType)) {
                    throw new IllegalStateException( String.format(
                            "Transformation method '%s.%s' parameter type incompatible. " +
                                    "Expected: %s (output of computedBy), Found: %s.",
                            provider.className(),
                            thenMethodName,
                            computedByReturnType.toString(),
                            thenParameterType.toString()
                    ));
                }

                // 4. Return type must match field type
                TypeMirror thenReturnType = method.getReturnType();
                if (!types.isSameType(thenReturnType, fieldType)) {
                    throw new IllegalStateException( String.format(
                            "Transformation method '%s.%s' return type incompatible. " +
                                    "Expected: %s (field type), Found: %s.",
                            provider.className(),
                            thenMethodName,
                            fieldType.toString(),
                            thenReturnType.toString()
                    ));
                }

                // All validations passed
                thenMethod = method;
                thenProviderClass = provider.className();
                break;
            }

            if (thenMethod != null) break;
        }

        if (thenMethod == null) {
            throw new IllegalStateException( String.format(
                    "No matching transformation method '%s' found for @Computed field '%s'. " +
                            "Transformation methods must be static and accept one parameter of type %s.",
                    thenMethodName,
                    informativeMethod.getSimpleName(),
                    computedByReturnType.toString()
            ));
        }

        // Both stages validated successfully
        return new SimpleComputedField(field.dtoField,
                field.dependencies,
                field.reducerIndices,
                field.reducerNames,
                computedByProviderClass,
                computedByMethodName,
                thenProviderClass,
                field.thenMethod
        );
    }

    /**
     * Determines whether the given type represents a
     * {@link Collection}-like type
     * (including subtypes such as {@link List} or {@link Set}).
     *
     * @param type the type to inspect
     * @return {@code true} if the type is assignable to
     *         {@code java.util.Collection}, {@code false} otherwise
     */
    private boolean isCollection(TypeMirror type) {
        Types types = processingEnv.getTypeUtils();
        TypeMirror collectionType = processingEnv.getElementUtils()
                .getTypeElement("java.util.Collection").asType();
        return types.isAssignable(types.erasure(type), types.erasure(collectionType));
    }

    /**
     * Resolves the element type for a collection-related field.
     * <p>
     * If the field is a parameterized collection, the first generic argument type
     * is returned.
     * If the field is treated as an element collection but has no type arguments,
     * the raw
     * declared type is returned. Otherwise, {@code null} is returned.
     * </p>
     *
     * @param field               the field element to analyze
     * @param isElementCollection whether the field is known to represent an element
     *                            collection
     * @return the fully qualified name of the element type, or {@code null} if it
     *         cannot be determined
     */
    private TypeElement resolveRelatedType(TypeMirror field, boolean isElementCollection) {
        if (field instanceof DeclaredType dt) {

            if (!dt.getTypeArguments().isEmpty()) {
                return elementUtils.getTypeElement( AnnotationProcessorUtils.getTypeNameWithoutAnnotations(dt.getTypeArguments().getFirst()) );
            } else if (isElementCollection) {
                return elementUtils.getTypeElement( AnnotationProcessorUtils.getTypeNameWithoutAnnotations(dt) );
            }
        }
        return null;
    }

    /**
     * Analyzes a collection-mapped DTO field and derives its {@link CollectionKind}
     * and {@link CollectionType}.
     *
     * @param field       the DTO field representing a collection
     * @param elementType the fully qualified name of the element type
     * @return collection metadata describing the kind (scalar, entity, embeddable)
     *         and collection type
     */
    private DirectMapping.CollectionMetadata analyzeCollection(TypeMirror field, TypeElement elementType) {
        CollectionKind kind = AnnotationProcessorUtils.determineCollectionKind(elementType);
        CollectionType collectionType = AnnotationProcessorUtils.determineCollectionType(field);

        return new DirectMapping.CollectionMetadata(kind, collectionType);
    }

    /**
     * Extracts the entity class type mirror from the {@link Projection} annotation
     * present on the given DTO type.
     *
     * @param dtoClass the DTO type annotated with {@code @Projection}
     * @return the type mirror of the targeted entity, or {@code null} if not
     *         specified or not resolvable
     */
    private TypeMirror getEntityClass(TypeElement dtoClass) {
        for (AnnotationMirror mirror : dtoClass.getAnnotationMirrors()) {
            if (!Projection.class.getName().equals(mirror.getAnnotationType().toString())) {
                continue;
            }

            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : mirror.getElementValues()
                    .entrySet()) {
                if (entry.getKey().getSimpleName().toString().equals("from")) {
                    try {
                        return (TypeMirror) entry.getValue().getValue();
                    } catch (ClassCastException e) {
                        this.processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Validates that a dotted entity field path (e.g. {@code "address.city"})
     * exists
     * and can be navigated using metadata provided by {@link EntityProcessor}.
     * <p>
     * This method walks through entity and embeddable metadata, segment by segment,
     * ensuring
     * that each intermediate segment is non-scalar and that the final segment is
     * present.
     * When {@code withFqcn} is provided and the path is valid, the fully qualified
     * type of
     * the last segment is passed to the consumer.
     * </p>
     *
     * <p>
     * <b>Validation rules:</b>
     * </p>
     * <ul>
     * <li>Root entity must exist in {@link EntityProcessor#getRegistry()}.</li>
     * <li>Each path segment must exist in the current entity's metadata.</li>
     * <li>Intermediate segments must reference non-scalar types (entities or
     * embeddables).</li>
     * <li>Final segment can be any valid field type.</li>
     * </ul>
     *
     * <h3>Examples</h3>
     * 
     * <pre>{@code
     * // Valid paths → returns null
     * String error = validateEntityFieldPath("com.example.User", "address.city", null);
     * assertNull(error);
     *
     * validateEntityFieldPath("com.example.User", "profile.details.name", fqcnConsumer);
     *
     * // Invalid paths → returns error message
     * assertNotNull(validateEntityFieldPath("com.example.User", "name.surname", null)); // name is scalar
     * assertNotNull(validateEntityFieldPath("com.example.User", "address.unknown", null)); // unknown field
     * assertNotNull(validateEntityFieldPath("UnknownEntity", "field", null)); // entity not found
     * }</pre>
     *
     * <h3>Typical usage patterns</h3>
     * 
     * <pre>{@code
     * // 1. Simple validation
     * String error = validateEntityFieldPath("com.example.User", "address.city", null);
     * if (error != null) {
     *     throw new IllegalArgumentException(error);
     * }
     *
     * // 2. Validation + type resolution for dynamic type conversion
     * validateEntityFieldPath("com.example.User", "profile.details.value", typeFqcn -> {
     *     Class<?> targetType = loadClass(typeFqcn);
     *     configureTypeConverter(targetType);
     * });
     * }</pre>
     *
     * @param entityClassName the fully qualified name of the root entity (must
     *                        exist in registry)
     * @param fieldPath       the dotted path to validate (e.g.
     *                        {@code "address.city.name"})
     * @param withType        optional consumer to receive the type of the final
     *                        field. Called only if validation succeeds; can be
     *                        {@code null}
     * @return {@code null} if the path is valid, or an error message describing the
     *         validation failure
     *
     * @see #getSimpleName(String) for the simple class name used in error messages
     */
    public String validateEntityFieldPath(String entityClassName, String fieldPath, Consumer<TypeMirror> withType) {
        // Get entity metadata from EntityRegistryProcessor
        Map<String, Map<String, EntityProcessor.SimplePersistenceMetadata>> entityRegistry = entityProcessor
                .getRegistry();
        Map<String, Map<String, EntityProcessor.SimplePersistenceMetadata>> embeddableRegistry = entityProcessor
                .getEmbeddableRegistry();
        Map<String, EntityProcessor.SimplePersistenceMetadata> entityMetadata = entityRegistry.get(entityClassName);

        if (entityMetadata == null) {
            return "Entity " + entityClassName + " not found in registry";
        }

        // Handle nested paths (e.g., "address.city")
        String[] segments = fieldPath.split("\\.");
        String currentClassName = entityClassName;

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];

            // Get metadata for current class
            Map<String, EntityProcessor.SimplePersistenceMetadata> currentMetadata = entityRegistry.get(currentClassName);
            if (currentMetadata == null) {
                currentMetadata = embeddableRegistry.get(currentClassName);
            }

            if (currentMetadata == null) {
                return String.format("Field '%s' not found in %s (path: %s)", segment, currentClassName, fieldPath);
            }

            // Check if field exists in metadata
            EntityProcessor.SimplePersistenceMetadata fieldMetadata = currentMetadata.get(segment);
            if (fieldMetadata == null) {
                return String.format("Field '%s' not found in entity %s (path: %s)", segment, getSimpleName(currentClassName), fieldPath);
            }

            // If not the last segment, navigate to related type
            TypeMirror type = fieldMetadata.relatedType() != null ?
                    fieldMetadata.relatedType().asType() :
                    AnnotationProcessorUtils.getPrimitiveType(fieldMetadata.relatedTypeFqcn(), typeUtils);

            if (i < segments.length - 1) {
                if (BASIC_JPA_TYPES.contains(fieldMetadata.relatedTypeFqcn())) {
                    return String.format("Cannot navigate through scalar field '%s' in %s", segment, getSimpleName(currentClassName));
                }

                currentClassName = fieldMetadata.relatedTypeFqcn();
            } else if (withType != null) {
                // For collections, return the full parameterized type (e.g., java.util.List<User>)
                if (fieldMetadata.isCollection() && fieldMetadata.collection().isPresent()) {
                    TypeMirror fullType = buildCollectionTypeName(fieldMetadata.collection().get().collectionType(), type);
                    withType.accept(fullType);
                } else {
                    withType.accept(type);
                }
            }
        }

        return null;
    }

    /**
     * Finds all dependencies that traverse a collection in their path.
     * <p>
     * A dependency like {@code "orders.total"} is a collection dependency if
     * {@code orders} is a {@code @OneToMany} or {@code @ManyToMany} relationship.
     * A dependency like {@code "address.city"} is NOT a collection if
     * {@code address}
     * is an {@code @Embedded} or {@code @ManyToOne}.
     * </p>
     *
     * @param entityClassName the root entity class name
     * @param dependencies    the list of dependency paths to check
     * @return list of dependency paths that traverse at least one collection
     */
    private List<String> findCollectionDependencies(String entityClassName, List<String> dependencies) {
        List<String> collectionDeps = new ArrayList<>();
        for (String dependency : dependencies) {
            if (isCollectionPath(entityClassName, dependency)) {
                collectionDeps.add(dependency);
            }
        }
        return collectionDeps;
    }

    /**
     * Checks if a path traverses a collection at any segment.
     *
     * @param entityClassName the root entity class name
     * @param path            the dependency path (e.g., "orders.total")
     * @return true if any intermediate segment is a collection
     */
    private boolean isCollectionPath(String entityClassName, String path) {
        if (!path.contains(".")) {
            return false;
        }

        Map<String, Map<String, EntityProcessor.SimplePersistenceMetadata>> entityRegistry = entityProcessor
                .getRegistry();
        Map<String, Map<String, EntityProcessor.SimplePersistenceMetadata>> embeddableRegistry = entityProcessor
                .getEmbeddableRegistry();

        String[] segments = path.split("\\.");
        String currentClassName = entityClassName;

        // Check all segments except the last one
        for (int i = 0; i < segments.length - 1; i++) {
            Map<String, EntityProcessor.SimplePersistenceMetadata> currentMetadata = entityRegistry
                    .get(currentClassName);
            if (currentMetadata == null) {
                currentMetadata = embeddableRegistry.get(currentClassName);
            }
            if (currentMetadata == null) {
                return false;
            }

            EntityProcessor.SimplePersistenceMetadata fieldMetadata = currentMetadata.get(segments[i]);
            if (fieldMetadata == null) {
                return false;
            }

            if (fieldMetadata.isCollection()) {
                return true; // Found a collection in the path
            }

            currentClassName = fieldMetadata.relatedType().asType().toString();
        }
        return false;
    }

    /**
     * Writes the body of the generated
     * {@code ProjectionRegistryProviderImpl} class
     * to the given writer.
     * <p>
     * The generated code initializes a static registry of
     * {@code ProjectionMetadata} entries,
     * one per processed DTO, and implements the provider interface by returning an
     * unmodifiable
     * view of that registry.
     * </p>
     *
     * @param writer the writer used to output Java source code
     * @throws IOException if an error occurs while writing to the underlying stream
     */
    private void writeProjectionRegistry(Writer writer) throws IOException {
        writer.write("package io.github.cyfko.jpametamodel.providers.impl;\n\n");
        writer.write("import io.github.cyfko.jpametamodel.providers.ProjectionRegistryProvider;\n");
        writer.write("import io.github.cyfko.jpametamodel.api.*;\n");
        writer.write("import java.util.Map;\n");
        writer.write("import java.util.HashMap;\n");
        writer.write("import java.util.List;\n");
        writer.write("import java.util.Collections;\n");
        writer.write("import java.util.Optional;\n\n");

        writer.write("/**\n");
        writer.write(" * Generated projection metadata provider implementation.\n");
        writer.write(" * DO NOT EDIT - This file is automatically generated.\n");
        writer.write(" */\n");
        writer.write(
                "public class ProjectionRegistryProviderImpl implements ProjectionRegistryProvider {\n\n");

        writer.write("    private static final Map<Class<?>, ProjectionMetadata> REGISTRY;\n\n");

        writer.write("    static {\n");
        writer.write("        Map<Class<?>, ProjectionMetadata> registry = new HashMap<>();\n\n");

        for (var entry : projectionRegistry.entrySet()) {
            writeProjectionEntry(writer, entry);
        }

        writer.write("        REGISTRY = Collections.unmodifiableMap(registry);\n");
        writer.write("    }\n\n");

        writer.write("    @Override\n");
        writer.write("    public Map<Class<?>, ProjectionMetadata> getProjectionMetadataRegistry() {\n");
        writer.write("        return REGISTRY;\n");
        writer.write("    }\n");
        writer.write("}\n");
    }

    /**
     * Writes a single projection entry into the generated registry initialization
     * block.
     *
     * @param writer the writer used to output Java source code
     * @param entry  the metadata entry keyed by the DTO fully qualified name
     * @throws IOException if an error occurs while writing to the underlying stream
     */
    private void writeProjectionEntry(Writer writer, Map.Entry<String, SimpleProjectionMetadata> entry)
            throws IOException {
        String dtoType = entry.getKey();
        SimpleProjectionMetadata metadata = entry.getValue();

        StringBuilder sb = new StringBuilder();
        sb.append("        // ").append(dtoType).append(" → ").append(metadata.entityClass()).append("\n");
        sb.append("        registry.put(\n");
        sb.append("            ").append(dtoType).append(".class,\n");
        sb.append("            new ProjectionMetadata(\n");
        sb.append("                ").append(metadata.entityClass()).append(".class,\n");

        // Direct mappings
        sb.append("                new DirectMapping[]{");
        if (!metadata.directMappings().isEmpty()) {
            sb.append("\n");
            for (int i = 0; i < metadata.directMappings().size(); i++) {
                sb.append(formatDirectMapping(metadata.directMappings().get(i)));
                sb.append(i < metadata.directMappings().size() - 1 ? ",\n" : "\n");
            }
            sb.append("                ");
        }
        sb.append("},\n");

        // Computed fields
        sb.append("                new ComputedField[]{");
        if (!metadata.computedFields().isEmpty()) {
            sb.append("\n");
            for (int i = 0; i < metadata.computedFields().size(); i++) {
                sb.append(formatComputedField(metadata.computedFields().get(i), dtoType));
                sb.append(i < metadata.computedFields().size() - 1 ? ",\n" : "\n");
            }
            sb.append("                ");
        }
        sb.append("},\n");

        // Computers providers
        sb.append("                new ComputationProvider[]{\n");
        for (int i = 0; i < metadata.computers().length; i++) {
            sb.append(formatComputerProvider(metadata.computers()[i]));
            sb.append(i < metadata.computers().length - 1 ? ",\n" : "\n");
        }
        sb.append("                },\n");

        // ExposedCriterion[]
        sb.append("                new ExposedCriterion[]{");
        if (!metadata.exposedCriteria().isEmpty()) {
            sb.append("\n");
            for (int i = 0; i < metadata.exposedCriteria().size(); i++) {
                sb.append(formatExposedCriterion(metadata.exposedCriteria().get(i)));
                sb.append(i < metadata.exposedCriteria().size() - 1 ? ",\n" : "\n");
            }
            sb.append("                ");
        }
        sb.append("},\n");

        // ExposureMetadata (nullable)
        if (metadata.exposure() != null) {
            sb.append(formatExposureMetadata(metadata.exposure())).append("\n");
        } else {
            sb.append("                null\n");
        }

        sb.append("            )\n");
        sb.append("        );\n");

        writer.write(sb.toString());
    }

    /**
     * Formats a {@link SimpleDirectMapping} instance as a Java code snippet that
     * constructs
     * a corresponding {@link DirectMapping} in the generated registry.
     *
     * @param m the simple direct mapping metadata
     * @return a Java expression string constructing a {@code DirectMapping}
     */
    private String formatDirectMapping(SimpleDirectMapping m) {
        String collection = m.collection()
                .map(c -> "Optional.of(" + c.asInstance() + ")")
                .orElse("Optional.empty()");
        String logicalPrefix = m.logicalPrefix()
                .map(p -> "Optional.of(\"" + p + "\")")
                .orElse("Optional.empty()");
        return String.format(
                "                    new DirectMapping(\"%s\", \"%s\", %s.class, %s, %s, %s)",
                m.dtoField(), m.entityField(), m.dtoFieldType(), collection, logicalPrefix, m.cycleBreak());
    }

    /**
     * Formats an ExposedCriterion for code generation.
     */
    private String formatExposedCriterion(SimpleExposedCriterion c) {
        String operators = Arrays.stream(c.operators())
                .map(o -> "\"" + o + "\"")
                .collect(Collectors.joining(", "));
        return String.format(
                "                    new ExposedCriterion(\"%s\", \"%s\", new String[]{%s}, %s, %s)",
                c.ref(), c.sourcePath(), operators, c.exposed(), c.composed());
    }

    /**
     * Formats an ExposureMetadata for code generation.
     */
    private String formatExposureMetadata(SimpleExposureMetadata e) {
        return String.format(
                "                new ExposureMetadata(\"%s\", \"%s\", \"%s\")",
                e.value(), e.namespace(), e.strategy());
    }

    /**
     * Formats a {@link ComputedField} instance as a Java expression suitable for
     * inclusion
     * in the generated registry source code.
     *
     * @param f the computed field descriptor
     * @return a Java expression string constructing a {@code ComputedField}
     */
    private String formatComputedField(SimpleComputedField f, String dtoFqcn) {
        String deps = Arrays.stream(f.dependencies())
                .map(d -> "\"" + d + "\"")
                .collect(Collectors.joining(", "));

        // Build ReducerMapping array: new ComputedField.ReducerMapping(index,
        // "REDUCER")
        StringBuilder reducerMappings = new StringBuilder();
        for (int i = 0; i < f.reducerIndices().length; i++) {
            if (i > 0)
                reducerMappings.append(", ");
            reducerMappings.append(String.format("new ComputedField.ReducerMapping(%d, \"%s\")",
                    f.reducerIndices()[i], f.reducerNames()[i]));
        }

        String computeRef = String.format("new ComputedField.MethodReference(%s.class,\"%s\")",
                f.computedByClass() == null ? dtoFqcn : f.computedByClass,
                f.computedByMethod() == null ? "to" + StringUtils.capitalize(f.dtoField) : f.computedByMethod()
        );

        String thenRef = f.thenMethod() != null ? String.format("new ComputedField.MethodReference(%s.class,\"%s\")",
                f.thenClass() == null ? dtoFqcn : f.thenClass,
                f.thenMethod()) : null;


        return String.format("""
                                            new ComputedField(
                                                "%s",
                                                new String[]{%s},
                                                new ComputedField.ReducerMapping[]{%s},
                                                %s,
                                                %s
                                            )
                        """,
                f.dtoField(),
                deps,
                reducerMappings,
                computeRef,
                thenRef
        );
    }

    /**
     * Formats a {@link SimpleComputationProvider} instance as a Java expression
     * constructing
     * a {@code ComputationProvider} in the generated registry.
     *
     * @param c the computation provider metadata
     * @return a Java expression string constructing a {@code ComputationProvider}
     */
    private String formatComputerProvider(SimpleComputationProvider c) {
        return String.format(
                "                    new ComputationProvider(%s.class, \"%s\")",
                c.className(), c.bean());
    }

    /**
     * Returns the simple class name extracted from a fully qualified class name.
     *
     * @param fqcn the fully qualified class name
     * @return the simple name (segment after the last dot) or the original string
     *         if no dot is present
     */
    private String getSimpleName(String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
    }

    /**
     * Builds the fully qualified parameterized collection type name from the
     * collection type enum and element type.
     * <p>
     * For example, given {@code CollectionType.LIST} and {@code "io.example.User"},
     * returns {@code "java.util.List<io.example.User>"}.
     * </p>
     *
     * @param collectionType the type of collection (LIST, SET, MAP, COLLECTION)
     * @param item the element type within collection
     * @return the full parameterized type string
     */
    private TypeMirror buildCollectionTypeName(CollectionType collectionType, TypeMirror item) {
        String containerClass = switch (collectionType) {
            case LIST -> "java.util.List";
            case SET -> "java.util.Set";
            case MAP -> "java.util.Map";
            case COLLECTION -> "java.util.Collection";
            case ARRAY -> null;
            case UNKNOWN -> "java.util.Collection";
        };

        if (containerClass == null) { // is an array
            return typeUtils.getArrayType(item);
        }

        TypeElement container = elementUtils.getTypeElement(containerClass);
        return typeUtils.getDeclaredType(container, item);
    }

    public void setReferencedProjection(Set<TypeElement> projectionDtos) {
        this.referencedProjections.addAll(projectionDtos);
    }

    /**
     * Lightweight value object describing a direct mapping between a DTO field and
     * an entity field, including optional collection metadata.
     *
     * @param dtoField     the DTO field name
     * @param entityField  the entity field path
     * @param dtoFieldType the DTO field type as a fully qualified name
     * @param collection   optional collection metadata if the field represents a
     *                     collection
     */
    public record SimpleDirectMapping(String dtoField,
            String entityField,
            String dtoFieldType,
            Optional<DirectMapping.CollectionMetadata> collection,
            Optional<String> logicalPrefix,
            boolean cycleBreak) {
    }

    record SimpleComputedField(
            String dtoField,
            String[] dependencies,
            int[] reducerIndices,
            String[] reducerNames,
            String computedByClass,
            String computedByMethod,
            String thenClass,
            String thenMethod
    ) { }

    public record SimpleProjectionMetadata(String entityClass,
            List<SimpleDirectMapping> directMappings,
            List<SimpleComputedField> computedFields,
            SimpleComputationProvider[] computers,
            List<SimpleExposedCriterion> exposedCriteria,
            SimpleExposureMetadata exposure) {
    }

    public record SimpleComputationProvider(String className, String bean) {
    }

    record SimpleExposedCriterion(
            String ref,
            String sourcePath,
            String[] operators,
            boolean exposed,
            boolean composed
    ) { }

    record SimpleExposureMetadata(
            String value,
            String namespace,
            String strategy
    ) { }

    // ========================= COMPOSED CRITERION INHERITANCE =========================

    /**
     * Recursively collects all ExposedCriterion for a @Projection type.
     * <p>
     * Direct criteria come from @ExposedAs on scalar fields.
     * Composed criteria are inherited from @Projection-typed fields (via @Projected(as)).
     * </p>
     *
     * @param projectionType  the DTO type to collect criteria from
     * @param parentPrefix    accumulated prefix (empty for root)
     * @param parentEntityPath accumulated entity path (empty for root)
     * @param visited         set of visited type FQCNs for cycle detection
     * @return list of all criteria (direct + composed)
     */
    private List<SimpleExposedCriterion> collectCriteria(
            TypeElement projectionType,
            String parentPrefix,
            String parentEntityPath,
            Set<String> visited) {

        String typeFqcn = projectionType.getQualifiedName().toString();
        Messager messager = processingEnv.getMessager();

        if (visited.contains(typeFqcn)) {
            messager.printError(
                    String.format("Bidirectional projection cycle without cycleBreak: %s. " +
                                    "Fix: Annotate the field with @Projected(cycleBreak = true).",
                            String.join(" → ", visited) + " → " + typeFqcn),
                    projectionType);
            return List.of();
        }

        visited.add(typeFqcn);
        List<SimpleExposedCriterion> result = new ArrayList<>();
        Map<String, String> usedPrefixes = new HashMap<>(); // prefix → methodName (conflict detection)

        for (Element el : projectionType.getEnclosedElements()) {
            if (el.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) el;
            Set<Modifier> mods = method.getModifiers();
            if (mods.contains(Modifier.STATIC) || mods.contains(Modifier.PRIVATE)) continue;

            TypeMirror returnType = method.getReturnType();
            String returnTypeFqcn = AnnotationProcessorUtils.getTypeNameWithoutAnnotations(returnType);
            TypeElement returnTypeElement = elementUtils.getTypeElement(returnTypeFqcn);
            boolean isProjectionReturnType = returnTypeElement != null &&
                    AnnotationProcessorUtils.hasAnnotation(returnTypeElement, Projection.class.getName());

            // Check for @ExposedAs on this method
            AnnotationProcessorUtils.processExplicitFields(method,
                    ExposedAs.class.getName(),
                    params -> {
                        // Forbidden: @ExposedAs on @Projection return type
                        if (isProjectionReturnType) {
                            messager.printError(
                                    String.format("@ExposedAs is not allowed on %s#%s(). " +
                                                    "Reason: %s is a @Projection type. " +
                                                    "Fix: Remove @ExposedAs. The queryable properties are inherited automatically.",
                                            projectionType.getSimpleName(), method.getSimpleName(), returnTypeFqcn),
                                    method);
                            return;
                        }

                        String refValue = params.containsKey("value") && !params.get("value").toString().isBlank()
                                ? params.get("value").toString()
                                : StringUtils.toScreamingSnakeCase(method.getSimpleName().toString());

                        // Validate SCREAMING_SNAKE_CASE format
                        if (!refValue.matches(StringUtils.SCREAMING_SNAKE_PATTERN)) {
                            messager.printError(
                                    String.format("Invalid @ExposedAs value \"%s\" on %s#%s(). " +
                                                    "Criterion names must follow strict SCREAMING_SNAKE_CASE format. " +
                                                    "Expected pattern: %s",
                                            refValue, projectionType.getSimpleName(), method.getSimpleName(),
                                            StringUtils.SCREAMING_SNAKE_PATTERN),
                                    method);
                            return;
                        }

                        @SuppressWarnings("unchecked")
                        List<String> ops = params.containsKey("operators")
                                ? (List<String>) params.get("operators")
                                : List.of();
                        boolean exposed = !params.containsKey("exposed") || Boolean.TRUE.equals(params.get("exposed"));

                        // Resolve entity path for this method
                        String entityPath = resolveEntityPathForMethod(method, projectionType);

                        String fullRef;
                        String fullPath;
                        boolean composed;
                        if (parentPrefix.isEmpty()) {
                            fullRef = refValue;
                            fullPath = entityPath;
                            composed = false;
                        } else {
                            fullRef = parentPrefix + "__" + refValue;
                            fullPath = parentEntityPath + "." + entityPath;
                            composed = true;
                        }

                        result.add(new SimpleExposedCriterion(
                                fullRef, fullPath, ops.toArray(new String[0]), exposed, composed));
                    },
                    null);

            // Check for composed criterion inheritance (return type is @Projection)
            if (isProjectionReturnType) {
                // Read @Projected attributes for this method
                final String[] asHolder = {""};
                final boolean[] cycleBreakHolder = {false};
                AnnotationProcessorUtils.processExplicitFields(method,
                        Projected.class.getName(),
                        params -> {
                            asHolder[0] = params.containsKey("as") ? params.get("as").toString() : "";
                            cycleBreakHolder[0] = params.containsKey("cycleBreak") && Boolean.TRUE.equals(params.get("cycleBreak"));
                        },
                        null);

                if (cycleBreakHolder[0]) {
                    continue; // Do not recurse
                }

                // Determine prefix
                String prefix;
                if (asHolder[0] != null && !asHolder[0].isBlank()) {
                    prefix = asHolder[0];
                } else {
                    prefix = StringUtils.toScreamingSnakeCase(method.getSimpleName().toString());
                }

                // Check prefix conflict
                if (usedPrefixes.containsKey(prefix)) {
                    messager.printError(
                            String.format("Duplicate composed criterion prefix \"%s\" in %s. " +
                                            "Defined by: %s(), %s(). " +
                                            "Fix: Provide distinct values for the 'as' attribute.",
                                    prefix, projectionType.getSimpleName(),
                                    usedPrefixes.get(prefix), method.getSimpleName()),
                            method);
                    continue;
                }
                usedPrefixes.put(prefix, method.getSimpleName().toString());

                // Resolve child entity path
                String childEntityPath = resolveEntityFieldForMethod(method, projectionType);

                String fullPrefix = parentPrefix.isEmpty() ? prefix : parentPrefix + "__" + prefix;
                String fullEntityPath = parentEntityPath.isEmpty() ? childEntityPath : parentEntityPath + "." + childEntityPath;

                // Recurse
                List<SimpleExposedCriterion> childCriteria = collectCriteria(
                        returnTypeElement, fullPrefix, fullEntityPath, visited);
                result.addAll(childCriteria);
            }
        }

        visited.remove(typeFqcn); // backtrack for DFS
        return result;
    }

    /**
     * Resolves the entity field path for a method, by checking @Projected(from) or deriving from method name.
     */
    private String resolveEntityPathForMethod(ExecutableElement method, TypeElement dtoClass) {
        final String[] entityPath = {null};
        AnnotationProcessorUtils.processExplicitFields(method,
                Projected.class.getName(),
                params -> entityPath[0] = params.get("from").toString(),
                null);
        if (entityPath[0] != null) {
            return entityPath[0];
        }
        // Fallback: derive from method name
        return toJavaNamingAwareFieldName(method);
    }

    /**
     * Resolves the entity field for a @Projected method (for composed criterion path building).
     */
    private String resolveEntityFieldForMethod(ExecutableElement method, TypeElement dtoClass) {
        return resolveEntityPathForMethod(method, dtoClass);
    }
}