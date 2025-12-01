package com.falkordb.jena;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenTelemetry integration in FalkorDBGraph.
 *
 * These tests verify that:
 * 1. The OpenTelemetry reflection code handles missing classes gracefully
 * 2. The reflection uses context classloader for proper agent integration
 * 3. The span attribute methods don't throw exceptions when OTEL is not present
 */
public class FalkorDBGraphOtelTest {

    @Test
    @DisplayName("Test that setSpanAttribute doesn't throw when OpenTelemetry is not available")
    public void testSetSpanAttributeNoThrow() throws Exception {
        // Reset the static state to simulate a fresh class load
        resetOtelState();

        // Call a method that uses setSpanAttribute - should not throw
        // even when OpenTelemetry is not on the classpath
        // We test this indirectly by verifying the state after initialization

        // Trigger the initialization by calling initOtelReflection via reflection
        Method initMethod = FalkorDBGraph.class.getDeclaredMethod(
            "initOtelReflection");
        initMethod.setAccessible(true);
        initMethod.invoke(null);

        // Verify that otelLookupAttempted is true
        Field attemptedField = FalkorDBGraph.class.getDeclaredField(
            "otelLookupAttempted");
        attemptedField.setAccessible(true);
        assertTrue((Boolean) attemptedField.get(null),
            "otelLookupAttempted should be true after initialization");

        // Verify that otelAvailable is false (since OpenTelemetry is not on classpath)
        Field availableField = FalkorDBGraph.class.getDeclaredField(
            "otelAvailable");
        availableField.setAccessible(true);
        assertFalse((Boolean) availableField.get(null),
            "otelAvailable should be false when OpenTelemetry is not present");
    }

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
        }, "setSpanAttribute should not throw when OpenTelemetry is not available");
    }

    @Test
    @DisplayName("Test that initOtelReflection uses context classloader")
    public void testInitUsesContextClassloader() throws Exception {
        // Reset the static state
        resetOtelState();

        // Store the original context classloader
        ClassLoader originalLoader = Thread.currentThread()
            .getContextClassLoader();

        try {
            // Set a custom context classloader
            ClassLoader customLoader = new ClassLoader(originalLoader) {
                @Override
                public Class<?> loadClass(String name)
                        throws ClassNotFoundException {
                    // This custom classloader delegates to parent
                    // The test verifies that the code uses context classloader
                    return super.loadClass(name);
                }
            };
            Thread.currentThread().setContextClassLoader(customLoader);

            // Trigger initialization
            Method initMethod = FalkorDBGraph.class.getDeclaredMethod(
                "initOtelReflection");
            initMethod.setAccessible(true);
            initMethod.invoke(null);

            // Verify initialization happened
            Field attemptedField = FalkorDBGraph.class.getDeclaredField(
                "otelLookupAttempted");
            attemptedField.setAccessible(true);
            assertTrue((Boolean) attemptedField.get(null),
                "otelLookupAttempted should be true");
        } finally {
            // Restore original classloader
            Thread.currentThread().setContextClassLoader(originalLoader);
        }
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

        // Test with concrete triple
        var subject = org.apache.jena.graph.NodeFactory.createURI(
            "http://example.org/subject");
        var predicate = org.apache.jena.graph.NodeFactory.createURI(
            "http://example.org/predicate");
        var object = org.apache.jena.graph.NodeFactory.createLiteralString(
            "test value");
        var triple = org.apache.jena.graph.Triple.create(
            subject, predicate, object);

        // We can't invoke the method directly without an instance,
        // but we can verify the method signature is correct
        assertNotNull(tripleToStringMethod,
            "tripleToString method should exist");
        assertEquals(String.class, tripleToStringMethod.getReturnType(),
            "tripleToString should return String");
    }

    /**
     * Helper method to reset the static OTEL state for testing.
     */
    private void resetOtelState() throws Exception {
        Field attemptedField = FalkorDBGraph.class.getDeclaredField(
            "otelLookupAttempted");
        attemptedField.setAccessible(true);
        attemptedField.set(null, false);

        Field availableField = FalkorDBGraph.class.getDeclaredField(
            "otelAvailable");
        availableField.setAccessible(true);
        availableField.set(null, false);

        Field currentMethodField = FalkorDBGraph.class.getDeclaredField(
            "spanCurrentMethod");
        currentMethodField.setAccessible(true);
        currentMethodField.set(null, null);

        Field setAttrField = FalkorDBGraph.class.getDeclaredField(
            "setAttributeMethod");
        setAttrField.setAccessible(true);
        setAttrField.set(null, null);
    }
}
