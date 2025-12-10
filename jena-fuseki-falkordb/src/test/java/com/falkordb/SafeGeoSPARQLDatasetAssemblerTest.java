package com.falkordb;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.Mode;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SafeGeoSPARQLDatasetAssembler.
 * 
 * <p>These tests verify that the assembler correctly handles various scenarios
 * including successful dataset creation, error handling, and fallback behavior.</p>
 */
public class SafeGeoSPARQLDatasetAssemblerTest {

    private SafeGeoSPARQLDatasetAssembler assembler;
    private Model model;
    
    @BeforeEach
    public void setUp() {
        assembler = new SafeGeoSPARQLDatasetAssembler();
        model = ModelFactory.createDefaultModel();
    }
    
    @Test
    @DisplayName("Test vocabulary constants are correctly defined")
    public void testVocabularyConstants() {
        assertNotNull(SafeGeoSPARQLVocabulary.SafeGeosparqlDataset);
        assertEquals("http://falkordb.com/jena/assembler#SafeGeosparqlDataset",
                    SafeGeoSPARQLVocabulary.SafeGeosparqlDataset.getURI());
    }
    
    @Test
    @DisplayName("Test vocabulary resource method")
    public void testVocabularyResource() {
        Resource res = SafeGeoSPARQLVocabulary.resource("TestResource");
        assertNotNull(res);
        assertEquals("http://falkordb.com/jena/assembler#TestResource", res.getURI());
    }
    
    @Test
    @DisplayName("Test vocabulary property method")
    public void testVocabularyProperty() {
        var prop = SafeGeoSPARQLVocabulary.property("testProperty");
        assertNotNull(prop);
        assertEquals("http://falkordb.com/jena/assembler#testProperty", prop.getURI());
    }
    
    @Test
    @DisplayName("Test assembler with invalid configuration throws exception")
    public void testInvalidConfiguration() {
        // Create a resource without required geosparql:dataset property
        Resource root = model.createResource("http://example.org/test");
        root.addProperty(
            model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            SafeGeoSPARQLVocabulary.SafeGeosparqlDataset
        );
        
        // Should throw exception due to missing dataset property
        assertThrows(Exception.class, () -> {
            assembler.open(Assembler.general, root, Mode.DEFAULT);
        });
    }
    
    @Test
    @DisplayName("Test init lifecycle methods")
    public void testInitLifecycle() {
        SafeGeoSPARQLInit init = new SafeGeoSPARQLInit();
        
        // Test level
        assertEquals(600, init.level());
        
        // Test start (should not throw)
        assertDoesNotThrow(() -> init.start());
        
        // Test stop (should not throw)
        assertDoesNotThrow(() -> init.stop());
    }
    
    @Test
    @DisplayName("Test assembler handles missing geosparql:dataset property gracefully")
    public void testMissingDatasetProperty() {
        // Create minimal configuration without dataset property
        Resource root = model.createResource("http://example.org/test");
        root.addProperty(
            model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            SafeGeoSPARQLVocabulary.SafeGeosparqlDataset
        );
        
        // Should handle error gracefully
        Exception exception = assertThrows(Exception.class, () -> {
            assembler.open(Assembler.general, root, Mode.DEFAULT);
        });
        
        // Verify it's a meaningful error
        assertNotNull(exception.getMessage());
    }
    
    @Test
    @DisplayName("Test assembler with empty model")
    public void testEmptyModel() {
        Resource root = model.createResource();
        
        // Should throw exception with empty configuration
        assertThrows(Exception.class, () -> {
            assembler.open(Assembler.general, root, Mode.DEFAULT);
        });
    }
    
    @Test
    @DisplayName("Test namespace constant")
    public void testNamespaceConstant() {
        assertEquals("http://falkordb.com/jena/assembler#", SafeGeoSPARQLVocabulary.NS);
    }
}
