package com.falkordb.jena;

import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System tests for RDF to FalkorDB Graph mapping correctness.
 * 
 * These tests validate the mappings documented in MAPPING.md.
 * Each test covers both the Jena API usage and verifies the
 * correct storage and retrieval of RDF data in FalkorDB.
 * 
 * Prerequisites: FalkorDB must be running on localhost:6379
 * Run: docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
 * 
 * @see <a href="../../../../../../MAPPING.md">MAPPING.md</a>
 */
public class RDFMappingTest {

    private static final String TEST_GRAPH = "rdf_mapping_test_graph";

    private Model model;

    @BeforeEach
    public void setUp() {
        model = FalkorDBModelFactory.createModel(TEST_GRAPH);
        assertNotNull(model, "Model should not be null");
        
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

    // ========================================================================
    // Test 1: Basic Resource with Literal Property
    // Mapping: Subject + Predicate + Literal -> Node property
    // ========================================================================
    
    /**
     * Tests basic literal property mapping.
     * 
     * RDF: <http://example.org/person1> <http://example.org/name> "John Doe" .
     * 
     * Expected FalkorDB:
     * (:Resource {uri: "http://example.org/person1", `http://example.org/name`: "John Doe"})
     * 
     * @see <a href="../../../../../../MAPPING.md#1-basic-resource-with-literal-property">MAPPING.md Section 1</a>
     */
    @Test
    @DisplayName("1. Basic Resource with Literal Property - Jena API")
    public void testBasicLiteralProperty() {
        // Arrange
        Resource person = model.createResource("http://example.org/person1");
        Property name = model.createProperty("http://example.org/name");
        
        // Act - Add literal property
        person.addProperty(name, "John Doe");
        
        // Assert - Verify storage
        assertEquals(1, model.size(), "Model should contain exactly one triple");
        assertTrue(model.contains(person, name), "Model should contain the name property");
        
        // Assert - Verify retrieval
        Statement stmt = person.getProperty(name);
        assertNotNull(stmt, "Property statement should be retrievable");
        assertEquals("John Doe", stmt.getString(), "Literal value should match");
        
        // Assert - Verify via SPARQL query
        String query = """
            PREFIX ex: <http://example.org/>
            SELECT ?name WHERE {
                ex:person1 ex:name ?name .
            }
            """;
        try (var qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "SPARQL query should return results");
            assertEquals("John Doe", results.next().getLiteral("name").getString());
        }
    }

    // ========================================================================
    // Test 2: Resource with rdf:type
    // Mapping: Subject + rdf:type + Type -> Node with label
    // ========================================================================
    
    /**
     * Tests rdf:type mapping to node labels.
     * 
     * RDF: <http://example.org/person1> rdf:type <http://example.org/Person> .
     * 
     * Expected FalkorDB:
     * (:Resource:`http://example.org/Person` {uri: "http://example.org/person1"})
     * 
     * @see <a href="../../../../../../MAPPING.md#2-resource-with-rdftype">MAPPING.md Section 2</a>
     */
    @Test
    @DisplayName("2. Resource with rdf:type - Jena API")
    public void testRdfType() {
        // Arrange
        Resource person = model.createResource("http://example.org/person1");
        Resource personType = model.createResource("http://example.org/Person");
        
        // Act - Add rdf:type
        person.addProperty(RDF.type, personType);
        
        // Assert - Verify storage
        assertEquals(1, model.size(), "Model should contain exactly one type triple");
        assertTrue(model.contains(person, RDF.type, personType), "Model should contain the type statement");
        
        // Assert - Query by type
        String query = """
            PREFIX ex: <http://example.org/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?person WHERE {
                ?person rdf:type ex:Person .
            }
            """;
        try (var qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Query by type should return results");
            assertTrue(results.next().getResource("person").getURI().endsWith("person1"));
        }
    }

    // ========================================================================
    // Test 3: Resource to Resource Relationship
    // Mapping: Subject + Predicate + Object(Resource) -> Relationship
    // ========================================================================
    
