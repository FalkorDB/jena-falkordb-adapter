# OpenTelemetry Tracing for Jena-FalkorDB

This document describes how to set up and use OpenTelemetry tracing with Jena-FalkorDB.
Traces are exported via OTLP protocol to Jaeger for visualization.

## Overview

The tracing implementation provides visibility into three levels of the system:

1. **Level 1 - Fuseki HTTP Requests**: Top-level Fuseki calls with HTTP method, path, query parameters, and SPARQL query
2. **Level 2 - FalkorDBGraph Operations**: Graph operations (add, delete, find) with RDF triple/pattern details
3. **Level 3 - Redis/Driver Calls**: Every call to the underlying FalkorDB/Redis with Cypher queries and parameters

## Quick Start

### 1. Start FalkorDB and Jaeger

Use Docker Compose to start both FalkorDB and Jaeger:

```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

This starts:
- **FalkorDB**: Graph database on `localhost:6379`
- **Jaeger**: Tracing backend with UI on `http://localhost:16686`

### 2. Build the Project

```bash
mvn clean package -DskipTests
```

### 3. Run the Fuseki Server with Tracing

Set the required environment variables and run the server:

```bash
# Configure OpenTelemetry
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=jena-falkordb
export OTEL_TRACING_ENABLED=true

# Configure FalkorDB connection
export FALKORDB_HOST=localhost
export FALKORDB_PORT=6379
export FALKORDB_GRAPH=knowledge_graph

# Start the Fuseki server
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar
```

### 4. View Traces in Jaeger

Open `http://localhost:16686` in your browser to access the Jaeger UI.
Select "jena-falkordb" from the Service dropdown to view traces.

## Running the Inference Example

The inference example demonstrates SPARQL querying with rule-based reasoning.

### 1. Start Services

```bash
# Start FalkorDB and Jaeger
docker-compose -f docker-compose-tracing.yaml up -d
```

### 2. Load Sample Data

First, load the sample data with father relationships:

```bash
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @data/fathers_father_sample.ttl
```

### 3. Run with Inference Configuration

```bash
# Set environment variables
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=jena-falkordb
export OTEL_TRACING_ENABLED=true

# Run with inference config
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb-inference.ttl
```

### 4. Query for Inferred Grandfather Relationships

Query to find grandfather relationships (inferred by the rule engine):

```bash
curl -G --data-urlencode "query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?grandfather ?grandson
WHERE {
  ?grandfather ff:grandfather_of ?grandson .
}
" http://localhost:3330/falkor/query
```

Expected result: Abraham is the grandfather of Jacob (inferred from Abraham -> Isaac -> Jacob)

### 5. Query All Family Relationships

```bash
curl -G --data-urlencode "query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?subject ?predicate ?object
WHERE {
  ?subject ?predicate ?object .
  FILTER (?predicate = ff:father_of || ?predicate = ff:grandfather_of)
}
" http://localhost:3330/falkor/query
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP gRPC endpoint for traces | `http://localhost:4317` |
| `OTEL_SERVICE_NAME` | Service name in traces | `jena-falkordb` |
| `OTEL_TRACING_ENABLED` | Enable/disable tracing | `true` |
| `FALKORDB_HOST` | FalkorDB host | `localhost` |
| `FALKORDB_PORT` | FalkorDB port | `6379` |
| `FALKORDB_GRAPH` | Graph name | `my_knowledge_graph` |
| `FUSEKI_PORT` | Fuseki server port | `3330` |

## Trace Attributes

### Level 1 - HTTP Request Traces

| Attribute | Description |
|-----------|-------------|
| `http.method` | HTTP method (GET, POST, etc.) |
| `http.url` | Full request URL |
| `http.route` | Request path |
| `http.status_code` | Response status code |
| `http.query_string` | URL query string |
| `sparql.query` | SPARQL query (if present) |
| `http.request.content_type` | Request content type |
| `http.user_agent` | Client user agent |

### Level 2 - FalkorDBGraph Operation Traces

| Attribute | Description |
|-----------|-------------|
| `falkordb.operation` | Operation type (add, delete, find) |
| `falkordb.graph_name` | Graph name |
| `rdf.triple.subject` | Triple subject URI |
| `rdf.triple.predicate` | Triple predicate URI |
| `rdf.triple.object` | Triple object (URI or literal) |
| `rdf.pattern` | Search pattern (for find operations) |
| `rdf.result_count` | Number of results (for find operations) |

### Level 3 - Redis/Driver Traces

| Attribute | Description |
|-----------|-------------|
| `db.system` | Database system (`falkordb`) |
| `db.name` | Graph name |
| `db.statement` | Cypher query |
| `db.falkordb.params` | Query parameters |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Fuseki Server                            │
│  ┌─────────────────────────────────────────────────────────┐│
│  │         FusekiTracingFilter (Level 1)                   ││
│  │         - HTTP requests                                  ││
│  │         - SPARQL queries                                 ││
│  └─────────────────────────────────────────────────────────┘│
│                            │                                 │
│  ┌─────────────────────────────────────────────────────────┐│
│  │         FalkorDBGraph (Level 2)                         ││
│  │         - performAdd                                     ││
│  │         - performDelete                                  ││
│  │         - graphBaseFind                                  ││
│  └─────────────────────────────────────────────────────────┘│
│                            │                                 │
│  ┌─────────────────────────────────────────────────────────┐│
│  │         TracedGraph (Level 3)                           ││
│  │         - Cypher queries                                 ││
│  │         - Query parameters                               ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                      FalkorDB                                │
└─────────────────────────────────────────────────────────────┘
                             │
                             │ OTLP (gRPC)
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                       Jaeger                                 │
│                  http://localhost:16686                      │
└─────────────────────────────────────────────────────────────┘
```

## Disabling Tracing

To disable tracing, set the environment variable:

```bash
export OTEL_TRACING_ENABLED=false
```

When tracing is disabled, no spans are created and there is minimal performance impact.

## Troubleshooting

### Traces Not Appearing in Jaeger

1. Verify Jaeger is running: `docker-compose -f docker-compose-tracing.yaml ps`
2. Check the OTLP endpoint is correct: `http://localhost:4317`
3. Verify tracing is enabled: `OTEL_TRACING_ENABLED=true`
4. Check Fuseki server logs for OpenTelemetry initialization messages

### Connection Refused to OTLP Endpoint

Ensure Jaeger container is running and the port 4317 is accessible:

```bash
docker-compose -f docker-compose-tracing.yaml logs jaeger
```

### High Memory Usage

If you experience high memory usage, consider:
- Reducing batch span processor queue size
- Adjusting export interval
- Filtering out high-frequency low-value spans

## Advanced Configuration

For production deployments, consider:

1. **Using Jaeger Agent**: Deploy Jaeger agent as a sidecar for lower latency
2. **Sampling**: Configure trace sampling to reduce overhead
3. **Context Propagation**: Ensure W3C trace context is propagated across services
4. **Custom Exporters**: Use different OTLP exporters for different backends

Example with custom sampling:

```java
// In TracingUtil.java, modify the tracer provider setup
SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
        .setMaxQueueSize(2048)
        .setScheduleDelay(Duration.ofMillis(5000))
        .build())
    .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(0.1))) // 10% sampling
    .setResource(resource)
    .build();
```

## Clean Up

To stop and remove the Docker containers:

```bash
docker-compose -f docker-compose-tracing.yaml down -v
```
