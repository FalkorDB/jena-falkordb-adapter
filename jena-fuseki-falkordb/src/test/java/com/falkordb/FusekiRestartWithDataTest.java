package com.falkordb;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System test that reproduces the restart issue when there's already data in FalkorDB.
 * 
 * <p>This test verifies the fix for the issue where restarting Fuseki server
 * with existing data in FalkorDB results in a GeoSPARQL spatial index error:</p>
 * <pre>
 * org.apache.jena.datatypes.DatatypeFormatException: Unrecognised Geometry Datatype: 
 * http://www.w3.org/2001/XMLSchema#string Ensure that Datatype is extending GeometryDatatype.
 * </pre>
 *
 * <p>The issue occurs because:</p>
 * <ul>
 *   <li>GeoSPARQL's spatial index tries to build from existing data</li>
 *   <li>It encounters non-geometry literals (e.g., xsd:string) in the data</li>
 *   <li>It expects only geometry datatypes</li>
 * </ul>
 *
 * <p>Test scenario:</p>
 * <ol>
 *   <li>Start Fuseki server with config-falkordb.ttl</li>
 *   <li>Add mixed data (geometry and non-geometry literals)</li>
 *   <li>Stop server</li>
 *   <li>Restart server - should succeed without errors</li>
 *   <li>Verify data is still accessible</li>
 * </ol>
 *
 * <p>Prerequisites: FalkorDB must be running on localhost:6379</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FusekiRestartWithDataTest {

    private static final int TEST_PORT = 3338;
    private static final String DATASET_PATH = "/falkor";
    private static final int DEFAULT_FALKORDB_PORT = 6379;
    
    @TempDir
    static Path tempDir;
    
    private static String falkorHost;
    private static int falkorPort;
    private static String graphName;
    private static String sparqlEndpoint;
    private static String updateEndpoint;
    
    private FusekiServer server;
    
    @BeforeAll
    public static void setUpClass() {
        falkorHost = System.getenv().getOrDefault("FALKORDB_HOST", "localhost");
        falkorPort = Integer.parseInt(System.getenv().getOrDefault("FALKORDB_PORT", 
            String.valueOf(DEFAULT_FALKORDB_PORT)));
        graphName = "fuseki_restart_test_" + System.currentTimeMillis();
        
        sparqlEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/query";
        updateEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/update";
    }
    
    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
    
    @AfterAll
    public static void cleanUpClass() {
        // Clean up the test graph in FalkorDB
        try {
            UpdateExecutionHTTP.service(updateEndpoint)
                .update("DELETE WHERE { ?s ?p ?o }")
                .execute();
        } catch (Exception e) {
            // Ignore - server may already be down
        }
    }
    
    /**
     * Test 1: Start server, add data with mixed literals.
     * This simulates the initial state where data exists.
     */
    @Test
    @Order(1)
    @DisplayName("Step 1: Start server and add mixed data (geometry + non-geometry)")
    public void test01_addDataWithMixedLiterals() throws IOException {
        // Initialize GeoSPARQL
        org.apache.jena.geosparql.configuration.GeoSPARQLConfig.setupMemoryIndex();
        
        // Start server with config
        server = startFusekiServer();
        
        // Clear any existing data
        clearData();
        
        // Add mixed data: some with geometry, some with regular strings/numbers
        String testData = """
            @prefix ex: <http://example.org/> .
            @prefix geo: <http://www.opengis.net/ont/geosparql#> .
            @prefix sf: <http://www.opengis.net/ont/sf#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            
            # Person with regular string literal
            ex:alice a foaf:Person ;
                foaf:name "Alice" ;
                foaf:age 30 ;
                ex:description "A software engineer living in San Francisco" .
            
            # Person with number literal
            ex:bob a foaf:Person ;
                foaf:name "Bob" ;
                foaf:age 35 ;
                ex:salary 120000 .
            
            # Location with geometry
            ex:sanfrancisco a ex:City ;
                ex:cityName "San Francisco" ;
                ex:population 873965 ;
                geo:hasGeometry [
                    a sf:Point ;
                    geo:asWKT "POINT(-122.4194 37.7749)"^^geo:wktLiteral
                ] .
            
            # Building with geometry
            ex:officeBuilding a ex:Building ;
                ex:buildingName "Tech Tower" ;
                ex:floors 25 ;
                geo:hasGeometry [
                    a sf:Point ;
                    geo:asWKT "POINT(-122.3895 37.7937)"^^geo:wktLiteral
                ] .
            """;
        
        // Load the test data
        loadData(testData);
        
        // Verify data was loaded
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (var qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            var results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have results");
            var solution = results.nextSolution();
            int count = solution.getLiteral("count").getInt();
            assertTrue(count > 10, "Should have loaded multiple triples, got: " + count);
        }
        
        // Verify we can query regular literals
        String nameQuery = "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
                          "SELECT ?name WHERE { ?person foaf:name ?name }";
        try (var qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(nameQuery).build()) {
            var results = qexec.execSelect();
            int nameCount = 0;
            while (results.hasNext()) {
                results.nextSolution();
                nameCount++;
            }
            assertEquals(2, nameCount, "Should have found 2 people with names");
        }
    }
    
    /**
     * Test 2: Restart server with existing data.
     * This is the critical test - server should start without errors.
     */
    @Test
    @Order(2)
    @DisplayName("Step 2: Restart server with existing data - should succeed")
    public void test02_restartServerWithExistingData() throws IOException {
        // Initialize GeoSPARQL
        org.apache.jena.geosparql.configuration.GeoSPARQLConfig.setupMemoryIndex();
        
        // Restart server - this is where the bug would occur
        assertDoesNotThrow(() -> {
            server = startFusekiServer();
        }, "Server should start successfully even with existing data in FalkorDB");
        
        // Verify server is running
        assertNotNull(server, "Server should be running");
        assertTrue(server.getDataAccessPointRegistry().size() > 0, 
            "Server should have registered datasets");
    }
    
    /**
     * Test 3: Verify data is still accessible after restart.
     */
    @Test
    @Order(3)
    @DisplayName("Step 3: Verify data is still accessible after restart")
    public void test03_verifyDataAfterRestart() throws IOException {
        // Initialize GeoSPARQL
        org.apache.jena.geosparql.configuration.GeoSPARQLConfig.setupMemoryIndex();
        
        // Start server
        server = startFusekiServer();
        
        // Query data to verify it's still there
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (var qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            var results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have results");
            var solution = results.nextSolution();
            int count = solution.getLiteral("count").getInt();
            assertTrue(count > 10, "Data should still be present after restart, got: " + count);
        }
        
        // Verify we can still query regular literals
        String nameQuery = "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
                          "SELECT ?name WHERE { ?person foaf:name ?name }";
        try (var qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(nameQuery).build()) {
            var results = qexec.execSelect();
            int nameCount = 0;
            while (results.hasNext()) {
                results.nextSolution();
                nameCount++;
            }
            assertEquals(2, nameCount, "Should still have 2 people with names");
        }
        
        // Verify we can query geometry data
        String geoQuery = """
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            SELECT ?feature ?geom WHERE {
                ?feature geo:hasGeometry ?geometry .
                ?geometry geo:asWKT ?geom .
            }
            """;
        try (var qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(geoQuery).build()) {
            var results = qexec.execSelect();
            int geoCount = 0;
            while (results.hasNext()) {
                results.nextSolution();
                geoCount++;
            }
            assertEquals(2, geoCount, "Should have 2 geometries");
        }
    }
    
    /**
     * Start Fuseki server with config-falkordb.ttl.
     */
    private FusekiServer startFusekiServer() throws IOException {
        // Load and customize config
        String configContent = loadAndCustomizeConfig(falkorHost, falkorPort, graphName);
        Path configPath = tempDir.resolve("config-falkordb-restart-test.ttl");
        Files.writeString(configPath, configContent);
        
        // Copy the grandfather forward rule file
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        try (InputStream ruleStream = getClass().getClassLoader()
                .getResourceAsStream("rules/grandfather_of_fwd.rule")) {
            if (ruleStream != null) {
                Files.copy(ruleStream, rulesDir.resolve("grandfather_of_fwd.rule"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        
        // Start server
        return FusekiServer.create()
                .parseConfigFile(configPath.toString())
                .port(TEST_PORT)
                .build()
                .start();
    }
    
    /**
     * Load config-falkordb.ttl and customize it for this test.
     */
    private String loadAndCustomizeConfig(String host, int port, String graph) throws IOException {
        try (InputStream configStream = getClass().getClassLoader()
                .getResourceAsStream("config-falkordb.ttl")) {
            if (configStream == null) {
                throw new IOException("config-falkordb.ttl not found in test resources");
            }
            String content = new String(configStream.readAllBytes());
            // Customize settings
            content = content.replace("falkor:host \"localhost\"", "falkor:host \"" + host + "\"");
            content = content.replace("falkor:port 6379", "falkor:port " + port);
            content = content.replace("falkor:graphName \"knowledge_graph\"", 
                                    "falkor:graphName \"" + graph + "\"");
            return content;
        }
    }
    
    /**
     * Load RDF data into the dataset.
     */
    private void loadData(String turtleData) {
        Model model = ModelFactory.createDefaultModel();
        try (var reader = new java.io.StringReader(turtleData)) {
            RDFDataMgr.read(model, reader, null, Lang.TURTLE);
        }
        
        // Convert to string and POST
        StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, model, Lang.TURTLE);
        
        UpdateExecutionHTTP.service(updateEndpoint)
            .update("INSERT DATA { " + writer.toString() + " }")
            .execute();
    }
    
    /**
     * Clear all data from the dataset.
     */
    private void clearData() {
        try {
            UpdateExecutionHTTP.service(updateEndpoint)
                .update("DELETE WHERE { ?s ?p ?o }")
                .execute();
        } catch (Exception e) {
            // Ignore - graph might not exist yet
        }
    }
}
