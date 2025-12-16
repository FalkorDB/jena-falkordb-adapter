package com.falkordb.jena;

import com.falkordb.Driver;
import com.falkordb.FalkorDB;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FalkorDB-Jena adapter.
 * 
 * Prerequisites: FalkorDB must be running on localhost:6379
 * Run: docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
 */
public class FalkorDBGraphTest {
    
    private static final String TEST_GRAPH = "test_graph";
    
    private Model createTestModel() {
        return createTestModel(TEST_GRAPH);
    }
    
    private Model createTestModel(String graphName) {
        var model = FalkorDBModelFactory.createModel(graphName);
        assertNotNull(model, "Model should not be null after creation");
        // Get the underlying graph and clear it completely
        if (model.getGraph() instanceof FalkorDBGraph falkorGraph) {
            falkorGraph.clear();
        }
        return model;
    }
    
    @Test
    @DisplayName("Test creating and closing model")
    public void testCreateModel() {
        var model = createTestModel();
        try {
            assertNotNull(model, "Model should not be null");
            assertTrue(model.isEmpty(), "New model should be empty");
        } finally {
            model.close();
        }
    }
    
    @Test
    @DisplayName("Test adding a simple triple")  
    public void testAddTriple() {
        var model = createTestModel();
        try {
            var subject = model.createResource("http://test.example.org/person1");
            var predicate = model.createProperty("http://test.example.org/name");
            
            subject.addProperty(predicate, "John Doe");
            
            assertEquals(1, model.size(), "Model should contain exactly one triple");
            assertTrue(model.contains(subject, predicate), "Model should contain the added triple");
        } finally {
            model.close();
        }
    }
    
    @Test
    @DisplayName("Test adding multiple triples")
    public void testAddMultipleTriples() {
        var model = createTestModel();
        try {
            var person1 = model.createResource("http://test.example.org/person1");
            var person2 = model.createResource("http://test.example.org/person2");
            var name = model.createProperty("http://test.example.org/name");
            var age = model.createProperty("http://test.example.org/age");
            
            person1.addProperty(name, "John Doe");
            person1.addProperty(age, model.createTypedLiteral(30));
            person2.addProperty(name, "Jane Smith");
            person2.addProperty(age, model.createTypedLiteral(25));
            
            assertEquals(4, model.size(), "Model should contain exactly four triples");
        } finally {
            model.close();
        }
    }
    
    @Test
    @DisplayName("Test factory builder pattern")
    public void testFactoryBuilder() {
        var testModel = FalkorDBModelFactory.builder()
            .host("localhost")
            .port(6379)
            .graphName("builder_test_graph")
            .build();
        
        try {
            assertNotNull(testModel, "Builder should create a valid model");
            assertTrue(testModel.isEmpty(), "New model should be empty");
        } finally {
            testModel.close();
        }
    }
    
    @Test
    @DisplayName("Test model size")
    public void testModelSize() {
        var model = createTestModel();
        try {
            assertEquals(0, model.size(), "Empty model should have size 0");
            
            var subject = model.createResource("http://test.example.org/person1");
            var predicate = model.createProperty("http://test.example.org/name");
            subject.addProperty(predicate, "Test Person");
            
            assertEquals(1, model.size(), "Model with one triple should have size 1");
        } finally {
            model.close();
        }
    }
    
