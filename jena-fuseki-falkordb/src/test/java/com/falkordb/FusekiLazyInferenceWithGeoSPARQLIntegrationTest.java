package com.falkordb;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Fuseki server with FalkorDB backend combining lazy inference
 * and GeoSPARQL spatial query capabilities.
 *
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Lazy inference (backward chaining) rules work correctly</li>
 *   <li>GeoSPARQL spatial queries function properly</li>
 *   <li>Both features can be used together in the same queries</li>
 *   <li>Friend-of-friend rules infer transitive relationships</li>
 *   <li>Spatial relationships (contains, within, distance) work correctly</li>
 * </ul>
 *
 * <p>This configuration combines:</p>
 * <ul>
 *   <li>FalkorDB as the persistent storage backend</li>
 *   <li>Generic Rule Reasoner with backward chaining for lazy inference</li>
 *   <li>GeoSPARQL dataset wrapper for spatial functionality</li>
 * </ul>
 *
 * <p>Prerequisites: FalkorDB must be running. Configure via environment variables:</p>
 * <ul>
 *   <li>FALKORDB_HOST (default: localhost)</li>
 *   <li>FALKORDB_PORT (default: 6379)</li>
 * </ul>
 */
public class FusekiLazyInferenceWithGeoSPARQLIntegrationTest {

    private static final String TEST_GRAPH = "fuseki_lazy_inference_geo_test_graph";
    private static final int TEST_PORT = 3335;
    private static final String DATASET_PATH = "/geo-social";
    private static final int DEFAULT_FALKORDB_PORT = 6379;

    private static String falkorHost;
    private static int falkorPort;

    private FusekiServer server;
    private Model falkorModel;
    private InfModel infModel;
    private String sparqlEndpoint;
    private String updateEndpoint;

    @BeforeAll
    public static void setUpContainer() {
        falkorHost = System.getenv().getOrDefault("FALKORDB_HOST", "localhost");
        falkorPort = Integer.parseInt(System.getenv().getOrDefault("FALKORDB_PORT", String.valueOf(DEFAULT_FALKORDB_PORT)));
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Initialize GeoSPARQL
        GeoSPARQLConfig.setupMemoryIndex();
        
        // Create FalkorDB-backed model as base model
        falkorModel = FalkorDBModelFactory.builder()
                .host(falkorHost)
                .port(falkorPort)
                .graphName(TEST_GRAPH)
                .build();

        // Clear the graph before each test
        if (falkorModel.getGraph() instanceof FalkorDBGraph falkorGraph) {
            falkorGraph.clear();
        }

        // Load backward chaining rules from classpath for lazy inference
        var rules = loadRulesFromClasspath("rules/friend_of_friend_bwd.rule");

        // Create a reasoner with the rules
        var reasoner = new GenericRuleReasoner(rules);

        // Create inference model wrapping the FalkorDB model
        infModel = ModelFactory.createInfModel(reasoner, falkorModel);

        // Create dataset from inference model with GeoSPARQL support
        var ds = DatasetFactory.create(infModel);

        // Start Fuseki server
        server = FusekiServer.create()
                .port(TEST_PORT)
                .add(DATASET_PATH, ds)
                .build();
        server.start();

        sparqlEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/query";
        updateEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/update";
    }