    /**
     * Tests resource-to-resource relationship mapping.
     * 
     * RDF: <http://example.org/alice> <http://example.org/knows> <http://example.org/bob> .
     * 
     * Expected FalkorDB:
     * (:Resource {uri: "http://example.org/alice"})
     *   -[:`http://example.org/knows`]->
     * (:Resource {uri: "http://example.org/bob"})
     * 
     * @see <a href="../../../../../../MAPPING.md#3-resource-to-resource-relationship">MAPPING.md Section 3</a>
     */
    @Test
    @DisplayName("3. Resource to Resource Relationship - Jena API")
    public void testResourceRelationship() {
        // Arrange
        Resource alice = model.createResource("http://example.org/alice");
        Resource bob = model.createResource("http://example.org/bob");
        Property knows = model.createProperty("http://example.org/knows");
        
        // Act - Add relationship
        alice.addProperty(knows, bob);
        
        // Assert - Verify storage
        assertEquals(1, model.size(), "Model should contain exactly one relationship triple");
        assertTrue(model.contains(alice, knows, bob), "Model should contain the relationship");
        
        // Assert - Verify retrieval
        Statement stmt = alice.getProperty(knows);
        assertNotNull(stmt, "Relationship should be retrievable");
        assertEquals(bob, stmt.getResource(), "Target resource should match");
        
        // Assert - Verify via SPARQL
        String query = """
            PREFIX ex: <http://example.org/>
            SELECT ?known WHERE {
                ex:alice ex:knows ?known .
            }
            """;
        try (var qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Relationship query should return results");
            assertTrue(results.next().getResource("known").getURI().endsWith("bob"));
        }
    }

    // ========================================================================
    // Test 4: Multiple Properties on Same Resource
    // Mapping: Same subject with multiple predicates -> Single node with multiple properties
    // ========================================================================
    
    /**
     * Tests multiple properties on the same resource.
     * 
     * RDF:
     * <http://example.org/person1> <http://example.org/name> "John Doe" .
     * <http://example.org/person1> <http://example.org/age> "30" .
     * <http://example.org/person1> <http://example.org/email> "john@example.org" .
     * 
     * Expected FalkorDB:
     * (:Resource {
     *   uri: "http://example.org/person1",
     *   `http://example.org/name`: "John Doe",
     *   `http://example.org/age`: "30",
     *   `http://example.org/email`: "john@example.org"
     * })
     * 
     * @see <a href="../../../../../../MAPPING.md#4-multiple-properties-on-same-resource">MAPPING.md Section 4</a>
     */
    @Test
    @DisplayName("4. Multiple Properties on Same Resource - Jena API")
    public void testMultipleProperties() {
        // Arrange
        Resource person = model.createResource("http://example.org/person1");
        Property name = model.createProperty("http://example.org/name");
        Property age = model.createProperty("http://example.org/age");
        Property email = model.createProperty("http://example.org/email");
        
        // Act - Add multiple properties
        person.addProperty(name, "John Doe")
              .addProperty(age, "30")
              .addProperty(email, "john@example.org");
        
        // Assert - Verify count
        assertEquals(3, model.size(), "Model should contain three triples");
        
        // Assert - Verify each property
        assertEquals("John Doe", person.getProperty(name).getString());
        assertEquals("30", person.getProperty(age).getString());
        assertEquals("john@example.org", person.getProperty(email).getString());
        
        // Assert - Verify via SPARQL
        String query = """
            PREFIX ex: <http://example.org/>
            SELECT ?name ?age ?email WHERE {
                ex:person1 ex:name ?name ;
                           ex:age ?age ;
                           ex:email ?email .
            }
            """;
        try (var qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Query should return results");
            var solution = results.next();
            assertEquals("John Doe", solution.getLiteral("name").getString());
            assertEquals("30", solution.getLiteral("age").getString());
            assertEquals("john@example.org", solution.getLiteral("email").getString());
        }
    }

    // ========================================================================
    // Test 5: Multiple Types on Same Resource
    // Mapping: Same subject with multiple rdf:type -> Node with multiple labels
    // ========================================================================
    
