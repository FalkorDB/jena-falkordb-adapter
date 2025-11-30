package com.falkordb.jena.assembler;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FalkorDBAssembler class.
 *
 * Note: Tests that require a running FalkorDB instance are marked with
 * appropriate conditions. The basic unit tests verify the assembler
 * structure and configuration parsing without requiring FalkorDB.
 */
public class FalkorDBAssemblerTest {

    private Model configModel;

    @BeforeEach
    public void setUp() {
        configModel = ModelFactory.createDefaultModel();
    }

    @Test
    @DisplayName("Test assembler instance is not null")
    public void testAssemblerInstance() {
        assertNotNull(FalkorDBAssembler.INSTANCE);
        assertTrue(FalkorDBAssembler.INSTANCE instanceof Assembler);
    }

    @Test
    @DisplayName("Test assembler singleton is same instance")
    public void testAssemblerSingleton() {
        Assembler instance1 = FalkorDBAssembler.INSTANCE;
        Assembler instance2 = FalkorDBAssembler.INSTANCE;
        assertSame(instance1, instance2);
    }

    @Test
    @DisplayName("Test config resource with all properties")
    public void testConfigResourceAllProperties() {
        // Create a config resource with all properties
        Resource config = configModel.createResource("http://example.org/config")
            .addProperty(configModel.createProperty(FalkorDBVocab.NS, "host"),
                "testhost")
            .addProperty(configModel.createProperty(FalkorDBVocab.NS, "port"),
                configModel.createTypedLiteral(9999))
            .addProperty(configModel.createProperty(FalkorDBVocab.NS, "graphName"),
                "test_graph");

        // Verify the config was created correctly
        assertNotNull(config);
        assertEquals("testhost",
            config.getProperty(FalkorDBVocab.host).getString());
        assertEquals(9999,
            config.getProperty(FalkorDBVocab.port).getInt());
        assertEquals("test_graph",
            config.getProperty(FalkorDBVocab.graphName).getString());
    }

    @Test
    @DisplayName("Test config resource with partial properties")
    public void testConfigResourcePartialProperties() {
        // Create a config resource with only some properties
        Resource config = configModel.createResource("http://example.org/config")
            .addProperty(configModel.createProperty(FalkorDBVocab.NS, "graphName"),
                "my_graph");

        // Verify the config was created correctly
        assertNotNull(config);
        assertNull(config.getProperty(FalkorDBVocab.host));
        assertNull(config.getProperty(FalkorDBVocab.port));
        assertEquals("my_graph",
            config.getProperty(FalkorDBVocab.graphName).getString());
    }
}
