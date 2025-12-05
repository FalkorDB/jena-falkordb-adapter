package com.falkordb.jena.query;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FalkorDB query pushdown.
 *
 * <p>These tests verify that SPARQL queries are correctly pushed down
 * to FalkorDB using native Cypher execution.</p>
 *
 * <p>Prerequisites: FalkorDB must be running on localhost:6379</p>
 */
public class FalkorDBQueryPushdownTest {

    private static final String TEST_GRAPH = "test_pushdown_graph";
    private Model model;

    @BeforeAll
    public static void setupClass() {
        // Register the query engine factory
        FalkorDBQueryEngineFactory.register();
    }

    @AfterAll
    public static void teardownClass() {
        // Unregister the query engine factory
        FalkorDBQueryEngineFactory.unregister();
    }

    @BeforeEach
    public void setUp() {
        model = FalkorDBModelFactory.createModel(TEST_GRAPH);
        // Clear the graph before each test
        if (model.getGraph() instanceof FalkorDBGraph falkorGraph) {
            falkorGraph.clear();
        }
    }

    @AfterEach
    public void tearDown() {
        if (model != null) {
            model.close();
        }
    }

    @Test
    @DisplayName("Test factory registration")
    public void testFactoryRegistration() {
        assertTrue(FalkorDBQueryEngineFactory.isRegistered(),
            "Factory should be registered");
    }

    @Test
    @DisplayName("Test simple property query falls back to standard evaluation")
    public void testSimplePropertyQueryFallback() {
        // Add test data
        var person = model.createResource("http://example.org/person/john");
        var name = model.createProperty("http://example.org/name");
        person.addProperty(name, "John Doe");

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable object - falls back to standard evaluation
        String sparql = """
            SELECT ?name WHERE {
                ?s <http://example.org/name> ?name .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have at least one result");

            QuerySolution solution = results.nextSolution();
            assertEquals("John Doe", solution.getLiteral("name").getString());
        }
    }

    @Test
    @DisplayName("Test relationship query falls back to standard evaluation")
    public void testRelationshipQueryFallback() {
        // Add test data
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var knows = model.createProperty("http://example.org/knows");
        var name = model.createProperty("http://example.org/name");

        alice.addProperty(name, "Alice");
        bob.addProperty(name, "Bob");
        alice.addProperty(knows, bob);

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable object - falls back to standard evaluation
        String sparql = """
            SELECT ?friend WHERE {
                <http://example.org/person/alice> <http://example.org/knows> ?friend .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should find a friend");

            QuerySolution solution = results.nextSolution();
            assertEquals("http://example.org/person/bob",
                solution.getResource("friend").getURI());
        }
    }

    @Test
    @DisplayName("Test friends of friends query falls back to standard evaluation")
    public void testFriendsOfFriendsQueryFallback() {
        // Create a simple social network: Alice -> Bob -> Carol
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var carol = model.createResource("http://example.org/person/carol");
        var knows = model.createProperty("http://example.org/knows");

        alice.addProperty(knows, bob);
        bob.addProperty(knows, carol);

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable objects - falls back to standard evaluation
        String sparql = """
            SELECT ?fof WHERE {
                <http://example.org/person/alice> <http://example.org/knows> ?friend .
                ?friend <http://example.org/knows> ?fof .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should find friend of friend");

            QuerySolution solution = results.nextSolution();
            assertEquals("http://example.org/person/carol",
                solution.getResource("fof").getURI());
        }
    }

    @Test
    @DisplayName("Test rdf:type query with pushdown")
    public void testTypeQuery() {
        // Add test data with types
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var personType = model.createResource("http://example.org/Person");

        alice.addProperty(RDF.type, personType);
        bob.addProperty(RDF.type, personType);

        Dataset dataset = DatasetFactory.create(model);

        // Query for all persons
        String sparql = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?person WHERE {
                ?person rdf:type <http://example.org/Person> .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            Set<String> persons = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                persons.add(solution.getResource("person").getURI());
            }

            assertEquals(2, persons.size(), "Should find two persons");
            assertTrue(persons.contains("http://example.org/person/alice"));
            assertTrue(persons.contains("http://example.org/person/bob"));
        }
    }

    @Test
    @DisplayName("Test query with multiple patterns falls back to standard evaluation")
    public void testMultiplePatternsFallback() {
        // Add test data
        var person = model.createResource("http://example.org/person/john");
        var name = model.createProperty("http://example.org/name");
        var age = model.createProperty("http://example.org/age");
        var personType = model.createResource("http://example.org/Person");

        person.addProperty(RDF.type, personType);
        person.addProperty(name, "John Doe");
        person.addProperty(age, model.createTypedLiteral(30));

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable objects - falls back to standard evaluation
        String sparql = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?name WHERE {
                ?person rdf:type <http://example.org/Person> .
                ?person <http://example.org/name> ?name .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have results");

            QuerySolution solution = results.nextSolution();
            assertEquals("John Doe", solution.getLiteral("name").getString());
        }
    }

    @Test
    @DisplayName("Test query returns multiple results with fallback")
    public void testMultipleResultsFallback() {
        // Add multiple persons
        var name = model.createProperty("http://example.org/name");
        List<String> names = List.of("Alice", "Bob", "Carol", "Dave");

        for (String n : names) {
            var person = model.createResource(
                "http://example.org/person/" + n.toLowerCase());
            person.addProperty(name, n);
        }

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable objects - falls back to standard evaluation
        String sparql = """
            SELECT ?name WHERE {
                ?person <http://example.org/name> ?name .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            Set<String> resultNames = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                resultNames.add(solution.getLiteral("name").getString());
            }

            assertEquals(4, resultNames.size(), "Should have 4 results");
            assertTrue(resultNames.containsAll(names));
        }
    }

    @Test
    @DisplayName("Test query with no results")
    public void testNoResults() {
        // Empty graph - no data
        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            SELECT ?s WHERE {
                ?s <http://example.org/nonexistent> ?o .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(), "Should have no results");
        }
    }

    @Test
    @DisplayName("Test factory singleton")
    public void testFactorySingleton() {
        FalkorDBQueryEngineFactory factory1 = FalkorDBQueryEngineFactory.get();
        FalkorDBQueryEngineFactory factory2 = FalkorDBQueryEngineFactory.get();
        
        assertSame(factory1, factory2, "Factory should be singleton");
    }
}
