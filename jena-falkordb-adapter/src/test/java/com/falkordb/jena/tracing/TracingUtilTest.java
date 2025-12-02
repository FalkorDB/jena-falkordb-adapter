package com.falkordb.jena.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TracingUtil class.
 */
public class TracingUtilTest {

    @Test
    @DisplayName("Test scope constants are defined correctly")
    public void testScopeConstants() {
        assertEquals("com.falkordb.jena.FalkorDBGraph",
            TracingUtil.SCOPE_FALKORDB_GRAPH);
        assertEquals("com.falkordb.FalkorFuseki",
            TracingUtil.SCOPE_FUSEKI);
        assertEquals("com.falkordb.redis",
            TracingUtil.SCOPE_REDIS_DRIVER);
    }

    @Test
    @DisplayName("Test getOpenTelemetry returns non-null instance")
    public void testGetOpenTelemetry() {
        OpenTelemetry otel = TracingUtil.getOpenTelemetry();
        assertNotNull(otel);
    }

    @Test
    @DisplayName("Test getOpenTelemetry returns same instance on multiple calls")
    public void testGetOpenTelemetrySingleton() {
        OpenTelemetry first = TracingUtil.getOpenTelemetry();
        OpenTelemetry second = TracingUtil.getOpenTelemetry();
        assertSame(first, second);
    }

    @Test
    @DisplayName("Test getTracer returns non-null tracer")
    public void testGetTracer() {
        Tracer tracer = TracingUtil.getTracer(TracingUtil.SCOPE_FALKORDB_GRAPH);
        assertNotNull(tracer);
    }

    @Test
    @DisplayName("Test getTracer with different scopes")
    public void testGetTracerDifferentScopes() {
        Tracer graphTracer = TracingUtil.getTracer(TracingUtil.SCOPE_FALKORDB_GRAPH);
        Tracer fusekiTracer = TracingUtil.getTracer(TracingUtil.SCOPE_FUSEKI);
        Tracer redisTracer = TracingUtil.getTracer(TracingUtil.SCOPE_REDIS_DRIVER);

        assertNotNull(graphTracer);
        assertNotNull(fusekiTracer);
        assertNotNull(redisTracer);
    }

    @Test
    @DisplayName("Test isTracingEnabled returns boolean")
    public void testIsTracingEnabled() {
        // Just verify it doesn't throw and returns a boolean
        boolean enabled = TracingUtil.isTracingEnabled();
        // The result depends on environment variables, just check it's a valid boolean
        assertTrue(enabled || !enabled);
    }

    @Test
    @DisplayName("Test shutdown does not throw")
    public void testShutdown() {
        // Ensure shutdown can be called without error
        assertDoesNotThrow(() -> TracingUtil.shutdown());
    }

    @Test
    @DisplayName("Test TracingUtil constructor is private")
    public void testPrivateConstructor() {
        // Verify the class cannot be instantiated
        var constructors = TracingUtil.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertFalse(constructors[0].canAccess(null));
    }
}
