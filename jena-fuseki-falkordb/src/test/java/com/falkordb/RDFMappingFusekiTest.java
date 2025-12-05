package com.falkordb;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System tests for RDF to FalkorDB Graph mapping via Fuseki SPARQL interface.
 * 
 * These tests validate the mappings documented in MAPPING.md using
 * Turtle syntax through the Fuseki SPARQL endpoint.
 * 
 * Prerequisites: FalkorDB must be running on localhost:6379
 * Run: docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
 * 
 * @see <a href="../../../../../../MAPPING.md">MAPPING.md</a>
 */
public class RDFMappingFusekiTest {

    private static final String TEST_GRAPH = "rdf_mapping_fuseki_test_graph";
    private static final int TEST_PORT = 3332;
    private static final String DATASET_PATH = "/mapping";
    private static final int DEFAULT_FALKORDB_PORT = 6379;

    private static String falkorHost;
    private static int falkorPort;

    private FusekiServer server;
    private Model falkorModel;
    private String sparqlEndpoint;
    private String updateEndpoint;

    @BeforeAll
    public static void setUpContainer() {
        falkorHost = System.getenv().getOrDefault("FALKORDB_HOST", "localhost");
        falkorPort = Integer.parseInt(
            System.getenv().getOrDefault("FALKORDB_PORT", String.valueOf(DEFAULT_FALKORDB_PORT)));
    }

    @BeforeEach
    public void setUp() {
        // Create FalkorDB-backed model
        falkorModel = FalkorDBModelFactory.builder()
            .host(falkorHost)
            .port(falkorPort)
            .graphName(TEST_GRAPH)
            .build();

        // Clear the graph before each test
        if (falkorModel.getGraph() instanceof FalkorDBGraph falkorGraph) {
            falkorGraph.clear();
        }

        // Create dataset from model
        var ds = DatasetFactory.create(falkorModel);

        // Start Fuseki server
        server = FusekiServer.create()
            .port(TEST_PORT)
            .add(DATASET_PATH, ds)
            .build();
        server.start();

        sparqlEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/query";
        updateEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/update";
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (falkorModel != null) {
            falkorModel.close();
        }
    }

    // ========================================================================
    // Test 1: Basic Resource with Literal Property via Fuseki
    // ========================================================================

    /**
     * Tests basic literal property mapping via Fuseki/Turtle.
     * 
     * Turtle:
     * @prefix ex: <http://example.org/> .
     * ex:person1 ex:name "John Doe" .
     * 
     * @see <a href="../../../../../../MAPPING.md#1-basic-resource-with-literal-property">MAPPING.md Section 1</a>
     */
    @Test
    @DisplayName("1. Basic Literal Property - Fuseki SPARQL")
    public void testBasicLiteralPropertyFuseki() {
        // Arrange - Insert using SPARQL UPDATE
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            INSERT DATA {
                ex:person1 ex:name "John Doe" .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Act - Query via SPARQL
        String selectQuery = """
            PREFIX ex: <http://example.org/>
            SELECT ?name WHERE {
                ex:person1 ex:name ?name .
            }
            """;

        // Assert
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Query should return results");
            assertEquals("John Doe", results.next().getLiteral("name").getString());
        }
    }

    // ========================================================================
    // Test 2: Resource with rdf:type via Fuseki
    // ========================================================================

    /**
     * Tests rdf:type mapping via Fuseki/Turtle.
     * 
     * Turtle:
     * @prefix ex: <http://example.org/> .
     * ex:person1 a ex:Person .
     * 
     * @see <a href="../../../../../../MAPPING.md#2-resource-with-rdftype">MAPPING.md Section 2</a>
     */
    @Test
    @DisplayName("2. Resource with rdf:type - Fuseki SPARQL")
    public void testRdfTypeFuseki() {
        // Arrange - Insert using SPARQL UPDATE with Turtle shorthand 'a' for rdf:type
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            INSERT DATA {
                ex:person1 rdf:type ex:Person .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Act - Query by type
        String selectQuery = """
            PREFIX ex: <http://example.org/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?person WHERE {
                ?person rdf:type ex:Person .
            }
            """;

        // Assert
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Query by type should return results");
            assertTrue(results.next().getResource("person").getURI().endsWith("person1"));
        }
    }

