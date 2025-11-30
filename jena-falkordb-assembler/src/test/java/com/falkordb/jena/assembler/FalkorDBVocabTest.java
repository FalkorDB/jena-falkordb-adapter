package com.falkordb.jena.assembler;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FalkorDBVocab class.
 */
public class FalkorDBVocabTest {

    @Test
    @DisplayName("Test namespace URI is correct")
    public void testNamespaceURI() {
        assertEquals("http://falkordb.com/jena/assembler#",
            FalkorDBVocab.NS);
        assertEquals("http://falkordb.com/jena/assembler#",
            FalkorDBVocab.getURI());
    }

    @Test
    @DisplayName("Test FalkorDBModel resource is defined correctly")
    public void testFalkorDBModelResource() {
        Resource model = FalkorDBVocab.FalkorDBModel;
        assertNotNull(model);
        assertEquals("http://falkordb.com/jena/assembler#FalkorDBModel",
            model.getURI());
    }

    @Test
    @DisplayName("Test host property is defined correctly")
    public void testHostProperty() {
        Property host = FalkorDBVocab.host;
        assertNotNull(host);
        assertEquals("http://falkordb.com/jena/assembler#host",
            host.getURI());
    }

    @Test
    @DisplayName("Test port property is defined correctly")
    public void testPortProperty() {
        Property port = FalkorDBVocab.port;
        assertNotNull(port);
        assertEquals("http://falkordb.com/jena/assembler#port",
            port.getURI());
    }

    @Test
    @DisplayName("Test graphName property is defined correctly")
    public void testGraphNameProperty() {
        Property graphName = FalkorDBVocab.graphName;
        assertNotNull(graphName);
        assertEquals("http://falkordb.com/jena/assembler#graphName",
            graphName.getURI());
    }
}
