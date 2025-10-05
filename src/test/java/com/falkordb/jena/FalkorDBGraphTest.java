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
        Model model = FalkorDBModelFactory.createModel(graphName);
        assertNotNull(model, "Model should not be null after creation");
        // Get the underlying graph and clear it completely
        if (model.getGraph() instanceof FalkorDBGraph) {
            ((FalkorDBGraph) model.getGraph()).clear();
        }
        return model;
    }
    
    @Test
    @DisplayName("Test creating and closing model")
    public void testCreateModel() {
        Model model = createTestModel();
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
        Model model = createTestModel();
        try {
            Resource subject = model.createResource("http://test.example.org/person1");
            Property predicate = model.createProperty("http://test.example.org/name");
            
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
        Model model = createTestModel();
        try {
            Resource person1 = model.createResource("http://test.example.org/person1");
            Resource person2 = model.createResource("http://test.example.org/person2");
            Property name = model.createProperty("http://test.example.org/name");
            Property age = model.createProperty("http://test.example.org/age");
            
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
        Model testModel = FalkorDBModelFactory.builder()
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
        Model model = createTestModel();
        try {
            assertEquals(0, model.size(), "Empty model should have size 0");
            
            Resource subject = model.createResource("http://test.example.org/person1");
            Property predicate = model.createProperty("http://test.example.org/name");
            subject.addProperty(predicate, "Test Person");
            
            assertEquals(1, model.size(), "Model with one triple should have size 1");
        } finally {
            model.close();
        }
    }
    
    @Test
    @DisplayName("Test remove triple")
    public void testRemoveTriple() {
        Model model = createTestModel();
        try {
            Resource subject = model.createResource("http://test.example.org/person1");
            Property predicate = model.createProperty("http://test.example.org/name");
            
            // Add triple
            Statement stmt = model.createStatement(subject, predicate, "John Doe");
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
        Model model = createTestModel();
        try {
            Resource person = model.createResource("http://test.example.org/person1");
            Property name = model.createProperty("http://test.example.org/name");
            Property age = model.createProperty("http://test.example.org/age");
            Property email = model.createProperty("http://test.example.org/email");
            
            // Add multiple literal properties
            person.addProperty(name, "John Doe");
            person.addProperty(age, model.createTypedLiteral(30));
            person.addProperty(email, "john@example.org");
            
            assertEquals(3, model.size(), "Model should contain three triples");
            
            // Query for literals
            Statement nameStmt = person.getProperty(name);
            assertNotNull(nameStmt, "Name property should be retrievable");
            assertEquals("John Doe", nameStmt.getString(), "Name value should match");
            
            Statement ageStmt = person.getProperty(age);
            assertNotNull(ageStmt, "Age property should be retrievable");
            assertEquals(30, ageStmt.getInt(), "Age value should match");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test rdf:type creates labels")
    public void testRdfTypeCreatesLabels() {
        Model model = createTestModel();
        try {
            Resource person = model.createResource("http://test.example.org/person1");
            Resource personType = model.createResource("http://test.example.org/Person");
            
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
        Model model = createTestModel();
        try {
            Resource person1 = model.createResource("http://test.example.org/person1");
            Resource person2 = model.createResource("http://test.example.org/person2");
            Resource personType = model.createResource("http://test.example.org/Person");
            
            person1.addProperty(RDF.type, personType);
            person2.addProperty(RDF.type, personType);
            
            // Query for all resources of type Person
            StmtIterator iter = model.listStatements(null, RDF.type, personType);
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            
            assertEquals(2, count, "Should find two Person instances");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test delete literal property")
    public void testDeleteLiteralProperty() {
        Model model = createTestModel();
        try {
            Resource person = model.createResource("http://test.example.org/person1");
            Property name = model.createProperty("http://test.example.org/name");
            Property age = model.createProperty("http://test.example.org/age");
            
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
        Model model = createTestModel();
        try {
            Resource person = model.createResource("http://test.example.org/person1");
            Resource personType = model.createResource("http://test.example.org/Person");
            
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
        Driver customDriver = FalkorDB.driver("localhost", 6379);
        try {
            FalkorDBGraph graph = new FalkorDBGraph(customDriver, "custom_driver_test");
            graph.clear();
            
            Model model = ModelFactory.createModelForGraph(graph);
            try {
                assertNotNull(model, "Model with custom driver should not be null");
                assertTrue(model.isEmpty(), "New model should be empty");
                
                // Test basic operations
                Resource person = model.createResource("http://test.example.org/person1");
                Property name = model.createProperty("http://test.example.org/name");
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
        Driver customDriver = FalkorDB.driver("localhost", 6379);
        try {
            Model model = FalkorDBModelFactory.createModel(customDriver, "factory_driver_test");
            try {
                assertNotNull(model, "Factory should create model with custom driver");
                
                // Clear and test
                if (model.getGraph() instanceof FalkorDBGraph) {
                    ((FalkorDBGraph) model.getGraph()).clear();
                }
                
                Resource person = model.createResource("http://test.example.org/person1");
                Property name = model.createProperty("http://test.example.org/ename");
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
        Driver customDriver = FalkorDB.driver("localhost", 6379);
        try {
            Model model = FalkorDBModelFactory.builder()
                .driver(customDriver)
                .graphName("builder_driver_test")
                .build();
            
            try {
                assertNotNull(model, "Builder should create model with custom driver");
                
                // Clear and test
                if (model.getGraph() instanceof FalkorDBGraph) {
                    ((FalkorDBGraph) model.getGraph()).clear();
                }
                
                Resource person = model.createResource("http://test.example.org/person1");
                Property name = model.createProperty("http://test.example.org/name");
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
        Model model = createTestModel();
        try {
            Resource person1 = model.createResource("http://test.example.org/person1");
            Resource person2 = model.createResource("http://test.example.org/person2");
            Property name = model.createProperty("http://test.example.org/name");
            Property knows = model.createProperty("http://test.example.org/knows");
            
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
            Statement knowsStmt = person1.getProperty(knows);
            assertNotNull(knowsStmt, "Knows relationship should exist");
            assertEquals(person2, knowsStmt.getResource(), "Should know person2");
        } finally {
            model.close();
        }
    }

    @Test
    @DisplayName("Test querying with patterns")
    public void testQueryPatterns() {
        Model model = createTestModel();
        try {
            Resource person1 = model.createResource("http://test.example.org/person1");
            Resource person2 = model.createResource("http://test.example.org/person2");
            Property name = model.createProperty("http://test.example.org/name");
            
            person1.addProperty(name, "John Doe");
            person2.addProperty(name, "Jane Smith");
            
            // Query all name properties
            StmtIterator iter = model.listStatements(null, name, (RDFNode) null);
            int count = 0;
            while (iter.hasNext()) {
                Statement stmt = iter.next();
                assertNotNull(stmt.getObject().asLiteral());
                count++;
            }
            
            assertEquals(2, count, "Should find two name properties");
        } finally {
            model.close();
        }
    }
}