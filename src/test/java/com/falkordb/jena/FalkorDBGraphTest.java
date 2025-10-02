package com.falkordb.jena;

import org.apache.jena.rdf.model.*;
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
}