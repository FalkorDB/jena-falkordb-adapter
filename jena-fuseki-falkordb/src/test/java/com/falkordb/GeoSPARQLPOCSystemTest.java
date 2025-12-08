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
 * System test for GeoSPARQL queries from POC.md section 5.
 *
 * <p>This test validates all the working queries from POC.md section 5 (GeoSPARQL with Lazy Inference):</p>
 * <ul>
 *   <li>5.4: Find friend-of-friend with their locations</li>
 *   <li>5.5: Check friend-of-friend connection (ASK query)</li>
 *   <li>5.6: Find friends-of-friends with occupations and locations</li>
 *   <li>5.7: Geographic features with people</li>
 *   <li>5.8: Count people by geographic feature</li>
 * </ul>
 *
 * <p>This test uses config-falkordb-lazy-inference-with-geosparql.ttl which provides:</p>
 * <ul>
 *   <li>FalkorDB as the backend storage</li>
 *   <li>Lazy inference with friend-of-friend rules (2-hop relationships)</li>
 *   <li>GeoSPARQL spatial query capabilities</li>
 * </ul>
 *
 * <p>Prerequisites: FalkorDB must be running on localhost:6379</p>
 */
public class GeoSPARQLPOCSystemTest {

    private static final int TEST_PORT = 3336;
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
        // Load and customize config for this test
        String configContent = loadAndCustomizeConfig(falkorHost, falkorPort);
        Path configPath = tempDir.resolve("config-test-geosparql-poc.ttl");
        Files.writeString(configPath, configContent);

        // Copy the friend_of_friend rule file to a location accessible by the config
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        try (InputStream ruleStream = getClass().getClassLoader()
                .getResourceAsStream("rules/friend_of_friend_bwd.rule")) {
            if (ruleStream != null) {
                Files.copy(ruleStream, rulesDir.resolve("friend_of_friend_bwd.rule"));
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
     * Load and customize the config file for this test.
     */
    private String loadAndCustomizeConfig(String host, int port) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(
                "config-falkordb-lazy-inference-with-geosparql.ttl")) {
            if (is == null) {
                throw new IllegalStateException("Config file not found");
            }
            String config = new String(is.readAllBytes());
            
            // Customize for this test
            config = config.replace("\"localhost\"", "\"" + host + "\"");
            config = config.replace("6379", String.valueOf(port));
            config = config.replace("knowledge_graph_geo", "geosparql_poc_test_" + System.currentTimeMillis());
            
            return config;
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
    @DisplayName("POC 5.4: Find friend-of-friend with their locations")
    public void testFindFriendOfFriendWithLocations() {
        String query = """
            PREFIX social: <http://example.org/social#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX ex: <http://example.org/>
            SELECT ?friendName ?location
            WHERE {
              ex:alice social:knows_transitively ?friend .
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
            
            // Alice knows Bob, Bob knows Carol and Dave
            // So Alice's friends-of-friends (2-hop) are Carol and Dave
            assertTrue(names.contains("Carol Williams"), "Carol should be in results");
            assertTrue(names.contains("Dave Brown"), "Dave should be in results");
            assertEquals(2, names.size(), "Should return exactly 2 friends-of-friends");
        }
    }

    @Test
    @DisplayName("POC 5.5: Check friend-of-friend connection with ASK query")
    public void testAskFriendOfFriendConnection() {
        // Test that Carol is reachable (Alice -> Bob -> Carol)
        String carolQuery = """
            PREFIX social: <http://example.org/social#>
            PREFIX ex: <http://example.org/>
            ASK {
              ex:alice social:knows_transitively ex:carol .
            }
            """;

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(carolQuery).build()) {
            assertTrue(qexec.execAsk(), "Alice should transitively know Carol (2 hops)");
        }

        // Test that Eve is NOT reachable in 2 hops (would need 3: Alice -> Bob -> Dave -> Eve)
        String eveQuery = """
            PREFIX social: <http://example.org/social#>
            PREFIX ex: <http://example.org/>
            ASK {
              ex:alice social:knows_transitively ex:eve .
            }
            """;

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(eveQuery).build()) {
            assertFalse(qexec.execAsk(), "Alice should NOT transitively know Eve (requires 3 hops, rule only supports 2)");
        }
    }

    @Test
    @DisplayName("POC 5.6: Find friends-of-friends with occupations and locations")
    public void testFindFriendsOfFriendsWithOccupations() {
        String query = """
            PREFIX social: <http://example.org/social#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX ex: <http://example.org/>
            SELECT ?friendName ?occupation ?location
            WHERE {
              ex:bob social:knows_transitively ?friend .
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
            
            // Bob knows Carol and Dave directly (not transitively inferred)
            // Bob -> Dave -> Eve (2-hop, transitively inferred)
            assertTrue(names.contains("Eve Davis"), "Eve should be in results as friend-of-friend");
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
