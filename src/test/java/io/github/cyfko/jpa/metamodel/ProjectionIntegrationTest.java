package io.github.cyfko.jpa.metamodel;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.github.cyfko.jpa.metamodel.processor.MetamodelProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies the generated projection metadata.
 * This test compiles entities and DTOs from test/resources/testdata/
 * and validates the generated projection registry.
 */
class ProjectionIntegrationTest {

    @Test
    void testUserDTOProjectionGeneration() throws IOException {
        JavaFileObject userEntity = JavaFileObjects.forResource("testdata/User.java");
        JavaFileObject addressEmbeddable = JavaFileObjects.forResource("testdata/Address.java");
        JavaFileObject departmentEntity = JavaFileObjects.forResource("testdata/Department.java");
        JavaFileObject orderEntity = JavaFileObjects.forResource("testdata/Order.java");
        JavaFileObject userDTO = JavaFileObjects.forResource("testdata/UserDTO.java");
        JavaFileObject orderDTO = JavaFileObjects.forResource("testdata/OrderDTO.java");
        JavaFileObject computationProvider = JavaFileObjects.forResource("testdata/TestComputationProvider.java");

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(userEntity, addressEmbeddable, departmentEntity, orderEntity, userDTO, orderDTO, computationProvider);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("io.github.cyfko.jpa.metamodel.providers.ProjectionMetadataRegistryProviderImpl");

        String generatedCode = getGeneratedProjectionCode(compilation);

        // Verify UserDTO metadata is present
        assertTrue(generatedCode.contains("UserDTO"));
//        assertTrue(generatedCode.contains("User.class"));
    }

    @Test
    void testUserDTODirectMappings() throws IOException {
        JavaFileObject userEntity = JavaFileObjects.forResource("testdata/User.java");
        JavaFileObject addressEmbeddable = JavaFileObjects.forResource("testdata/Address.java");
        JavaFileObject departmentEntity = JavaFileObjects.forResource("testdata/Department.java");
        JavaFileObject orderEntity = JavaFileObjects.forResource("testdata/Order.java");
        JavaFileObject userDTO = JavaFileObjects.forResource("testdata/UserDTO.java");
        JavaFileObject orderDTO = JavaFileObjects.forResource("testdata/OrderDTO.java");
        JavaFileObject computationProvider = JavaFileObjects.forResource("testdata/TestComputationProvider.java");

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(userEntity, addressEmbeddable, departmentEntity, orderEntity, userDTO, orderDTO, computationProvider);

        assertThat(compilation).succeeded();

        String generatedCode = getGeneratedProjectionCode(compilation);

        // Verify direct mappings
        assertTrue(generatedCode.contains("userEmail"));
        assertTrue(generatedCode.contains("\"email\""));
        assertTrue(generatedCode.contains("city"));
        assertTrue(generatedCode.contains("\"address.city\""));
        assertTrue(generatedCode.contains("departmentName"));
        assertTrue(generatedCode.contains("\"department.name\""));
        assertTrue(generatedCode.contains("new DirectMapping.CollectionMetadata(CollectionKind.UNKNOWN, CollectionType.LIST"));
    }

    @Test
    void testUserDTOComputedFields() throws IOException {
        JavaFileObject userEntity = JavaFileObjects.forResource("testdata/User.java");
        JavaFileObject addressEmbeddable = JavaFileObjects.forResource("testdata/Address.java");
        JavaFileObject departmentEntity = JavaFileObjects.forResource("testdata/Department.java");
        JavaFileObject orderEntity = JavaFileObjects.forResource("testdata/Order.java");
        JavaFileObject userDTO = JavaFileObjects.forResource("testdata/UserDTO.java");
        JavaFileObject orderDTO = JavaFileObjects.forResource("testdata/OrderDTO.java");
        JavaFileObject computationProvider = JavaFileObjects.forResource("testdata/TestComputationProvider.java");

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(userEntity, addressEmbeddable, departmentEntity, orderEntity, userDTO, orderDTO, computationProvider);

        assertThat(compilation).succeeded();

        String generatedCode = getGeneratedProjectionCode(compilation);

        // Verify computed field: fullName depends on firstName and lastName
        assertTrue(generatedCode.contains("fullName"));
        assertTrue(generatedCode.contains("firstName"));
        assertTrue(generatedCode.contains("lastName"));

        // Verify computed field: age depends on birthDate
        assertTrue(generatedCode.contains("age"));
        assertTrue(generatedCode.contains("birthDate"));
    }

