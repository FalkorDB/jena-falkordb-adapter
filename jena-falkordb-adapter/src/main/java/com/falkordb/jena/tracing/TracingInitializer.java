package com.falkordb.jena.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes OpenTelemetry SDK for tracing without requiring the Java agent.
 * 
 * This class provides programmatic configuration of the OpenTelemetry SDK,
 * allowing spans to be exported to Jaeger or other OTLP-compatible backends.
 * 
 * Usage:
 * <pre>
 * // Initialize at application startup (before using FalkorDBGraph)
 * TracingInitializer.initialize();
 * 
 * // Or with custom configuration
 * TracingInitializer.initialize("my-service", "http://jaeger:4317");
 * </pre>
 */
public final class TracingInitializer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(TracingInitializer.class);
    
    private static volatile boolean initialized = false;
    private static volatile SdkTracerProvider tracerProvider;
    
    private TracingInitializer() {
        // Utility class
    }
    
    /**
     * Initialize OpenTelemetry with default configuration.
     * Uses environment variables for configuration:
     * - OTEL_SERVICE_NAME: Service name (default: fuseki-falkordb)
     * - OTEL_EXPORTER_OTLP_ENDPOINT: OTLP endpoint (default: http://localhost:4317)
     */
    public static synchronized void initialize() {
        String serviceName = System.getenv().getOrDefault("OTEL_SERVICE_NAME", "fuseki-falkordb");
        String endpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        initialize(serviceName, endpoint);
    }
    
    /**
     * Initialize OpenTelemetry with custom configuration.
     * 
     * @param serviceName the service name to use in traces
     * @param otlpEndpoint the OTLP endpoint (e.g., http://localhost:4317 for Jaeger)
     */
    public static synchronized void initialize(String serviceName, String otlpEndpoint) {
        if (initialized) {
            LOGGER.debug("OpenTelemetry already initialized");
            return;
        }
        
        try {
            // Check if GlobalOpenTelemetry is already set (e.g., by Java agent)
            OpenTelemetry existing = GlobalOpenTelemetry.get();
            if (existing != null && !existing.getClass().getName().contains("DefaultOpenTelemetry")) {
                LOGGER.info("OpenTelemetry already configured (possibly by Java agent): {}", 
                    existing.getClass().getName());
                initialized = true;
                return;
            }
        } catch (Exception e) {
            // GlobalOpenTelemetry not set yet, we'll configure it
        }
        
        LOGGER.info("Initializing OpenTelemetry SDK: service={}, endpoint={}", serviceName, otlpEndpoint);
        
        try {
            // Create resource with service name
            Resource resource = Resource.getDefault()
                .merge(Resource.builder()
                    .put(AttributeKey.stringKey("service.name"), serviceName)
                    .build());
            
            // Create OTLP exporter for Jaeger
            SpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();
            
            // Create tracer provider with batch processor
            tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setSampler(Sampler.alwaysOn())
                .build();
            
            // Build and register the SDK
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
            
            // Register as global
            GlobalOpenTelemetry.set(sdk);
            
            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("Shutting down OpenTelemetry SDK...");
                if (tracerProvider != null) {
                    tracerProvider.close();
                }
            }));
            
            initialized = true;
            LOGGER.info("OpenTelemetry SDK initialized successfully");
            
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize OpenTelemetry SDK: {}", e.getMessage());
            // Continue without tracing - FalkorDBGraph will use no-op tracer
        }
    }
    
    /**
     * Check if tracing has been initialized.
     * 
     * @return true if OpenTelemetry has been initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Shutdown the OpenTelemetry SDK and flush any pending spans.
     */
    public static synchronized void shutdown() {
        if (tracerProvider != null) {
            tracerProvider.close();
            tracerProvider = null;
        }
        initialized = false;
        LOGGER.info("OpenTelemetry SDK shut down");
    }
}