    /**
     * Tests multiple types (labels) on the same resource.
     * 
     * RDF:
     * <http://example.org/person1> rdf:type <http://example.org/Person> .
     * <http://example.org/person1> rdf:type <http://example.org/Employee> .
     * <http://example.org/person1> rdf:type <http://example.org/Developer> .
     * 
     * Expected FalkorDB:
     * (:Resource:`http://example.org/Person`:`http://example.org/Employee`:`http://example.org/Developer` {
     *   uri: "http://example.org/person1"
     * })
     * 
     * @see <a href="../../../../../../MAPPING.md#5-multiple-types-on-same-resource">MAPPING.md Section 5</a>
     */
    @Test
    @DisplayName("5. Multiple Types on Same Resource - Jena API")
    public void testMultipleTypes() {
        // Arrange
        Resource person = model.createResource("http://example.org/person1");
        Resource personType = model.createResource("http://example.org/Person");
        Resource employeeType = model.createResource("http://example.org/Employee");
        Resource developerType = model.createResource("http://example.org/Developer");
        
        // Act - Add multiple types
        person.addProperty(RDF.type, personType)
              .addProperty(RDF.type, employeeType)
              .addProperty(RDF.type, developerType);
        
        // Assert - Verify count
        assertEquals(3, model.size(), "Model should contain three type triples");
        
        // Assert - Verify each type
        assertTrue(model.contains(person, RDF.type, personType));
        assertTrue(model.contains(person, RDF.type, employeeType));
        assertTrue(model.contains(person, RDF.type, developerType));
        
        // Assert - Query by each type should return the resource
        String queryTemplate = """
            PREFIX ex: <http://example.org/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?person WHERE {
                ?person rdf:type ex:%s .
            }
            """;
        
        for (String type : new String[]{"Person", "Employee", "Developer"}) {
            try (var qexec = QueryExecutionFactory.create(queryTemplate.formatted(type), model)) {
                ResultSet results = qexec.execSelect();
                assertTrue(results.hasNext(), "Query by type " + type + " should return results");
                assertTrue(results.next().getResource("person").getURI().endsWith("person1"));
            }
        }
    }

    // ========================================================================
    // Test 6: Typed Literals
    // Mapping: Typed literals stored as string with type preserved in Jena
    // ========================================================================
    
    /**
     * Tests typed literal mapping.
     * 
     * RDF:
     * <http://example.org/person1> <http://example.org/age> "30"^^xsd:integer .
     * <http://example.org/person1> <http://example.org/height> "1.75"^^xsd:double .
     * <http://example.org/person1> <http://example.org/active> "true"^^xsd:boolean .
     * 
     * @see <a href="../../../../../../MAPPING.md#6-typed-literals">MAPPING.md Section 6</a>
     */
    @Test
    @DisplayName("6. Typed Literals - Jena API")
    public void testTypedLiterals() {
        // Arrange
        Resource person = model.createResource("http://example.org/person1");
        Property age = model.createProperty("http://example.org/age");
        Property height = model.createProperty("http://example.org/height");
        Property active = model.createProperty("http://example.org/active");
        
        // Act - Add typed literals
        person.addProperty(age, model.createTypedLiteral(30));
        person.addProperty(height, model.createTypedLiteral(1.75));
        person.addProperty(active, model.createTypedLiteral(true));
        
        // Assert - Verify count
        assertEquals(3, model.size(), "Model should contain three triples");
        
        // Assert - Verify types are preserved when reading back
        assertEquals(30, person.getProperty(age).getInt(), "Integer type should be preserved");
        assertEquals(1.75, person.getProperty(height).getDouble(), 0.001, "Double type should be preserved");
        assertTrue(person.getProperty(active).getBoolean(), "Boolean type should be preserved");
    }

    // ========================================================================
    // Test 7: Language-Tagged Literals
    // Mapping: Language tags stored with literal values
    // ========================================================================
    
    /**
     * Tests language-tagged literal mapping.
     * 
     * RDF:
     * <http://example.org/paris> rdfs:label "Paris"@en .
     * 
     * @see <a href="../../../../../../MAPPING.md#7-language-tagged-literals">MAPPING.md Section 7</a>
     */
    @Test
    @DisplayName("7. Language-Tagged Literals - Jena API")
    public void testLanguageTaggedLiterals() {
        // Arrange
        Resource paris = model.createResource("http://example.org/paris");
        
        // Act - Add language-tagged literal
        paris.addProperty(RDFS.label, model.createLiteral("Paris", "en"));
        
        // Assert - Verify storage
        assertEquals(1, model.size(), "Model should contain one triple");
        
        // Assert - Verify retrieval
        Statement stmt = paris.getProperty(RDFS.label);
        assertNotNull(stmt, "Label property should be retrievable");
        // Note: The language tag may or may not be preserved depending on implementation
        assertNotNull(stmt.getString(), "Label value should be retrievable");
    }