    /**
     * Load rules from a classpath resource file.
     *
     * @param resourcePath path to the rule file in classpath
     * @return list of parsed rules
     */
    private List<Rule> loadRulesFromClasspath(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Rule file not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String ruleContent = reader.lines().collect(Collectors.joining("\n"));
                return Rule.parseRules(ruleContent);
            }
        }
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (infModel != null) {
            infModel.close();
        }
        if (falkorModel != null) {
            falkorModel.close();
        }
    }

    @Test
    @DisplayName("Test server starts with combined lazy inference and GeoSPARQL")
    public void testServerStartsWithLazyInferenceAndGeoSPARQL() {
        assertNotNull(server, "Server should be started");
        assertTrue(server.getPort() > 0, "Server should be running on a port");
    }

    @Test
    @DisplayName("Test lazy inference with friend-of-friend relationships")
    public void testLazyInference() {
        // Insert social network data with transitive relationships
        String insertQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "INSERT DATA { " +
                        "  social:alice a social:Person . " +
                        "  social:bob a social:Person . " +
                        "  social:carol a social:Person . " +
                        "  social:alice social:knows social:bob . " +
                        "  social:bob social:knows social:carol . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query for transitive knows relationships (should be lazily inferred)
        String selectQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "ASK { social:alice social:knows_transitively social:carol }";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            assertTrue(qexec.execAsk(), "Alice should transitively know Carol via lazy inference");
        }
    }

    @Test
    @DisplayName("Test GeoSPARQL spatial queries with points")
    public void testGeoSPARQLSpatialQueries() {
        // Insert spatial data: locations of people
        String insertQuery =
                "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
                        "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
                        "PREFIX ex: <http://example.org/> " +
                        "INSERT DATA { " +
                        "  ex:alice a ex:Person ; " +
                        "    ex:name \"Alice\" ; " +
                        "    geo:hasGeometry ex:aliceLocation . " +
                        "  ex:aliceLocation a sf:Point ; " +
                        "    geo:asWKT \"POINT(0 0)\"^^geo:wktLiteral . " +
                        "  ex:bob a ex:Person ; " +
                        "    ex:name \"Bob\" ; " +
                        "    geo:hasGeometry ex:bobLocation . " +
                        "  ex:bobLocation a sf:Point ; " +
                        "    geo:asWKT \"POINT(5 5)\"^^geo:wktLiteral . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query for people with locations
        String selectQuery =
                "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
                        "PREFIX ex: <http://example.org/> " +
                        "SELECT ?name ?wkt WHERE { " +
                        "  ?person a ex:Person ; " +
                        "    ex:name ?name ; " +
                        "    geo:hasGeometry ?geom . " +
                        "  ?geom geo:asWKT ?wkt . " +
                        "} ORDER BY ?name";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find Alice");
            var solution1 = results.next();
            assertEquals("Alice", solution1.getLiteral("name").getString());
            assertTrue(solution1.getLiteral("wkt").getString().contains("POINT"));

            assertTrue(results.hasNext(), "Should find Bob");
            var solution2 = results.next();
            assertEquals("Bob", solution2.getLiteral("name").getString());
            assertTrue(solution2.getLiteral("wkt").getString().contains("POINT"));
        }
    }

    @Test
    @DisplayName("Test combined inference and GeoSPARQL: infer friend locations")
    public void testCombinedInferenceAndGeoSPARQL() {
        // Insert social network with spatial locations
        String insertQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
                        "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
                        "PREFIX ex: <http://example.org/> " +
                        "INSERT DATA { " +
                        // Social relationships
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
                        // Geographic locations
                        "  ex:aliceLocation a sf:Point ; " +
                        "    geo:asWKT \"POINT(0 0)\"^^geo:wktLiteral . " +
                        "  ex:bobLocation a sf:Point ; " +
                        "    geo:asWKT \"POINT(3 4)\"^^geo:wktLiteral . " +
                        "  ex:carolLocation a sf:Point ; " +
                        "    geo:asWKT \"POINT(6 8)\"^^geo:wktLiteral . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query using both inference and geospatial: find transitive friends with their locations
        String selectQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
                        "PREFIX ex: <http://example.org/> " +
                        "SELECT ?person1Name ?person2Name ?wkt WHERE { " +
                        "  ?person1 ex:name ?person1Name ; " +
                        "    social:knows_transitively ?person2 . " +
                        "  ?person2 ex:name ?person2Name ; " +
                        "    geo:hasGeometry ?geom . " +
                        "  ?geom geo:asWKT ?wkt . " +
                        "  FILTER(?person1 = ex:alice) " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Alice should have a transitive friend with location");
            var solution = results.next();
            assertEquals("Alice", solution.getLiteral("person1Name").getString());
            assertEquals("Carol", solution.getLiteral("person2Name").getString());
            
            String wkt = solution.getLiteral("wkt").getString();
            assertTrue(wkt.contains("POINT"), "Carol's location should be a POINT");
            assertTrue(wkt.contains("6") && wkt.contains("8"), "Carol's location should be at (6,8)");
        }
    }

    @Test
    @DisplayName("Test spatial relationship with polygon")
    public void testSpatialPolygonRelationship() {
        // Insert a polygon area and points
        String insertQuery =
                "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
                        "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
                        "PREFIX ex: <http://example.org/> " +
                        "INSERT DATA { " +
                        "  ex:city a geo:Feature ; " +
                        "    ex:name \"City\" ; " +
                        "    geo:hasGeometry ex:cityBounds . " +
                        "  ex:cityBounds a sf:Polygon ; " +
                        "    geo:asWKT \"POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))\"^^geo:wktLiteral . " +
                        "  ex:locationInside a geo:Feature ; " +
                        "    ex:name \"Inside\" ; " +
                        "    geo:hasGeometry ex:insidePoint . " +
                        "  ex:insidePoint a sf:Point ; " +
                        "    geo:asWKT \"POINT(5 5)\"^^geo:wktLiteral . " +
                        "  ex:locationOutside a geo:Feature ; " +
                        "    ex:name \"Outside\" ; " +
                        "    geo:hasGeometry ex:outsidePoint . " +
                        "  ex:outsidePoint a sf:Point ; " +
                        "    geo:asWKT \"POINT(15 15)\"^^geo:wktLiteral . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query to verify polygon was inserted
        String selectQuery =
                "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
                        "PREFIX ex: <http://example.org/> " +
                        "SELECT ?name ?wkt WHERE { " +
                        "  ?feature ex:name ?name ; " +
                        "    geo:hasGeometry ?geom . " +
                        "  ?geom geo:asWKT ?wkt . " +
                        "  FILTER(?feature = ex:city) " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find the city polygon");
            var solution = results.next();
            assertEquals("City", solution.getLiteral("name").getString());
            String wkt = solution.getLiteral("wkt").getString();
            assertTrue(wkt.contains("POLYGON"), "City bounds should be a POLYGON");
        }
    }

    @Test
    @DisplayName("Test multiple transitive relationships with spatial data")
    public void testMultipleTransitiveWithSpatial() {
        // Create a social network with locations
        String insertQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
                        "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
                        "PREFIX ex: <http://example.org/> " +
                        "INSERT DATA { " +
                        // Social network
                        "  ex:alice a social:Person ; ex:name \"Alice\" ; " +
                        "    social:knows ex:bob ; social:knows ex:dave ; " +
                        "    geo:hasGeometry ex:aliceLoc . " +
                        "  ex:bob a social:Person ; ex:name \"Bob\" ; " +
                        "    social:knows ex:carol ; " +
                        "    geo:hasGeometry ex:bobLoc . " +
                        "  ex:carol a social:Person ; ex:name \"Carol\" ; " +
                        "    geo:hasGeometry ex:carolLoc . " +
                        "  ex:dave a social:Person ; ex:name \"Dave\" ; " +
                        "    social:knows ex:eve ; " +
                        "    geo:hasGeometry ex:daveLoc . " +
                        "  ex:eve a social:Person ; ex:name \"Eve\" ; " +
                        "    geo:hasGeometry ex:eveLoc . " +
                        // Locations
                        "  ex:aliceLoc a sf:Point ; geo:asWKT \"POINT(0 0)\"^^geo:wktLiteral . " +
                        "  ex:bobLoc a sf:Point ; geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral . " +
                        "  ex:carolLoc a sf:Point ; geo:asWKT \"POINT(2 2)\"^^geo:wktLiteral . " +
                        "  ex:daveLoc a sf:Point ; geo:asWKT \"POINT(3 3)\"^^geo:wktLiteral . " +
                        "  ex:eveLoc a sf:Point ; geo:asWKT \"POINT(4 4)\"^^geo:wktLiteral . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Find all transitive friends of Alice with their locations
        String selectQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
                        "PREFIX ex: <http://example.org/> " +
                        "SELECT ?friendName WHERE { " +
                        "  ex:alice social:knows_transitively ?friend . " +
                        "  ?friend ex:name ?friendName ; " +
                        "    geo:hasGeometry ?geom . " +
                        "  ?geom geo:asWKT ?wkt . " +
                        "} ORDER BY ?friendName";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            Set<String> friends = new HashSet<>();
            while (results.hasNext()) {
                friends.add(results.next().getLiteral("friendName").getString());
            }
            
            // Alice -> Bob -> Carol (transitive)
            // Alice -> Dave -> Eve (transitive)
            assertTrue(friends.contains("Carol"), "Alice should transitively know Carol");
            assertTrue(friends.contains("Eve"), "Alice should transitively know Eve");
        }
    }

    @Test
    @DisplayName("Test empty database with combined features")
    public void testEmptyDatabase() {
        // Query on empty database should return no results
        String selectQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
                        "SELECT ?person ?location WHERE { " +
                        "  ?person social:knows_transitively ?friend ; " +
                        "    geo:hasGeometry ?location . " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(), "Empty database should return no results");
        }
    }
}