    // ========================================================================
    // Test 3: Resource to Resource Relationship via Fuseki
    // ========================================================================

    /**
     * Tests resource-to-resource relationship via Fuseki/Turtle.
     * 
     * Turtle:
     * @prefix ex: <http://example.org/> .
     * ex:alice ex:knows ex:bob .
     * 
     * @see <a href="../../../../../../MAPPING.md#3-resource-to-resource-relationship">MAPPING.md Section 3</a>
     */
    @Test
    @DisplayName("3. Resource to Resource Relationship - Fuseki SPARQL")
    public void testResourceRelationshipFuseki() {
        // Arrange
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            INSERT DATA {
                ex:alice ex:knows ex:bob .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Act
        String selectQuery = """
            PREFIX ex: <http://example.org/>
            SELECT ?known WHERE {
                ex:alice ex:knows ?known .
            }
            """;

        // Assert
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Relationship query should return results");
            assertTrue(results.next().getResource("known").getURI().endsWith("bob"));
        }
    }

    // ========================================================================
    // Test 4: Multiple Properties via Fuseki
    // ========================================================================

    /**
     * Tests multiple properties on the same resource via Fuseki/Turtle.
     * 
     * Turtle:
     * @prefix ex: <http://example.org/> .
     * ex:person1 ex:name "John Doe" ;
     *            ex:age "30" ;
     *            ex:email "john@example.org" .
     * 
     * @see <a href="../../../../../../MAPPING.md#4-multiple-properties-on-same-resource">MAPPING.md Section 4</a>
     */
    @Test
    @DisplayName("4. Multiple Properties - Fuseki SPARQL")
    public void testMultiplePropertiesFuseki() {
        // Arrange - Using Turtle semicolon notation
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            INSERT DATA {
                ex:person1 ex:name "John Doe" ;
                           ex:age "30" ;
                           ex:email "john@example.org" .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Act
        String selectQuery = """
            PREFIX ex: <http://example.org/>
            SELECT ?name ?age ?email WHERE {
                ex:person1 ex:name ?name ;
                           ex:age ?age ;
                           ex:email ?email .
            }
            """;

        // Assert
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            var solution = results.next();
            assertEquals("John Doe", solution.getLiteral("name").getString());
            assertEquals("30", solution.getLiteral("age").getString());
            assertEquals("john@example.org", solution.getLiteral("email").getString());
        }
    }

    // ========================================================================
    // Test 5: Multiple Types via Fuseki
    // ========================================================================

    /**
     * Tests multiple types on the same resource via Fuseki/Turtle.
     * 
     * Turtle:
     * @prefix ex: <http://example.org/> .
     * ex:person1 a ex:Person, ex:Employee, ex:Developer .
     * 
     * @see <a href="../../../../../../MAPPING.md#5-multiple-types-on-same-resource">MAPPING.md Section 5</a>
     */
    @Test
    @DisplayName("5. Multiple Types - Fuseki SPARQL")
    public void testMultipleTypesFuseki() {
        // Arrange - Using Turtle comma notation for multiple types
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            INSERT DATA {
                ex:person1 rdf:type ex:Person ;
                           rdf:type ex:Employee ;
                           rdf:type ex:Developer .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Act & Assert - Query by each type
        String[] types = {"Person", "Employee", "Developer"};
        for (String type : types) {
            String selectQuery = """
                PREFIX ex: <http://example.org/>
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                SELECT ?person WHERE {
                    ?person rdf:type ex:%s .
                }
                """.formatted(type);

            try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
                ResultSet results = qexec.execSelect();
                assertTrue(results.hasNext(), "Query by type " + type + " should return results");
                assertTrue(results.next().getResource("person").getURI().endsWith("person1"));
            }
        }
    }

