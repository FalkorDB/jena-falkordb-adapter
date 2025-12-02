package com.falkordb.jena.pfunction;

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
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CypherQueryFunc magic property function.
 *
 * Prerequisites: FalkorDB must be running on localhost:6379
 * Run: docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
 */
public class CypherQueryFuncTest {

    private static final String TEST_GRAPH = "test_magic_property_graph";
    private Model model;

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
    }

    @AfterEach
    public void tearDown() {
        if (model != null) {
            model.close();
        }
    }

    @Test
    @DisplayName("Test simple Cypher query via magic property")
    public void testSimpleCypherQuery() {
        // Add test data
        var person = model.createResource("http://example.org/person/john");
        var name = model.createProperty("http://example.org/name");
        var personType = model.createResource("http://example.org/Person");

        person.addProperty(RDF.type, personType);
        person.addProperty(name, "John Doe");

        // Create dataset for SPARQL query execution
        Dataset dataset = DatasetFactory.create(model);

        // Execute SPARQL query with magic property
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?name WHERE {
                (?name) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/name` IS NOT NULL
                    RETURN p.`http://example.org/name` AS name
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have at least one result");

            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("name"), "name variable should be bound");
            assertEquals("John Doe", solution.getLiteral("name").getString());
        }
    }

    @Test
    @DisplayName("Test Cypher query with multiple return values")
    public void testMultipleReturnValues() {
        // Add test data
        var person1 = model.createResource("http://example.org/person/john");
        var person2 = model.createResource("http://example.org/person/jane");
        var name = model.createProperty("http://example.org/name");
        var age = model.createProperty("http://example.org/age");
        var personType = model.createResource("http://example.org/Person");

        person1.addProperty(RDF.type, personType);
        person1.addProperty(name, "John Doe");
        person1.addProperty(age, model.createTypedLiteral(30));

        person2.addProperty(RDF.type, personType);
        person2.addProperty(name, "Jane Smith");
        person2.addProperty(age, model.createTypedLiteral(25));

        // Create dataset for SPARQL query execution
        Dataset dataset = DatasetFactory.create(model);

        // Execute SPARQL query with magic property returning multiple columns
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?name ?age WHERE {
                (?name ?age) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/name` IS NOT NULL
                    RETURN p.`http://example.org/name` AS name,
                           p.`http://example.org/age` AS age
                    ORDER BY name
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            List<String> names = new ArrayList<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                names.add(solution.getLiteral("name").getString());
            }

            assertEquals(2, names.size(), "Should have two results");
            assertTrue(names.contains("John Doe"), "Should contain John Doe");
            assertTrue(names.contains("Jane Smith"), "Should contain Jane Smith");
        }
    }

    @Test
    @DisplayName("Test Cypher query returning nodes")
    public void testCypherQueryReturningNodes() {
        // Add test data with relationships
        var person1 = model.createResource("http://example.org/person/john");
        var person2 = model.createResource("http://example.org/person/jane");
        var knows = model.createProperty("http://example.org/knows");

        person1.addProperty(knows, person2);

        // Create dataset for SPARQL query execution
        Dataset dataset = DatasetFactory.create(model);

        // Execute SPARQL query that returns node URIs
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?person WHERE {
                (?person) falkor:cypher '''
                    MATCH (p:Resource)-[:`http://example.org/knows`]->(o:Resource)
                    RETURN p
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have at least one result");

            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("person"), "person variable should be bound");
        }
    }

    @Test
    @DisplayName("Test empty result set")
    public void testEmptyResultSet() {
        // Don't add any data - query should return empty result
        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?name WHERE {
                (?name) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/nonexistent` IS NOT NULL
                    RETURN p.`http://example.org/nonexistent` AS name
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertFalse(results.hasNext(), "Should have no results");
        }
    }

    @Test
    @DisplayName("Test magic property URI constant")
    public void testMagicPropertyURI() {
        assertEquals("http://falkordb.com/jena#cypher", CypherQueryFunc.URI,
            "Magic property URI should be correctly defined");
    }
}
