package com.falkordb;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Fuseki server with FalkorDB backend.
 * 
 * These tests verify that:
 * 1. Fuseki server can be started with FalkorDB as backend
 * 2. SPARQL queries can be executed against the FalkorDB-backed endpoint
 * 3. SPARQL updates can be executed against the FalkorDB-backed endpoint
 * 4. The father-grandfather example from requirements works correctly
 * 
 * Prerequisites: FalkorDB must be running. Configure via environment variables:
 * - FALKORDB_HOST (default: localhost)
 * - FALKORDB_PORT (default: 6379)
 */
public class FusekiFalkorDBIntegrationTest {

    private static final String TEST_GRAPH = "fuseki_test_graph";
    private static final int TEST_PORT = 3331;
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
    
    @Test
    @DisplayName("Test Fuseki server starts with FalkorDB backend")
    public void testServerStarts() {
        assertNotNull(server, "Server should be started");
        assertTrue(server.getPort() > 0, "Server should be running on a port");
    }
    
    @Test
    @DisplayName("Test SPARQL query on empty database")
    public void testEmptyQuery() {
        String query = "SELECT * WHERE { ?s ?p ?o }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(), "Empty database should return no results");
        }
    }
    
    @Test
    @DisplayName("Test SPARQL update inserts data")
    public void testSparqlUpdate() {
        // Insert data using SPARQL update
        String updateQuery = 
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:person1 ex:name \"John Doe\" . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(updateQuery).execute();
        
        // Verify data was inserted
        String selectQuery = 
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?name WHERE { ex:person1 ex:name ?name }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Query should return results after insert");
            assertEquals("John Doe", results.next().getLiteral("name").getString());
        }
    }
    
    @Test
    @DisplayName("Test father-son relationships from requirements")
    public void testFatherSonRelationships() {
        // This test mirrors the example from the requirements (Proposal.md)
        // Insert father-son relationship data
        String insertQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "INSERT DATA { " +
            "  ff:Jacob a ff:Male . " +
            "  ff:Isaac a ff:Male ; " +
            "    ff:father_of ff:Jacob . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query for father-son relationships
        String selectQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "SELECT ?father ?son " +
            "WHERE { " +
            "  ?father ff:father_of ?son . " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Query should return father-son relationship");
            
            var solution = results.next();
            String father = solution.getResource("father").getURI();
            String son = solution.getResource("son").getURI();
            
            assertTrue(father.endsWith("Isaac"), "Father should be Isaac");
            assertTrue(son.endsWith("Jacob"), "Son should be Jacob");
        }
    }
    
    @Test
    @DisplayName("Test multiple inserts and queries")
    public void testMultipleInsertsAndQueries() {
        // Insert multiple triples
        String insertQuery = 
            "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:alice foaf:name \"Alice\" ; " +
            "           foaf:knows ex:bob . " +
            "  ex:bob foaf:name \"Bob\" . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query for all names
        String namesQuery = 
            "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
            "SELECT ?name WHERE { ?person foaf:name ?name } ORDER BY ?name";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(namesQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            assertEquals("Alice", results.next().getLiteral("name").getString());
            
            assertTrue(results.hasNext());
            assertEquals("Bob", results.next().getLiteral("name").getString());
            
            assertFalse(results.hasNext());
        }
        
        // Query for relationships
        String knowsQuery = 
            "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?known WHERE { ex:alice foaf:knows ?known }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(knowsQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            assertTrue(results.next().getResource("known").getURI().endsWith("bob"));
        }
    }
    
    @Test
    @DisplayName("Test SPARQL DELETE operation")
    public void testSparqlDelete() {
        // First insert data
        String insertQuery = 
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:person1 ex:name \"John\" ; " +
            "             ex:age \"30\" . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify data exists
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertEquals(2, results.next().getLiteral("count").getInt());
        }
        
        // Delete age property
        String deleteQuery = 
            "PREFIX ex: <http://example.org/> " +
            "DELETE DATA { " +
            "  ex:person1 ex:age \"30\" . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(deleteQuery).execute();
        
        // Verify deletion
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertEquals(1, results.next().getLiteral("count").getInt());
        }
    }
    
    @Test
    @DisplayName("Test rdf:type with Fuseki")
    public void testRdfTypeWithFuseki() {
        // Insert data with rdf:type
        String insertQuery = 
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:person1 rdf:type ex:Person ; " +
            "             ex:name \"John\" . " +
            "  ex:person2 rdf:type ex:Person ; " +
            "             ex:name \"Jane\" . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query for all Person instances
        String selectQuery = 
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?person ?name " +
            "WHERE { " +
            "  ?person rdf:type ex:Person ; " +
            "          ex:name ?name . " +
            "} ORDER BY ?name";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            var first = results.next();
            assertEquals("Jane", first.getLiteral("name").getString());
            
            assertTrue(results.hasNext());
            var second = results.next();
            assertEquals("John", second.getLiteral("name").getString());
            
            assertFalse(results.hasNext());
        }
    }
}
