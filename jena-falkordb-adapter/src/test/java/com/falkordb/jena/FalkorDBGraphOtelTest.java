package com.falkordb.jena;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenTelemetry integration in FalkorDBGraph.
 *
 * These tests verify that:
 * 1. The setSpanAttribute method handles errors gracefully
 * 2. The method signature is correct
 * 3. Calling span attribute methods doesn't throw exceptions
 */
public class FalkorDBGraphOtelTest {

    @Test
    @DisplayName("Test that setSpanAttribute method exists and is accessible")
    public void testSetSpanAttributeMethodExists() throws Exception {
        Method setSpanAttrMethod = FalkorDBGraph.class.getDeclaredMethod(
            "setSpanAttribute", String.class, String.class);
        assertNotNull(setSpanAttrMethod,
            "setSpanAttribute method should exist");
        setSpanAttrMethod.setAccessible(true);

        // Should not throw when called
        assertDoesNotThrow(() -> {
            setSpanAttrMethod.invoke(null, "test.key", "test.value");
        }, "setSpanAttribute should not throw");
    }

    @Test
    @DisplayName("Test that multiple calls to setSpanAttribute work without errors")
    public void testMultipleSetSpanAttributeCalls() throws Exception {
        Method setSpanAttrMethod = FalkorDBGraph.class.getDeclaredMethod(
            "setSpanAttribute", String.class, String.class);
        setSpanAttrMethod.setAccessible(true);

        // Call multiple times - should not throw
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10; i++) {
                setSpanAttrMethod.invoke(null, "key" + i, "value" + i);
            }
        }, "Multiple calls to setSpanAttribute should not throw");
    }

    @Test
    @DisplayName("Test tripleToString method for tracing")
    public void testTripleToStringMethod() throws Exception {
        Method tripleToStringMethod = FalkorDBGraph.class.getDeclaredMethod(
            "tripleToString", org.apache.jena.graph.Triple.class);
        tripleToStringMethod.setAccessible(true);

        // Verify the method signature is correct
        assertNotNull(tripleToStringMethod,
            "tripleToString method should exist");
        assertEquals(String.class, tripleToStringMethod.getReturnType(),
            "tripleToString should return String");
    }

    @Test
    @DisplayName("Test that key methods exist for tracing")
    public void testTracedMethodsExist() throws Exception {
        // Verify key methods exist that are configured for tracing
        assertNotNull(FalkorDBGraph.class.getDeclaredMethod(
            "graphBaseFind", org.apache.jena.graph.Triple.class),
            "graphBaseFind method should exist");
        assertNotNull(FalkorDBGraph.class.getDeclaredMethod(
            "performAdd", org.apache.jena.graph.Triple.class),
            "performAdd method should exist");
        assertNotNull(FalkorDBGraph.class.getDeclaredMethod(
            "performDelete", org.apache.jena.graph.Triple.class),
            "performDelete method should exist");
        assertNotNull(FalkorDBGraph.class.getDeclaredMethod("clear"),
            "clear method should exist");
    }

    @Test
    @DisplayName("Test initOtelReflection method exists")
    public void testInitOtelReflectionMethodExists() throws Exception {
        Method initMethod = FalkorDBGraph.class.getDeclaredMethod("initOtelReflection");
        assertNotNull(initMethod, "initOtelReflection method should exist");
        initMethod.setAccessible(true);
        
        // Should not throw
        assertDoesNotThrow(() -> initMethod.invoke(null),
            "initOtelReflection should not throw");
    }
}
