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
import org.apache.jena.riot.RDFDataMgr;
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
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System test for grandfather inference using config-falkordb-lazy-inference.ttl pattern.
 *
 * <p>This test demonstrates the complete workflow from POC.md:</p>
 * <ul>
 *   <li>Starting a Fuseki server with FalkorDB backend and lazy inference</li>
 *   <li>Loading fathers_father_sample.ttl data file</li>
 *   <li>Querying for grandfather_of relationships using backward chaining rules</li>
 * </ul>
 *
 * <p>This test addresses the issue where the user ran:</p>
 * <pre>
 * curl -G http://localhost:3330/falkor/query \
 *   -H "Accept: application/sparql-results+json" \
 *   --data-urlencode 'query=
 * PREFIX ff: http://www.semanticweb.org/ontologies/2023/1/fathers_father#
 * SELECT ?grandfather ?grandson
 * WHERE {
 *   ?grandfather ff:grandfather_of ?grandson .
 * }'
 * </pre>
 * and got empty results.
 *
 * <p>Prerequisites: FalkorDB must be running on localhost:6379</p>
 */
public class GrandfatherInferenceSystemTest {

    private static final String TEST_GRAPH = "grandfather_system_test_graph";
    private static final int TEST_PORT = 3335;
    private static final String DATASET_PATH = "/falkor";
    private static final int DEFAULT_FALKORDB_PORT = 6379;

    private static String falkorHost;
    private static int falkorPort;

    private FusekiServer server;
    private Model falkorModel;
    private InfModel infModel;
    private String sparqlEndpoint;
    private String updateEndpoint;
    private String dataEndpoint;

    @BeforeAll
    public static void setUpContainer() {
        falkorHost = System.getenv().getOrDefault("FALKORDB_HOST", "localhost");
        falkorPort = Integer.parseInt(System.getenv().getOrDefault("FALKORDB_PORT", String.valueOf(DEFAULT_FALKORDB_PORT)));
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Create FalkorDB-backed model as base model (mimicking config-falkordb-lazy-inference.ttl)
        falkorModel = FalkorDBModelFactory.builder()
                .host(falkorHost)
                .port(falkorPort)
                .graphName(TEST_GRAPH)
                .build();

        // Clear the graph before each test
        if (falkorModel.getGraph() instanceof FalkorDBGraph falkorGraph) {
            falkorGraph.clear();
        }

        // Load backward chaining rules for grandfather inference
        var rules = loadRulesFromClasspath("rules/grandfather_of_bwd.rule");

        // Create a Generic Rule Reasoner with backward chaining (lazy inference)
        var reasoner = new GenericRuleReasoner(rules);

        // Create inference model wrapping the FalkorDB model
        infModel = ModelFactory.createInfModel(reasoner, falkorModel);

        // Create dataset from inference model
        var ds = DatasetFactory.create(infModel);

        // Start Fuseki server (mimicking the server configuration)
        server = FusekiServer.create()
                .port(TEST_PORT)
                .add(DATASET_PATH, ds)
                .build();
        server.start();

        sparqlEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/query";
        updateEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/update";
        dataEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/data";
    }

    /**
     * Load rules from a classpath resource file.
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
    @DisplayName("System Test: Load fathers_father_sample.ttl and query grandfather_of with lazy inference")
    public void testLoadFathersFatherSampleAndQueryGrandfather() throws Exception {
        // Step 1: Load the fathers_father_sample.ttl data file
        // This mimics: curl -X POST http://localhost:3330/falkor/data \
        //                -H "Content-Type: text/turtle" \
        //                --data-binary @data/fathers_father_sample.ttl
        
        try (InputStream dataStream = getClass().getClassLoader()
                .getResourceAsStream("data/fathers_father.ttl")) {
            assertNotNull(dataStream, "fathers_father.ttl should be available in test resources");
            
            // Read the data file into the inference model
            RDFDataMgr.read(infModel, dataStream, null, org.apache.jena.riot.Lang.TURTLE);
        }

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

        // Step 2: Query for grandfather_of relationships (should be inferred by the rule)
        // This mimics the exact query the user ran:
        // curl -G http://localhost:3330/falkor/query \
        //   -H "Accept: application/sparql-results+json" \
        //   --data-urlencode 'query=
        // PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
        // SELECT ?grandfather ?grandson
        // WHERE {
        //   ?grandfather ff:grandfather_of ?grandson .
        // }'
        
        String grandfatherQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?grandfather ?grandson " +
                        "WHERE { " +
                        "  ?grandfather ff:grandfather_of ?grandson . " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(grandfatherQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), 
                "Should infer grandfather relationship (Abraham grandfather_of Jacob)");

            var solution = results.next();
            String grandfather = solution.getResource("grandfather").getURI();
            String grandson = solution.getResource("grandson").getURI();

            System.out.println("Inferred grandfather relationship: " + grandfather + " -> " + grandson);

            assertTrue(grandfather.endsWith("Abraham"), 
                "Abraham should be inferred as grandfather");
            assertTrue(grandson.endsWith("Jacob"), 
                "Jacob should be inferred as grandson");
            
            assertFalse(results.hasNext(), 
                "Should have exactly one grandfather relationship inferred");
        }
    }

    @Test
    @DisplayName("System Test: Verify inference works with programmatic data insertion")
    public void testGrandfatherInferenceWithProgrammaticInsert() {
        // Insert the same data as fathers_father_sample.ttl programmatically
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

        // Query for inferred grandfather relationship
        String grandfatherQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "SELECT ?grandfather ?grandson " +
                        "WHERE { " +
                        "  ?grandfather ff:grandfather_of ?grandson . " +
                        "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(grandfatherQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), 
                "Should infer grandfather relationship with programmatic insert");

            var solution = results.next();
            String grandfather = solution.getResource("grandfather").getLocalName();
            String grandson = solution.getResource("grandson").getLocalName();

            assertEquals("Abraham", grandfather, "Abraham should be grandfather");
            assertEquals("Jacob", grandson, "Jacob should be grandson");
        }
    }

    @Test
    @DisplayName("System Test: Verify ASK query for specific grandfather relationship")
    public void testAskQueryForGrandfatherRelationship() throws Exception {
        // Load data
        try (InputStream dataStream = getClass().getClassLoader()
                .getResourceAsStream("data/fathers_father.ttl")) {
            RDFDataMgr.read(infModel, dataStream, null, org.apache.jena.riot.Lang.TURTLE);
        }

        // ASK if Abraham is grandfather of Jacob
        String askQuery =
                "PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> " +
                        "ASK { ff:Abraham ff:grandfather_of ff:Jacob }";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(askQuery).build()) {
            boolean result = qexec.execAsk();
            assertTrue(result, "Abraham should be inferred as grandfather of Jacob");
        }
    }

    @Test
    @DisplayName("System Test: Query all relationships including inferred ones")
    public void testQueryAllRelationshipsIncludingInferred() throws Exception {
        // Load data
        try (InputStream dataStream = getClass().getClassLoader()
                .getResourceAsStream("data/fathers_father.ttl")) {
            RDFDataMgr.read(infModel, dataStream, null, org.apache.jena.riot.Lang.TURTLE);
        }

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
            
            assertEquals(2, fatherCount, "Should have 2 direct father relationships");
            assertEquals(1, grandfatherCount, "Should have 1 inferred grandfather relationship");
        }
    }
}
