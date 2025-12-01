# Distributed Tracing with OpenTelemetry and Jaeger

This guide explains how to enable distributed tracing for the Jena-Fuseki-FalkorDB server using OpenTelemetry Java Agent and Jaeger. This allows you to visualize call trees, parameters, and timing information for debugging and performance analysis.

## Overview

When `ENABLE_PROFILING=true` is set, the server will be instrumented with OpenTelemetry to capture:
- HTTP requests to Fuseki endpoints
- SPARQL query execution
- FalkorDB/Redis operations including Cypher queries
- Internal method calls (configurable)

The traces are exported to Jaeger, which provides a web UI for visualization.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Your Application                               │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────────────────┐ │
│  │   Fuseki    │───▶│ FalkorDB    │───▶│  FalkorDB (Redis)          │ │
│  │   Server    │    │ Adapter      │    │  Database                  │ │
│  └─────────────┘    └──────────────┘    └─────────────────────────────┘ │
│         │                  │                         │                   │
│         │    OpenTelemetry Java Agent (auto-instrumentation)            │
│         └──────────────────┼─────────────────────────┘                   │
└────────────────────────────┼─────────────────────────────────────────────┘
                             │
                             ▼ OTLP (gRPC/HTTP)
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

Build the project with tracing dependencies:

```bash
mvn clean install -DskipTests
```

This will automatically download the OpenTelemetry Java Agent to `target/agents/`.

### Step 4: Run with Profiling Enabled

Use the provided startup script:

```bash
cd jena-fuseki-falkordb
ENABLE_PROFILING=true ./run_fuseki_tracing.sh
```

Or manually with environment variables:

```bash
ENABLE_PROFILING=true \
OTEL_SERVICE_NAME=fuseki-falkordb \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
OTEL_TRACES_SAMPLER=always_on \
java -javaagent:target/agents/opentelemetry-javaagent.jar \
     -jar target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar
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

**Expected result:**
```json
{
  "head": { "vars": [ "father", "son" ] },
  "results": {
    "bindings": [
      {
        "father": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Isaac" },
        "son": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Jacob" }
      },
      {
        "father": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Abraham" },
        "son": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Isaac" }
      }
    ]
  }
}
```

**Step 6b: Query grandfather relationships (requires inference):**

> **Note**: The grandfather query requires running Fuseki with inference configuration enabled. 
> By default, the server runs without inference, so this query won't return results unless you 
> start with the inference configuration.

To enable inference, **restart** Fuseki with the inference configuration:

```bash
# Stop the running Fuseki server (Ctrl+C)
# Then start with inference configuration:
ENABLE_PROFILING=true ./run_fuseki_tracing.sh --config src/main/resources/config-falkordb-inference.ttl
```

After restarting with inference, **re-insert the data** (Step 5a) since inference uses a different graph name (`knowledge_graph`), then run the grandfather query:

```bash
curl -G --data-urlencode "query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?grandfather ?grandson
WHERE {
    ?grandfather ff:grandfather_of ?grandson .
}" \
     http://localhost:3330/falkor/query
```

**Expected result with inference:**
```json
{
  "head": { "vars": [ "grandfather", "grandson" ] },
  "results": {
    "bindings": [
      {
        "grandfather": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Abraham" },
        "grandson": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Jacob" }
      }
    ]
  }
}
```

### Step 7: View Traces in Jaeger

1. Open Jaeger UI: **http://localhost:16686**
2. Select Service: **fuseki-falkordb**
3. Click **Find Traces**
4. Click on a trace to see the detailed call tree

## What You Will See

### Trace Overview

Each HTTP request creates a trace containing:

- **Root Span**: `POST /falkor/update` or `GET /falkor/query`
  - Shows HTTP method, URL, status code
  
- **Child Spans**:
  - **SPARQL Processing**: Query parsing and execution
  - **FalkorDB Operations**: Database calls including Cypher queries
  - **Redis Commands**: Low-level Redis protocol operations

### Span Details

Click on any span to see:

- **Tags/Attributes**:
  - `http.method`: HTTP method (GET, POST)
  - `http.url`: Full request URL
  - `http.status_code`: Response status
  - `db.system`: Database system (redis/falkordb)
  - `db.statement`: The Cypher query executed
  - `db.operation`: Operation type (GRAPH.QUERY, etc.)

- **Timing**:
  - Start time
  - Duration
  - Relative timing within parent span

### Example Trace Structure

```
POST /falkor/update (45ms)
├── SPARQL Update Parse (2ms)
├── FalkorDBGraph.performAdd (38ms)
│   ├── GRAPH.QUERY: MERGE (s:Resource...) (15ms)
│   ├── GRAPH.QUERY: MERGE (s:Resource...) (12ms)
│   └── GRAPH.QUERY: MERGE (s:Resource...) (11ms)
└── HTTP Response (5ms)
```

## Configuration Options

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_PROFILING` | `false` | Enable/disable tracing |
| `OTEL_SERVICE_NAME` | `fuseki-falkordb` | Service name in Jaeger |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | Jaeger collector endpoint (OTLP HTTP) |
| `OTEL_TRACES_SAMPLER` | `always_on` | Sampling strategy |
| `OTEL_TRACES_SAMPLER_ARG` | `1.0` | Sampling rate (for ratio samplers) |

