package com.falkordb.jena.assembler;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AssemblerUtils class.
 */
public class AssemblerUtilsTest {

    private Model model;
    private Resource root;
    private Property stringProp;
    private Property intProp;

    @BeforeEach
    public void setUp() {
        model = ModelFactory.createDefaultModel();
        root = model.createResource("http://example.org/config");
        stringProp = model.createProperty("http://example.org/stringProp");
        intProp = model.createProperty("http://example.org/intProp");
    }

    @Test
    @DisplayName("Test getStringProperty returns value when present")
    public void testGetStringPropertyWithValue() {
        root.addProperty(stringProp, "test-value");

        String result = AssemblerUtils.getStringProperty(root, stringProp,
            "default");

        assertEquals("test-value", result);
    }

    @Test
    @DisplayName("Test getStringProperty returns default when missing")
    public void testGetStringPropertyWithDefault() {
        String result = AssemblerUtils.getStringProperty(root, stringProp,
            "default");

        assertEquals("default", result);
    }

    @Test
    @DisplayName("Test getIntProperty returns value when present")
    public void testGetIntPropertyWithValue() {
        root.addProperty(intProp, model.createTypedLiteral(42));

        int result = AssemblerUtils.getIntProperty(root, intProp, 0);

        assertEquals(42, result);
    }

    @Test
    @DisplayName("Test getIntProperty returns default when missing")
    public void testGetIntPropertyWithDefault() {
        int result = AssemblerUtils.getIntProperty(root, intProp, 100);

        assertEquals(100, result);
    }
}
