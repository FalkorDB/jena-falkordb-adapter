package com.falkordb;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that verify all examples from GETTING_STARTED.md work correctly.
 * 
 * These tests ensure that the documentation examples are accurate and functional.
 * Each test corresponds to an example in the GETTING_STARTED.md file.
 * 
 * Prerequisites: FalkorDB must be running. Configure via environment variables:
 * - FALKORDB_HOST (default: localhost)
 * - FALKORDB_PORT (default: 6379)
 */
public class GettingStartedExamplesTest {

    private static final String TEST_GRAPH = "getting_started_test_graph";
    private static final int TEST_PORT = 3333;
    private static final String DATASET_PATH = "/falkor";
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
        falkorPort = Integer.parseInt(System.getenv().getOrDefault("FALKORDB_PORT", String.valueOf(DEFAULT_FALKORDB_PORT)));
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
        if (falkorModel.getGraph() instanceof FalkorDBGraph) {
            ((FalkorDBGraph) falkorModel.getGraph()).clear();
        }
        
        // Create dataset from model
        Dataset ds = DatasetFactory.create(falkorModel);
        
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
    // Quick Start Example Tests
    // ========================================================================

    @Test
    @DisplayName("Quick Start: Insert simple data (Step 3)")
    public void testQuickStartInsertData() {
        // From GETTING_STARTED.md Quick Start Step 3
        String insertQuery = "INSERT DATA { <http://example/s> <http://example/p> 123 }";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify data was inserted
        String selectQuery = "SELECT * WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Data should be inserted");
            
            QuerySolution solution = results.next();
            assertEquals("http://example/s", solution.getResource("s").getURI());
            assertEquals("http://example/p", solution.getResource("p").getURI());
            assertEquals(123, solution.getLiteral("o").getInt());
            
