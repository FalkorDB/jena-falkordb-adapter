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
 * System test for grandfather inference using config-falkordb.ttl.
 *
 * <p>This test demonstrates the complete workflow using the three-layer onion architecture:</p>
 * <ul>
 *   <li>GeoSPARQL Dataset (outer layer) - handles spatial queries</li>
 *   <li>Inference Model (middle layer) - applies forward chaining rules for eager inference</li>
 *   <li>FalkorDB Model (core layer) - physical storage</li>
 * </ul>
 *
 * <p>The test verifies that grandfather_of relationships are eagerly materialized
 * when father_of triples are inserted, using the forward chaining rules from
 * rules/grandfather_of_fwd.rule.</p>
 *
 * <p>Prerequisites: FalkorDB must be running on localhost:6379</p>
 */
public class GrandfatherInferenceSystemTest {

    private static final int TEST_PORT = 3335;
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
        
        // Load config-falkordb.ttl from resources and customize it for this test
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

        // Start Fuseki server with the config file
        server = FusekiServer.create()
                .port(TEST_PORT)
                .parseConfigFile(configPath.toString())
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
    }

    /**
     * Load config-falkordb.ttl from resources and customize it for this test with dynamic settings.
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
                                    "falkor:graphName \"grandfather_test_graph\"");
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
    @DisplayName("System Test: Load fathers_father.ttl and query grandfather_of using config-falkordb.ttl with forward chaining")
    public void testLoadFathersFatherAndQueryGrandfather() {
        // Step 1: Load the fathers_father.ttl data file using SPARQL INSERT
        // This mimics: curl -X POST http://localhost:3330/falkor/data \
        //                -H "Content-Type: text/turtle" \
        //                --data-binary @data/fathers_father_sample.ttl
        
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
                        "  ff:Abraham rdf:type ff:Male ; " +
                        "    ff:father_of ff:Isaac . " +
                        "  ff:Isaac rdf:type ff:Male ; " +
                        "    ff:father_of ff:Jacob . " +
                        "  ff:Jacob rdf:type ff:Male . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Verify data was loaded - check for father_of relationships
        String verifyDataQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?father ?child " +
                        "WHERE { " +
                        "  ?father ff:father_of ?child . " +
                        "} ORDER BY ?father";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(verifyDataQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have father_of relationships loaded");
            
            int count = 0;
            while (results.hasNext()) {
                var solution = results.next();
                count++;
                System.out.println("Father relationship: " + 
                    solution.getResource("father").getLocalName() + " -> " +
                    solution.getResource("child").getLocalName());
            }
            assertEquals(2, count, "Should have 2 father relationships (Abraham->Isaac, Isaac->Jacob)");
        }

        // Step 2: Query for grandfather_of relationships (should be materialized by forward chaining)
        // With forward chaining (eager inference), the grandfather_of triple is materialized
        // immediately when the father_of triples are inserted, so it should exist in the data
        
        String grandfatherQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?grandfather ?grandson " +
                        "WHERE { " +
                        "  ?grandfather ff:grandfather_of ?grandson . " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(grandfatherQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), 
                "Should have materialized grandfather relationship (Abraham grandfather_of Jacob) via forward chaining");

            var solution = results.next();
            String grandfather = solution.getResource("grandfather").getURI();
            String grandson = solution.getResource("grandson").getURI();

            System.out.println("Materialized grandfather relationship: " + grandfather + " -> " + grandson);

            assertTrue(grandfather.endsWith("Abraham"), 
                "Abraham should be materialized as grandfather");
            assertTrue(grandson.endsWith("Jacob"), 
                "Jacob should be materialized as grandson");
            
            assertFalse(results.hasNext(), 
                "Should have exactly one grandfather relationship materialized");
        }
    }

    @Test
    @DisplayName("System Test: Verify ASK query for specific grandfather relationship with FalkorDB backend and forward chaining")
    public void testAskQueryForGrandfatherRelationship() {
        // Insert data
        String insertQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "INSERT DATA { " +
                        "  ff:Abraham ff:father_of ff:Isaac . " +
                        "  ff:Isaac ff:father_of ff:Jacob . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // ASK if Abraham is grandfather of Jacob (should be materialized via forward chaining)
        String askQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "ASK { ff:Abraham ff:grandfather_of ff:Jacob }";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(askQuery).build()) {
            boolean result = qexec.execAsk();
            assertTrue(result, "Abraham should be materialized as grandfather of Jacob using FalkorDB+forward chaining inference");
        }
    }

    @Test
    @DisplayName("System Test: Query all relationships including materialized ones with FalkorDB backend and forward chaining")
    public void testQueryAllRelationshipsIncludingMaterialized() {
        // Insert data
        String insertQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "INSERT DATA { " +
                        "  ff:Abraham ff:father_of ff:Isaac . " +
                        "  ff:Isaac ff:father_of ff:Jacob . " +
                        "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query for both father_of (direct) and grandfather_of (inferred) relationships
        String allRelationshipsQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?person ?relation ?relative " +
                        "WHERE { " +
                        "  { " +
                        "    ?person ff:father_of ?relative . " +
                        "    BIND('father_of' AS ?relation) " +
                        "  } " +
                        "  UNION " +
                        "  { " +
                        "    ?person ff:grandfather_of ?relative . " +
                        "    BIND('grandfather_of' AS ?relation) " +
                        "  } " +
                        "} ORDER BY ?person ?relation";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(allRelationshipsQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            int fatherCount = 0;
            int grandfatherCount = 0;
            
            while (results.hasNext()) {
                var solution = results.next();
                String person = solution.getResource("person").getLocalName();
                String relation = solution.getLiteral("relation").getString();
                String relative = solution.getResource("relative").getLocalName();
                
                System.out.println(person + " " + relation + " " + relative);
                
                if ("father_of".equals(relation)) {
                    fatherCount++;
                } else if ("grandfather_of".equals(relation)) {
                    grandfatherCount++;
                }
            }
            
            assertEquals(2, fatherCount, "Should have 2 direct father relationships stored in FalkorDB");
            assertEquals(1, grandfatherCount, "Should have 1 inferred grandfather relationship");
        }
    }
}
