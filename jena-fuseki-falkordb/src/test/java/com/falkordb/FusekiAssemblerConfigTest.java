package com.falkordb;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Fuseki server started with config-falkordb.ttl configuration file.
 * 
 * This verifies that:
 * 1. The FalkorDBAssembler is properly registered and used
 * 2. The config-falkordb.ttl file correctly defines the three-layer onion architecture
 * 3. The server works correctly with GeoSPARQL + Inference + FalkorDB layers
 * 
 * Prerequisites: FalkorDB must be running. Configure via environment variables:
 * - FALKORDB_HOST (default: localhost)
 * - FALKORDB_PORT (default: 6379)
 */
public class FusekiAssemblerConfigTest {

    private static final int TEST_PORT = 3332;
    private static final int DEFAULT_FALKORDB_PORT = 6379;
    
    @TempDir
    Path tempDir;
    
    private static String falkorHost;
    private static int falkorPort;
    
    private FusekiServer server;
    private String sparqlEndpoint;
    private String updateEndpoint;
    
    @BeforeAll
    public static void setUpContainer() {
        falkorHost = System.getenv().getOrDefault("FALKORDB_HOST", "localhost");
        falkorPort = Integer.parseInt(System.getenv().getOrDefault("FALKORDB_PORT", String.valueOf(DEFAULT_FALKORDB_PORT)));
    }
    
    @BeforeEach
    public void setUp() throws IOException {
        // Initialize GeoSPARQL - required for the GeoSPARQL layer
        org.apache.jena.geosparql.configuration.GeoSPARQLConfig.setupMemoryIndex();
        
        // Load config-falkordb.ttl from test resources and customize it with dynamic connection
        String configContent = loadAndCustomizeConfig(falkorHost, falkorPort);
        Path configPath = tempDir.resolve("config-falkordb.ttl");
        Files.writeString(configPath, configContent);
        
        // Copy the grandfather forward rule file to a location accessible by the config
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        try (InputStream ruleStream = getClass().getClassLoader()
                .getResourceAsStream("rules/grandfather_of_fwd.rule")) {
            if (ruleStream != null) {
                Files.copy(ruleStream, rulesDir.resolve("grandfather_of_fwd.rule"));
            }
        }
        
        // Start Fuseki server with config file
        server = FusekiServer.create()
            .port(TEST_PORT)
            .parseConfigFile(configPath.toString())
            .build();
        server.start();
        
        // Endpoints based on config file service name "falkor"
        sparqlEndpoint = "http://localhost:" + TEST_PORT + "/falkor/query";
        updateEndpoint = "http://localhost:" + TEST_PORT + "/falkor/update";
        
        // Clear any existing data
        String clearQuery = "DELETE WHERE { ?s ?p ?o }";
        UpdateExecutionHTTP.service(updateEndpoint).update(clearQuery).execute();
    }
    
    /**
     * Load config-falkordb.ttl from resources and customize with dynamic FalkorDB connection.
     */
    private String loadAndCustomizeConfig(String host, int port) throws IOException {
        try (InputStream configStream = getClass().getClassLoader()
                .getResourceAsStream("config-falkordb.ttl")) {
            if (configStream == null) {
                throw new IOException("config-falkordb.ttl not found in test resources");
            }
            String content = new String(configStream.readAllBytes());
            // Customize the host, port, and graph name for testing
            content = content.replace("falkor:host \"localhost\"", "falkor:host \"" + host + "\"");
            content = content.replace("falkor:port 6379", "falkor:port " + port);
            content = content.replace("falkor:graphName \"knowledge_graph\"", 
                                    "falkor:graphName \"test_assembler_graph\"");
            return content;
        }
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
        // Insert data with unique URI to avoid conflicts
        String uniqueId = String.valueOf(System.currentTimeMillis());
        String insertQuery = 
            "PREFIX ex: <http://example.org/updatetest/> " +
            "INSERT DATA { " +
            "  ex:test" + uniqueId + " ex:property \"value\" . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify insert worked
        String selectQuery = 
            "PREFIX ex: <http://example.org/updatetest/> " +
            "SELECT ?value WHERE { ex:test" + uniqueId + " ex:property ?value }";
        
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
        
        // Verify specific relationships exist using ASK queries
        String verifyAbrahamToIsaac = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "ASK { ff:Abraham ff:father_of ff:Isaac }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(verifyAbrahamToIsaac).build()) {
            assertTrue(qexec.execAsk(), "Abraham should be father of Isaac");
        }
        
        String verifyIsaacToJacob = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "ASK { ff:Isaac ff:father_of ff:Jacob }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(verifyIsaacToJacob).build()) {
            assertTrue(qexec.execAsk(), "Isaac should be father of Jacob");
        }
        
        // Verify types were set
        String verifyTypes = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "ASK { " +
            "  ff:Jacob a ff:Male . " +
            "  ff:Isaac a ff:Male . " +
            "  ff:Abraham a ff:Male . " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(verifyTypes).build()) {
            assertTrue(qexec.execAsk(), "All three should be of type Male");
        }
        
        // Test forward chaining inference: grandfather_of should be materialized
        String verifyGrandfather = 
            "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
            "ASK { ff:Abraham ff:grandfather_of ff:Jacob }";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(verifyGrandfather).build()) {
            assertTrue(qexec.execAsk(), "Abraham should be grandfather of Jacob (forward chaining inference)");
        }
    }
    
    @Test
    @DisplayName("Test count query with assembler config")
    public void testCountQueryWithAssemblerConfig() {
        // Get initial count
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        int initialCount;
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            initialCount = results.next().getLiteral("count").getInt();
        }
        
        // Insert some data
        String insertQuery = 
            "PREFIX ex: <http://example.org/counttest/> " +
            "INSERT DATA { " +
            "  ex:a ex:p \"1\" . " +
            "  ex:b ex:p \"2\" . " +
            "  ex:c ex:p \"3\" . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Count triples - should be initial + 3
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            int newCount = results.next().getLiteral("count").getInt();
            assertEquals(initialCount + 3, newCount, "Should have 3 more triples after insert");
        }
    }
}
