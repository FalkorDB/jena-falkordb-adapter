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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System tests for Fuseki server using the config-test-falkordb-lazy-inference-with-geosparql.ttl
 * configuration file. These tests verify that the configuration file correctly combines:
 * - FalkorDB backend storage
 * - Lazy inference (backward chaining rules)
 * - GeoSPARQL spatial query capabilities
 *
 * <p>These tests load the actual configuration file from resources and demonstrate
 * real-world usage patterns for querying geospatial data with inference.</p>
 *
 * <p>Prerequisites: FalkorDB must be running. Configure via environment variables:</p>
 * <ul>
 *   <li>FALKORDB_HOST (default: localhost)</li>
 *   <li>FALKORDB_PORT (default: 6379)</li>
 * </ul>
 */
public class FusekiLazyInferenceGeoSPARQLConfigTest {

    private static final int TEST_PORT = 3336;
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
        // Initialize GeoSPARQL - this must be done before creating datasets
        org.apache.jena.geosparql.configuration.GeoSPARQLConfig.setupMemoryIndex();
        
        // Generate config file with dynamic port and FalkorDB connection
        String configContent = generateConfigFile(falkorHost, falkorPort);
        Path configPath = tempDir.resolve("config-test-lazy-inference-geosparql.ttl");
        Files.writeString(configPath, configContent);
        
        // Also create the rules file that the config references
        String rulesContent = loadRulesFromResources();
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Path rulesPath = rulesDir.resolve("friend_of_friend_bwd.rule");
        Files.writeString(rulesPath, rulesContent);
        
        // Start Fuseki server with config file
        server = FusekiServer.create()
            .port(TEST_PORT)
            .parseConfigFile(configPath.toString())
            .build();
        server.start();
        
        // Endpoints based on config file service name "testdb"
        sparqlEndpoint = "http://localhost:" + TEST_PORT + "/testdb/query";
        updateEndpoint = "http://localhost:" + TEST_PORT + "/testdb/update";
        
