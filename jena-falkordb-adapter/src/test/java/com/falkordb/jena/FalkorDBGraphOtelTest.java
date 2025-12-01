package com.falkordb.jena;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenTelemetry integration in FalkorDBGraph.
 *
 * These tests verify that:
 * 1. The setSpanAttribute method handles errors gracefully
 * 2. The method signature is correct
 * 3. Calling span attribute methods doesn't throw exceptions
 * 4. The OpenTelemetry API is accessible
 * 5. Span attributes are correctly set when a tracer is configured
 */
public class FalkorDBGraphOtelTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;

    @BeforeEach
    void setUp() {
        // Reset GlobalOpenTelemetry before each test
        GlobalOpenTelemetry.resetForTest();
        
        // Create an in-memory span exporter to capture spans
        spanExporter = InMemorySpanExporter.create();
        
        // Create SDK with the in-memory exporter
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
        
        openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal();
    }

    @AfterEach
    void tearDown() {
        if (openTelemetry != null) {
            openTelemetry.close();
        }
        GlobalOpenTelemetry.resetForTest();
    }

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
    @DisplayName("Test that key methods exist for tracing")
    public void testTracedMethodsExist() throws Exception {
        // Verify key methods exist that are configured for tracing
        // via otel.instrumentation.methods.include
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
    @DisplayName("Test that span attributes are set correctly within an active span")
    public void testSpanAttributesAreSetWithinActiveSpan() throws Exception {
        // Get the setSpanAttribute method
        Method setSpanAttrMethod = FalkorDBGraph.class.getDeclaredMethod(
            "setSpanAttribute", String.class, String.class);
        setSpanAttrMethod.setAccessible(true);

        // Create a tracer and start a span
        Tracer tracer = openTelemetry.getTracer("test-tracer");
        Span span = tracer.spanBuilder("test-span").startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Call setSpanAttribute within the active span context
            setSpanAttrMethod.invoke(null, "pattern", "(?s ?p ?o)");
            setSpanAttrMethod.invoke(null, "triple", "(http://example.org/s http://example.org/p http://example.org/o)");
        } finally {
            span.end();
        }

        // Get the exported spans
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "Should have exactly one span");

        SpanData spanData = spans.get(0);
        
        // Verify the attributes were set
        String patternAttr = spanData.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("pattern"));
        String tripleAttr = spanData.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("triple"));

        assertEquals("(?s ?p ?o)", patternAttr,
            "pattern attribute should be set correctly");
        assertEquals("(http://example.org/s http://example.org/p http://example.org/o)", tripleAttr,
            "triple attribute should be set correctly");
    }

    @Test
    @DisplayName("Verify span attributes are captured when SDK tracer is active")
    public void testSpanAttributesCapturedByExporter() throws Exception {
        Method setSpanAttrMethod = FalkorDBGraph.class.getDeclaredMethod(
            "setSpanAttribute", String.class, String.class);
        setSpanAttrMethod.setAccessible(true);

        // Start a span and set attributes
        Tracer tracer = openTelemetry.getTracer("falkordb-test");
        Span testSpan = tracer.spanBuilder("FalkorDBGraph.graphBaseFind").startSpan();

        try (Scope scope = testSpan.makeCurrent()) {
            // Simulate what happens in graphBaseFind
            setSpanAttrMethod.invoke(null, "pattern", "(http://example.org/Jacob ?p ?o)");
        } finally {
            testSpan.end();
        }

        // Verify the span was exported with the attribute
        List<SpanData> exportedSpans = spanExporter.getFinishedSpanItems();
        assertFalse(exportedSpans.isEmpty(), "Should have at least one exported span");

        SpanData span = exportedSpans.get(0);
        assertEquals("FalkorDBGraph.graphBaseFind", span.getName());

        String patternValue = span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("pattern"));
        assertNotNull(patternValue, "pattern attribute should be present");
        assertEquals("(http://example.org/Jacob ?p ?o)", patternValue,
            "pattern attribute should have the correct value");
        
        // Print span info for debugging
        System.out.println("Exported span: " + span.getName());
        System.out.println("Attributes: " + span.getAttributes().asMap());
    }
}
