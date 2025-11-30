package com.falkordb.jena;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Tests that exercise the demo Main class code paths using Testcontainers.
 * 
 * Uses Testcontainers to automatically start a FalkorDB container for testing.
 * No manual Docker setup required - tests work both locally and in CI.
 */
@Testcontainers
public class MainTest {

    private static final int FALKORDB_PORT = 6379;

    @Container
    private static final GenericContainer<?> falkordb = new GenericContainer<>(
            DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(FALKORDB_PORT);

    private static String falkorHost;
    private static int falkorPort;

    @BeforeAll
    public static void setUpContainer() {
        falkorHost = falkordb.getHost();
        falkorPort = falkordb.getMappedPort(FALKORDB_PORT);
    }

    @Test
    @DisplayName("Test Main class demo functionality using Testcontainers")
    public void testMainDemoFunctionality() {
        // This test exercises the same code paths as Main.main() but uses
        // Testcontainers to ensure the test works both locally and in CI
        assertDoesNotThrow(() -> {
            Model model = FalkorDBModelFactory.builder()
                .host(falkorHost)
                .port(falkorPort)
                .graphName("demo_graph")
                .build();

            try {
                // Clear any previous data
                if (model.getGraph() instanceof FalkorDBGraph) {
                    ((FalkorDBGraph) model.getGraph()).clear();
                }

                // Create some sample RDF data (same as Main.main)
                Property name = model.createProperty("http://example.org/name");
                Property age = model.createProperty("http://example.org/age");
                Resource person = model.createResource(
                    "http://example.org/person/john");
                
                person.addProperty(RDF.type, model.createResource(
                    "http://example.org/Person"));
                person.addProperty(name, "John Doe");
                person.addProperty(age, model.createTypedLiteral(30));

                // Verify data was added
                assertTrue(model.size() > 0, "Model should contain data");
                assertNotNull(person.getProperty(name), 
                    "Person should have name property");

                // Test delete operations (same as Main.main)
                person.removeAll(age);
                assertNotNull(person.getProperty(name), 
                    "Name should still exist after removing age");

                // Add another resource
                Resource person2 = model.createResource(
                    "http://example.org/person/jane");
                Resource personType = model.createResource(
                    "http://example.org/Person");
                person2.addProperty(RDF.type, personType);
                person2.addProperty(name, "Jane Smith");
                Property email = model.createProperty("http://example.org/email");
                person2.addProperty(email, "jane@example.org");

                // Test querying
                var iter = model.listStatements(null, RDF.type, personType);
                int count = 0;
                while (iter.hasNext()) {
                    iter.next();
                    count++;
                }
                assertTrue(count >= 1, "Should find at least one Person");

                // Test complete resource deletion
                person2.removeProperties();
            } finally {
                model.close();
            }
        });
    }
}
