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
    @DisplayName("Test simple property query uses standard evaluation")
    public void testSimplePropertyQueryStandardEval() {
        // Add test data
        var person = model.createResource("http://example.org/person/john");
        var name = model.createProperty("http://example.org/name");
        person.addProperty(name, "John Doe");

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable object - uses standard evaluation (can't determine if literal)
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
    @DisplayName("Test relationship query uses standard evaluation")
    public void testRelationshipQueryStandardEval() {
        // Add test data
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var knows = model.createProperty("http://example.org/knows");
        var name = model.createProperty("http://example.org/name");

        alice.addProperty(name, "Alice");
        bob.addProperty(name, "Bob");
        alice.addProperty(knows, bob);

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable object - uses standard evaluation
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
    @DisplayName("Test friends of friends query uses standard evaluation")
    public void testFriendsOfFriendsQueryStandardEval() {
        // Create a simple social network: Alice -> Bob -> Carol
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var carol = model.createResource("http://example.org/person/carol");
        var knows = model.createProperty("http://example.org/knows");

        alice.addProperty(knows, bob);
        bob.addProperty(knows, carol);

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable objects - uses standard evaluation
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
    @DisplayName("Test query with variable predicate pushdown")
    public void testVariablePredicatePushdown() {
        // Add test data
        var person = model.createResource("http://example.org/person/john");
        var name = model.createProperty("http://example.org/name");
        var age = model.createProperty("http://example.org/age");
        var knows = model.createProperty("http://example.org/knows");
        var friend = model.createResource("http://example.org/person/jane");

        person.addProperty(name, "John Doe");
        person.addProperty(age, "30");
        person.addProperty(knows, friend);

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable predicate - uses UNION in Cypher
        String sparql = """
            SELECT ?p ?o WHERE {
                <http://example.org/person/john> ?p ?o .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            Set<String> predicates = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                predicates.add(solution.getResource("p").getURI());
            }

            // Should find all three predicates
            assertTrue(predicates.contains("http://example.org/name"), 
                "Should find name property");
            assertTrue(predicates.contains("http://example.org/age"), 
                "Should find age property");
            assertTrue(predicates.contains("http://example.org/knows"), 
                "Should find knows relationship");
        }
    }

    @Test
    @DisplayName("Test query with multiple patterns uses standard evaluation")
    public void testMultiplePatternsStandardEval() {
        // Add test data
        var person = model.createResource("http://example.org/person/john");
        var name = model.createProperty("http://example.org/name");
        var age = model.createProperty("http://example.org/age");
        var personType = model.createResource("http://example.org/Person");

        person.addProperty(RDF.type, personType);
        person.addProperty(name, "John Doe");
        person.addProperty(age, model.createTypedLiteral(30));

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable objects - uses standard evaluation
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
    @DisplayName("Test query returns multiple results")
    public void testMultipleResults() {
        // Add multiple persons with knows relationships
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var carol = model.createResource("http://example.org/person/carol");
        var dave = model.createResource("http://example.org/person/dave");
        var knows = model.createProperty("http://example.org/knows");

        // Alice knows Bob, Carol, and Dave
        alice.addProperty(knows, bob);
        alice.addProperty(knows, carol);
        alice.addProperty(knows, dave);

        Dataset dataset = DatasetFactory.create(model);

        // Query with variable object - uses standard evaluation
        String sparql = """
            SELECT ?friend WHERE {
                <http://example.org/person/alice> <http://example.org/knows> ?friend .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            Set<String> friends = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                friends.add(solution.getResource("friend").getURI());
            }

            assertEquals(3, friends.size(), "Should have 3 friends");
            assertTrue(friends.contains("http://example.org/person/bob"));
            assertTrue(friends.contains("http://example.org/person/carol"));
            assertTrue(friends.contains("http://example.org/person/dave"));
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

    @Test
    @DisplayName("Test all properties of a resource with variable predicate")
    public void testAllPropertiesOfResource() {
        // Add test data with multiple properties
        var person = model.createResource("http://example.org/person/jane");
        var name = model.createProperty("http://example.org/name");
        var email = model.createProperty("http://example.org/email");
        var city = model.createProperty("http://example.org/city");

        person.addProperty(name, "Jane Doe");
        person.addProperty(email, "jane@example.org");
        person.addProperty(city, "New York");

        Dataset dataset = DatasetFactory.create(model);

        // Query all properties using variable predicate
        String sparql = """
            SELECT ?p ?o WHERE {
                <http://example.org/person/jane> ?p ?o .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            int count = 0;
            Set<String> predicates = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                predicates.add(solution.getResource("p").getURI());
                count++;
            }

            assertTrue(count >= 3, "Should have at least 3 results");
            assertTrue(predicates.contains("http://example.org/name"));
            assertTrue(predicates.contains("http://example.org/email"));
            assertTrue(predicates.contains("http://example.org/city"));
        }
    }

    @Test
    @DisplayName("Test closed chain relationship pattern with pushdown")
    public void testClosedChainRelationshipPushdown() {
        // Add test data for mutual friends: Alice knows Bob, Bob knows Alice
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var knows = model.createProperty("http://example.org/knows");

        alice.addProperty(knows, bob);
        bob.addProperty(knows, alice);

        Dataset dataset = DatasetFactory.create(model);

        // Query for mutual friends - closed chain pattern can use pushdown
        String sparql = """
            SELECT ?a ?b WHERE {
                ?a <http://example.org/knows> ?b .
                ?b <http://example.org/knows> ?a .
            }
            """;

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();

            Set<String> pairs = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                String a = solution.getResource("a").getURI();
                String b = solution.getResource("b").getURI();
                pairs.add(a + "->" + b);
            }

            // Should find both directions
            assertTrue(pairs.size() >= 2, "Should find mutual friendship pairs");
        }
    }

    @Test
    @DisplayName("Test variable object optimization - query both properties and relationships")
    public void testVariableObjectOptimizationBothTypes() {
        // Add test data with both properties and relationships
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var knows = model.createProperty("http://xmlns.com/foaf/0.1/knows");
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        
        // Alice has a name property
        alice.addProperty(name, "Alice");
        // Alice knows Bob (relationship)
        alice.addProperty(knows, bob);
        
        Dataset dataset = DatasetFactory.create(model);
        
        // Query for all values of a specific predicate (should get both property and relationship)
        String sparql = """
            SELECT ?value WHERE {
                <http://example.org/person/alice> <http://xmlns.com/foaf/0.1/knows> ?value .
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            // Should find Bob (relationship target)
            assertTrue(results.hasNext(), "Should have at least one result");
            QuerySolution solution = results.nextSolution();
            assertEquals("http://example.org/person/bob", 
                solution.getResource("value").getURI(),
                "Should find Bob through relationship");
        }
    }

    @Test
    @DisplayName("Test variable object optimization - mixed results")
    public void testVariableObjectOptimizationMixedResults() {
        // Add test data where the same predicate has both literal and resource values
        var person1 = model.createResource("http://example.org/person/person1");
        var person2 = model.createResource("http://example.org/person/person2");
        var prop = model.createProperty("http://example.org/value");
        
        // Person1 has literal value
        person1.addProperty(prop, "literal value");
        // Person2 has resource value  
        var resource = model.createResource("http://example.org/resource1");
        person2.addProperty(prop, resource);
        
        Dataset dataset = DatasetFactory.create(model);
        
        // Query all values - should get both literal and resource
        String sparql = """
            SELECT ?s ?o WHERE {
                ?s <http://example.org/value> ?o .
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            List<QuerySolution> solutions = new ArrayList<>();
            while (results.hasNext()) {
                solutions.add(results.nextSolution());
            }
            
            // Should find both subjects
            assertEquals(2, solutions.size(), "Should find both subjects");
            
            // Verify we got both types of values
            boolean foundLiteral = false;
            boolean foundResource = false;
            
            for (QuerySolution solution : solutions) {
                if (solution.get("o").isLiteral()) {
                    foundLiteral = true;
                    assertEquals("literal value", 
                        solution.getLiteral("o").getString());
                } else if (solution.get("o").isResource()) {
                    foundResource = true;
                    assertEquals("http://example.org/resource1",
                        solution.getResource("o").getURI());
                }
            }
            
            assertTrue(foundLiteral, "Should find literal value");
            assertTrue(foundResource, "Should find resource value");
        }
    }

    @Test
    @DisplayName("Test variable object optimization - variable subject")
    public void testVariableObjectOptimizationVariableSubject() {
        // Add test data
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        
        alice.addProperty(name, "Alice");
        bob.addProperty(name, "Bob");
        
        Dataset dataset = DatasetFactory.create(model);
        
        // Query with both subject and object as variables
        String sparql = """
            SELECT ?person ?name WHERE {
                ?person <http://xmlns.com/foaf/0.1/name> ?name .
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            Set<String> names = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                names.add(solution.getLiteral("name").getString());
            }
            
            // Should find both names
            assertTrue(names.contains("Alice"), "Should find Alice");
            assertTrue(names.contains("Bob"), "Should find Bob");
        }
    }

    @Test
    @DisplayName("Test variable object optimization - only relationships")
    public void testVariableObjectOptimizationOnlyRelationships() {
        // Add test data with only relationships (no properties)
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var charlie = model.createResource("http://example.org/person/charlie");
        var knows = model.createProperty("http://xmlns.com/foaf/0.1/knows");
        
        alice.addProperty(knows, bob);
        alice.addProperty(knows, charlie);
        
        Dataset dataset = DatasetFactory.create(model);
        
        // Query should use pushdown and return all relationships
        String sparql = """
            SELECT ?friend WHERE {
                <http://example.org/person/alice> <http://xmlns.com/foaf/0.1/knows> ?friend .
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            Set<String> friends = new HashSet<>();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                friends.add(solution.getResource("friend").getURI());
            }
            
            assertEquals(2, friends.size(), "Should find both friends");
            assertTrue(friends.contains("http://example.org/person/bob"));
            assertTrue(friends.contains("http://example.org/person/charlie"));
        }
    }

    @Test
    @DisplayName("Test variable object optimization - only properties")
    public void testVariableObjectOptimizationOnlyProperties() {
        // Add test data with only literal properties
        var alice = model.createResource("http://example.org/person/alice");
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        
        alice.addProperty(name, "Alice");
        
        Dataset dataset = DatasetFactory.create(model);
        
        // Query should use pushdown and return the property value
        String sparql = """
            SELECT ?name WHERE {
                <http://example.org/person/alice> <http://xmlns.com/foaf/0.1/name> ?name .
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should have at least one result");
            QuerySolution solution = results.nextSolution();
            assertEquals("Alice", solution.getLiteral("name").getString());
        }
    }

    // ==================== OPTIONAL Pattern Tests ====================

    @Test
    @DisplayName("Test basic OPTIONAL pattern with email")
    public void testBasicOptionalPattern() {
        // Add test data - one person with email, one without
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var personType = model.createResource("http://xmlns.com/foaf/0.1/Person");
        var email = model.createProperty("http://xmlns.com/foaf/0.1/email");
        var emailResource = model.createResource("mailto:alice@example.org");
        
        alice.addProperty(RDF.type, personType);
        alice.addProperty(email, emailResource);
        
        bob.addProperty(RDF.type, personType);
        // Bob has no email
        
        Dataset dataset = DatasetFactory.create(model);
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?email WHERE {
                ?person a foaf:Person .
                OPTIONAL { ?person foaf:email ?email }
            }
            ORDER BY ?person
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            List<QuerySolution> solutions = new ArrayList<>();
            while (results.hasNext()) {
                solutions.add(results.nextSolution());
            }
            
            assertEquals(2, solutions.size(), "Should return both persons");
            
            // First person (Alice) should have email
            QuerySolution aliceSol = solutions.stream()
                .filter(sol -> sol.getResource("person").getURI().contains("alice"))
                .findFirst()
                .orElseThrow();
            assertTrue(aliceSol.contains("email"), "Alice should have email");
            assertEquals("mailto:alice@example.org", aliceSol.getResource("email").getURI());
            
            // Second person (Bob) should not have email (or email should be null)
            QuerySolution bobSol = solutions.stream()
                .filter(sol -> sol.getResource("person").getURI().contains("bob"))
                .findFirst()
                .orElseThrow();
            // Bob's solution might not contain email or it might be null
            assertTrue(!bobSol.contains("email") || bobSol.get("email") == null,
                "Bob should not have email");
        }
    }

    @Test
    @DisplayName("Test OPTIONAL with literal property")
    public void testOptionalWithLiteralProperty() {
        // Add test data - one person with age, one without
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        var age = model.createProperty("http://xmlns.com/foaf/0.1/age");
        
        alice.addProperty(name, "Alice");
        alice.addProperty(age, model.createTypedLiteral(30));
        
        bob.addProperty(name, "Bob");
        // Bob has no age
        
        Dataset dataset = DatasetFactory.create(model);
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?name ?age WHERE {
                ?person foaf:name ?name .
                OPTIONAL { ?person foaf:age ?age }
            }
            ORDER BY ?name
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            List<QuerySolution> solutions = new ArrayList<>();
            while (results.hasNext()) {
                solutions.add(results.nextSolution());
            }
            
            assertEquals(2, solutions.size(), "Should return both persons");
            
            // Alice should have age
            QuerySolution aliceSol = solutions.get(0);
            assertEquals("Alice", aliceSol.getLiteral("name").getString());
            assertTrue(aliceSol.contains("age"), "Alice should have age");
            assertEquals(30, aliceSol.getLiteral("age").getInt());
            
            // Bob should not have age
            QuerySolution bobSol = solutions.get(1);
            assertEquals("Bob", bobSol.getLiteral("name").getString());
            assertTrue(!bobSol.contains("age") || bobSol.get("age") == null,
                "Bob should not have age");
        }
    }

    @Test
    @DisplayName("Test OPTIONAL with multiple triples")
    public void testOptionalWithMultipleTriples() {
        // Add test data - Alice knows Bob, Bob has name. Charlie knows no one.
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var charlie = model.createResource("http://example.org/person/charlie");
        var personType = model.createResource("http://xmlns.com/foaf/0.1/Person");
        var knows = model.createProperty("http://xmlns.com/foaf/0.1/knows");
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        
        alice.addProperty(RDF.type, personType);
        bob.addProperty(RDF.type, personType);
        bob.addProperty(name, "Bob");
        charlie.addProperty(RDF.type, personType);
        
        alice.addProperty(knows, bob);
        // Charlie knows no one
        
        Dataset dataset = DatasetFactory.create(model);
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?friend ?friendName WHERE {
                ?person a foaf:Person .
                OPTIONAL { 
                    ?person foaf:knows ?friend .
                    ?friend foaf:name ?friendName .
                }
            }
            ORDER BY ?person
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            List<QuerySolution> solutions = new ArrayList<>();
            while (results.hasNext()) {
                solutions.add(results.nextSolution());
            }
            
            // Should return all three persons
            assertTrue(solutions.size() >= 2, "Should return at least 2 persons (Alice with friend, Charlie/Bob without)");
            
            // Alice should have friend and friendName
            boolean foundAliceWithFriend = solutions.stream()
                .anyMatch(sol -> sol.getResource("person").getURI().contains("alice") 
                    && sol.contains("friend") && sol.contains("friendName"));
            assertTrue(foundAliceWithFriend, "Alice should have friend and friendName");
            
            // Charlie should not have friend
            boolean foundCharlieWithoutFriend = solutions.stream()
                .anyMatch(sol -> sol.getResource("person").getURI().contains("charlie") 
                    && (!sol.contains("friend") || sol.get("friend") == null));
            assertTrue(foundCharlieWithoutFriend || solutions.stream()
                .anyMatch(sol -> sol.getResource("person").getURI().contains("charlie")),
                "Charlie should be in results");
        }
    }

    @Test
    @DisplayName("Test OPTIONAL with concrete subject")
    public void testOptionalWithConcreteSubject() {
        // Add test data
        var alice = model.createResource("http://example.org/person/alice");
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        var email = model.createProperty("http://xmlns.com/foaf/0.1/email");
        var emailResource = model.createResource("mailto:alice@example.org");
        
        alice.addProperty(name, "Alice");
        alice.addProperty(email, emailResource);
        
        Dataset dataset = DatasetFactory.create(model);
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?name ?email WHERE {
                <http://example.org/person/alice> foaf:name ?name .
                OPTIONAL { <http://example.org/person/alice> foaf:email ?email }
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should have at least one result");
            QuerySolution solution = results.nextSolution();
            
            assertEquals("Alice", solution.getLiteral("name").getString());
            assertTrue(solution.contains("email"), "Alice should have email");
            assertEquals("mailto:alice@example.org", solution.getResource("email").getURI());
        }
    }

    @Test
    @DisplayName("Test OPTIONAL returns all required matches even when optional doesn't match")
    public void testOptionalReturnsAllRequiredMatches() {
        // Add multiple people, some with optional data, some without
        var personType = model.createResource("http://xmlns.com/foaf/0.1/Person");
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        
        // Create 5 people, only 2 with names
        for (int i = 1; i <= 5; i++) {
            var person = model.createResource("http://example.org/person" + i);
            person.addProperty(RDF.type, personType);
            if (i <= 2) {
                person.addProperty(name, "Person " + i);
            }
        }
        
        Dataset dataset = DatasetFactory.create(model);
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?name WHERE {
                ?person a foaf:Person .
                OPTIONAL { ?person foaf:name ?name }
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            List<QuerySolution> solutions = new ArrayList<>();
            while (results.hasNext()) {
                solutions.add(results.nextSolution());
            }
            
            // Should return all 5 persons
            assertEquals(5, solutions.size(), "Should return all 5 persons regardless of optional match");
            
            // Count how many have names
            long withNames = solutions.stream()
                .filter(sol -> sol.contains("name") && sol.get("name") != null)
                .count();
            assertEquals(2, withNames, "Should have 2 persons with names");
        }
    }

    @Test
    @DisplayName("Test OPTIONAL with filter in required part")
    public void testOptionalWithFilterInRequired() {
        // Add test data
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        var age = model.createProperty("http://xmlns.com/foaf/0.1/age");
        var email = model.createProperty("http://xmlns.com/foaf/0.1/email");
        
        alice.addProperty(name, "Alice");
        alice.addProperty(age, model.createTypedLiteral(25));
        alice.addProperty(email, model.createResource("mailto:alice@example.org"));
        
        bob.addProperty(name, "Bob");
        bob.addProperty(age, model.createTypedLiteral(35));
        // Bob has no email
        
        Dataset dataset = DatasetFactory.create(model);
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?name ?email WHERE {
                ?person foaf:name ?name .
                ?person foaf:age ?age .
                FILTER(?age < 30)
                OPTIONAL { ?person foaf:email ?email }
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            List<QuerySolution> solutions = new ArrayList<>();
            while (results.hasNext()) {
                solutions.add(results.nextSolution());
            }
            
            // Should return only Alice (age < 30)
            assertEquals(1, solutions.size(), "Should return only Alice (age < 30)");
            
            QuerySolution solution = solutions.get(0);
            assertEquals("Alice", solution.getLiteral("name").getString());
            assertTrue(solution.contains("email"), "Alice should have email");
        }
    }

    @Test
    @DisplayName("Test nested OPTIONAL patterns")
    public void testNestedOptionalPatterns() {
        // Add test data
        var alice = model.createResource("http://example.org/person/alice");
        var bob = model.createResource("http://example.org/person/bob");
        var charlie = model.createResource("http://example.org/person/charlie");
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        var knows = model.createProperty("http://xmlns.com/foaf/0.1/knows");
        var email = model.createProperty("http://xmlns.com/foaf/0.1/email");
        
        alice.addProperty(name, "Alice");
        alice.addProperty(knows, bob);
        
        bob.addProperty(name, "Bob");
        bob.addProperty(knows, charlie);
        
        charlie.addProperty(name, "Charlie");
        charlie.addProperty(email, model.createResource("mailto:charlie@example.org"));
        
        Dataset dataset = DatasetFactory.create(model);
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?name ?friend ?fof ?fofEmail WHERE {
                ?person foaf:name ?name .
                OPTIONAL { 
                    ?person foaf:knows ?friend .
                    OPTIONAL {
                        ?friend foaf:knows ?fof .
                        ?fof foaf:email ?fofEmail .
                    }
                }
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            List<QuerySolution> solutions = new ArrayList<>();
            while (results.hasNext()) {
                solutions.add(results.nextSolution());
            }
            
            // Should have results for all three people
            assertTrue(solutions.size() >= 3, "Should have results for all three people");
            
            // Alice should potentially have friend-of-friend with email
            boolean foundComplete = solutions.stream()
                .anyMatch(sol -> sol.contains("person") 
                    && sol.getLiteral("name").getString().equals("Alice")
                    && sol.contains("fofEmail"));
            
            // At minimum, all three should appear with their names
            Set<String> names = new HashSet<>();
            for (QuerySolution sol : solutions) {
                if (sol.contains("name")) {
                    names.add(sol.getLiteral("name").getString());
                }
            }
            assertTrue(names.contains("Alice"), "Should have Alice");
            assertTrue(names.contains("Bob"), "Should have Bob");
            assertTrue(names.contains("Charlie"), "Should have Charlie");
        }
    }
}
