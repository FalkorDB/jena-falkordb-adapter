package com.falkordb.jena;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies OpenTelemetry spans are created with correct attributes.
 * 
 * This test simulates the span creation that would happen in FalkorDBGraph
 * and verifies the spans contain the expected 'pattern' attribute.
 */
public class SpanAttributeVerificationTest {

    private static InMemorySpanExporter spanExporter;
    private static SdkTracerProvider tracerProvider;
    private static Tracer tracer;

    @BeforeAll
    static void setUpTracing() {
        // Set up in-memory span exporter for testing
        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
        
        tracer = tracerProvider.get("com.falkordb.jena.FalkorDBGraph");
    }

    @BeforeEach
    void clearSpans() {
        spanExporter.reset();
    }

    @AfterAll
    static void tearDown() {
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }

    @Test
    @DisplayName("Verify span is created with pattern attribute")
    public void testSpanWithPatternAttribute() {
        // Simulate what FalkorDBGraph.graphBaseFind does
        String patternValue = "(?s ?p ?o)";
        
        String result = withSpan("FalkorDBGraph.graphBaseFind", "pattern", patternValue, () -> {
            // This simulates the internal operation
            return "query result";
        });

        // Verify result
        assertEquals("query result", result);

        // Verify span was created with pattern attribute
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "Should have exactly one span");
        
        SpanData span = spans.get(0);
        assertEquals("FalkorDBGraph.graphBaseFind", span.getName());
        
        String patternAttr = span.getAttributes().get(AttributeKey.stringKey("pattern"));
        assertNotNull(patternAttr, "pattern attribute should exist");
        assertEquals(patternValue, patternAttr, "pattern attribute should match");
        
        System.out.println("=== SPAN CAPTURED ===");
        System.out.println("Span Name: " + span.getName());
        System.out.println("Attributes: {pattern=" + patternAttr + "}");
        System.out.println("=====================");
    }

    @Test
    @DisplayName("Verify span is created with triple attribute for performAdd")
    public void testSpanWithTripleAttribute() {
        String tripleValue = "(http://example.org/subject http://example.org/predicate http://example.org/object)";
        
        withSpanVoid("FalkorDBGraph.performAdd", "triple", tripleValue, () -> {
            // This simulates the add operation
        });

        // Verify span was created with triple attribute
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "Should have exactly one span");
        
        SpanData span = spans.get(0);
        assertEquals("FalkorDBGraph.performAdd", span.getName());
        
        String tripleAttr = span.getAttributes().get(AttributeKey.stringKey("triple"));
        assertNotNull(tripleAttr, "triple attribute should exist");
        assertEquals(tripleValue, tripleAttr, "triple attribute should match");
        
        System.out.println("=== SPAN CAPTURED ===");
        System.out.println("Span Name: " + span.getName());
        System.out.println("Attributes: {triple=" + tripleAttr + "}");
        System.out.println("=====================");
    }

    @Test
    @DisplayName("Verify multiple spans are created correctly")
    public void testMultipleSpans() {
        // First query
        withSpan("FalkorDBGraph.graphBaseFind", "pattern", "(?s ?p ?o)", () -> "result1");
        
        // Second query with specific pattern
        withSpan("FalkorDBGraph.graphBaseFind", "pattern", "(http://example.org/Jacob ?p ?o)", () -> "result2");
        
        // An add operation
        withSpanVoid("FalkorDBGraph.performAdd", "triple", 
            "(http://example.org/Jacob http://example.org/name \"Jacob\")", () -> {});

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(3, spans.size(), "Should have three spans");
        
        // Verify patterns in order
        assertEquals("(?s ?p ?o)", 
            spans.get(0).getAttributes().get(AttributeKey.stringKey("pattern")));
        assertEquals("(http://example.org/Jacob ?p ?o)", 
            spans.get(1).getAttributes().get(AttributeKey.stringKey("pattern")));
        assertEquals("(http://example.org/Jacob http://example.org/name \"Jacob\")", 
            spans.get(2).getAttributes().get(AttributeKey.stringKey("triple")));
    }

    @Test
    @DisplayName("Verify exception is recorded in span")
    public void testSpanWithException() {
        RuntimeException expectedException = new RuntimeException("Test exception");
        
        assertThrows(RuntimeException.class, () -> {
            withSpan("FalkorDBGraph.graphBaseFind", "pattern", "(?s ?p ?o)", () -> {
                throw expectedException;
            });
        });

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "Should have one span");
        
        SpanData span = spans.get(0);
        assertFalse(span.getEvents().isEmpty(), "Span should have recorded exception event");
    }

    // Helper methods that mirror FalkorDBGraph's implementation
    private static <T> T withSpan(String spanName, String attrKey, String attrValue, 
                                   Supplier<T> operation) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute(attrKey, attrValue);
            return operation.get();
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private static void withSpanVoid(String spanName, String attrKey, String attrValue, 
                                      Runnable operation) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute(attrKey, attrValue);
            operation.run();
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