        // Clear any existing data
        String clearQuery = "DELETE WHERE { ?s ?p ?o }";
        UpdateExecutionHTTP.service(updateEndpoint).update(clearQuery).execute();
    }
    
    /**
     * Generate a Fuseki configuration file matching config-test-falkordb-lazy-inference-with-geosparql.ttl
     * with dynamic connection details.
     */
    private String generateConfigFile(String host, int port) {
        return "# FalkorDB Fuseki Test Configuration with Lazy Inference and GeoSPARQL\n" +
               "#\n" +
               "# This configuration file is used for testing the combination of lazy inference\n" +
               "# rules with GeoSPARQL spatial queries using FalkorDB backend.\n" +
               "\n" +
               "@prefix :        <#> .\n" +
               "@prefix falkor:  <http://falkordb.com/jena/assembler#> .\n" +
               "@prefix fuseki:  <http://jena.apache.org/fuseki#> .\n" +
               "@prefix ja:      <http://jena.hpl.hp.com/2005/11/Assembler#> .\n" +
               "@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n" +
               "@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .\n" +
               "@prefix geosparql: <http://jena.apache.org/geosparql#> .\n" +
               "\n" +
               "# Declare FalkorDBModel as a subclass of ja:Model for assembler compatibility\n" +
               "falkor:FalkorDBModel rdfs:subClassOf ja:Model .\n" +
               "\n" +
               "# Fuseki server configuration\n" +
               "[] rdf:type fuseki:Server ;\n" +
               "   fuseki:services (\n" +
               "     :service\n" +
               "   ) .\n" +
               "\n" +
               "# The service with combined lazy inference and GeoSPARQL\n" +
               ":service rdf:type fuseki:Service ;\n" +
               "    fuseki:name \"testdb\" ;\n" +
               "    \n" +
               "    fuseki:endpoint [\n" +
               "        fuseki:operation fuseki:query ;\n" +
               "        fuseki:name \"sparql\"\n" +
               "    ] ;\n" +
               "    fuseki:endpoint [\n" +
               "        fuseki:operation fuseki:query ;\n" +
               "        fuseki:name \"query\"\n" +
               "    ] ;\n" +
               "    fuseki:endpoint [\n" +
               "        fuseki:operation fuseki:update ;\n" +
               "        fuseki:name \"update\"\n" +
               "    ] ;\n" +
               "    fuseki:endpoint [\n" +
               "        fuseki:operation fuseki:gsp-r ;\n" +
               "        fuseki:name \"get\"\n" +
               "    ] ;\n" +
               "    fuseki:endpoint [\n" +
               "        fuseki:operation fuseki:gsp-rw ;\n" +
               "        fuseki:name \"data\"\n" +
               "    ] ;\n" +
               "    \n" +
               "    fuseki:dataset :dataset_geosparql .\n" +
               "\n" +
               "# GeoSPARQL Dataset Configuration\n" +
               ":dataset_geosparql rdf:type geosparql:GeosparqlDataset ;\n" +
               "    geosparql:inference            true ;\n" +
               "    geosparql:queryRewrite         true ;\n" +
               "    geosparql:indexEnabled         true ;\n" +
               "    geosparql:applyDefaultGeometry false ;\n" +
               "    geosparql:indexSizes           \"-1,-1,-1\" ;\n" +
               "    geosparql:indexExpires         \"5000,5000,5000\" ;\n" +
               "    geosparql:dataset :dataset_rdf .\n" +
               "\n" +
               "# RDF Dataset wrapping the inference model\n" +
               ":dataset_rdf rdf:type ja:RDFDataset ;\n" +
               "    ja:defaultGraph :model_inf .\n" +
               "\n" +
               "# Inference model with Generic Rule Reasoner using backward chaining\n" +
               ":model_inf rdf:type ja:InfModel ;\n" +
               "    ja:baseModel :falkor_db_model ;\n" +
               "    ja:reasoner [\n" +
               "        ja:reasonerURL <http://jena.hpl.hp.com/2003/GenericRuleReasoner> ;\n" +
               "        ja:rulesFrom <file:rules/friend_of_friend_bwd.rule> ;\n" +
               "    ] .\n" +
               "\n" +
               "# FalkorDB-backed model configuration\n" +
               ":falkor_db_model rdf:type falkor:FalkorDBModel ;\n" +
               "    falkor:host \"" + host + "\" ;\n" +
               "    falkor:port " + port + " ;\n" +
               "    falkor:graphName \"test_lazy_inf_geo_cfg_" + System.currentTimeMillis() + "\" .\n";
    }
    
    /**
     * Load the friend_of_friend backward chaining rules from resources.
     */
    private String loadRulesFromResources() throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream("rules/friend_of_friend_bwd.rule")) {
            if (is == null) {
                throw new IllegalArgumentException("Rule file not found in resources");
            }
            return new String(is.readAllBytes());
        }
    }
    
    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }
    
    @Test
    @DisplayName("Test server starts with lazy inference + GeoSPARQL config file")
    public void testServerStartsWithConfigFile() {
        assertNotNull(server, "Server should be started");
        assertEquals(TEST_PORT, server.getPort(), "Server should be running on configured port");
    }
    
    @Test
    @DisplayName("Example: Query people in geographic locations with social network")
    public void testGeospatialDataWithSocialNetwork() {
        // Insert data: people with locations and social connections
        String insertQuery = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            // Social network: Alice knows Bob, Bob knows Carol
            "  ex:alice a social:Person ; " +
            "    ex:name \"Alice\" ; " +
            "    social:knows ex:bob ; " +
            "    geo:hasGeometry ex:aliceLocation . " +
            "  ex:bob a social:Person ; " +
            "    ex:name \"Bob\" ; " +
            "    social:knows ex:carol ; " +
            "    geo:hasGeometry ex:bobLocation . " +
            "  ex:carol a social:Person ; " +
            "    ex:name \"Carol\" ; " +
            "    geo:hasGeometry ex:carolLocation . " +
            // Geographic locations (London area coordinates)
            "  ex:aliceLocation a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.1278 51.5074)\"^^geo:wktLiteral . " +  // London city center
            "  ex:bobLocation a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.1406 51.5155)\"^^geo:wktLiteral . " +    // Kings Cross
            "  ex:carolLocation a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.0759 51.5155)\"^^geo:wktLiteral . " +   // Shoreditch
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query: Find all people Alice knows (directly or transitively) with their locations
        String selectQuery = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?personName ?wkt WHERE { " +
            "  ex:alice social:knows_transitively ?person . " +  // Uses lazy inference
            "  ?person ex:name ?personName ; " +
            "    geo:hasGeometry ?geom . " +                      // GeoSPARQL
            "  ?geom geo:asWKT ?wkt . " +
            "} ORDER BY ?personName";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find Carol via transitive inference");
            var solution = results.next();
            assertEquals("Carol", solution.getLiteral("personName").getString());
            String wkt = solution.getLiteral("wkt").getString();
            assertTrue(wkt.contains("POINT"), "Location should be a point");
            assertTrue(wkt.contains("51.5155"), "Should have Carol's coordinates");
        }
    }
    
    @Test
    @DisplayName("Example: Query locations within a geographic area with friendship inference")
    public void testSpatialAreaWithInferredFriendships() {
        // Insert data: people in various London locations with transitive relationships
        String insertQuery = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            // Define a geographic region (central London area)
            "  ex:centralLondon a geo:Feature ; " +
            "    ex:name \"Central London\" ; " +
            "    geo:hasGeometry ex:centralLondonArea . " +
            "  ex:centralLondonArea a sf:Polygon ; " +
            "    geo:asWKT \"POLYGON((-0.2 51.48, -0.2 51.54, -0.05 51.54, -0.05 51.48, -0.2 51.48))\"^^geo:wktLiteral . " +
            // Social network with 2-hop transitive path: dave -> frank -> eve
            "  ex:dave a social:Person ; " +
            "    ex:name \"Dave\" ; " +
            "    social:knows ex:frank ; " +
            "    geo:hasGeometry ex:daveLocation . " +
            "  ex:frank a social:Person ; " +
            "    ex:name \"Frank\" ; " +
            "    social:knows ex:eve ; " +
            "    geo:hasGeometry ex:frankLocation . " +
            "  ex:eve a social:Person ; " +
            "    ex:name \"Eve\" ; " +
            "    geo:hasGeometry ex:eveLocation . " +
            // Locations
            "  ex:daveLocation a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.1278 51.5074)\"^^geo:wktLiteral . " +  // Inside central London
            "  ex:frankLocation a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.1150 51.5100)\"^^geo:wktLiteral . " +  // Inside central London
            "  ex:eveLocation a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.0876 51.5152)\"^^geo:wktLiteral . " +  // Inside central London
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query: Find people Dave knows (transitively) who are in central London
        // This combines inference AND spatial queries
        String selectQuery = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?friendName WHERE { " +
            "  ex:dave social:knows_transitively ?friend . " +
            "  ?friend ex:name ?friendName ; " +
            "    geo:hasGeometry ?friendGeom . " +
            "  ?friendGeom geo:asWKT ?friendWkt . " +
            "} ORDER BY ?friendName";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find Eve via transitive inference (dave->frank->eve)");
            var solution = results.next();
            assertEquals("Eve", solution.getLiteral("friendName").getString());
        }
    }
    
    @Test
    @DisplayName("Example: Find nearby people in extended social network")
    public void testNearbyPeopleInSocialNetwork() {
        // Insert data: cluster of friends in close proximity
        String insertQuery = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            // Social network chain: Frank -> Grace -> Henry
            "  ex:frank a social:Person ; " +
            "    ex:name \"Frank\" ; " +
            "    social:knows ex:grace ; " +
            "    geo:hasGeometry ex:frankLocation . " +
            "  ex:grace a social:Person ; " +
            "    ex:name \"Grace\" ; " +
            "    social:knows ex:henry ; " +
            "    geo:hasGeometry ex:graceLocation . " +
            "  ex:henry a social:Person ; " +
            "    ex:name \"Henry\" ; " +
            "    geo:hasGeometry ex:henryLocation . " +
            // All in Camden area (North London), close together
            "  ex:frankLocation a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.1426 51.5390)\"^^geo:wktLiteral . " +  // Camden Town
            "  ex:graceLocation a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.1470 51.5413)\"^^geo:wktLiteral . " +  // Near Chalk Farm
            "  ex:henryLocation a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.1455 51.5450)\"^^geo:wktLiteral . " +  // Belsize Park
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query: Find transitive friends with their locations
        String selectQuery = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?person1Name ?person2Name ?loc1 ?loc2 WHERE { " +
            "  ?person1 ex:name ?person1Name ; " +
            "    social:knows_transitively ?person2 ; " +  // Lazy inference
            "    geo:hasGeometry ?geom1 . " +
            "  ?person2 ex:name ?person2Name ; " +
            "    geo:hasGeometry ?geom2 . " +
            "  ?geom1 geo:asWKT ?loc1 . " +               // GeoSPARQL
            "  ?geom2 geo:asWKT ?loc2 . " +
            "  FILTER(?person1 = ex:frank) " +
            "} ORDER BY ?person2Name";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find Henry via transitive inference");
            var solution = results.next();
            assertEquals("Frank", solution.getLiteral("person1Name").getString());
            assertEquals("Henry", solution.getLiteral("person2Name").getString());
            
            String loc1 = solution.getLiteral("loc1").getString();
            String loc2 = solution.getLiteral("loc2").getString();
            assertTrue(loc1.contains("POINT"), "Frank's location should be a point");
            assertTrue(loc2.contains("POINT"), "Henry's location should be a point");
        }
    }
    
    @Test
    @DisplayName("Example: Complex query with multiple spatial features and inference")
    public void testComplexSpatialAndInferenceQuery() {
        // Insert data: mix of polygons and points with social connections
        String insertQuery = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            // Geographic regions
            "  ex:westminster a geo:Feature ; " +
            "    ex:name \"Westminster\" ; " +
            "    geo:hasGeometry ex:westminsterArea . " +
            "  ex:westminsterArea a sf:Polygon ; " +
            "    geo:asWKT \"POLYGON((-0.15 51.49, -0.15 51.51, -0.11 51.51, -0.11 51.49, -0.15 51.49))\"^^geo:wktLiteral . " +
            // People with social connections
            "  ex:ian a social:Person ; " +
            "    ex:name \"Ian\" ; " +
            "    ex:workplace \"Westminster Office\" ; " +
            "    social:knows ex:jane ; " +
            "    geo:hasGeometry ex:ianHome . " +
            "  ex:jane a social:Person ; " +
            "    ex:name \"Jane\" ; " +
            "    ex:workplace \"Remote\" ; " +
            "    geo:hasGeometry ex:janeHome . " +
            // Home locations
            "  ex:ianHome a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.13 51.50)\"^^geo:wktLiteral . " +  // Inside Westminster
            "  ex:janeHome a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.18 51.52)\"^^geo:wktLiteral . " +  // Outside Westminster
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query: Find geographic features and people's locations
        String selectQuery = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?featureName ?personName ?personLoc WHERE { " +
            "  ?feature a geo:Feature ; " +
            "    ex:name ?featureName ; " +
            "    geo:hasGeometry ?featureGeom . " +
            "  ?person a social:Person ; " +
            "    ex:name ?personName ; " +
            "    geo:hasGeometry ?personGeom . " +
            "  ?personGeom geo:asWKT ?personLoc . " +
            "} ORDER BY ?featureName ?personName";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find features and people");
            int count = 0;
            while (results.hasNext()) {
                var solution = results.next();
                assertNotNull(solution.getLiteral("featureName"));
                assertNotNull(solution.getLiteral("personName"));
                assertNotNull(solution.getLiteral("personLoc"));
                count++;
            }
            // Westminster feature with 2 people = 2 results
            assertEquals(2, count, "Should have 2 combinations of feature and person");
        }
    }
}