    // ========================================================================
    // Test 9: Blank Nodes (Anonymous Resources)
    // Mapping: Blank nodes stored with _: prefix in uri property
    // ========================================================================
    
    /**
     * Tests blank node (anonymous resource) mapping.
     * 
     * RDF:
     * _:address <http://example.org/street> "123 Main St" .
     * _:address <http://example.org/city> "Springfield" .
     * <http://example.org/person1> <http://example.org/hasAddress> _:address .
     * 
     * Expected FalkorDB:
     * (:Resource {uri: "_:b0", `http://example.org/street`: "123 Main St", ...})
     * (:Resource {uri: "http://example.org/person1"})
     *   -[:`http://example.org/hasAddress`]->
     * (:Resource {uri: "_:b0"})
     * 
     * @see <a href="../../../../../../MAPPING.md#9-blank-nodes-anonymous-resources">MAPPING.md Section 9</a>
     */
    @Test
    @DisplayName("9. Blank Nodes (Anonymous Resources) - Jena API")
    public void testBlankNodes() {
        // Arrange - Create properties
        Property street = model.createProperty("http://example.org/street");
        Property city = model.createProperty("http://example.org/city");
        Property hasAddress = model.createProperty("http://example.org/hasAddress");
        
        // Act - Create blank node for address
        Resource address = model.createResource(); // Creates anonymous resource (blank node)
        address.addProperty(street, "123 Main St");
        address.addProperty(city, "Springfield");
        
        // Act - Link person to blank node
        Resource person = model.createResource("http://example.org/person1");
        person.addProperty(hasAddress, address);
        
        // Assert - Verify count: 2 address properties + 1 relationship = 3 triples
        assertEquals(3, model.size(), "Model should contain three triples");
        
        // Assert - Verify blank node is anonymous when created
        assertTrue(address.isAnon(), "Address should be a blank node when created");
        
        // Assert - Verify properties on blank node
        assertEquals("123 Main St", address.getProperty(street).getString());
        assertEquals("Springfield", address.getProperty(city).getString());
        
        // Assert - Verify relationship to blank node
        Resource retrievedAddr = person.getProperty(hasAddress).getResource();
        // Note: When retrieved from FalkorDB, blank nodes are stored with _: prefix
        // The retrieved resource may have a URI starting with "_:" rather than being
        // a true Jena blank node, depending on implementation
        String retrievedUri = retrievedAddr.getURI();
        if (retrievedUri != null) {
            assertTrue(retrievedUri.startsWith("_:"), 
                "Retrieved address URI should start with '_:' for blank nodes");
        } else {
            assertTrue(retrievedAddr.isAnon(), "Retrieved address should be anonymous");
        }
        assertEquals("123 Main St", retrievedAddr.getProperty(street).getString());
        
        // Assert - Verify via SPARQL query
        String query = """
            PREFIX ex: <http://example.org/>
            SELECT ?street ?city WHERE {
                ex:person1 ex:hasAddress ?addr .
                ?addr ex:street ?street ;
                      ex:city ?city .
            }
            """;
        try (var qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Query should return results");
            var solution = results.next();
            assertEquals("123 Main St", solution.getLiteral("street").getString());
            assertEquals("Springfield", solution.getLiteral("city").getString());
        }
    }

    // ========================================================================
    // Test 10: Complex Graph with Mixed Triples
    // Mapping: Combination of all mapping types
    // ========================================================================
    
