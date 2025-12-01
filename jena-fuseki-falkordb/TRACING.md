# Distributed Tracing with OpenTelemetry and Jaeger

This guide explains how to enable distributed tracing for the Jena-Fuseki-FalkorDB server using OpenTelemetry SDK and Jaeger. This allows you to visualize call trees, parameters, and timing information for debugging and performance analysis.

## Overview

When `ENABLE_PROFILING=true` is set (or `OTEL_EXPORTER_OTLP_ENDPOINT` is configured), the server will be instrumented with OpenTelemetry to capture:
- HTTP requests to Fuseki endpoints
- SPARQL query execution
- FalkorDB/Redis operations including Cypher queries
- FalkorDBGraph method calls with pattern/triple attributes

The traces are exported to Jaeger, which provides a web UI for visualization.

**Note:** This implementation uses the OpenTelemetry SDK directly in code - **no Java agent is required**.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Your Application                               │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────────────────┐ │
│  │   Fuseki    │───▶│ FalkorDB    │───▶│  FalkorDB (Redis)          │ │
│  │   Server    │    │ Adapter      │    │  Database                  │ │
│  └─────────────┘    └──────────────┘    └─────────────────────────────┘ │
│         │                  │                         │                   │
│         │    OpenTelemetry SDK (code-based instrumentation)             │
│         └──────────────────┼─────────────────────────┘                   │
└────────────────────────────┼─────────────────────────────────────────────┘
                             │
                             ▼ OTLP (gRPC)
                    ┌─────────────────┐
                    │     Jaeger      │
                    │   (Collector)   │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │   Jaeger UI     │
                    │ localhost:16686 │
                    └─────────────────┘
```

## Quick Start

### Prerequisites

- Docker (for running Jaeger)
- Java 21+
- Maven 3.6+
- FalkorDB running (see [GETTING_STARTED.md](GETTING_STARTED.md))

### Step 1: Start FalkorDB

```bash
docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
```

### Step 2: Start Jaeger

Run Jaeger using Docker:

```bash
docker run -d --name jaeger \
  -e COLLECTOR_ZIPKIN_HOST_PORT=:9411 \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 6831:6831/udp \
  -p 6832:6832/udp \
  -p 5778:5778 \
  -p 16686:16686 \
  -p 4317:4317 \
  -p 4318:4318 \
  -p 14250:14250 \
  -p 14268:14268 \
  -p 14269:14269 \
  -p 9411:9411 \
  jaegertracing/all-in-one:latest
```

The Jaeger UI will be available at: **http://localhost:16686**

### Step 3: Build with Tracing Support

Build the project:

```bash
mvn clean install -DskipTests
```

### Step 4: Run with Profiling Enabled

Use the provided startup script:

```bash
cd jena-fuseki-falkordb
ENABLE_PROFILING=true ./run_fuseki_tracing.sh
```

Or manually with environment variables:

```bash
OTEL_SERVICE_NAME=fuseki-falkordb \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
java -jar target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar
```

### Step 5: Load Test Data

> **Important**: You must insert data before running queries. The FalkorDB graph starts empty.

Run the Grandfather Example (from [GETTING_STARTED.md](GETTING_STARTED.md#grandfather-example)):

**Step 5a: Insert the test data first** (required):

```bash
curl -X POST \
     -H "Content-Type: application/sparql-update" \
     --data '
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
INSERT DATA {
    ff:Jacob a ff:Male .
    ff:Isaac a ff:Male ;
        ff:father_of ff:Jacob .
    ff:Abraham a ff:Male ;
        ff:father_of ff:Isaac .
}' \
     http://localhost:3330/falkor/update
```

You should see an empty response (HTTP 204 No Content), which indicates success.

### Step 6: Query Data and View Traces

**Step 6a: Query father-son relationships:**

```bash
curl -G --data-urlencode "query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?father ?son
WHERE {
    ?father ff:father_of ?son .
}" \
     http://localhost:3330/falkor/query
```

### Step 7: View Traces in Jaeger

1. Open Jaeger UI: **http://localhost:16686**
2. Select Service: **fuseki-falkordb**
3. Click **Find Traces**
4. Click on a trace to see the detailed call tree

## What You Will See

### FalkorDBGraph Method Tracing with Arguments

The `FalkorDBGraph` class creates OpenTelemetry spans for key methods with custom attributes:

- `FalkorDBGraph.performAdd` - Adding triples to the graph
  - Span attribute: `triple` showing the full Triple in format `(subject predicate object)`
- `FalkorDBGraph.performDelete` - Deleting triples from the graph
  - Span attribute: `triple` showing the full Triple in format `(subject predicate object)`
- `FalkorDBGraph.graphBaseFind` - Finding/querying triples
  - Span attribute: `pattern` showing the query pattern (variables shown as `?s`, `?p`, `?o`)
- `FalkorDBGraph.clear` - Clearing the graph

Example of what you'll see in Jaeger for a span:
```json
{
  "key": "triple",
  "type": "string",
  "value": "(http://example.org/Jacob http://example.org/father_of http://example.org/Isaac)"
}
```

For query patterns with unbound variables:
```json
{
  "key": "pattern",
  "type": "string",
  "value": "(http://example.org/Jacob ?p ?o)"
}
```

## Configuration Options

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_PROFILING` | `false` | Enable/disable tracing (for script) |
| `OTEL_SERVICE_NAME` | `fuseki-falkordb` | Service name in Jaeger |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | Jaeger collector endpoint (OTLP gRPC) |

### Programmatic Initialization

You can also initialize tracing programmatically in your code:

```java
import com.falkordb.jena.tracing.TracingInitializer;

// Initialize with defaults (uses environment variables)
TracingInitializer.initialize();

// Or with custom configuration
TracingInitializer.initialize("my-service", "http://jaeger:4317");
```

## Troubleshooting

### No Traces Appearing in Jaeger

1. **Check Jaeger is running:**
   ```bash
   curl http://localhost:16686
   ```

2. **Check tracing is initialized:**
   Look for this in the startup logs:
   ```
   OpenTelemetry SDK initialized successfully
   ```

3. **Check OTLP endpoint:**
   Ensure `OTEL_EXPORTER_OTLP_ENDPOINT` is set and reachable.

### Spans Not Showing Attributes

The FalkorDBGraph class uses `GlobalOpenTelemetry.get().getTracer()` to create spans.
If no tracer is configured, it will use a no-op tracer and spans won't be exported.

Ensure either:
- `OTEL_EXPORTER_OTLP_ENDPOINT` is set before the application starts
- `TracingInitializer.initialize()` is called in your code

## Performance Impact

| Mode | Overhead |
|------|----------|
| Tracing disabled | 0% |
| Tracing enabled | 5-15% (typical) |

For production use, you may want to implement sampling or disable tracing entirely.

## Next Steps

- [GETTING_STARTED.md](GETTING_STARTED.md) - Basic Fuseki setup
- [Apache Jena Fuseki Documentation](https://jena.apache.org/documentation/fuseki2/)
- [OpenTelemetry Java Documentation](https://opentelemetry.io/docs/instrumentation/java/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