    @Test
    @DisplayName("Test remove triple")
    public void testRemoveTriple() {
        var model = createTestModel();
        try {
            var subject = model.createResource("http://test.example.org/person1");
            var predicate = model.createProperty("http://test.example.org/name");
            
            // Add triple
            var stmt = model.createStatement(subject, predicate, "John Doe");
            model.add(stmt);
            assertEquals(1, model.size());
            
            // Remove triple
            model.remove(stmt);
            assertEquals(0, model.size(), "Model should be empty after removing the triple");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test literals stored as properties")
    public void testLiteralsAsProperties() {
        var model = createTestModel();
        try {
            var person = model.createResource("http://test.example.org/person1");
            var name = model.createProperty("http://test.example.org/name");
            var age = model.createProperty("http://test.example.org/age");
            var email = model.createProperty("http://test.example.org/email");
            
            // Add multiple literal properties
            person.addProperty(name, "John Doe");
            person.addProperty(age, model.createTypedLiteral(30));
            person.addProperty(email, "john@example.org");
            
            assertEquals(3, model.size(), "Model should contain three triples");
            
            // Query for literals
            var nameStmt = person.getProperty(name);
            assertNotNull(nameStmt, "Name property should be retrievable");
            assertEquals("John Doe", nameStmt.getString(), "Name value should match");
            
            var ageStmt = person.getProperty(age);
            assertNotNull(ageStmt, "Age property should be retrievable");
            assertEquals(30, ageStmt.getInt(), "Age value should match");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test rdf:type creates labels")
    public void testRdfTypeCreatesLabels() {
        var model = createTestModel();
        try {
            var person = model.createResource("http://test.example.org/person1");
            var personType = model.createResource("http://test.example.org/Person");
            
            // Add rdf:type - should create a label
            person.addProperty(RDF.type, personType);
            
            assertEquals(1, model.size(), "Model should contain the type triple");
            assertTrue(model.contains(person, RDF.type, personType), 
                       "Model should contain the type statement");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test querying rdf:type triples")
    public void testQueryRdfType() {
        var model = createTestModel();
        try {
            var person1 = model.createResource("http://test.example.org/person1");
            var person2 = model.createResource("http://test.example.org/person2");
            var personType = model.createResource("http://test.example.org/Person");
            
            person1.addProperty(RDF.type, personType);
            person2.addProperty(RDF.type, personType);
            
            // Query for all resources of type Person
            var iter = model.listStatements(null, RDF.type, personType);
            var count = iter.toList().size();
            
            assertEquals(2, count, "Should find two Person instances");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test delete literal property")
    public void testDeleteLiteralProperty() {
        var model = createTestModel();
        try {
            var person = model.createResource("http://test.example.org/person1");
            var name = model.createProperty("http://test.example.org/name");
            var age = model.createProperty("http://test.example.org/age");
            
            person.addProperty(name, "John Doe");
            person.addProperty(age, model.createTypedLiteral(30));
            assertEquals(2, model.size());
            
            // Remove age property
            person.removeAll(age);
            assertEquals(1, model.size(), "Should have one property remaining after deletion");
            assertFalse(model.contains(person, age), "Age property should be removed");
            assertTrue(model.contains(person, name), "Name property should still exist");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test delete rdf:type (label)")
    public void testDeleteRdfType() {
        var model = createTestModel();
        try {
            var person = model.createResource("http://test.example.org/person1");
            var personType = model.createResource("http://test.example.org/Person");
            
            person.addProperty(RDF.type, personType);
            assertEquals(1, model.size());
            
            // Remove type
            person.removeAll(RDF.type);
            assertEquals(0, model.size(), "Model should be empty after removing type");
            assertFalse(model.contains(person, RDF.type, personType), 
                        "Type should be removed");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test custom driver constructor")
    public void testCustomDriverConstructor() throws Exception {
        var customDriver = FalkorDB.driver("localhost", 6379);
        try {
            var graph = new FalkorDBGraph(customDriver, "custom_driver_test");
            graph.clear();
            
            var model = ModelFactory.createModelForGraph(graph);
            try {
                assertNotNull(model, "Model with custom driver should not be null");
                assertTrue(model.isEmpty(), "New model should be empty");
                
                // Test basic operations
                var person = model.createResource("http://test.example.org/person1");
                var name = model.createProperty("http://test.example.org/name");
                person.addProperty(name, "Test");
                
                assertEquals(1, model.size(), "Model should contain one triple");
            } finally {
                model.close();
            }
        } finally {
            customDriver.close();
        }
    }

    @Test
    @DisplayName("Test factory with custom driver")
    public void testFactoryWithCustomDriver() throws Exception {
        var customDriver = FalkorDB.driver("localhost", 6379);
        try {
            var model = FalkorDBModelFactory.createModel(customDriver, "factory_driver_test");
            try {
                assertNotNull(model, "Factory should create model with custom driver");
                
                // Clear and test
                if (model.getGraph() instanceof FalkorDBGraph falkorGraph) {
                    falkorGraph.clear();
                }
                
                var person = model.createResource("http://test.example.org/person1");
                var name = model.createProperty("http://test.example.org/ename");
                person.addProperty(name, "Test");
                
                assertEquals(1, model.size());
            } finally {
                model.close();
            }
        } finally {
            customDriver.close();
        }
    }

    @Test
    @DisplayName("Test builder with custom driver")
    public void testBuilderWithCustomDriver() throws Exception {
        var customDriver = FalkorDB.driver("localhost", 6379);
        try {
            var model = FalkorDBModelFactory.builder()
                .driver(customDriver)
                .graphName("builder_driver_test")
                .build();
            
            try {
                assertNotNull(model, "Builder should create model with custom driver");
                
                // Clear and test
                if (model.getGraph() instanceof FalkorDBGraph falkorGraph) {
                    falkorGraph.clear();
                }
                
                var person = model.createResource("http://test.example.org/person1");
                var name = model.createProperty("http://test.example.org/name");
                person.addProperty(name, "Test");
                
                assertEquals(1, model.size());
            } finally {
                model.close();
            }
        } finally {
            customDriver.close();
        }
    }

    @Test
    @DisplayName("Test mixed literal and resource triples")
    public void testMixedTriples() {
        var model = createTestModel();
        try {
            var person1 = model.createResource("http://test.example.org/person1");
            var person2 = model.createResource("http://test.example.org/person2");
            var name = model.createProperty("http://test.example.org/name");
            var knows = model.createProperty("http://test.example.org/knows");
            
            // Add literal properties
            person1.addProperty(name, "John Doe");
            person2.addProperty(name, "Jane Smith");
            
            // Add relationship
            person1.addProperty(knows, person2);
            
            assertEquals(3, model.size(), "Model should contain three triples");
            
            // Query literals
            assertEquals("John Doe", person1.getProperty(name).getString());
            assertEquals("Jane Smith", person2.getProperty(name).getString());
            
            // Query relationship
            var knowsStmt = person1.getProperty(knows);
            assertNotNull(knowsStmt, "Knows relationship should exist");
            assertEquals(person2, knowsStmt.getResource(), "Should know person2");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test querying with patterns")
    public void testQueryPatterns() {
        var model = createTestModel();
        try {
            var person1 = model.createResource("http://test.example.org/person1");
            var person2 = model.createResource("http://test.example.org/person2");
            var name = model.createProperty("http://test.example.org/name");
            
            person1.addProperty(name, "John Doe");
            person2.addProperty(name, "Jane Smith");
            
            // Query all name properties
            var iter = model.listStatements(null, name, (RDFNode) null);
            var count = 0;
            while (iter.hasNext()) {
                var stmt = iter.next();
                assertNotNull(stmt.getObject().asLiteral());
                count++;
            }
            
            assertEquals(2, count, "Should find two name properties");
        } finally {
            model.close();
        }
    }

    // Tests for optimized methods: graphBaseContains, graphBaseSize, isEmpty

    @Test
    @DisplayName("Test isEmpty on empty graph")
    public void testIsEmptyOnEmptyGraph() {
        var model = createTestModel();
        try {
            assertTrue(model.isEmpty(), "New empty model should be empty");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test isEmpty on non-empty graph")
    public void testIsEmptyOnNonEmptyGraph() {
        var model = createTestModel();
        try {
            var subject = model.createResource("http://test.example.org/person1");
            var name = model.createProperty("http://test.example.org/name");
            subject.addProperty(name, "Test Person");

            assertFalse(model.isEmpty(), "Model with data should not be empty");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test isEmpty after adding and removing triple")
    public void testIsEmptyAfterAddAndRemove() {
        var model = createTestModel();
        try {
            var subject = model.createResource("http://test.example.org/person1");
            var name = model.createProperty("http://test.example.org/name");
            var stmt = model.createStatement(subject, name, "Test Person");
            
            model.add(stmt);
            assertFalse(model.isEmpty(), "Model with data should not be empty");
            
            model.remove(stmt);
            assertTrue(model.isEmpty(), "Model should be empty after removing all data");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test graphBaseContains with literal property")
    public void testContainsLiteralProperty() {
        var model = createTestModel();
        try {
            var subject = model.createResource("http://test.example.org/person1");
            var name = model.createProperty("http://test.example.org/name");
            subject.addProperty(name, "John Doe");

            // Test contains for exact literal match
            assertTrue(model.contains(subject, name, model.createLiteral("John Doe")),
                "Model should contain the literal property triple");

            // Test contains for non-existent literal
            assertFalse(model.contains(subject, name, model.createLiteral("Jane Doe")),
                "Model should not contain a non-existent literal");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test graphBaseContains with rdf:type")
    public void testContainsRdfType() {
        var model = createTestModel();
        try {
            var subject = model.createResource("http://test.example.org/person1");
            var personType = model.createResource("http://test.example.org/Person");
            
            subject.addProperty(RDF.type, personType);

            // Test contains for exact type match
            assertTrue(model.contains(subject, RDF.type, personType),
                "Model should contain the rdf:type triple");

            // Test contains for non-existent type
            var companyType = model.createResource("http://test.example.org/Company");
            assertFalse(model.contains(subject, RDF.type, companyType),
                "Model should not contain a non-existent rdf:type");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test graphBaseContains with relationship")
    public void testContainsRelationship() {
        var model = createTestModel();
        try {
            var person1 = model.createResource("http://test.example.org/person1");
            var person2 = model.createResource("http://test.example.org/person2");
            var knows = model.createProperty("http://test.example.org/knows");
            
            person1.addProperty(knows, person2);

            // Test contains for exact relationship match
            assertTrue(model.contains(person1, knows, person2),
                "Model should contain the relationship triple");

            // Test contains for non-existent relationship
            var person3 = model.createResource("http://test.example.org/person3");
            assertFalse(model.contains(person1, knows, person3),
                "Model should not contain a non-existent relationship");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test graphBaseSize with mixed triples")
    public void testSizeWithMixedTriples() {
        var model = createTestModel();
        try {
            assertEquals(0, model.size(), "Empty model should have size 0");

            // Test with literal properties only on one subject
            var person = model.createResource("http://test.example.org/person1");
            var name = model.createProperty("http://test.example.org/name");
            var age = model.createProperty("http://test.example.org/age");

            person.addProperty(name, "John Doe");
            assertEquals(1, model.size(), "Size should be 1 after adding literal property");

            person.addProperty(age, model.createTypedLiteral(30));
            assertEquals(2, model.size(), "Size should be 2 after adding another literal");

            // Test with rdf:type on a separate subject
            var animal = model.createResource("http://test.example.org/animal1");
            var animalType = model.createResource("http://test.example.org/Animal");
            animal.addProperty(RDF.type, animalType);
            assertEquals(3, model.size(), "Size should be 3 after adding rdf:type");

            // Test with relationship on separate subjects
            var person2 = model.createResource("http://test.example.org/person2");
            var person3 = model.createResource("http://test.example.org/person3");
            var knows = model.createProperty("http://test.example.org/knows");
            person2.addProperty(knows, person3);
            assertEquals(4, model.size(), "Size should be 4 after adding relationship");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test contains with concrete triple")
    public void testContainsConcreteTriple() {
        var model = createTestModel();
        try {
            var subject = model.createResource("http://test.example.org/person1");
            var name = model.createProperty("http://test.example.org/name");
            var stmt = model.createStatement(subject, name, "Test Name");
            
            assertFalse(model.contains(stmt), "Should not contain statement before adding");
            
            model.add(stmt);
            assertTrue(model.contains(stmt), "Should contain statement after adding");
            
            model.remove(stmt);
            assertFalse(model.contains(stmt), "Should not contain statement after removing");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test storing and retrieving all typed literals")
    public void testAllTypedLiterals() {
        var model = createTestModel();
        try {
            var resource = model.createResource("http://test.example.org/resource1");
            
            // Test Integer
            var intProp = model.createProperty("http://test.example.org/intValue");
            resource.addProperty(intProp, model.createTypedLiteral(42));
            
            // Test Long
            var longProp = model.createProperty("http://test.example.org/longValue");
            resource.addProperty(longProp, model.createTypedLiteral(9999999999L));
            
            // Test Double
            var doubleProp = model.createProperty("http://test.example.org/doubleValue");
            resource.addProperty(doubleProp, model.createTypedLiteral(3.14159));
            
            // Test Float
            var floatProp = model.createProperty("http://test.example.org/floatValue");
            resource.addProperty(floatProp, model.createTypedLiteral(2.71828f));
            
            // Test Boolean true
            var boolTrueProp = model.createProperty("http://test.example.org/boolTrue");
            resource.addProperty(boolTrueProp, model.createTypedLiteral(true));
            
            // Test Boolean false
            var boolFalseProp = model.createProperty("http://test.example.org/boolFalse");
            resource.addProperty(boolFalseProp, model.createTypedLiteral(false));
            
            // Test String
            var stringProp = model.createProperty("http://test.example.org/stringValue");
            resource.addProperty(stringProp, "Hello World");
            
            assertEquals(7, model.size(), "Model should contain 7 typed literal triples");
            
            // Verify retrieval of Integer
            var intStmt = resource.getProperty(intProp);
            assertNotNull(intStmt, "Integer property should be retrievable");
            assertEquals(42, intStmt.getInt(), "Integer value should match");
            
            // Verify retrieval of Long
            var longStmt = resource.getProperty(longProp);
            assertNotNull(longStmt, "Long property should be retrievable");
            assertEquals(9999999999L, longStmt.getLong(), "Long value should match");
            
            // Verify retrieval of Double
            var doubleStmt = resource.getProperty(doubleProp);
            assertNotNull(doubleStmt, "Double property should be retrievable");
            assertEquals(3.14159, doubleStmt.getDouble(), 0.00001, "Double value should match");
            
            // Verify retrieval of Float
            var floatStmt = resource.getProperty(floatProp);
            assertNotNull(floatStmt, "Float property should be retrievable");
            assertEquals(2.71828f, floatStmt.getFloat(), 0.00001f, "Float value should match");
            
            // Verify retrieval of Boolean true
            var boolTrueStmt = resource.getProperty(boolTrueProp);
            assertNotNull(boolTrueStmt, "Boolean true property should be retrievable");
            assertTrue(boolTrueStmt.getBoolean(), "Boolean true value should be true");
            
            // Verify retrieval of Boolean false
            var boolFalseStmt = resource.getProperty(boolFalseProp);
            assertNotNull(boolFalseStmt, "Boolean false property should be retrievable");
            assertFalse(boolFalseStmt.getBoolean(), "Boolean false value should be false");
            
            // Verify retrieval of String
            var stringStmt = resource.getProperty(stringProp);
            assertNotNull(stringStmt, "String property should be retrievable");
            assertEquals("Hello World", stringStmt.getString(), "String value should match");
            
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test pattern matching with Number and Boolean instances")
    public void testPatternMatchingLiterals() {
        var model = createTestModel();
        try {
            var resource = model.createResource("http://test.example.org/pattern1");
            
            // Add different number types to ensure pattern matching works
            var prop1 = model.createProperty("http://test.example.org/prop1");
            var prop2 = model.createProperty("http://test.example.org/prop2");
            var prop3 = model.createProperty("http://test.example.org/prop3");
            
            resource.addProperty(prop1, model.createTypedLiteral(100));
            resource.addProperty(prop2, model.createTypedLiteral(200.5));
            resource.addProperty(prop3, model.createTypedLiteral(true));
            
            assertEquals(3, model.size(), "Should have 3 properties");
            
            // Verify all can be retrieved (tests pattern matching path)
            assertNotNull(resource.getProperty(prop1));
            assertNotNull(resource.getProperty(prop2));
            assertNotNull(resource.getProperty(prop3));
        } finally {
            model.close();
        }
    }
}