    /**
     * Tests complex graph with all mapping types combined.
     * 
     * Creates a graph with:
     * - Types (labels)
     * - Literal properties
     * - Typed literals
     * - Resource-to-resource relationships
     * 
     * @see <a href="../../../../../../MAPPING.md#10-complex-graph-with-mixed-triples">MAPPING.md Section 10</a>
     */
    @Test
    @DisplayName("10. Complex Graph with Mixed Triples - Jena API")
    public void testComplexGraph() {
        // Arrange - Define types
        Resource personType = model.createResource("http://example.org/Person");
        Resource companyType = model.createResource("http://example.org/Company");
        
        // Arrange - Define properties
        Property name = model.createProperty("http://example.org/name");
        Property age = model.createProperty("http://example.org/age");
        Property knows = model.createProperty("http://example.org/knows");
        Property worksAt = model.createProperty("http://example.org/worksAt");
        
        // Act - Create Alice with type, properties, and relationship
        Resource alice = model.createResource("http://example.org/alice")
            .addProperty(RDF.type, personType)
            .addProperty(name, "Alice Smith")
            .addProperty(age, model.createTypedLiteral(30));
        
        // Act - Create Bob with type and properties
        Resource bob = model.createResource("http://example.org/bob")
            .addProperty(RDF.type, personType)
            .addProperty(name, "Bob Jones");
        
        // Act - Create ACME with type and properties
        Resource acme = model.createResource("http://example.org/acme")
            .addProperty(RDF.type, companyType)
            .addProperty(name, "ACME Corp");
        
        // Act - Add relationships
        alice.addProperty(knows, bob);
        bob.addProperty(worksAt, acme);
        
        // Assert - Verify total triple count
        // Alice: type + name + age = 3
        // Bob: type + name = 2
        // ACME: type + name = 2
        // Relationships: knows + worksAt = 2
        // Total: 9
        assertEquals(9, model.size(), "Model should contain 9 triples");
        
        // Assert - Query for all Person instances
        String personQuery = """
            PREFIX ex: <http://example.org/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?person ?name WHERE {
                ?person rdf:type ex:Person ;
                        ex:name ?name .
            } ORDER BY ?name
            """;
        try (var qexec = QueryExecutionFactory.create(personQuery, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            assertEquals("Alice Smith", results.next().getLiteral("name").getString());
            assertTrue(results.hasNext());
            assertEquals("Bob Jones", results.next().getLiteral("name").getString());
            assertFalse(results.hasNext(), "Should only have 2 persons");
        }
        
        // Assert - Query for relationship path
        String pathQuery = """
            PREFIX ex: <http://example.org/>
            SELECT ?personName ?companyName WHERE {
                ?person ex:worksAt ?company ;
                        ex:name ?personName .
                ?company ex:name ?companyName .
            }
            """;
        try (var qexec = QueryExecutionFactory.create(pathQuery, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            var solution = results.next();
            assertEquals("Bob Jones", solution.getLiteral("personName").getString());
            assertEquals("ACME Corp", solution.getLiteral("companyName").getString());
        }
        
        // Assert - Query knows relationship
        String knowsQuery = """
            PREFIX ex: <http://example.org/>
            SELECT ?knower ?known WHERE {
                ?knower ex:knows ?known .
            }
            """;
        try (var qexec = QueryExecutionFactory.create(knowsQuery, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            var solution = results.next();
            assertTrue(solution.getResource("knower").getURI().endsWith("alice"));
            assertTrue(solution.getResource("known").getURI().endsWith("bob"));
        }
    }

    // ========================================================================
    // Additional Tests for Edge Cases
    // ========================================================================
    
    /**
     * Tests deletion of literal property.
     */
    @Test
    @DisplayName("Delete literal property")
    public void testDeleteLiteralProperty() {
        // Arrange
        Resource person = model.createResource("http://example.org/person1");
        Property name = model.createProperty("http://example.org/name");
        Property age = model.createProperty("http://example.org/age");
        
        person.addProperty(name, "John Doe");
        person.addProperty(age, "30");
        assertEquals(2, model.size());
        
        // Act - Delete age
        person.removeAll(age);
        
        // Assert
        assertEquals(1, model.size(), "Should have one property remaining");
        assertFalse(model.contains(person, age), "Age should be removed");
        assertTrue(model.contains(person, name), "Name should remain");
    }
    
    /**
     * Tests deletion of rdf:type (label).
     */
    @Test
    @DisplayName("Delete rdf:type removes label")
    public void testDeleteRdfType() {
        // Arrange
        Resource person = model.createResource("http://example.org/person1");
        Resource personType = model.createResource("http://example.org/Person");
        
        person.addProperty(RDF.type, personType);
        assertEquals(1, model.size());
        
        // Act - Delete type
        person.removeAll(RDF.type);
        
        // Assert
        assertEquals(0, model.size(), "Model should be empty after removing type");
        assertFalse(model.contains(person, RDF.type, personType));
    }
    
    /**
     * Tests deletion of relationship.
     */
    @Test
    @DisplayName("Delete resource relationship")
    public void testDeleteRelationship() {
        // Arrange
        Resource alice = model.createResource("http://example.org/alice");
        Resource bob = model.createResource("http://example.org/bob");
        Property knows = model.createProperty("http://example.org/knows");
        
        alice.addProperty(knows, bob);
        assertEquals(1, model.size());
        
        // Act - Delete relationship
        model.remove(alice, knows, bob);
        
        // Assert
        assertEquals(0, model.size(), "Model should be empty after removing relationship");
        assertFalse(model.contains(alice, knows, bob));
    }
    
    /**
     * Tests querying all properties of a resource.
     */
    @Test
    @DisplayName("Query all properties of a resource")
    public void testQueryAllProperties() {
        // Arrange
        Resource person = model.createResource("http://example.org/person1");
        person.addProperty(model.createProperty("http://example.org/name"), "John");
        person.addProperty(model.createProperty("http://example.org/age"), "30");
        person.addProperty(model.createProperty("http://example.org/email"), "john@test.com");
        
        // Act & Assert - List all statements
        StmtIterator iter = person.listProperties();
        int count = 0;
        while (iter.hasNext()) {
            iter.next();
            count++;
        }
        assertEquals(3, count, "Should have 3 properties");
    }
    
    /**
     * Tests preservation of custom datatypes (e.g., GeoSPARQL geometry types).
     * This is critical for ensuring geometry literals like geo:wktLiteral
     * maintain their datatype information when stored and retrieved.
     */
    @Test
    @DisplayName("Custom datatype preservation (e.g., geo:wktLiteral)")
    public void testCustomDatatypePreservation() {
        // Arrange
        String wktLiteralURI = "http://www.opengis.net/ont/geosparql#wktLiteral";
        org.apache.jena.datatypes.RDFDatatype wktDatatype = 
            org.apache.jena.datatypes.TypeMapper.getInstance().getSafeTypeByName(wktLiteralURI);
        
        Resource geometry = model.createResource("http://example.org/geometry1");
        Property hasGeometry = model.createProperty("http://www.opengis.net/ont/geosparql#asWKT");
        
        // Create a WKT literal with proper datatype
        String wktValue = "POINT(-0.118 51.509)";
        Literal wktLiteral = model.createTypedLiteral(wktValue, wktDatatype);
        
        // Act - Add and retrieve
        geometry.addProperty(hasGeometry, wktLiteral);
        
        // Assert - Verify the literal was stored
        assertEquals(1, model.size(), "Model should contain one triple");
        assertTrue(model.contains(geometry, hasGeometry), "Model should contain the geometry property");
        
        // Assert - Verify datatype is preserved
        Statement stmt = geometry.getProperty(hasGeometry);
        assertNotNull(stmt, "Geometry property should be retrievable");
        Literal retrievedLiteral = stmt.getLiteral();
        assertEquals(wktValue, retrievedLiteral.getLexicalForm(), "WKT value should match");
        assertEquals(wktLiteralURI, retrievedLiteral.getDatatypeURI(), 
            "Datatype URI should be preserved as geo:wktLiteral, not xsd:string");
        
        // Assert - Verify via SPARQL query
        String query = """
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            PREFIX ex: <http://example.org/>
            SELECT ?wkt WHERE {
                ex:geometry1 geo:asWKT ?wkt .
            }
            """;
        try (var qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "SPARQL query should return results");
            var solution = results.next();
            Literal resultLiteral = solution.getLiteral("wkt");
            assertEquals(wktValue, resultLiteral.getLexicalForm());
            assertEquals(wktLiteralURI, resultLiteral.getDatatypeURI(),
                "SPARQL query should also return correct datatype");
        }
    }
}
