package com.falkordb.jena.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for configuring and managing OpenTelemetry tracing.
 *
 * <p>This class provides centralized tracing configuration using the
 * OpenTelemetry SDK (not an agent). Traces are exported via OTLP to
 * a configured endpoint (e.g., Jaeger).</p>
 *
 * <p>To enable tracing, set the following environment variables:</p>
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} - OTLP endpoint
 *       (default: http://localhost:4317)</li>
 *   <li>{@code OTEL_SERVICE_NAME} - Service name
 *       (default: jena-falkordb)</li>
 *   <li>{@code OTEL_TRACING_ENABLED} - Enable/disable tracing
 *       (default: true)</li>
 * </ul>
 */
public final class TracingUtil {
    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        TracingUtil.class);

    /** Default service name. */
    private static final String DEFAULT_SERVICE_NAME = "jena-falkordb";

    /** Default OTLP endpoint. */
    private static final String DEFAULT_OTLP_ENDPOINT =
        "http://localhost:4317";

    /** Environment variable for OTLP endpoint. */
    private static final String ENV_OTLP_ENDPOINT =
        "OTEL_EXPORTER_OTLP_ENDPOINT";

    /** Environment variable for service name. */
    private static final String ENV_SERVICE_NAME = "OTEL_SERVICE_NAME";

    /** Environment variable to enable/disable tracing. */
    private static final String ENV_TRACING_ENABLED = "OTEL_TRACING_ENABLED";

    /** Instrumentation scope name for FalkorDB graph operations. */
    public static final String SCOPE_FALKORDB_GRAPH =
        "com.falkordb.jena.FalkorDBGraph";

    /** Instrumentation scope name for Fuseki server operations. */
    public static final String SCOPE_FUSEKI = "com.falkordb.FalkorFuseki";

    /** Instrumentation scope name for Redis/driver operations. */
    public static final String SCOPE_REDIS_DRIVER = "com.falkordb.redis";

    /** Singleton OpenTelemetry instance. */
    private static volatile OpenTelemetry openTelemetry;

    /** Lock for initialization. */
    private static final Object INIT_LOCK = new Object();

    /** Whether tracing is enabled. */
    private static volatile boolean tracingEnabled = true;

    /** Prevent instantiation. */
    private TracingUtil() {
        throw new AssertionError("No instances");
    }

    /**
     * Initialize OpenTelemetry if not already initialized.
     *
     * <p>This method is thread-safe and will only initialize once.
     * Subsequent calls return the existing instance.</p>
     *
     * @return the OpenTelemetry instance
     */
    public static OpenTelemetry getOpenTelemetry() {
        if (openTelemetry == null) {
            synchronized (INIT_LOCK) {
                if (openTelemetry == null) {
                    openTelemetry = initializeOpenTelemetry();
                }
            }
        }
        return openTelemetry;
    }

    /**
     * Get a tracer for the specified instrumentation scope.
     *
     * @param scopeName the instrumentation scope name
     * @return the tracer for the given scope
     */
    public static Tracer getTracer(final String scopeName) {
        return getOpenTelemetry().getTracer(scopeName);
    }

    /**
     * Check if tracing is enabled.
     *
     * @return true if tracing is enabled, false otherwise
     */
    public static boolean isTracingEnabled() {
        // Initialize to read environment variable on first check
        getOpenTelemetry();
        return tracingEnabled;
    }

    /**
     * Initialize the OpenTelemetry SDK.
     *
     * @return the configured OpenTelemetry instance
     */
    private static OpenTelemetry initializeOpenTelemetry() {
        // Check if tracing is enabled
        String enabledEnv = System.getenv(ENV_TRACING_ENABLED);
        if (enabledEnv != null && !enabledEnv.isEmpty()) {
            tracingEnabled = Boolean.parseBoolean(enabledEnv);
        }

        if (!tracingEnabled) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("OpenTelemetry tracing is disabled");
            }
            return OpenTelemetry.noop();
        }

        String serviceName = getEnvOrDefault(ENV_SERVICE_NAME,
            DEFAULT_SERVICE_NAME);
        String otlpEndpoint = getEnvOrDefault(ENV_OTLP_ENDPOINT,
            DEFAULT_OTLP_ENDPOINT);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Initializing OpenTelemetry with service={}, "
                + "endpoint={}", serviceName, otlpEndpoint);
        }

        // Build resource with service information
        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(
                ServiceAttributes.SERVICE_NAME, serviceName)));

        // Configure OTLP exporter
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(otlpEndpoint)
            .build();

        // Build tracer provider with batch span processor
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
            .setResource(resource)
            .build();

        // Build OpenTelemetry SDK with W3C trace context propagation
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(
                W3CTraceContextPropagator.getInstance()))
            .build();

        // Register shutdown hook to flush traces
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Shutting down OpenTelemetry tracer provider");
            }
            tracerProvider.shutdown();
        }));

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("OpenTelemetry tracing initialized successfully");
        }

        return sdk;
    }

    /**
     * Get environment variable or default value.
     *
     * @param name environment variable name
     * @param defaultValue default value if not set
     * @return the value
     */
    private static String getEnvOrDefault(final String name,
            final String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    /**
     * Shutdown the tracing system gracefully.
     * Call this when the application is shutting down.
     */
    public static void shutdown() {
        if (openTelemetry instanceof OpenTelemetrySdk sdk) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Shutting down OpenTelemetry SDK");
            }
            sdk.getSdkTracerProvider().shutdown();
        }
    }
}
