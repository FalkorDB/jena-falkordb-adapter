#!/bin/bash
#
# run_fuseki_tracing.sh - Start Fuseki server with optional OpenTelemetry tracing
#
# Usage:
#   ./run_fuseki_tracing.sh                    # Normal mode (no profiling)
#   ENABLE_PROFILING=true ./run_fuseki_tracing.sh  # With tracing
#   ENABLE_PROFILING=true ./run_fuseki_tracing.sh --config config.ttl
#
# Environment Variables:
#   ENABLE_PROFILING              - Set to "true" to enable tracing
#   OTEL_SERVICE_NAME             - Service name in Jaeger (default: fuseki-falkordb)
#   OTEL_EXPORTER_OTLP_ENDPOINT   - Jaeger collector endpoint (default: http://localhost:4318)
#   OTEL_TRACES_SAMPLER           - Sampling strategy (default: always_on)
#   OTEL_INSTRUMENTATION_METHODS_INCLUDE - Additional methods to trace
#   FALKORDB_HOST                 - FalkorDB host (default: localhost)
#   FALKORDB_PORT                 - FalkorDB port (default: 6379)
#   FUSEKI_PORT                   - Fuseki server port (default: 3330)
#

set -e

# Determine script directory (for relative paths)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Default configuration
JAVA_OPTS="${JAVA_OPTS:--Xmx4G}"
JAR_FILE="target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar"
AGENT_PATH="target/agents/opentelemetry-javaagent.jar"

# Check if JAR exists
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ JAR file not found: $JAR_FILE"
    echo "   Please run 'mvn clean package -DskipTests' first."
    exit 1
fi

# Parse command line arguments
CONFIG_ARGS=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --config)
            CONFIG_ARGS="--config $2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --config <file>    Path to TTL configuration file"
            echo "  --help, -h         Show this help message"
            echo ""
            echo "Environment Variables:"
            echo "  ENABLE_PROFILING              Enable OpenTelemetry tracing (default: false)"
            echo "  OTEL_SERVICE_NAME             Service name in Jaeger (default: fuseki-falkordb)"
            echo "  OTEL_EXPORTER_OTLP_ENDPOINT   Jaeger OTLP endpoint (default: http://localhost:4318)"
            echo "  OTEL_TRACES_SAMPLER           Sampling strategy (default: always_on)"
            echo ""
            echo "Examples:"
            echo "  ./run_fuseki_tracing.sh                           # Normal mode"
            echo "  ENABLE_PROFILING=true ./run_fuseki_tracing.sh     # With tracing"
            echo "  ENABLE_PROFILING=true ./run_fuseki_tracing.sh --config config.ttl"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information."
            exit 1
            ;;
    esac
done

# Profiling configuration
if [ "$ENABLE_PROFILING" == "true" ]; then
    echo "🔍 Profiling ENABLED (OpenTelemetry + Jaeger)"
    
    # Check if agent exists
    if [ ! -f "$AGENT_PATH" ]; then
        echo "⚠️  OpenTelemetry agent not found at: $AGENT_PATH"
        echo "   Please run 'mvn clean package -DskipTests' to download the agent."
        exit 1
    fi
    
    # OpenTelemetry configuration
    OTEL_OPTS="-javaagent:$AGENT_PATH"
    OTEL_OPTS="$OTEL_OPTS -Dotel.service.name=${OTEL_SERVICE_NAME:-fuseki-falkordb}"
    OTEL_OPTS="$OTEL_OPTS -Dotel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318}"
    OTEL_OPTS="$OTEL_OPTS -Dotel.traces.sampler=${OTEL_TRACES_SAMPLER:-always_on}"
    
    # Disable logs and metrics exporters (Jaeger only supports traces)
    OTEL_OPTS="$OTEL_OPTS -Dotel.logs.exporter=none"
    OTEL_OPTS="$OTEL_OPTS -Dotel.metrics.exporter=none"
    
    # Enable full db statement capture (show actual Cypher queries instead of sanitized ?)
    OTEL_OPTS="$OTEL_OPTS -Dotel.instrumentation.common.db-statement-sanitizer.enabled=false"
    
    # Default FalkorDBGraph methods to trace
    # These methods also have @WithSpan annotations for additional argument capture
    DEFAULT_METHODS="com.falkordb.jena.FalkorDBGraph[performAdd,performDelete,graphBaseFind,clear,findTypeTriples,findPropertyTriples]"
    
    # Combine default methods with any user-specified methods
    if [ -n "$OTEL_INSTRUMENTATION_METHODS_INCLUDE" ]; then
        OTEL_OPTS="$OTEL_OPTS -Dotel.instrumentation.methods.include=$DEFAULT_METHODS;$OTEL_INSTRUMENTATION_METHODS_INCLUDE"
    else
        OTEL_OPTS="$OTEL_OPTS -Dotel.instrumentation.methods.include=$DEFAULT_METHODS"
    fi
    
    # Log configuration
    OTEL_OPTS="$OTEL_OPTS -Dotel.javaagent.logging=simple"
    
    JAVA_OPTS="$JAVA_OPTS $OTEL_OPTS"
    
    echo "   Service Name: ${OTEL_SERVICE_NAME:-fuseki-falkordb}"
    echo "   OTLP Endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318}"
    echo "   Sampler: ${OTEL_TRACES_SAMPLER:-always_on}"
    echo "   FalkorDBGraph methods: performAdd, performDelete, graphBaseFind, clear, findTypeTriples, findPropertyTriples (with arguments)"
    echo ""
    echo "   Jaeger UI: http://localhost:16686"
    echo ""
else
    echo "🚀 Profiling DISABLED"
    echo "   Set ENABLE_PROFILING=true to enable OpenTelemetry tracing"
    echo ""
fi

# Display connection info
echo "   FalkorDB: ${FALKORDB_HOST:-localhost}:${FALKORDB_PORT:-6379}"
echo "   Fuseki Port: ${FUSEKI_PORT:-3330}"
echo ""

# Run the server
echo "Starting Fuseki server..."
exec java $JAVA_OPTS -jar "$JAR_FILE" $CONFIG_ARGS
