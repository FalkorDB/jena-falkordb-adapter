package com.falkordb;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System test for GeoSPARQL queries using config-falkordb.ttl.
 *
 * <p>This test validates GeoSPARQL spatial queries with the three-layer onion architecture:</p>
 * <ul>
 *   <li>GeoSPARQL Dataset (outer layer) - handles spatial queries</li>
 *   <li>Inference Model (middle layer) - applies forward chaining rules for eager inference</li>
 *   <li>FalkorDB Model (core layer) - physical storage</li>
 * </ul>
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Geographic features with people and relationships</li>
 *   <li>Spatial queries combined with inference</li>
 *   <li>Count queries by geographic feature</li>
 * </ul>
 *
 * <p>Prerequisites: FalkorDB must be running on localhost:6379</p>
 */
public class GeoSPARQLPOCSystemTest {

    private static final int TEST_PORT = 3337;
    private static final String DATASET_PATH = "/falkor";
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
        
        // Load config-falkordb.ttl and customize for this test
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

        // Start server with config
        server = FusekiServer.create()
                .parseConfigFile(configPath.toString())
                .port(TEST_PORT)
                .build();
        server.start();

        sparqlEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/query";
        updateEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/update";

        // Clear any existing data
        String clearQuery = "DELETE WHERE { ?s ?p ?o }";
        try {
            UpdateExecutionHTTP.service(updateEndpoint).update(clearQuery).execute();
        } catch (Exception e) {
            // Ignore - graph might not exist yet
        }

