package io.github.cyfko.tests;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.github.cyfko.jpametamodel.processor.MetamodelProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class ExposureValidationTest {

    private JavaFileObject createCycleEntity() {
        return JavaFileObjects.forSourceString(
                "io.github.cyfko.example.CycleEntity",
                """
                        package io.github.cyfko.example;
                        import jakarta.persistence.*;

                        @Entity
                        public class CycleEntity {
                            @Id private Long id;
                            @ManyToOne private CycleEntity next;
                        }
                        """);
    }

    private JavaFileObject createLocalityEntity() {
        return JavaFileObjects.forSourceString(
                "io.github.cyfko.example.Locality",
                """
                        package io.github.cyfko.example;
                        import jakarta.persistence.*;

                        @Entity
                        public class Locality {
                            @Id
                            private Long id;
                            private String name;
                            private String regionCode;
                        }
                        """);
    }

    private JavaFileObject createSiteEntity() {
        return JavaFileObjects.forSourceString(
                "io.github.cyfko.example.Site",
                """
                        package io.github.cyfko.example;
                        import jakarta.persistence.*;

                        @Entity
                        public class Site {
                            @Id
                            private Long id;
                            private String name;
                            private String siteCode;
                            
                            @ManyToOne
                            private Locality locality;
                        }
                        """);
    }

    private JavaFileObject createConnectionEntity() {
        return JavaFileObjects.forSourceString(
                "io.github.cyfko.example.Connection",
                """
                        package io.github.cyfko.example;
                        import jakarta.persistence.*;

                        @Entity
                        public class Connection {
                            @Id
                            private Long id;
                            private String connectionId;
                            
                            @ManyToOne
                            private Site site;
                        }
                        """);
    }

    @Test
    void testExposedCriterionDirect_succeeds() throws IOException {
        JavaFileObject dto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.LocalityDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Locality.class)
                        public interface LocalityDTO {
                            @Projected(from = "id")
                            @ExposedAs(value = "LOCALITY_ID", operators = {"EQ", "IN"})
                            Long getId();

                            @Projected(from = "name")
                            @ExposedAs("LOCALITY_NAME")
                            String getName();
                            
                            @Projected(from = "regionCode")
                            @ExposedAs("REGION_CODE")
                            String getRegionCode(); // Should derive REGION_CODE
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(createLocalityEntity(), dto);

        assertThat(compilation).succeeded();

        JavaFileObject generatedFile = compilation.generatedSourceFile(
                "io.github.cyfko.jpametamodel.providers.impl.ProjectionRegistryProviderImpl").orElseThrow();
        String generatedCode = generatedFile.getCharContent(true).toString();

        assertThat(generatedCode).contains("new ExposedCriterion(\"LOCALITY_ID\", \"id\", new String[]{\"EQ\", \"IN\"}, true, false)");
        assertThat(generatedCode).contains("new ExposedCriterion(\"LOCALITY_NAME\", \"name\", new String[]{}, true, false)");
        assertThat(generatedCode).contains("new ExposedCriterion(\"REGION_CODE\", \"regionCode\", new String[]{}, true, false)");
    }

    @Test
    void testExposureMetadata_succeeds() throws IOException {
        JavaFileObject dto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.LocalityDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Locality.class)
                        @Exposure(value = "localities", namespace = "core", strategy = Exposure.Strategy.WINDOWED)
                        public interface LocalityDTO {
                            @Projected(from = "id")
                            Long getId();
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(createLocalityEntity(), dto);

        assertThat(compilation).succeeded();

        JavaFileObject generatedFile = compilation.generatedSourceFile(
                "io.github.cyfko.jpametamodel.providers.impl.ProjectionRegistryProviderImpl").orElseThrow();
        String generatedCode = generatedFile.getCharContent(true).toString();

        assertThat(generatedCode).contains("new ExposureMetadata(\"localities\", \"core\", \"WINDOWED\", new MethodReference[]{}, null)");
    }

    @Test
    void testExposedAsValidation_invalidFormat_fails() {
        JavaFileObject dto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.LocalityDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Locality.class)
                        public interface LocalityDTO {
                            @Projected(from = "id")
                            @ExposedAs("invalid_lowercase")
                            Long getId();
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(createLocalityEntity(), dto);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Invalid @ExposedAs value \"invalid_lowercase\" on LocalityDTO#getId()");
        assertThat(compilation).hadErrorContaining("Criterion names must follow strict SCREAMING_SNAKE_CASE format");
    }

    @Test
    void testExposedAsOnProjectionType_fails() {
        JavaFileObject localityDto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.LocalityDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Locality.class)
                        public interface LocalityDTO {
                            @Projected(from = "id")
                            Long getId();
                        }
                        """);
                        
        JavaFileObject siteDto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.SiteDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Site.class)
                        public interface SiteDTO {
                            @Projected(from = "locality")
                            @ExposedAs("LOCALITY_DATA") // NOT ALLOWED!
                            LocalityDTO getLocality();
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(createLocalityEntity(), createSiteEntity(), localityDto, siteDto);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@ExposedAs is not allowed on SiteDTO#getLocality()");
    }

    @Test
    void testExposedCriterionComposed_succeeds() throws IOException {
        JavaFileObject localityDto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.LocalityDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Locality.class)
                        public interface LocalityDTO {
                            @Projected(from = "id")
                            @ExposedAs("ID")
                            Long getId();
                        }
                        """);
                        
        JavaFileObject siteDto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.SiteDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Site.class)
                        public interface SiteDTO {
                            @Projected(from = "id")
                            @ExposedAs("ID")
                            Long getId();
                            
                            @Projected(from = "locality", as = "LOC")
                            LocalityDTO getLocality();
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(createLocalityEntity(), createSiteEntity(), localityDto, siteDto);

        assertThat(compilation).succeeded();
        
        JavaFileObject generatedFile = compilation.generatedSourceFile(
                "io.github.cyfko.jpametamodel.providers.impl.ProjectionRegistryProviderImpl").orElseThrow();
        String generatedCode = generatedFile.getCharContent(true).toString();

        // site.id
        assertThat(generatedCode).contains("new ExposedCriterion(\"ID\", \"id\", new String[]{}, true, false)");
        // site.locality.id (composed)
        assertThat(generatedCode).contains("new ExposedCriterion(\"LOC__ID\", \"locality.id\", new String[]{}, true, true)");
    }

    @Test
    void testExposureCycleDetection_fails() {
        JavaFileObject cycleA = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.CycleADTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = CycleEntity.class)
                        public interface CycleADTO {
                            @Projected(from = "next")
                            CycleBDTO getB();
                        }
                        """);
                        
        JavaFileObject cycleB = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.CycleBDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = CycleEntity.class)
                        public interface CycleBDTO {
                            @Projected(from = "next")
                            CycleADTO getA();
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(createCycleEntity(), cycleA, cycleB);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Bidirectional projection cycle without cycleBreak");
    }

    @Test
    void testExposureCycleBreak_succeeds() {
        JavaFileObject cycleA = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.CycleADTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = CycleEntity.class)
                        public interface CycleADTO {
                            @Projected(from = "next", cycleBreak = true)
                            CycleBDTO getB();
                        }
                        """);
                        
        JavaFileObject cycleB = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.CycleBDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = CycleEntity.class)
                        public interface CycleBDTO {
                            @Projected(from = "next")
                            CycleADTO getA();
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(createCycleEntity(), cycleA, cycleB);

        // Should succeed because of cycleBreak
        assertThat(compilation).succeeded();
    }

    @Test
    void testExposurePrefixConflict_fails() {
        JavaFileObject localityDto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.LocalityDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Locality.class)
                        public interface LocalityDTO {
                            @Projected(from = "id")
                            Long getId();
                        }
                        """);
                        
        JavaFileObject siteDto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.SiteDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Site.class)
                        public interface SiteDTO {
                            @Projected(from = "locality", as = "LOC")
                            LocalityDTO getPrimaryLocality();
                            
                            @Projected(from = "locality", as = "LOC") // Conflict!
                            LocalityDTO getSecondaryLocality();
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(createLocalityEntity(), createSiteEntity(), localityDto, siteDto);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Duplicate composed criterion prefix \"LOC\"");
    }
    @Test
    void testImplicitExposureAndMapping_succeeds() throws IOException {
        JavaFileObject localityDto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.LocalityDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Locality.class)
                        public interface LocalityDTO {
                            // Implicit mapping: "id"
                            // Implicit exposure: "ID" via fallback to getter name
                            @ExposedAs("ID")
                            Long getId();
                            
                            // Absolute implicit mapping from "regionCode"
                            // NO @ExposedAs, so IT WON'T BE EXPOSED
                            String getRegionCode();
                        }
                        """);
                        
        JavaFileObject siteDto = JavaFileObjects.forSourceString(
                "io.github.cyfko.example.SiteDTO",
                """
                        package io.github.cyfko.example;
                        import io.github.cyfko.projection.*;

                        @Projection(from = Site.class)
                        public interface SiteDTO {
                            // Implicit mapping: "id"
                            // Implicit exposure: "ID"
                            @ExposedAs("ID")
                            Long getId();
                            
                            // Implicit composition: "locality" mapped from "getLocality"
                            // Implicit prefix "LOCALITY" from toScreamingSnakeCase
                            LocalityDTO getLocality();
                        }
                        """);

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(createLocalityEntity(), createSiteEntity(), localityDto, siteDto);

        assertThat(compilation).succeeded();
        
        JavaFileObject generatedFile = compilation.generatedSourceFile(
                "io.github.cyfko.jpametamodel.providers.impl.ProjectionRegistryProviderImpl").orElseThrow();
        String generatedCode = generatedFile.getCharContent(true).toString();

        // Check direct mapping is correct without @Projected (it maps to "id" and "locality")
        assertThat(generatedCode).contains("new DirectMapping(\"id\", \"id\"");
        assertThat(generatedCode).contains("new DirectMapping(\"locality\", \"locality\"");
        
        // Assert criteria collected implicitly or correctly
        assertThat(generatedCode).contains("new ExposedCriterion(\"ID\", \"id\", new String[]{}, true, false)");
        assertThat(generatedCode).contains("new ExposedCriterion(\"LOCALITY__ID\", \"locality.id\", new String[]{}, true, true)");
        
        // "regionCode" should just be directly mapped, but absolutely NO criterion emitted
        assertThat(generatedCode).contains("new DirectMapping(\"regionCode\", \"regionCode\"");
        assertThat(generatedCode).doesNotContain("REGION_CODE");
    }
}