### Database Statement Visibility

By default, the startup script disables database statement sanitization so you can see the full Cypher queries in traces. This is controlled by:

```
-Dotel.instrumentation.common.db-statement-sanitizer.enabled=false
```

With this setting, you will see the actual Cypher queries like:
```
MATCH (s:Resource {uri: $subjectUri})-[r]->(o) RETURN s, r, o
```

Instead of sanitized placeholders:
```
graph.QUERY ? ? ?
```

**Note**: In production environments with sensitive data, you may want to re-enable sanitization for security.

### Logs and Metrics

The startup script disables logs and metrics exporters since Jaeger only supports traces:
```
-Dotel.logs.exporter=none
-Dotel.metrics.exporter=none
```

This prevents 404 errors when the agent tries to export logs/metrics to Jaeger.

### FalkorDBGraph Method Tracing with Arguments

The `FalkorDBGraph` class is traced using **two complementary approaches**:

1. **Method instrumentation via `otel.instrumentation.methods.include`** - Ensures methods appear in traces even if annotation processing fails
2. **OpenTelemetry `@WithSpan` annotations with programmatic `Span.current().setAttribute()`** - Captures method arguments (Triple patterns) as span attributes

When tracing is enabled with `ENABLE_PROFILING=true`, you will automatically see these methods in your trace tree:

- `FalkorDBGraph.performAdd(Triple)` - Adding triples to the graph
  - Span attribute: `triple` showing the full Triple in format `(subject predicate object)`
- `FalkorDBGraph.performDelete(Triple)` - Deleting triples from the graph
  - Span attribute: `triple` showing the full Triple in format `(subject predicate object)`
- `FalkorDBGraph.graphBaseFind(Triple)` - Finding/querying triples
  - Span attribute: `pattern` showing the query pattern (variables shown as `?s`, `?p`, `?o`)
- `FalkorDBGraph.clear()` - Clearing all data from the graph
- `FalkorDBGraph.findTypeTriples(Triple)` - Finding rdf:type triples
  - Span attribute: `pattern` showing the query pattern
- `FalkorDBGraph.findPropertyTriples(Triple)` - Finding property-based triples
  - Span attribute: `pattern` showing the query pattern

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

### Additional Method-Level Tracing

To trace additional methods, use the `OTEL_INSTRUMENTATION_METHODS_INCLUDE` environment variable:

```bash
OTEL_INSTRUMENTATION_METHODS_INCLUDE="com.falkordb.jena.FalkorDBModelFactory[createModel]"
```

### Sampling Strategies

| Sampler | Description |
|---------|-------------|
| `always_on` | Sample every request (100%) - use for debugging |
| `always_off` | Sample no requests (0%) |
| `traceidratio` | Sample based on ratio (set with `OTEL_TRACES_SAMPLER_ARG`) |
| `parentbased_always_on` | Follow parent decision, default to always_on |

For production, use ratio-based sampling:

```bash
OTEL_TRACES_SAMPLER=traceidratio \
OTEL_TRACES_SAMPLER_ARG=0.1 \
# ... rest of command
```

## Startup Script Reference

### run_fuseki_tracing.sh

The provided script handles all configuration automatically:

```bash
#!/bin/bash
# Usage: ENABLE_PROFILING=true ./run_fuseki_tracing.sh [--config config.ttl]

# Default configuration
JAVA_OPTS="-Xmx4G"
JAR_FILE="target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar"
AGENT_PATH="target/agents/opentelemetry-javaagent.jar"

# Parse arguments
CONFIG_ARGS=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --config)
            CONFIG_ARGS="--config $2"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

# Profiling configuration
if [ "$ENABLE_PROFILING" == "true" ]; then
    echo "🔍 Profiling ENABLED (OpenTelemetry + Jaeger)"
    
    # Check if agent exists
    if [ ! -f "$AGENT_PATH" ]; then
        echo "⚠️  OpenTelemetry agent not found. Run 'mvn package' first."
        exit 1
    fi
    
    # OpenTelemetry configuration
    OTEL_OPTS="-javaagent:$AGENT_PATH"
    OTEL_OPTS="$OTEL_OPTS -Dotel.service.name=${OTEL_SERVICE_NAME:-fuseki-falkordb}"
    OTEL_OPTS="$OTEL_OPTS -Dotel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318}"
    OTEL_OPTS="$OTEL_OPTS -Dotel.traces.sampler=${OTEL_TRACES_SAMPLER:-always_on}"
    
    # Optional: Add method-level instrumentation
    if [ -n "$OTEL_INSTRUMENTATION_METHODS_INCLUDE" ]; then
        OTEL_OPTS="$OTEL_OPTS -Dotel.instrumentation.methods.include=$OTEL_INSTRUMENTATION_METHODS_INCLUDE"
    fi
    
    JAVA_OPTS="$JAVA_OPTS $OTEL_OPTS"
else
    echo "🚀 Profiling DISABLED (set ENABLE_PROFILING=true to enable)"
fi

# Run the server
exec java $JAVA_OPTS -jar $JAR_FILE $CONFIG_ARGS
```

### Usage Examples

**Normal run (no profiling):**
```bash
./run_fuseki_tracing.sh
```

**With profiling:**
```bash
ENABLE_PROFILING=true ./run_fuseki_tracing.sh
```

**With profiling and custom config:**
```bash
ENABLE_PROFILING=true ./run_fuseki_tracing.sh --config src/main/resources/config-falkordb-inference.ttl
```

**With method-level tracing:**
```bash
ENABLE_PROFILING=true \
OTEL_INSTRUMENTATION_METHODS_INCLUDE="com.falkordb.jena.FalkorDBGraph[performAdd,performDelete,graphBaseFind]" \
./run_fuseki_tracing.sh
```

## Docker Compose Setup

For a complete local environment with FalkorDB and Jaeger:

```yaml
# docker-compose-tracing.yml
version: '3.8'

services:
  falkordb:
    image: falkordb/falkordb:latest
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "16686:16686"  # Jaeger UI
      - "4317:4317"    # OTLP gRPC
      - "4318:4318"    # OTLP HTTP
    environment:
      - COLLECTOR_ZIPKIN_HOST_PORT=:9411
      - COLLECTOR_OTLP_ENABLED=true

  fuseki:
    build:
      context: .
      dockerfile: Dockerfile.tracing
    ports:
      - "3330:3330"
    environment:
      - ENABLE_PROFILING=true
      - FALKORDB_HOST=falkordb
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318
      - OTEL_SERVICE_NAME=fuseki-falkordb
      - OTEL_TRACES_SAMPLER=always_on
    depends_on:
      falkordb:
        condition: service_healthy
      jaeger:
        condition: service_started
```

Run with:
```bash
docker-compose -f docker-compose-tracing.yml up
```

## Troubleshooting

### No Traces Appearing in Jaeger

1. **Check Jaeger is running:**
   ```bash
   curl http://localhost:16686
   ```

2. **Check agent is loaded:**
   Look for this in the startup logs:
   ```
   [otel.javaagent] ... version 2.x.x
   ```

3. **Check OTLP endpoint:**
   ```bash
   curl -v http://localhost:4317
   ```

4. **Verify service name:**
   Ensure `OTEL_SERVICE_NAME` is set correctly.

### Agent Not Found

Ensure you've built with the agent download:
```bash
mvn clean package -DskipTests
ls target/agents/opentelemetry-javaagent.jar
```

### High Latency with Tracing

- Use sampling to reduce overhead:
  ```bash
  OTEL_TRACES_SAMPLER=traceidratio OTEL_TRACES_SAMPLER_ARG=0.01
  ```

- Disable method-level tracing in production
- Use async export mode (default)

### Missing FalkorDB/Redis Spans

The OpenTelemetry Java Agent automatically instruments Jedis/Lettuce Redis clients. Ensure:
- You're using a supported Redis client version
- The auto-instrumentation is not disabled

## Performance Impact

| Mode | Overhead |
|------|----------|
| Profiling disabled | 0% |
| 100% sampling | 5-15% (typical) |
| 10% sampling | 1-2% |
| 1% sampling | <1% |

For production use, we recommend:
- Sampling at 1-10% for normal operation
- 100% sampling only for debugging specific issues

## Next Steps

- [GETTING_STARTED.md](GETTING_STARTED.md) - Basic Fuseki setup
- [Apache Jena Fuseki Documentation](https://jena.apache.org/documentation/fuseki2/)
- [OpenTelemetry Java Agent](https://opentelemetry.io/docs/instrumentation/java/automatic/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
