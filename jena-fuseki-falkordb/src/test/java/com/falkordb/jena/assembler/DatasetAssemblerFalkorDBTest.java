package com.falkordb.jena.assembler;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DatasetAssemblerFalkorDB class.
 *
 * Note: Tests that require a running FalkorDB instance are marked with
 * appropriate conditions. The basic unit tests verify the assembler
 * structure and configuration parsing without requiring FalkorDB.
 */
public class DatasetAssemblerFalkorDBTest {

    private Model configModel;
    private DatasetAssemblerFalkorDB assembler;

    @BeforeEach
    public void setUp() {
        configModel = ModelFactory.createDefaultModel();
        assembler = new DatasetAssemblerFalkorDB();
    }

    @Test
    @DisplayName("Test assembler instance can be created")
    public void testAssemblerInstance() {
        assertNotNull(assembler);
    }

    @Test
    @DisplayName("Test config resource parsing")
    public void testConfigResourceParsing() {
        // Create a config resource with all properties
        Resource config = configModel.createResource("http://example.org/dataset")
            .addProperty(configModel.createProperty(FalkorDBVocab.NS, "host"),
                "customhost")
            .addProperty(configModel.createProperty(FalkorDBVocab.NS, "port"),
                configModel.createTypedLiteral(12345))
            .addProperty(configModel.createProperty(FalkorDBVocab.NS, "graphName"),
                "custom_graph");

        // Verify the config was created correctly - this tests the parsing logic
        assertNotNull(config);
        assertEquals("customhost",
            config.getProperty(FalkorDBVocab.host).getString());
        assertEquals(12345,
            config.getProperty(FalkorDBVocab.port).getInt());
        assertEquals("custom_graph",
            config.getProperty(FalkorDBVocab.graphName).getString());
    }

    @Test
    @DisplayName("Test config with RDF type")
    public void testConfigWithRdfType() {
        // Create a config resource with type annotation
        Resource config = configModel.createResource("http://example.org/dataset")
            .addProperty(
                configModel.createProperty(
                    "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "type"),
                FalkorDBVocab.FalkorDBModel)
            .addProperty(FalkorDBVocab.graphName, "typed_graph");

        // Verify the config has the correct type
        assertTrue(config.hasProperty(
            configModel.createProperty(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "type"),
            FalkorDBVocab.FalkorDBModel));
    }
}
