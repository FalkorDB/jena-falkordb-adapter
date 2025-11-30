package com.falkordb;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Fuseki server with FalkorDB backend and inference rules.
 *
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Rules can be applied to infer new triples</li>
 *   <li>The grandfather_of rule correctly infers grandfather relationships</li>
 *   <li>Data from fathers_father.ttl works with inference</li>
 * </ul>
 *
 * <p>Uses Testcontainers to automatically start FalkorDB if not already running.</p>
 */
@Testcontainers
public class FusekiInferenceIntegrationTest {

    private static final String TEST_GRAPH = "fuseki_inference_test_graph";
    private static final int TEST_PORT = 3333;
    private static final String DATASET_PATH = "/falkor";
    private static final int FALKORDB_PORT = 6379;

    @Container
    private static final GenericContainer<?> falkordb = new GenericContainer<>(
            DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(FALKORDB_PORT);

    private static String falkorHost;
    private static int falkorPort;

    private FusekiServer server;
    private Model falkorModel;
    private InfModel infModel;
    private String sparqlEndpoint;
    private String updateEndpoint;

    @BeforeAll
    public static void setUpContainer() {
        falkorHost = falkordb.getHost();
        falkorPort = falkordb.getMappedPort(FALKORDB_PORT);
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
        if (falkorModel.getGraph() instanceof FalkorDBGraph) {
            ((FalkorDBGraph) falkorModel.getGraph()).clear();
        }

        // Load rules from classpath
        List<Rule> rules = loadRulesFromClasspath("rules/grandfather_of_bwd.rule");

        // Create a reasoner with the rules
        Reasoner reasoner = new GenericRuleReasoner(rules);

        // Create inference model wrapping the FalkorDB model
        infModel = ModelFactory.createInfModel(reasoner, falkorModel);

        // Create dataset from inference model
        Dataset ds = DatasetFactory.create(infModel);

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
    @DisplayName("Test Fuseki server starts with inference model")
    public void testServerStartsWithInference() {
        assertNotNull(server, "Server should be started");
        assertTrue(server.getPort() > 0, "Server should be running on a port");
    }

    @Test
    @DisplayName("Test grandfather inference from father relationships")
    public void testGrandfatherInference() {
        // Insert father-son relationship data: Abraham -> Isaac -> Jacob
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

        // Query for grandfather relationships (should be inferred by the rule)
        String selectQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?grandfather ?grandson " +
                        "WHERE { " +
                        "  ?grandfather ff:grandfather_of ?grandson . " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Inference should produce grandfather relationship");

            var solution = results.next();
            String grandfather = solution.getResource("grandfather").getURI();
            String grandson = solution.getResource("grandson").getURI();

            assertTrue(grandfather.endsWith("Abraham"), "Abraham should be grandfather");
            assertTrue(grandson.endsWith("Jacob"), "Jacob should be grandson");
        }
    }

    @Test
    @DisplayName("Test grandfather inference with ASK query")
    public void testGrandfatherInferenceWithAsk() {
        // Insert father-son relationship data
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

        // Verify grandfather relationship is inferred
        String askQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "ASK { ff:Abraham ff:grandfather_of ff:Jacob }";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(askQuery).build()) {
            assertTrue(qexec.execAsk(), "Abraham should be inferred as grandfather of Jacob");
        }
    }

    @Test
    @DisplayName("Test multiple generations of grandfather inference")
    public void testMultipleGenerationsInference() {
        // Insert four generations: GreatGrandfather -> Grandfather -> Father -> Son
        String insertQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "INSERT DATA { " +
                        "  ff:David a ff:Male . " +
                        "  ff:Solomon a ff:Male ; " +
                        "    ff:father_of ff:David . " +
                        "  ff:Jesse a ff:Male ; " +
                        "    ff:father_of ff:Solomon . " +
                        "  ff:Obed a ff:Male ; " +
                        "    ff:father_of ff:Jesse . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query for all grandfather relationships
        String selectQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?grandfather ?grandson " +
                        "WHERE { " +
                        "  ?grandfather ff:grandfather_of ?grandson . " +
                        "} ORDER BY ?grandfather ?grandson";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();

            // Should have 2 grandfather relationships inferred:
            // Jesse grandfather_of David (Jesse -> Solomon -> David)
            // Obed grandfather_of Solomon (Obed -> Jesse -> Solomon)
            // Note: Obed is great-grandfather of David (not grandfather), so no direct relationship
            int count = 0;
            while (results.hasNext()) {
                results.next();
                count++;
            }
            assertTrue(count >= 2, "Should infer at least 2 grandfather relationships");
        }
    }

    @Test
    @DisplayName("Test original father relationships are preserved")
    public void testOriginalRelationshipsPreserved() {
        // Insert father-son relationship data
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

        // Verify original father relationships still exist
        String fatherQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?father ?son " +
                        "WHERE { " +
                        "  ?father ff:father_of ?son . " +
                        "} ORDER BY ?father";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(fatherQuery).build()) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext(), "Father relationships should be preserved");

            // Count the results
            int count = 0;
            while (results.hasNext()) {
                results.next();
                count++;
            }
            assertEquals(2, count, "Should have exactly 2 father relationships");
        }
    }

    @Test
    @DisplayName("Test loading fathers_father.ttl data file structure")
    public void testFathersFatherDataStructure() {
        // Insert data similar to the fathers_father.ttl ontology structure
        String insertQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "PREFIX owl: <http://www.w3.org/2002/07/owl#> " +
                        "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                        "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
                        "INSERT DATA { " +
                        "  ff:father_of rdf:type owl:ObjectProperty ; " +
                        "    rdfs:domain ff:Male ; " +
                        "    rdfs:range ff:Male . " +
                        "  ff:grandfather_of rdf:type owl:ObjectProperty ; " +
                        "    rdfs:domain ff:Male ; " +
                        "    rdfs:range ff:Male . " +
                        "  ff:Male rdf:type owl:Class . " +
                        "  ff:Jacob a ff:Male . " +
                        "  ff:Isaac a ff:Male ; " +
                        "    ff:father_of ff:Jacob . " +
                        "  ff:Abraham a ff:Male ; " +
                        "    ff:father_of ff:Isaac . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query for grandfather relationship
        String grandfatherQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "ASK { ff:Abraham ff:grandfather_of ff:Jacob }";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(grandfatherQuery).build()) {
            assertTrue(qexec.execAsk(), "Abraham should be inferred as grandfather of Jacob");
        }

        // Query for all Males
        String malesQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?male WHERE { ?male a ff:Male } ORDER BY ?male";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(malesQuery).build()) {
            ResultSet results = qexec.execSelect();
            int count = 0;
            while (results.hasNext()) {
                results.next();
                count++;
            }
            assertEquals(3, count, "Should have exactly 3 Males: Abraham, Isaac, Jacob");
        }
    }

    @Test
    @DisplayName("Test inference with empty database")
    public void testEmptyDatabaseInference() {
        // Query on empty database should return no results
        String selectQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?grandfather ?grandson " +
                        "WHERE { " +
                        "  ?grandfather ff:grandfather_of ?grandson . " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(), "Empty database should return no inferred results");
        }
    }
}
