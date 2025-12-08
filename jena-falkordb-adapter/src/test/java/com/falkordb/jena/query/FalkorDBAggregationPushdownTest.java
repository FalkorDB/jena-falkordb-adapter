package com.falkordb.jena.query;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FalkorDB aggregation query pushdown.
 *
 * <p>These tests verify that SPARQL aggregation queries (GROUP BY, COUNT, SUM, AVG, etc.)
 * are correctly pushed down to FalkorDB using native Cypher execution.</p>
 *
 * <p>Prerequisites: FalkorDB must be running on localhost:6379</p>
 * <p>Run: docker run -p 6379:6379 -d falkordb/falkordb:latest</p>
 */
public class FalkorDBAggregationPushdownTest {

    private static final String TEST_GRAPH = "test_aggregation_graph";
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
    @DisplayName("Test COUNT aggregation with GROUP BY")
    public void testCountWithGroupBy() {
        // Add test data - people with types
        Resource personType = model.createResource("http://example.org/Person");
        Resource orgType = model.createResource("http://example.org/Organization");

        for (int i = 1; i <= 3; i++) {
            var person = model.createResource("http://example.org/person/" + i);
            person.addProperty(RDF.type, personType);
        }

        for (int i = 1; i <= 2; i++) {
            var org = model.createResource("http://example.org/org/" + i);
            org.addProperty(RDF.type, orgType);
        }

        Dataset dataset = DatasetFactory.create(model);

        // Query with COUNT and GROUP BY
        String sparql = """
            SELECT ?type (COUNT(?entity) AS ?count)
            WHERE {
                ?entity a ?type .
            }
            GROUP BY ?type
            ORDER BY DESC(?count)
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should have results");

            // First result should be Person type with count 3
            QuerySolution solution1 = results.nextSolution();
            Resource type1 = solution1.getResource("type");
            int count1 = solution1.getLiteral("count").getInt();
            assertEquals(personType.getURI(), type1.getURI(), "First type should be Person");
            assertEquals(3, count1, "Person count should be 3");

            assertTrue(results.hasNext(), "Should have second result");

            // Second result should be Organization type with count 2
            QuerySolution solution2 = results.nextSolution();
            Resource type2 = solution2.getResource("type");
            int count2 = solution2.getLiteral("count").getInt();
            assertEquals(orgType.getURI(), type2.getURI(), "Second type should be Organization");
            assertEquals(2, count2, "Organization count should be 2");

            assertFalse(results.hasNext(), "Should have no more results");
        }
    }

    @Test
    @DisplayName("Test SUM aggregation")
    public void testSumAggregation() {
        // Add test data - items with prices
        Property price = model.createProperty("http://example.org/price");

        for (int i = 1; i <= 5; i++) {
            var item = model.createResource("http://example.org/item/" + i);
            item.addProperty(price, model.createTypedLiteral(i * 10));
        }

        Dataset dataset = DatasetFactory.create(model);

        // Query with SUM
        String sparql = """
            PREFIX ex: <http://example.org/>
            SELECT (SUM(?p) AS ?total)
            WHERE {
                ?item ex:price ?p .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should have result");
            QuerySolution solution = results.nextSolution();
            int total = solution.getLiteral("total").getInt();
            assertEquals(150, total, "Sum should be 10+20+30+40+50=150");
        }
    }