    // ========================================================================
    // Test 6: Typed Literals via Fuseki
    // ========================================================================

    /**
     * Tests typed literals via Fuseki/Turtle.
     * 
     * Turtle:
     * @prefix ex: <http://example.org/> .
     * @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
     * ex:person1 ex:age "30"^^xsd:integer ;
     *            ex:height "1.75"^^xsd:double ;
     *            ex:active "true"^^xsd:boolean .
     * 
     * @see <a href="../../../../../../MAPPING.md#6-typed-literals">MAPPING.md Section 6</a>
     */
    @Test
    @DisplayName("6. Typed Literals - Fuseki SPARQL")
    public void testTypedLiteralsFuseki() {
        // Arrange
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            INSERT DATA {
                ex:person1 ex:age "30"^^xsd:integer ;
                           ex:height "1.75"^^xsd:double ;
                           ex:active "true"^^xsd:boolean .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Act
        String selectQuery = """
            PREFIX ex: <http://example.org/>
            SELECT ?age ?height ?active WHERE {
                ex:person1 ex:age ?age ;
                           ex:height ?height ;
                           ex:active ?active .
            }
            """;

        // Assert
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            var solution = results.next();
            // Values are stored and can be retrieved
            assertNotNull(solution.getLiteral("age"));
            assertNotNull(solution.getLiteral("height"));
            assertNotNull(solution.getLiteral("active"));
        }
    }

    // ========================================================================
    // Test 7: Language-Tagged Literals via Fuseki
    // ========================================================================

