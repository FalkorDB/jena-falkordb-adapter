package com.falkordb;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Fuseki server started with assembler configuration file.
 * 
 * This verifies that:
 * 1. The FalkorDBAssembler is properly registered and used
 * 2. Configuration files can define FalkorDB-backed datasets
 * 3. The server works correctly with config-based initialization
 * 
 * Prerequisites: FalkorDB must be running on localhost:6379
 * Run: docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
 */
public class FusekiAssemblerConfigTest {

    private static final int TEST_PORT = 3332;
    private static final String CONFIG_FILE = "config-test-falkordb.ttl";
    
    private FusekiServer server;
    private String sparqlEndpoint;
    private String updateEndpoint;
    
    @BeforeEach
    public void setUp() {
        // Load config from classpath
        String configPath = getClass().getClassLoader().getResource(CONFIG_FILE).getPath();
        
        // Start Fuseki server with config file
        server = FusekiServer.create()
            .port(TEST_PORT)
            .parseConfigFile(configPath)
            .build();
        server.start();
        
        // Endpoints based on config file service name "testdb"
        sparqlEndpoint = "http://localhost:" + TEST_PORT + "/testdb/query";
        updateEndpoint = "http://localhost:" + TEST_PORT + "/testdb/update";
        
        // Clear any existing data
        String clearQuery = "DELETE WHERE { ?s ?p ?o }";
        UpdateExecutionHTTP.service(updateEndpoint).update(clearQuery).execute();
    }
    
    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }
    
    @Test
    @DisplayName("Test server starts with config file")
    public void testServerStartsWithConfig() {
        assertNotNull(server, "Server should be started");
        assertEquals(TEST_PORT, server.getPort(), "Server should be running on configured port");
    }
    
    @Test
    @DisplayName("Test assembler-configured endpoint responds to queries")
    public void testAssemblerEndpointQuery() {
        String query = "SELECT * WHERE { ?s ?p ?o }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            // Should not throw - endpoint should be functional
            assertNotNull(results);
        }
    }
    
    @Test
    @DisplayName("Test assembler-configured endpoint accepts updates")
    public void testAssemblerEndpointUpdate() {
        // Insert data
        String insertQuery = 
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:test ex:property \"value\" . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify insert worked
        String selectQuery = 
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?value WHERE { ex:test ex:property ?value }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Data should be retrievable after insert");
            assertEquals("value", results.next().getLiteral("value").getString());
        }
    }
    
    @Test
    @DisplayName("Test full workflow with assembler config - father example from requirements")
    public void testFatherExampleWithAssemblerConfig() {
        // Insert father-son data (from Proposal.md requirements)
        String insertQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "INSERT DATA { " +
            "  ff:Jacob a ff:Male . " +
            "  ff:Isaac a ff:Male ; " +
            "    ff:father_of ff:Jacob . " +
            "  ff:Abraham a ff:Male ; " +
            "    ff:father_of ff:Isaac . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query for father-son relationships
        String selectQuery = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "SELECT ?father ?son " +
            "WHERE { " +
            "  ?father ff:father_of ?son . " +
            "} ORDER BY ?father";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            // First result: Abraham -> Isaac
            assertTrue(results.hasNext(), "Should have first result");
            var first = results.next();
            assertTrue(first.getResource("father").getURI().endsWith("Abraham"));
            assertTrue(first.getResource("son").getURI().endsWith("Isaac"));
            
            // Second result: Isaac -> Jacob
            assertTrue(results.hasNext(), "Should have second result");
            var second = results.next();
            assertTrue(second.getResource("father").getURI().endsWith("Isaac"));
            assertTrue(second.getResource("son").getURI().endsWith("Jacob"));
            
            assertFalse(results.hasNext(), "Should have only two results");
        }
    }
    
    @Test
    @DisplayName("Test count query with assembler config")
    public void testCountQueryWithAssemblerConfig() {
        // Insert some data
        String insertQuery = 
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:a ex:p \"1\" . " +
            "  ex:b ex:p \"2\" . " +
            "  ex:c ex:p \"3\" . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Count triples
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            assertEquals(3, results.next().getLiteral("count").getInt());
        }
    }
}