            assertFalse(results.hasNext(), "Should have exactly one result");
        }
    }

    @Test
    @DisplayName("Quick Start: Query data (Step 3)")
    public void testQuickStartQueryData() {
        // First insert some data
        String insertQuery = 
            "INSERT DATA { " +
            "  <http://example/s1> <http://example/p> \"value1\" . " +
            "  <http://example/s2> <http://example/p> \"value2\" . " +
            "}";
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // From GETTING_STARTED.md Quick Start Step 3
        String selectQuery = "SELECT * WHERE { ?s ?p ?o }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            int count = 0;
            while (results.hasNext()) {
                results.next();
                count++;
            }
            assertEquals(2, count, "Should return two results");
        }
    }

    // ========================================================================
    // Father-Son Example Tests
    // ========================================================================

    @Test
    @DisplayName("Father-Son: Insert relationships from requirements")
    public void testFatherSonInsert() {
        // From GETTING_STARTED.md Father-Son Example
        String insertQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "INSERT DATA { " +
            "    ff:Jacob a ff:Male . " +
            "    ff:Isaac a ff:Male ; " +
            "        ff:father_of ff:Jacob . " +
            "    ff:Abraham a ff:Male ; " +
            "        ff:father_of ff:Isaac . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify all three persons were inserted
        String countQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "SELECT (COUNT(DISTINCT ?person) AS ?count) " +
            "WHERE { ?person a ff:Male }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            assertEquals(3, results.next().getLiteral("count").getInt(), 
                "Should have three males: Jacob, Isaac, Abraham");
        }
    }

    @Test
    @DisplayName("Father-Son: Query relationships from requirements")
    public void testFatherSonQuery() {
        // Insert the data first
        String insertQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "INSERT DATA { " +
            "    ff:Jacob a ff:Male . " +
            "    ff:Isaac a ff:Male ; " +
            "        ff:father_of ff:Jacob . " +
            "    ff:Abraham a ff:Male ; " +
            "        ff:father_of ff:Isaac . " +
            "}";
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // From GETTING_STARTED.md Father-Son Example
        String selectQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "SELECT ?father ?son " +
            "WHERE { " +
            "    ?father ff:father_of ?son . " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            Set<String> relationships = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.next();
                String father = solution.getResource("father").getLocalName();
                String son = solution.getResource("son").getLocalName();
                relationships.add(father + "->" + son);
            }
            
            assertEquals(2, relationships.size(), "Should have two father-son relationships");
            assertTrue(relationships.contains("Isaac->Jacob"), "Isaac should be father of Jacob");
            assertTrue(relationships.contains("Abraham->Isaac"), "Abraham should be father of Isaac");
        }
    }

    @Test
    @DisplayName("Father-Son: Query all males")
    public void testFatherSonQueryAllMales() {
        // Insert the data first
        String insertQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "INSERT DATA { " +
            "    ff:Jacob a ff:Male . " +
            "    ff:Isaac a ff:Male ; " +
            "        ff:father_of ff:Jacob . " +
            "    ff:Abraham a ff:Male ; " +
            "        ff:father_of ff:Isaac . " +
            "}";
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // From GETTING_STARTED.md - Query All Males
        String selectQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "SELECT ?person " +
            "WHERE { " +
            "    ?person a ff:Male . " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            Set<String> males = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.next();
                males.add(solution.getResource("person").getLocalName());
            }
            
            assertEquals(3, males.size(), "Should have three males");
            assertTrue(males.contains("Jacob"), "Jacob should be a Male");
            assertTrue(males.contains("Isaac"), "Isaac should be a Male");
            assertTrue(males.contains("Abraham"), "Abraham should be a Male");
        }
    }

    // ========================================================================
    // SPARQL Endpoint Reference Tests
    // ========================================================================

    @Test
    @DisplayName("SPARQL: ASK query")
    public void testAskQuery() {
        // Insert data
        String insertQuery = "INSERT DATA { <http://example/s> <http://example/p> \"value\" }";
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // From GETTING_STARTED.md ASK query example
        String askQuery = "ASK { <http://example/s> ?p ?o }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(askQuery).build()) {
            assertTrue(qexec.execAsk(), "ASK should return true when data exists");
        }
        
        // ASK for non-existent data
        String askQueryFalse = "ASK { <http://example/nonexistent> ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(askQueryFalse).build()) {
            assertFalse(qexec.execAsk(), "ASK should return false when data doesn't exist");
        }
    }

    @Test
    @DisplayName("SPARQL: INSERT DATA")
    public void testInsertData() {
        // From GETTING_STARTED.md INSERT DATA example
        String insertQuery = 
            "INSERT DATA { <http://example/subject> <http://example/predicate> \"value\" }";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify
        String selectQuery = 
            "SELECT ?o WHERE { <http://example/subject> <http://example/predicate> ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            assertEquals("value", results.next().getLiteral("o").getString());
        }
    }

    @Test
    @DisplayName("SPARQL: DELETE DATA")
    public void testDeleteData() {
        // First insert
        String insertQuery = 
            "INSERT DATA { <http://example/subject> <http://example/predicate> \"value\" }";
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify inserted
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            assertEquals(1, qexec.execSelect().next().getLiteral("count").getInt());
        }
        
        // From GETTING_STARTED.md DELETE DATA example
        String deleteQuery = 
            "DELETE DATA { <http://example/subject> <http://example/predicate> \"value\" }";
        UpdateExecutionHTTP.service(updateEndpoint).update(deleteQuery).execute();
        
        // Verify deleted
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            assertEquals(0, qexec.execSelect().next().getLiteral("count").getInt());
        }
    }

    @Test
    @DisplayName("SPARQL: DELETE WHERE")
    public void testDeleteWhere() {
        // Insert multiple triples
        String insertQuery = 
            "INSERT DATA { " +
            "  <http://example/s1> <http://example/p> \"value1\" . " +
            "  <http://example/s2> <http://example/p> \"value2\" . " +
            "}";
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify inserted
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            assertEquals(2, qexec.execSelect().next().getLiteral("count").getInt());
        }
        
        // From GETTING_STARTED.md DELETE WHERE example
        String deleteQuery = "DELETE WHERE { ?s ?p ?o }";
        UpdateExecutionHTTP.service(updateEndpoint).update(deleteQuery).execute();
        
        // Verify all deleted
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            assertEquals(0, qexec.execSelect().next().getLiteral("count").getInt());
        }
    }

    // ========================================================================
    // Social Network Example Tests
    // ========================================================================

    @Test
    @DisplayName("Social Network: Insert data")
    public void testSocialNetworkInsert() {
        // From GETTING_STARTED.md Social Network Example
        String insertQuery = 
            "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "    ex:alice a foaf:Person ; " +
            "        foaf:name \"Alice\" ; " +
            "        foaf:age 30 ; " +
            "        foaf:knows ex:bob . " +
            "    ex:bob a foaf:Person ; " +
            "        foaf:name \"Bob\" ; " +
            "        foaf:age 35 ; " +
            "        foaf:knows ex:charlie . " +
            "    ex:charlie a foaf:Person ; " +
            "        foaf:name \"Charlie\" ; " +
            "        foaf:age 25 . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify all three persons were inserted with correct types
        String countQuery = 
            "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
            "SELECT (COUNT(DISTINCT ?person) AS ?count) " +
            "WHERE { ?person a foaf:Person }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            assertEquals(3, results.next().getLiteral("count").getInt(), 
                "Should have three persons: Alice, Bob, Charlie");
        }
    }

    @Test
    @DisplayName("Social Network: Query all people")
    public void testSocialNetworkQueryAllPeople() {
        // Insert the data first
        String insertQuery = 
            "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "    ex:alice a foaf:Person ; " +
            "        foaf:name \"Alice\" ; " +
            "        foaf:age 30 ; " +
            "        foaf:knows ex:bob . " +
            "    ex:bob a foaf:Person ; " +
            "        foaf:name \"Bob\" ; " +
            "        foaf:age 35 ; " +
            "        foaf:knows ex:charlie . " +
            "    ex:charlie a foaf:Person ; " +
            "        foaf:name \"Charlie\" ; " +
            "        foaf:age 25 . " +
            "}";
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // From GETTING_STARTED.md - Query All People
        String selectQuery = 
            "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
            "SELECT ?person ?name ?age " +
            "WHERE { " +
            "    ?person a foaf:Person ; " +
            "        foaf:name ?name ; " +
            "        foaf:age ?age . " +
            "} " +
            "ORDER BY ?name";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            // Collect all results (order may vary due to FalkorDB behavior)
            Set<String> names = new HashSet<>();
            Set<Integer> ages = new HashSet<>();
            
            while (results.hasNext()) {
                QuerySolution solution = results.next();
                names.add(solution.getLiteral("name").getString());
                ages.add(solution.getLiteral("age").getInt());
            }
            
            // Verify all three persons are returned with correct data
            assertEquals(3, names.size(), "Should have three people");
            assertTrue(names.contains("Alice"), "Alice should be in results");
            assertTrue(names.contains("Bob"), "Bob should be in results");
            assertTrue(names.contains("Charlie"), "Charlie should be in results");
            
            assertTrue(ages.contains(30), "Age 30 should be in results");
            assertTrue(ages.contains(35), "Age 35 should be in results");
            assertTrue(ages.contains(25), "Age 25 should be in results");
        }
    }

    @Test
    @DisplayName("Social Network: Query who knows whom")
    public void testSocialNetworkQueryWhoKnowsWhom() {
        // Insert the data first
        String insertQuery = 
            "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "    ex:alice a foaf:Person ; " +
            "        foaf:name \"Alice\" ; " +
            "        foaf:age 30 ; " +
            "        foaf:knows ex:bob . " +
            "    ex:bob a foaf:Person ; " +
            "        foaf:name \"Bob\" ; " +
            "        foaf:age 35 ; " +
            "        foaf:knows ex:charlie . " +
            "    ex:charlie a foaf:Person ; " +
            "        foaf:name \"Charlie\" ; " +
            "        foaf:age 25 . " +
            "}";
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // From GETTING_STARTED.md - Query Who Knows Whom
        String selectQuery = 
            "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
            "SELECT ?person ?friend " +
            "WHERE { " +
            "    ?person foaf:knows ?friend . " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            Set<String> relationships = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.next();
                String person = solution.getResource("person").getLocalName();
                String friend = solution.getResource("friend").getLocalName();
                relationships.add(person + " knows " + friend);
            }
            
            assertEquals(2, relationships.size(), "Should have two 'knows' relationships");
            assertTrue(relationships.contains("alice knows bob"), 
                "Alice should know Bob");
            assertTrue(relationships.contains("bob knows charlie"), 
                "Bob should know Charlie");
        }
    }

    // ========================================================================
    // Additional Coverage Tests
    // ========================================================================

    @Test
    @DisplayName("SPARQL: SELECT with LIMIT")
    public void testSelectWithLimit() {
        // Insert multiple triples
        for (int i = 0; i < 5; i++) {
            String insertQuery = String.format(
                "INSERT DATA { <http://example/s%d> <http://example/p> \"%d\" }", i, i);
            UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        }
        
        // Query with LIMIT (from GETTING_STARTED.md reference section)
        String selectQuery = "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            int count = 0;
            while (results.hasNext()) {
                results.next();
                count++;
            }
            assertEquals(5, count, "Should return exactly 5 results");
        }
    }

    @Test
    @DisplayName("Multiple updates in sequence")
    public void testMultipleUpdatesInSequence() {
        // Insert first person
        String insert1 = 
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { ex:person1 ex:name \"Person One\" }";
        UpdateExecutionHTTP.service(updateEndpoint).update(insert1).execute();
        
        // Insert second person
        String insert2 = 
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { ex:person2 ex:name \"Person Two\" }";
        UpdateExecutionHTTP.service(updateEndpoint).update(insert2).execute();
        
        // Delete first person
        String delete1 = 
            "PREFIX ex: <http://example.org/> " +
            "DELETE DATA { ex:person1 ex:name \"Person One\" }";
        UpdateExecutionHTTP.service(updateEndpoint).update(delete1).execute();
        
        // Verify only second person remains
        String selectQuery = 
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?name WHERE { ?person ex:name ?name }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            assertEquals("Person Two", results.next().getLiteral("name").getString());
            assertFalse(results.hasNext());
        }
    }

    @Test
    @DisplayName("Empty result set handling")
    public void testEmptyResultSet() {
        // Query on empty database
        String selectQuery = "SELECT * WHERE { ?s ?p ?o }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(), "Empty database should return no results");
        }
    }
}