    /**
     * Tests language-tagged literals via Fuseki/Turtle.
     * 
     * Turtle:
     * @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
     * @prefix ex: <http://example.org/> .
     * ex:paris rdfs:label "Paris"@en .
     * 
     * @see <a href="../../../../../../MAPPING.md#7-language-tagged-literals">MAPPING.md Section 7</a>
     */
    @Test
    @DisplayName("7. Language-Tagged Literals - Fuseki SPARQL")
    public void testLanguageTaggedLiteralsFuseki() {
        // Arrange
        String insertQuery = """
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX ex: <http://example.org/>
            INSERT DATA {
                ex:paris rdfs:label "Paris"@en .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Act
        String selectQuery = """
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX ex: <http://example.org/>
            SELECT ?label WHERE {
                ex:paris rdfs:label ?label .
            }
            """;

        // Assert
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            assertNotNull(results.next().getLiteral("label"));
        }
    }

    // ========================================================================
    // Test 9: Blank Nodes via Fuseki
    // ========================================================================

    /**
     * Tests blank nodes (anonymous resources) via Fuseki/Turtle.
     * 
     * Turtle:
     * @prefix ex: <http://example.org/> .
     * ex:person1 ex:hasAddress [
     *     ex:street "123 Main St" ;
     *     ex:city "Springfield"
     * ] .
     * 
     * @see <a href="../../../../../../MAPPING.md#9-blank-nodes-anonymous-resources">MAPPING.md Section 9</a>
     */
    @Test
    @DisplayName("9. Blank Nodes - Fuseki SPARQL")
    public void testBlankNodesFuseki() {
        // Arrange - Insert data with blank node using Turtle bracket syntax
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            INSERT DATA {
                ex:person1 ex:hasAddress [
                    ex:street "123 Main St" ;
                    ex:city "Springfield"
                ] .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Act - Query through the blank node
        String selectQuery = """
            PREFIX ex: <http://example.org/>
            SELECT ?street ?city WHERE {
                ex:person1 ex:hasAddress ?addr .
                ?addr ex:street ?street ;
                      ex:city ?city .
            }
            """;

        // Assert
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Query through blank node should return results");
            var solution = results.next();
            assertEquals("123 Main St", solution.getLiteral("street").getString());
            assertEquals("Springfield", solution.getLiteral("city").getString());
        }
    }

    // ========================================================================
    // Test 10: Complex Graph via Fuseki
    // ========================================================================

    /**
     * Tests complex graph with all mapping types via Fuseki/Turtle.
     * 
     * @see <a href="../../../../../../MAPPING.md#10-complex-graph-with-mixed-triples">MAPPING.md Section 10</a>
     */
    @Test
    @DisplayName("10. Complex Graph - Fuseki SPARQL")
    public void testComplexGraphFuseki() {
        // Arrange - Insert complex graph using Turtle syntax
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            INSERT DATA {
                ex:alice rdf:type ex:Person ;
                    ex:name "Alice Smith" ;
                    ex:age "30"^^xsd:integer ;
                    ex:knows ex:bob .
                
                ex:bob rdf:type ex:Person ;
                    ex:name "Bob Jones" ;
                    ex:worksAt ex:acme .
                
                ex:acme rdf:type ex:Company ;
                    ex:name "ACME Corp" .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Assert - Query for all persons
        String personQuery = """
            PREFIX ex: <http://example.org/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?person ?name WHERE {
                ?person rdf:type ex:Person ;
                        ex:name ?name .
            } ORDER BY ?name
            """;
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(personQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            assertEquals("Alice Smith", results.next().getLiteral("name").getString());
            assertTrue(results.hasNext());
            assertEquals("Bob Jones", results.next().getLiteral("name").getString());
            assertFalse(results.hasNext());
        }

        // Assert - Query for relationship path (person -> company)
        String pathQuery = """
            PREFIX ex: <http://example.org/>
            SELECT ?personName ?companyName WHERE {
                ?person ex:worksAt ?company ;
                        ex:name ?personName .
                ?company ex:name ?companyName .
            }
            """;
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(pathQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            var solution = results.next();
            assertEquals("Bob Jones", solution.getLiteral("personName").getString());
            assertEquals("ACME Corp", solution.getLiteral("companyName").getString());
        }

        // Assert - Query for knows relationship
        String knowsQuery = """
            PREFIX ex: <http://example.org/>
            SELECT ?knower ?known WHERE {
                ?knower ex:knows ?known .
            }
            """;
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(knowsQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            var solution = results.next();
            assertTrue(solution.getResource("knower").getURI().endsWith("alice"));
            assertTrue(solution.getResource("known").getURI().endsWith("bob"));
        }
    }

    // ========================================================================
    // Test: SPARQL DELETE Operation
    // ========================================================================

    /**
     * Tests SPARQL DELETE operation via Fuseki.
     */
    @Test
    @DisplayName("SPARQL DELETE Operation - Fuseki")
    public void testSparqlDeleteFuseki() {
        // Arrange - Insert data
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            INSERT DATA {
                ex:person1 ex:name "John Doe" ;
                           ex:age "30" .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Verify initial count
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertEquals(2, results.next().getLiteral("count").getInt());
        }

        // Act - Delete age property
        String deleteQuery = """
            PREFIX ex: <http://example.org/>
            DELETE DATA {
                ex:person1 ex:age "30" .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(deleteQuery).execute();

        // Assert - Verify deletion
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertEquals(1, results.next().getLiteral("count").getInt());
        }
    }

    // ========================================================================
    // Test: Count Triples
    // ========================================================================

    /**
     * Tests triple counting via Fuseki.
     */
    @Test
    @DisplayName("Count Triples - Fuseki SPARQL")
    public void testCountTriplesFuseki() {
        // Arrange - Insert data
        String insertQuery = """
            PREFIX ex: <http://example.org/>
            INSERT DATA {
                ex:a ex:p1 "v1" .
                ex:a ex:p2 "v2" .
                ex:a ex:p3 "v3" .
                ex:b ex:p1 "v4" .
                ex:b ex:p2 "v5" .
            }
            """;
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Act & Assert
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertEquals(5, results.next().getLiteral("count").getInt());
        }
    }
}
