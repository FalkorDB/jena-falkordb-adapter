package com.falkordb;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.fuseki.main.FusekiServer;
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
 * Integration tests for Fuseki server with FalkorDB backend and lazy inference rules.
 *
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Backward chaining (lazy) rules can be applied to infer new triples on-demand</li>
 *   <li>The friend_of_friend rule correctly infers transitive knows relationships</li>
 *   <li>Social network data works with lazy inference</li>
 * </ul>
 *
 * <p>Lazy inference (backward chaining) computes inferences only when queried,
 * rather than materializing all possible inferences upfront. This is more
 * efficient for large datasets.</p>
 *
 * <p>Prerequisites: FalkorDB must be running. Configure via environment variables:</p>
 * <ul>
 *   <li>FALKORDB_HOST (default: localhost)</li>
 *   <li>FALKORDB_PORT (default: 6379)</li>
 * </ul>
 */
public class FusekiLazyInferenceIntegrationTest {

    private static final String TEST_GRAPH = "fuseki_lazy_inference_test_graph";
    private static final int TEST_PORT = 3334;
    private static final String DATASET_PATH = "/social";
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

        // Create dataset from inference model
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
    @DisplayName("Test Fuseki server starts with lazy inference model")
    public void testServerStartsWithLazyInference() {
        assertNotNull(server, "Server should be started");
        assertTrue(server.getPort() > 0, "Server should be running on a port");
    }

    @Test
    @DisplayName("Test friend-of-friend lazy inference from knows relationships")
    public void testFriendOfFriendLazyInference() {
        // Insert social network data: person1 -> person2 -> person3
        String insertQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "INSERT DATA { " +
                        "  social:person1 a social:Person . " +
                        "  social:person2 a social:Person . " +
                        "  social:person3 a social:Person . " +
                        "  social:person1 social:knows social:person2 . " +
                        "  social:person2 social:knows social:person3 . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query for transitive knows relationships (should be lazily inferred by the rule)
        String selectQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "SELECT ?person1 ?person2 " +
                        "WHERE { " +
                        "  ?person1 social:knows_transitively ?person2 . " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Lazy inference should produce friend-of-friend relationship");

            var solution = results.next();
            String person1 = solution.getResource("person1").getURI();
            String person2 = solution.getResource("person2").getURI();

            assertTrue(person1.endsWith("person1"), "person1 should know person3 transitively");
            assertTrue(person2.endsWith("person3"), "person3 should be known transitively");
        }
    }

    @Test
    @DisplayName("Test lazy inference with ASK query")
    public void testLazyInferenceWithAsk() {
        // Insert social network data
        String insertQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "INSERT DATA { " +
                        "  social:person1 a social:Person . " +
                        "  social:person2 a social:Person . " +
                        "  social:person3 a social:Person . " +
                        "  social:person1 social:knows social:person2 . " +
                        "  social:person2 social:knows social:person3 . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Verify transitive relationship is lazily inferred
        String askQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "ASK { social:person1 social:knows_transitively social:person3 }";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(askQuery).build()) {
            assertTrue(qexec.execAsk(), "person1 should transitively know person3 via lazy inference");
        }
    }

    @Test
    @DisplayName("Test multiple friend-of-friend relationships with lazy inference")
    public void testMultipleFriendOfFriendRelationships() {
        // Insert data with multiple potential transitive relationships
        String insertQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "INSERT DATA { " +
                        "  social:alice a social:Person . " +
                        "  social:bob a social:Person . " +
                        "  social:carol a social:Person . " +
                        "  social:dave a social:Person . " +
                        "  social:alice social:knows social:bob . " +
                        "  social:alice social:knows social:carol . " +
                        "  social:bob social:knows social:dave . " +
                        "  social:carol social:knows social:dave . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query for all transitive relationships
        String selectQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "SELECT ?person1 ?person2 " +
                        "WHERE { " +
                        "  ?person1 social:knows_transitively ?person2 . " +
                        "} ORDER BY ?person1 ?person2";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();

            // Alice should transitively know Dave through both Bob and Carol
            Set<String> transitiveRelationships = new HashSet<>();
            while (results.hasNext()) {
                var solution = results.next();
                String from = solution.getResource("person1").getLocalName();
                String to = solution.getResource("person2").getLocalName();
                transitiveRelationships.add(from + "->" + to);
            }

            // Alice -> Dave should be inferred (alice knows bob, bob knows dave)
            // Alice -> Dave should also be inferred (alice knows carol, carol knows dave)
            assertTrue(transitiveRelationships.contains("alice->dave"),
                    "Alice should transitively know Dave");
        }
    }

    @Test
    @DisplayName("Test original knows relationships are preserved")
    public void testOriginalRelationshipsPreserved() {
        // Insert social network data
        String insertQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "INSERT DATA { " +
                        "  social:person1 a social:Person . " +
                        "  social:person2 a social:Person . " +
                        "  social:person3 a social:Person . " +
                        "  social:person1 social:knows social:person2 . " +
                        "  social:person2 social:knows social:person3 . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Verify original knows relationships still exist
        String knowsQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "SELECT ?person1 ?person2 " +
                        "WHERE { " +
                        "  ?person1 social:knows ?person2 . " +
                        "} ORDER BY ?person1";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(knowsQuery).build()) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext(), "Knows relationships should be preserved");

            // Count the results
            int count = 0;
            while (results.hasNext()) {
                results.next();
                count++;
            }
            assertEquals(2, count, "Should have exactly 2 knows relationships");
        }
    }

    @Test
    @DisplayName("Test lazy inference with empty database")
    public void testEmptyDatabaseLazyInference() {
        // Query on empty database should return no results
        String selectQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "SELECT ?person1 ?person2 " +
                        "WHERE { " +
                        "  ?person1 social:knows_transitively ?person2 . " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(), "Empty database should return no inferred results");
        }
    }

    @Test
    @DisplayName("Test that direct knows relationship does not create self-transitive")
    public void testNoSelfTransitive() {
        // Insert direct relationship only
        String insertQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "INSERT DATA { " +
                        "  social:person1 a social:Person . " +
                        "  social:person2 a social:Person . " +
                        "  social:person1 social:knows social:person2 . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query for transitive knows - should have no results since there's no 2-hop path
        String selectQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "SELECT ?person1 ?person2 " +
                        "WHERE { " +
                        "  ?person1 social:knows_transitively ?person2 . " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(),
                    "No transitive relationship should be inferred without 2-hop path");
        }
    }

    @Test
    @DisplayName("Test lazy inference chain - friend of friend of friend")
    public void testInferenceChain() {
        // Insert a chain: A -> B -> C -> D
        String insertQuery =
                "PREFIX social: <http://example.org/social#> " +
                        "INSERT DATA { " +
                        "  social:a a social:Person . " +
                        "  social:b a social:Person . " +
                        "  social:c a social:Person . " +
                        "  social:d a social:Person . " +
                        "  social:a social:knows social:b . " +
                        "  social:b social:knows social:c . " +
                        "  social:c social:knows social:d . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // A should transitively know C (via B)
        String askAC =
                "PREFIX social: <http://example.org/social#> " +
                        "ASK { social:a social:knows_transitively social:c }";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(askAC).build()) {
            assertTrue(qexec.execAsk(), "A should transitively know C");
        }

        // B should transitively know D (via C)
        String askBD =
                "PREFIX social: <http://example.org/social#> " +
                        "ASK { social:b social:knows_transitively social:d }";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(askBD).build()) {
            assertTrue(qexec.execAsk(), "B should transitively know D");
        }
    }
}