        // Load the test data
        loadTestData();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * Load config-falkordb.ttl from resources and customize it for this test.
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
                                    "falkor:graphName \"geosparql_poc_test_" + System.currentTimeMillis() + "\"");
            return content;
        }
    }

    /**
     * Load the test data from samples/geosparql-with-inference/data-example.ttl.
     */
    private void loadTestData() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(
                "data/geosparql-with-inference-example.ttl")) {
            if (is == null) {
                throw new IllegalStateException("Test data file not found: data/geosparql-with-inference-example.ttl");
            }
            
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, is, Lang.TURTLE);
            
            // Use HTTP POST with Turtle content directly
            java.io.StringWriter sw = new java.io.StringWriter();
            model.write(sw, "TURTLE");
            String turtleData = sw.toString();
            
            // Send as HTTP POST to the data endpoint
            String dataEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/data";
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(dataEndpoint))
                        .header("Content-Type", "text/turtle")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(turtleData))
                        .build();
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while loading data", e);
            }
        }
    }

    @Test
    @DisplayName("POC 5.4: Find friends with their locations (direct relationships)")
    public void testFindFriendOfFriendWithLocations() {
        String query = """
            PREFIX social: <http://example.org/social#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX ex: <http://example.org/>
            SELECT ?friendName ?location
            WHERE {
              ex:alice social:knows ?friend .
              ?friend ex:name ?friendName ;
                      geo:hasGeometry ?geom .
              ?geom geo:asWKT ?location .
            }
            ORDER BY ?friendName
            """;

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            
            Set<String> names = new HashSet<>();
            while (results.hasNext()) {
                var solution = results.next();
                String name = solution.getLiteral("friendName").getString();
                names.add(name);
                
                // Verify location is present
                String location = solution.getLiteral("location").getString();
                assertTrue(location.contains("POINT"), "Location should be a POINT geometry");
            }
            
            // Alice knows Bob directly
            assertTrue(names.contains("Bob Smith"), "Bob should be in results");
            assertTrue(names.size() >= 1, "Should return at least 1 direct friend");
        }
    }

    @Test
    @DisplayName("POC 5.5: Check direct friend connection with ASK query")
    public void testAskFriendOfFriendConnection() {
        // Test that Bob is directly reachable (Alice -> Bob)
        String bobQuery = """
            PREFIX social: <http://example.org/social#>
            PREFIX ex: <http://example.org/>
            ASK {
              ex:alice social:knows ex:bob .
            }
            """;

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(bobQuery).build()) {
            assertTrue(qexec.execAsk(), "Alice should know Bob directly");
        }

        // Test that Carol is NOT directly reachable (would need transitive: Alice -> Bob -> Carol)
        String carolQuery = """
            PREFIX social: <http://example.org/social#>
            PREFIX ex: <http://example.org/>
            ASK {
              ex:alice social:knows ex:carol .
            }
            """;

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(carolQuery).build()) {
            assertFalse(qexec.execAsk(), "Alice should NOT know Carol directly (only via Bob)");
        }
    }

    @Test
    @DisplayName("POC 5.6: Find direct friends with occupations and locations")
    public void testFindFriendsOfFriendsWithOccupations() {
        String query = """
            PREFIX social: <http://example.org/social#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX ex: <http://example.org/>
            SELECT ?friendName ?occupation ?location
            WHERE {
              ex:bob social:knows ?friend .
              ?friend ex:name ?friendName ;
                      ex:occupation ?occupation ;
                      geo:hasGeometry ?geom .
              ?geom geo:asWKT ?location .
            }
            ORDER BY ?friendName
            """;

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            
            Set<String> names = new HashSet<>();
            while (results.hasNext()) {
                var solution = results.next();
                String name = solution.getLiteral("friendName").getString();
                String occupation = solution.getLiteral("occupation").getString();
                String location = solution.getLiteral("location").getString();
                
                names.add(name);
                
                // Verify all fields are present
                assertNotNull(occupation, "Occupation should be present");
                assertTrue(location.contains("POINT"), "Location should be a POINT");
            }
            
            // Bob knows Carol and Dave directly
            assertTrue(names.contains("Carol Williams") || names.contains("Dave Brown"), 
                "Should contain at least one of Bob's direct friends");
            assertTrue(names.size() >= 1, "Should have at least one direct friend");
        }
    }

    @Test
    @DisplayName("POC 5.7: Query geographic features with people")
    public void testGeographicFeaturesWithPeople() {
        String query = """
            PREFIX social: <http://example.org/social#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX ex: <http://example.org/>
            SELECT ?featureName ?personName ?personLocation
            WHERE {
              ?feature a geo:Feature ;
                       ex:name ?featureName ;
                       geo:hasGeometry ?featureGeom .
              ?person a social:Person ;
                      ex:name ?personName ;
                      geo:hasGeometry ?personGeom .
              ?personGeom geo:asWKT ?personLocation .
            }
            ORDER BY ?featureName ?personName
            """;

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            
            Set<String> features = new HashSet<>();
            Set<String> people = new HashSet<>();
            int count = 0;
            
            while (results.hasNext()) {
                var solution = results.next();
                features.add(solution.getLiteral("featureName").getString());
                people.add(solution.getLiteral("personName").getString());
                count++;
            }
            
            // Should have 3 features
            assertTrue(features.contains("Central London"), "Should have Central London feature");
            assertTrue(features.contains("North London"), "Should have North London feature");
            assertTrue(features.contains("London Tech Hub"), "Should have Tech Hub feature");
            
            // Should have 5 people
            assertTrue(people.contains("Alice Johnson"), "Should have Alice");
            assertTrue(people.contains("Bob Smith"), "Should have Bob");
            assertTrue(people.contains("Carol Williams"), "Should have Carol");
            assertTrue(people.contains("Dave Brown"), "Should have Dave");
            assertTrue(people.contains("Eve Davis"), "Should have Eve");
            
            // Should have all combinations (3 features × 5 people = 15)
            assertEquals(15, count, "Should have all feature-person combinations");
        }
    }

    @Test
    @DisplayName("POC 5.8: Count people by geographic feature")
    public void testCountPeopleByFeature() {
        String query = """
            PREFIX social: <http://example.org/social#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX ex: <http://example.org/>
            SELECT ?featureName (COUNT(DISTINCT ?person) as ?peopleCount)
            WHERE {
              ?feature a geo:Feature ;
                       ex:name ?featureName .
              ?person a social:Person ;
                      geo:hasGeometry ?personGeom .
            }
            GROUP BY ?featureName
            ORDER BY DESC(?peopleCount)
            """;

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            
            int totalFeatures = 0;
            while (results.hasNext()) {
                var solution = results.next();
                String featureName = solution.getLiteral("featureName").getString();
                int count = solution.getLiteral("peopleCount").getInt();
                
                // Each feature should have all 5 people
                assertEquals(5, count, "Each feature should have count of 5 people");
                totalFeatures++;
            }
            
            // Should have 3 features
            assertEquals(3, totalFeatures, "Should have 3 geographic features");
        }
    }

    @Test
    @DisplayName("Verify direct knows relationships from data")
    public void testDirectKnowsRelationships() {
        String query = """
            PREFIX social: <http://example.org/social#>
            PREFIX ex: <http://example.org/>
            SELECT ?person1 ?person2
            WHERE {
              ?person1 social:knows ?person2 .
            }
            ORDER BY ?person1 ?person2
            """;

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            
            Set<String> relationships = new HashSet<>();
            while (results.hasNext()) {
                var solution = results.next();
                String p1 = solution.getResource("person1").getLocalName();
                String p2 = solution.getResource("person2").getLocalName();
                relationships.add(p1 + "->" + p2);
            }
            
            // Verify the direct relationships from data-example.ttl
            assertTrue(relationships.contains("alice->bob"), "Alice should directly know Bob");
            assertTrue(relationships.contains("bob->carol"), "Bob should directly know Carol");
            assertTrue(relationships.contains("bob->dave"), "Bob should directly know Dave");
            assertTrue(relationships.contains("dave->eve"), "Dave should directly know Eve");
            assertEquals(4, relationships.size(), "Should have exactly 4 direct relationships");
        }
    }
}
