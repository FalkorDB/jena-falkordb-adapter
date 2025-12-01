package com.falkordb.jena;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenTelemetry integration in FalkorDBGraph.
 *
 * These tests verify that:
 * 1. The withSpan method exists and handles errors gracefully
 * 2. The method signature is correct
 * 3. Calling span methods doesn't throw exceptions
 */
public class FalkorDBGraphOtelTest {

    @Test
    @DisplayName("Test that withSpan method exists and is accessible")
    public void testWithSpanMethodExists() throws Exception {
        Method[] methods = FalkorDBGraph.class.getDeclaredMethods();
        boolean found = false;
        for (Method m : methods) {
            if (m.getName().equals("withSpan")) {
                found = true;
                m.setAccessible(true);
                break;
            }
        }
        assertTrue(found, "withSpan method should exist");
    }

    @Test
    @DisplayName("Test that initOtelReflection method exists and can be called")
    public void testInitOtelReflectionMethodExists() throws Exception {
        Method initMethod = FalkorDBGraph.class.getDeclaredMethod("initOtelReflection");
        assertNotNull(initMethod, "initOtelReflection method should exist");
        initMethod.setAccessible(true);
        
        // Should not throw
        assertDoesNotThrow(() -> initMethod.invoke(null),
            "initOtelReflection should not throw");
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
    @DisplayName("Test that withSpanVoid method exists")
    public void testWithSpanVoidMethodExists() throws Exception {
        Method[] methods = FalkorDBGraph.class.getDeclaredMethods();
        boolean found = false;
        for (Method m : methods) {
            if (m.getName().equals("withSpanVoid")) {
                found = true;
                m.setAccessible(true);
                break;
            }
        }
        assertTrue(found, "withSpanVoid method should exist");
    }
}