    @Test
    @DisplayName("Test AVG aggregation with GROUP BY")
    public void testAvgWithGroupBy() {
        // Add test data - people with ages grouped by type
        Property age = model.createProperty("http://example.org/age");
        Resource studentType = model.createResource("http://example.org/Student");
        Resource teacherType = model.createResource("http://example.org/Teacher");

        // Students: ages 20, 21, 22 (avg = 21)
        for (int i = 0; i < 3; i++) {
            var student = model.createResource("http://example.org/student/" + i);
            student.addProperty(RDF.type, studentType);
            student.addProperty(age, model.createTypedLiteral(20 + i));
        }

        // Teachers: ages 40, 50 (avg = 45)
        for (int i = 0; i < 2; i++) {
            var teacher = model.createResource("http://example.org/teacher/" + i);
            teacher.addProperty(RDF.type, teacherType);
            teacher.addProperty(age, model.createTypedLiteral(40 + i * 10));
        }

        Dataset dataset = DatasetFactory.create(model);

        // Query with AVG and GROUP BY
        String sparql = """
            PREFIX ex: <http://example.org/>
            SELECT ?type (AVG(?a) AS ?avgAge)
            WHERE {
                ?person a ?type .
                ?person ex:age ?a .
            }
            GROUP BY ?type
            ORDER BY ?type
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should have results");

            // Check results (order may vary)
            int resultCount = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                Resource type = solution.getResource("type");
                double avgAge = solution.getLiteral("avgAge").getDouble();

                if (type.getURI().equals(studentType.getURI())) {
                    assertEquals(21.0, avgAge, 0.1, "Student average age should be 21");
                } else if (type.getURI().equals(teacherType.getURI())) {
                    assertEquals(45.0, avgAge, 0.1, "Teacher average age should be 45");
                }
                resultCount++;
            }

            assertEquals(2, resultCount, "Should have 2 groups");
        }
    }

    @Test
    @DisplayName("Test MIN and MAX aggregations")
    public void testMinMaxAggregations() {
        // Add test data - items with prices
        Property price = model.createProperty("http://example.org/price");

        int[] prices = {15, 25, 10, 40, 5};
        for (int i = 0; i < prices.length; i++) {
            var item = model.createResource("http://example.org/item/" + i);
            item.addProperty(price, model.createTypedLiteral(prices[i]));
        }

        Dataset dataset = DatasetFactory.create(model);

        // Query with MIN and MAX
        String sparql = """
            PREFIX ex: <http://example.org/>
            SELECT (MIN(?p) AS ?minPrice) (MAX(?p) AS ?maxPrice)
            WHERE {
                ?item ex:price ?p .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should have result");
            QuerySolution solution = results.nextSolution();
            int minPrice = solution.getLiteral("minPrice").getInt();
            int maxPrice = solution.getLiteral("maxPrice").getInt();
            
            assertEquals(5, minPrice, "Min price should be 5");
            assertEquals(40, maxPrice, "Max price should be 40");
        }
    }

    @Test
    @DisplayName("Test COUNT DISTINCT aggregation")
    public void testCountDistinct() {
        // Add test data - people with duplicate types
        Resource personType = model.createResource("http://example.org/Person");

        // Add 5 people, all with same type
        for (int i = 1; i <= 5; i++) {
            var person = model.createResource("http://example.org/person/" + i);
            person.addProperty(RDF.type, personType);
        }

        Dataset dataset = DatasetFactory.create(model);

        // Query with COUNT DISTINCT
        String sparql = """
            SELECT (COUNT(DISTINCT ?type) AS ?uniqueTypes)
            WHERE {
                ?person a ?type .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should have result");
            QuerySolution solution = results.nextSolution();
            int uniqueTypes = solution.getLiteral("uniqueTypes").getInt();
            assertEquals(1, uniqueTypes, "Should have 1 unique type");
        }
    }

    @Test
    @DisplayName("Test multiple aggregations in single query")
    public void testMultipleAggregations() {
        // Add test data - people with ages
        Property age = model.createProperty("http://example.org/age");
        Resource personType = model.createResource("http://example.org/Person");

        int[] ages = {20, 25, 30, 35, 40};
        for (int i = 0; i < ages.length; i++) {
            var person = model.createResource("http://example.org/person/" + i);
            person.addProperty(RDF.type, personType);
            person.addProperty(age, model.createTypedLiteral(ages[i]));
        }

        Dataset dataset = DatasetFactory.create(model);

        // Query with multiple aggregations
        String sparql = """
            PREFIX ex: <http://example.org/>
            SELECT 
                (COUNT(?person) AS ?count)
                (SUM(?a) AS ?totalAge)
                (AVG(?a) AS ?avgAge)
                (MIN(?a) AS ?minAge)
                (MAX(?a) AS ?maxAge)
            WHERE {
                ?person a ex:Person .
                ?person ex:age ?a .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should have result");
            QuerySolution solution = results.nextSolution();
            
            int count = solution.getLiteral("count").getInt();
            int totalAge = solution.getLiteral("totalAge").getInt();
            double avgAge = solution.getLiteral("avgAge").getDouble();
            int minAge = solution.getLiteral("minAge").getInt();
            int maxAge = solution.getLiteral("maxAge").getInt();
            
            assertEquals(5, count, "Count should be 5");
            assertEquals(150, totalAge, "Total age should be 150");
            assertEquals(30.0, avgAge, 0.1, "Average age should be 30");
            assertEquals(20, minAge, "Min age should be 20");
            assertEquals(40, maxAge, "Max age should be 40");
        }
    }

    @Test
    @DisplayName("Test COUNT(*) without variables")
    public void testCountAll() {
        // Add test data
        for (int i = 1; i <= 10; i++) {
            var entity = model.createResource("http://example.org/entity/" + i);
            entity.addProperty(RDF.type, model.createResource("http://example.org/Thing"));
        }

        Dataset dataset = DatasetFactory.create(model);

        // Query with COUNT(*)
        String sparql = """
            SELECT (COUNT(*) AS ?total)
            WHERE {
                ?s a ?o .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should have result");
            QuerySolution solution = results.nextSolution();
            int total = solution.getLiteral("total").getInt();
            assertEquals(10, total, "Total should be 10");
        }
    }
}