    @Test
    void testOrderSummaryDTOProjectionGeneration() throws IOException {
        JavaFileObject userEntity = JavaFileObjects.forResource("testdata/User.java");
        JavaFileObject addressEmbeddable = JavaFileObjects.forResource("testdata/Address.java");
        JavaFileObject departmentEntity = JavaFileObjects.forResource("testdata/Department.java");
        JavaFileObject orderEntity = JavaFileObjects.forResource("testdata/Order.java");
        JavaFileObject orderSummaryDTO = JavaFileObjects.forResource("testdata/OrderSummaryDTO.java");
        JavaFileObject computationProvider = JavaFileObjects.forResource("testdata/TestComputationProvider.java");

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(userEntity, addressEmbeddable, departmentEntity, orderEntity, orderSummaryDTO, computationProvider);

        assertThat(compilation).succeeded();

        String generatedCode = getGeneratedProjectionCode(compilation);

        // Verify OrderSummaryDTO metadata is present
        assertTrue(generatedCode.contains("OrderSummaryDTO"));
//        assertTrue(generatedCode.contains("Order.class"));
    }

    @Test
    void testOrderSummaryDTONestedPaths() throws IOException {
        JavaFileObject userEntity = JavaFileObjects.forResource("testdata/User.java");
        JavaFileObject addressEmbeddable = JavaFileObjects.forResource("testdata/Address.java");
        JavaFileObject departmentEntity = JavaFileObjects.forResource("testdata/Department.java");
        JavaFileObject orderEntity = JavaFileObjects.forResource("testdata/Order.java");
        JavaFileObject orderSummaryDTO = JavaFileObjects.forResource("testdata/OrderSummaryDTO.java");
        JavaFileObject computationProvider = JavaFileObjects.forResource("testdata/TestComputationProvider.java");

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(userEntity, addressEmbeddable, departmentEntity, orderEntity, orderSummaryDTO, computationProvider);

        assertThat(compilation).succeeded();

        String generatedCode = getGeneratedProjectionCode(compilation);

        // Verify nested path mapping: customerEmail -> user.email
        assertTrue(generatedCode.contains("customerEmail"));
        assertTrue(generatedCode.contains("\"user.email\""));

        // Verify computed field with nested dependencies: customerName
        assertTrue(generatedCode.contains("customerName"));
        assertTrue(generatedCode.contains("\"user.firstName\""));
        assertTrue(generatedCode.contains("\"user.lastName\""));
    }

    @Test
    void testBothProjectionsGenerated() throws IOException {
        JavaFileObject userEntity = JavaFileObjects.forResource("testdata/User.java");
        JavaFileObject addressEmbeddable = JavaFileObjects.forResource("testdata/Address.java");
        JavaFileObject departmentEntity = JavaFileObjects.forResource("testdata/Department.java");
        JavaFileObject orderEntity = JavaFileObjects.forResource("testdata/Order.java");
        JavaFileObject userDTO = JavaFileObjects.forResource("testdata/UserDTO.java");
        JavaFileObject orderSummaryDTO = JavaFileObjects.forResource("testdata/OrderSummaryDTO.java");
        JavaFileObject orderDTO = JavaFileObjects.forResource("testdata/OrderDTO.java");
        JavaFileObject computationProvider = JavaFileObjects.forResource("testdata/TestComputationProvider.java");

        Compilation compilation = Compiler.javac()
                .withProcessors(new MetamodelProcessor())
                .compile(userEntity, addressEmbeddable, departmentEntity, orderEntity, userDTO, orderSummaryDTO, orderDTO, computationProvider);

        assertThat(compilation).succeeded();

        String generatedCode = getGeneratedProjectionCode(compilation);

        // Verify both DTOs are in the generated registry
        assertTrue(generatedCode.contains("UserDTO"));
        assertTrue(generatedCode.contains("OrderSummaryDTO"));
    }

    // ==================== Helper Methods ====================

    private String getGeneratedProjectionCode(Compilation compilation) throws IOException {
        return compilation
                .generatedSourceFile("io.github.cyfko.jpa.metamodel.providers.ProjectionMetadataRegistryProviderImpl")
                .orElseThrow(() -> new AssertionError("Generated projection provider not found"))
                .getCharContent(true)
                .toString();
    }
}