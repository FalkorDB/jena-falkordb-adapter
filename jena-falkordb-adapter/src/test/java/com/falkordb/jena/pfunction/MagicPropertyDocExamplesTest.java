package com.falkordb.jena.pfunction;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that verify all examples from MAGIC_PROPERTY.md work correctly.
 *
 * These tests ensure that the documentation examples are accurate and functional,
 * and that the magic property queries return equivalent results to standard SPARQL.
 *
 * Prerequisites: FalkorDB must be running on localhost:6379
 * Run: docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
 */
public class MagicPropertyDocExamplesTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        MagicPropertyDocExamplesTest.class);
    private static final String TEST_GRAPH = "test_magic_property_doc_examples";
    private Model model;
    private Dataset dataset;

    @BeforeAll
    public static void setupClass() {
        // Register the magic property function
        PropertyFunctionRegistry.get().put(
            CypherQueryFunc.URI,
            CypherQueryFunc.class
        );
    }

    @BeforeEach
    public void setUp() {
        model = FalkorDBModelFactory.createModel(TEST_GRAPH);
        // Clear the graph before each test
        if (model.getGraph() instanceof FalkorDBGraph falkorGraph) {
            falkorGraph.clear();
        }
        dataset = DatasetFactory.create(model);
    }

    @AfterEach
    public void tearDown() {
        if (model != null) {
            model.close();
        }
    }

    /**
     * Helper method to load social_network.ttl into the model.
     */
    private void loadSocialNetworkData() {
        InputStream inputStream = getClass().getResourceAsStream("/data/social_network.ttl");
        if (inputStream != null) {
            model.read(inputStream, null, "TURTLE");
        } else {
            // Fall back to creating minimal test data
            createMinimalSocialNetworkData();
        }
    }

    /**
     * Creates minimal social network data for testing when the TTL file is not available.
     */
    private void createMinimalSocialNetworkData() {
        var person1 = model.createResource("http://example.org/social#person1");
        var person2 = model.createResource("http://example.org/social#person2");
        var person3 = model.createResource("http://example.org/social#person3");
        var person4 = model.createResource("http://example.org/social#person4");
        var person5 = model.createResource("http://example.org/social#person5");

        var knows = model.createProperty("http://example.org/social#knows");
        var personType = model.createResource("http://example.org/social#Person");
        var name = model.createProperty("http://example.org/social#name");
        var rdfType = model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");

        // Add types and names
        person1.addProperty(rdfType, personType);
        person1.addProperty(name, "Person 1");
        person2.addProperty(rdfType, personType);
        person2.addProperty(name, "Person 2");
        person3.addProperty(rdfType, personType);
        person3.addProperty(name, "Person 3");
        person4.addProperty(rdfType, personType);
        person4.addProperty(name, "Person 4");
        person5.addProperty(rdfType, personType);
        person5.addProperty(name, "Person 5");

        // person1 knows person2, person3
        person1.addProperty(knows, person2);
        person1.addProperty(knows, person3);
        // person2 knows person4
        person2.addProperty(knows, person4);
        // person3 knows person5
        person3.addProperty(knows, person5);
    }

    // ========================================================================
    // Friends of Friends Query Tests (from MAGIC_PROPERTY.md Performance section)
    // ========================================================================

    @Test
    @DisplayName("Friends of Friends: Standard SPARQL query returns results")
    public void testFriendsOfFriendsStandardSparql() {
        loadSocialNetworkData();

        // From MAGIC_PROPERTY.md - Standard SPARQL (N+1 queries)
        String sparql = """
            PREFIX social: <http://example.org/social#>
            SELECT ?fof WHERE {
              social:person1 social:knows ?friend .
              ?friend social:knows ?fof .
            }
            """;

        try (QueryExecution qexec = QueryExecutionFactory.create(
                QueryFactory.create(sparql), dataset)) {
            ResultSet results = qexec.execSelect();

            Set<String> fofs = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                fofs.add(solution.get("fof").toString());
            }

            assertFalse(fofs.isEmpty(),
                "Standard SPARQL query should return friends of friends");
            assertTrue(fofs.size() > 0,
                "Should have at least one friend of friend");
        }
    }

    @Test
    @DisplayName("Friends of Friends: Magic property query returns results")
    public void testFriendsOfFriendsMagicProperty() {
        loadSocialNetworkData();

        // From MAGIC_PROPERTY.md - Magic Property (Single query)
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            PREFIX social: <http://example.org/social#>
            SELECT ?fof WHERE {
              (?fof) falkor:cypher '''
                MATCH (:Resource {uri: "http://example.org/social#person1"})
                      -[:`http://example.org/social#knows`]->(:Resource)
                      -[:`http://example.org/social#knows`]->(fof:Resource)
                RETURN DISTINCT fof.uri AS fof
              '''
            }
            """;

        try (QueryExecution qexec = QueryExecutionFactory.create(
                QueryFactory.create(sparql), dataset)) {
            ResultSet results = qexec.execSelect();

            Set<String> fofs = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                fofs.add(solution.get("fof").toString());
            }

            assertFalse(fofs.isEmpty(),
                "Magic property query should return friends of friends");
            assertTrue(fofs.size() > 0,
                "Should have at least one friend of friend");
        }
    }

    @Test
    @DisplayName("Friends of Friends: Standard SPARQL and Magic Property return equivalent results")
    public void testFriendsOfFriendsEquivalence() {
        loadSocialNetworkData();

        // Standard SPARQL query
        String standardSparql = """
            PREFIX social: <http://example.org/social#>
            SELECT DISTINCT ?fof WHERE {
              social:person1 social:knows ?friend .
              ?friend social:knows ?fof .
            }
            """;

        // Magic Property query
        String magicPropertySparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            PREFIX social: <http://example.org/social#>
            SELECT ?fof WHERE {
              (?fof) falkor:cypher '''
                MATCH (:Resource {uri: "http://example.org/social#person1"})
                      -[:`http://example.org/social#knows`]->(:Resource)
                      -[:`http://example.org/social#knows`]->(fof:Resource)
                RETURN DISTINCT fof.uri AS fof
              '''
            }
            """;

        // Get results from standard SPARQL
        Set<String> standardResults = new HashSet<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(
                QueryFactory.create(standardSparql), dataset)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                standardResults.add(solution.get("fof").toString());
            }
        }

        // Get results from magic property
        Set<String> magicPropertyResults = new HashSet<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(
                QueryFactory.create(magicPropertySparql), dataset)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                magicPropertyResults.add(solution.get("fof").toString());
            }
        }

        // Log results for debugging
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Standard SPARQL results: {}", standardResults);
            LOGGER.debug("Magic Property results: {}", magicPropertyResults);
        }

        // Both should have results
        assertFalse(standardResults.isEmpty(),
            "Standard SPARQL should return results");
        assertFalse(magicPropertyResults.isEmpty(),
            "Magic property should return results");

        // Results should be equivalent
        assertEquals(standardResults, magicPropertyResults,
            "Standard SPARQL and magic property should return equivalent results");
    }

    // ========================================================================
    // 3-Hop Path Query Tests (from MAGIC_PROPERTY.md)
    // ========================================================================

    @Test
    @DisplayName("3-Hop Path: Magic property query for friends within 3 hops")
    public void testThreeHopPathMagicProperty() {
        loadSocialNetworkData();

        // From MAGIC_PROPERTY.md - 3-Hop Path Query
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?person WHERE {
              (?person) falkor:cypher '''
                MATCH (:Resource {uri: "http://example.org/social#person1"})
                      -[:`http://example.org/social#knows`*1..3]->(p:Resource)
                RETURN DISTINCT p.uri AS person
              '''
            }
            """;

        try (QueryExecution qexec = QueryExecutionFactory.create(
                QueryFactory.create(sparql), dataset)) {
            ResultSet results = qexec.execSelect();

            Set<String> persons = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                persons.add(solution.get("person").toString());
            }

            assertFalse(persons.isEmpty(),
                "3-hop path query should return connected people");
        }
    }

    // ========================================================================
    // Counting Connections Tests (from MAGIC_PROPERTY.md)
    // ========================================================================

    @Test
    @DisplayName("Counting Connections: Magic property query for friend counts")
    public void testCountingConnectionsMagicProperty() {
        loadSocialNetworkData();

        // From MAGIC_PROPERTY.md - Counting Connections
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?person ?friendCount WHERE {
              (?person ?friendCount) falkor:cypher '''
                MATCH (p:Resource)-[r:`http://example.org/social#knows`]->(:Resource)
                RETURN p.uri AS person, count(r) AS friendCount
                ORDER BY friendCount DESC
                LIMIT 10
              '''
            }
            """;

        try (QueryExecution qexec = QueryExecutionFactory.create(
                QueryFactory.create(sparql), dataset)) {
            ResultSet results = qexec.execSelect();

            int count = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                assertNotNull(solution.get("person"), "person should be bound");
                assertNotNull(solution.get("friendCount"), "friendCount should be bound");
                count++;
            }

            assertTrue(count > 0, "Should return at least one person with connections");
            assertTrue(count <= 10, "Should return at most 10 results due to LIMIT");
        }
    }

    // ========================================================================
    // Example 2: Friends of Friends (simpler version from Examples section)
    // ========================================================================

    @Test
    @DisplayName("Example 2: Friends of Friends with alice (generic example)")
    public void testExample2FriendsOfFriends() {
        // Create test data matching Example 2 in MAGIC_PROPERTY.md
        var alice = model.createResource("http://example.org/alice");
        var bob = model.createResource("http://example.org/bob");
        var carol = model.createResource("http://example.org/carol");
        var knows = model.createProperty("http://example.org/knows");

        alice.addProperty(knows, bob);
        bob.addProperty(knows, carol);

        // From MAGIC_PROPERTY.md Example 2: Friends of Friends
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?friend WHERE {
              (?friend) falkor:cypher '''
                MATCH (:Resource {uri: "http://example.org/alice"})
                      -[:`http://example.org/knows`]->(:Resource)
                      -[:`http://example.org/knows`]->(f:Resource)
                RETURN f.uri AS friend
              '''
            }
            """;

        try (QueryExecution qexec = QueryExecutionFactory.create(
                QueryFactory.create(sparql), dataset)) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext(), "Should find friend of friend");
            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("friend"), "friend should be bound");
            assertEquals("http://example.org/carol", solution.get("friend").toString(),
                "Friend of friend should be Carol");
        }
    }

    @Test
    @DisplayName("Example 2: Friends of Friends - Standard SPARQL vs Magic Property equivalence")
    public void testExample2Equivalence() {
        // Create test data matching Example 2 in MAGIC_PROPERTY.md
        var alice = model.createResource("http://example.org/alice");
        var bob = model.createResource("http://example.org/bob");
        var carol = model.createResource("http://example.org/carol");
        var knows = model.createProperty("http://example.org/knows");

        alice.addProperty(knows, bob);
        bob.addProperty(knows, carol);

        // Standard SPARQL (from "Why Use Magic Property?" section)
        String standardSparql = """
            SELECT ?friend WHERE {
              <http://example.org/alice> <http://example.org/knows> ?x .
              ?x <http://example.org/knows> ?friend .
            }
            """;

        // Magic Property
        String magicPropertySparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?friend WHERE {
              (?friend) falkor:cypher '''
                MATCH (:Resource {uri: "http://example.org/alice"})
                      -[:`http://example.org/knows`]->(:Resource)
                      -[:`http://example.org/knows`]->(f:Resource)
                RETURN f.uri AS friend
              '''
            }
            """;

        // Get standard results
        Set<String> standardResults = new HashSet<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(
                QueryFactory.create(standardSparql), dataset)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                standardResults.add(results.nextSolution().get("friend").toString());
            }
        }

        // Get magic property results
        Set<String> magicPropertyResults = new HashSet<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(
                QueryFactory.create(magicPropertySparql), dataset)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                magicPropertyResults.add(results.nextSolution().get("friend").toString());
            }
        }

        // Both should return Carol
        assertEquals(standardResults, magicPropertyResults,
            "Standard SPARQL and magic property should return equivalent results");
        assertTrue(standardResults.contains("http://example.org/carol"),
            "Should find Carol as friend of friend");
    }
}
