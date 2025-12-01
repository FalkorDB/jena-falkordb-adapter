package com.falkordb.jena;

import io.opentelemetry.api.trace.Span;
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

        // Should not throw when called - the OpenTelemetry API is available
        // as a provided dependency, but if it doesn't work at runtime,
        // errors should be caught silently
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
    @DisplayName("Test that OpenTelemetry API is accessible")
    public void testOpenTelemetryApiAccessible() {
        // Verify that we can access the OpenTelemetry Span API
        // This tests that the dependency is properly configured
        assertDoesNotThrow(() -> {
            Span span = Span.current();
            assertNotNull(span, "Span.current() should return a non-null span");
        }, "OpenTelemetry Span API should be accessible");
    }

    @Test
    @DisplayName("Test that Span.current() returns invalid span when no tracer configured")
    public void testSpanCurrentReturnsInvalidSpan() {
        // Without a tracer configured, Span.current() returns an invalid span
        Span span = Span.current();
        assertNotNull(span, "Span.current() should return a non-null span");
        // The span should be invalid when no tracer is configured
        assertFalse(span.getSpanContext().isValid(),
            "Span context should be invalid when no tracer is configured");
    }
}
