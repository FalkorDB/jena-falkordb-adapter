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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Test
    @DisplayName("Test scope constant")
    public void testScopePFunctionConstant() {
        assertEquals("com.falkordb.jena.pfunction.CypherQueryFunc",
            CypherQueryFunc.SCOPE_PFUNCTION,
            "Scope constant should be correctly defined");
    }

    @Test
    @DisplayName("Test Cypher query with integer values")
    public void testCypherQueryWithIntegerValues() {
        // Add test data with integer
        var person = model.createResource("http://example.org/person/john");
        var age = model.createProperty("http://example.org/age");
        person.addProperty(age, model.createTypedLiteral(42));

        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?age WHERE {
                (?age) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/age` IS NOT NULL
                    RETURN p.`http://example.org/age` AS age
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have at least one result");

            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("age"), "age variable should be bound");
            assertEquals(42, solution.getLiteral("age").getInt());
        }
    }

    @Test
    @DisplayName("Test Cypher query with double values")
    public void testCypherQueryWithDoubleValues() {
        // Add test data with double
        var item = model.createResource("http://example.org/item/price");
        var price = model.createProperty("http://example.org/price");
        item.addProperty(price, model.createTypedLiteral(99.99));

        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?price WHERE {
                (?price) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/price` IS NOT NULL
                    RETURN p.`http://example.org/price` AS price
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have at least one result");

            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("price"), "price variable should be bound");
            assertEquals(99.99, solution.getLiteral("price").getDouble(), 0.001);
        }
    }

    @Test
    @DisplayName("Test Cypher query with boolean values")
    public void testCypherQueryWithBooleanValues() {
        // Add test data with boolean
        var item = model.createResource("http://example.org/item/active");
        var active = model.createProperty("http://example.org/active");
        item.addProperty(active, model.createTypedLiteral(true));

        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?active WHERE {
                (?active) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/active` IS NOT NULL
                    RETURN p.`http://example.org/active` AS active
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have at least one result");

            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("active"), "active variable should be bound");
            assertTrue(solution.getLiteral("active").getBoolean());
        }
    }

    @Test
    @DisplayName("Test Cypher query returning URI property")
    public void testCypherQueryReturningURI() {
        // Add test data
        var person = model.createResource("http://example.org/person/john");
        var name = model.createProperty("http://example.org/name");
        person.addProperty(name, "John");

        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?uri WHERE {
                (?uri) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.uri IS NOT NULL
                    RETURN p.uri AS uri
                    LIMIT 1
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have at least one result");

            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("uri"), "uri variable should be bound");
        }
    }

    @Test
    @DisplayName("Test Cypher query with position-based variable binding")
    public void testPositionBasedVariableBinding() {
        // Add test data
        var person = model.createResource("http://example.org/person/john");
        var name = model.createProperty("http://example.org/name");
        var age = model.createProperty("http://example.org/age");
        person.addProperty(name, "John");
        person.addProperty(age, model.createTypedLiteral(30));

        Dataset dataset = DatasetFactory.create(model);

        // Use different variable names than the column names to test position binding
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?firstName ?years WHERE {
                (?firstName ?years) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/name` IS NOT NULL
                    RETURN p.`http://example.org/name`, p.`http://example.org/age`
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have at least one result");

            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("firstName"), "firstName should be bound");
            assertEquals("John", solution.getLiteral("firstName").getString());
        }
    }

    @Test
    @DisplayName("Test Cypher query with single variable (not list)")
    public void testSingleVariableSubject() {
        // Add test data
        var person = model.createResource("http://example.org/person/john");
        var name = model.createProperty("http://example.org/name");
        person.addProperty(name, "John");

        Dataset dataset = DatasetFactory.create(model);

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
            assertEquals("John", solution.getLiteral("name").getString());
        }
    }

    @Test
    @DisplayName("Test Cypher query with count aggregation")
    public void testCypherQueryWithCountAggregation() {
        // Add test data - multiple people
        for (int i = 1; i <= 5; i++) {
            var person = model.createResource("http://example.org/person/p" + i);
            var name = model.createProperty("http://example.org/name");
            person.addProperty(name, "Person " + i);
        }

        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?count WHERE {
                (?count) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/name` IS NOT NULL
                    RETURN count(p) AS count
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should have a result");

            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("count"), "count variable should be bound");
            assertTrue(solution.getLiteral("count").getInt() >= 5,
                "Count should be at least 5");
        }
    }

    @Test
    @DisplayName("Test Cypher query with relationship traversal")
    public void testCypherQueryWithRelationshipTraversal() {
        // Create a simple social network: A knows B, B knows C
        var personA = model.createResource("http://example.org/person/alice");
        var personB = model.createResource("http://example.org/person/bob");
        var personC = model.createResource("http://example.org/person/carol");
        var knows = model.createProperty("http://example.org/knows");
        var name = model.createProperty("http://example.org/name");

        personA.addProperty(name, "Alice");
        personB.addProperty(name, "Bob");
        personC.addProperty(name, "Carol");
        personA.addProperty(knows, personB);
        personB.addProperty(knows, personC);

        Dataset dataset = DatasetFactory.create(model);

        // Find friends of friends (2-hop traversal)
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?fof WHERE {
                (?fof) falkor:cypher '''
                    MATCH (:Resource {uri: "http://example.org/person/alice"})
                          -[:`http://example.org/knows`]->(:Resource)
                          -[:`http://example.org/knows`]->(fof:Resource)
                    RETURN fof.uri AS fof
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should find friend of friend");

            QuerySolution solution = results.nextSolution();
            assertNotNull(solution.get("fof"), "fof variable should be bound");
            assertEquals("http://example.org/person/carol",
                solution.get("fof").toString());
        }
    }

    @Test
    @DisplayName("Test Cypher query with multiple results")
    public void testCypherQueryWithMultipleResults() {
        // Add multiple people
        var names = List.of("Alice", "Bob", "Carol", "Dave");
        var nameProp = model.createProperty("http://example.org/name");

        for (String n : names) {
            var person = model.createResource("http://example.org/person/" +
                n.toLowerCase());
            person.addProperty(nameProp, n);
        }

        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?name WHERE {
                (?name) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/name` IS NOT NULL
                    RETURN p.`http://example.org/name` AS name
                    ORDER BY name
                '''
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
            assertTrue(resultNames.containsAll(names), "Should contain all names");
        }
    }

    @Test
    @DisplayName("Test Cypher query with DISTINCT")
    public void testCypherQueryWithDistinct() {
        // Add data with duplicate values
        var person1 = model.createResource("http://example.org/person/p1");
        var person2 = model.createResource("http://example.org/person/p2");
        var status = model.createProperty("http://example.org/status");

        person1.addProperty(status, "active");
        person2.addProperty(status, "active");

        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?status WHERE {
                (?status) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/status` IS NOT NULL
                    RETURN DISTINCT p.`http://example.org/status` AS status
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            int count = 0;
            while (results.hasNext()) {
                results.next();
                count++;
            }

            assertEquals(1, count, "DISTINCT should return only 1 unique value");
        }
    }

    @Test
    @DisplayName("Test Cypher query with LIMIT")
    public void testCypherQueryWithLimit() {
        // Add many people
        var nameProp = model.createProperty("http://example.org/name");
        for (int i = 1; i <= 10; i++) {
            var person = model.createResource("http://example.org/person/p" + i);
            person.addProperty(nameProp, "Person " + i);
        }

        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?name WHERE {
                (?name) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/name` IS NOT NULL
                    RETURN p.`http://example.org/name` AS name
                    LIMIT 3
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            int count = 0;
            while (results.hasNext()) {
                results.next();
                count++;
            }

            assertEquals(3, count, "LIMIT 3 should return exactly 3 results");
        }
    }

    @Test
    @DisplayName("Test Cypher query with ORDER BY")
    public void testCypherQueryWithOrderBy() {
        // Add people with different ages
        var ageProp = model.createProperty("http://example.org/age");
        var nameProp = model.createProperty("http://example.org/name");

        var ages = List.of(30, 25, 35, 20);
        var names = List.of("Alice", "Bob", "Carol", "Dave");

        for (int i = 0; i < ages.size(); i++) {
            var person = model.createResource("http://example.org/person/p" + i);
            person.addProperty(ageProp, model.createTypedLiteral(ages.get(i)));
            person.addProperty(nameProp, names.get(i));
        }

        Dataset dataset = DatasetFactory.create(model);

        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?age WHERE {
                (?age) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://example.org/age` IS NOT NULL
                    RETURN p.`http://example.org/age` AS age
                    ORDER BY age ASC
                '''
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            List<Integer> resultAges = new ArrayList<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                resultAges.add(solution.getLiteral("age").getInt());
            }

            assertEquals(4, resultAges.size(), "Should have 4 results");
            // Verify ascending order
            for (int i = 1; i < resultAges.size(); i++) {
                assertTrue(resultAges.get(i) >= resultAges.get(i - 1),
                    "Ages should be in ascending order");
            }
        }
    }
}
