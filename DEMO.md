# Complete Demo Guide for Jena-FalkorDB Adapter

> **Important**: This demo guide provides step-by-step instructions to run and test all optimizations in the Jena-FalkorDB Adapter with complete examples, curl commands, and Jaeger tracing visualization.

## Table of Contents

1. [Prerequisites and Setup](#prerequisites-and-setup)
2. [Starting the Services](#starting-the-services)
3. [Running the Fuseki Server](#running-the-fuseki-server)
4. [Optimization 1: Batch Writes via Transactions](#optimization-1-batch-writes-via-transactions)
5. [Optimization 2: Query Pushdown (SPARQL to Cypher)](#optimization-2-query-pushdown-sparql-to-cypher)
6. [Optimization 3: Variable Objects](#optimization-3-variable-objects)
7. [Optimization 4: OPTIONAL Patterns](#optimization-4-optional-patterns)
8. [Optimization 5: UNION Patterns](#optimization-5-union-patterns)
9. [Optimization 6: FILTER Expressions](#optimization-6-filter-expressions)
10. [Optimization 7: Aggregations (GROUP BY)](#optimization-7-aggregations-group-by)
11. [Optimization 8: Magic Property (Direct Cypher)](#optimization-8-magic-property-direct-cypher)
12. [Viewing Traces in Jaeger](#viewing-traces-in-jaeger)
13. [Running Tests](#running-tests)
14. [Troubleshooting](#troubleshooting)

---

## Prerequisites and Setup

### Required Software

- **Java 21**: Required for building and running the project
- **Maven 3.6+**: For building the project
- **Docker**: For running FalkorDB and Jaeger
- **curl**: For testing HTTP endpoints

### Install Java and Maven using SDKMAN

Install Java 21.0.5-graal and Maven 3.9.11 using SDKMAN (matches `.sdkmanrc`):

```bash
# Install SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install Java and Maven from project's .sdkmanrc
sdk env install

# Verify installations
java -version
# Should show: java version "21.0.5" ... (GraalVM)

mvn -version
# Should show: Apache Maven 3.9.11
```

---

## Starting the Services

### Step 1: Clone the Repository

```bash
git clone https://github.com/FalkorDB/jena-falkordb-adapter.git
cd jena-falkordb-adapter
```

### Step 2: Start Docker Compose with FalkorDB and Jaeger

The `docker-compose-tracing.yaml` file configures both FalkorDB (graph database) and Jaeger (tracing backend).

```bash
# Start services in detached mode
docker-compose -f docker-compose-tracing.yaml up -d

# Verify services are running
docker-compose -f docker-compose-tracing.yaml ps
```

**Expected output:**
```
NAME                IMAGE                                 STATUS              PORTS
falkordb            falkordb/falkordb:latest              Up                  0.0.0.0:6379->6379/tcp
jaeger              jaegertracing/all-in-one:latest       Up                  0.0.0.0:4317-4318->4317-4318/tcp, 0.0.0.0:16686->16686/tcp
```

**Service Endpoints:**
- **FalkorDB**: `localhost:6379`
- **Jaeger UI**: http://localhost:16686
- **OTLP gRPC**: `localhost:4317` (for sending traces)

### Step 3: Verify FalkorDB is Running

```bash
# Test connection to FalkorDB
redis-cli -p 6379 PING
# Should return: PONG
```

### Step 4: Build the Project

```bash
# Build with Java 21
mvn clean install -DskipTests

# Verify build was successful
ls -lh jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar
```

---

## Running the Fuseki Server

### Configure Environment Variables

```bash
# FalkorDB connection settings
export FALKORDB_HOST=localhost
export FALKORDB_PORT=6379
export FALKORDB_GRAPH=demo_graph

# Fuseki server settings
export FUSEKI_PORT=3330

# OpenTelemetry tracing settings
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=jena-falkordb-demo
export OTEL_TRACING_ENABLED=true
```

### Start the Fuseki Server

```bash
# Start Fuseki with the three-layer config (GeoSPARQL + Forward Inference + FalkorDB)
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

**Expected output:**
```
INFO  Fuseki server starting...
INFO  Dataset: /falkor
INFO  SPARQL Query endpoint: http://localhost:3330/falkor/query
INFO  SPARQL Update endpoint: http://localhost:3330/falkor/update
INFO  Server started on port 3330
```

Keep this terminal open. Open a new terminal for running the demo commands.

### Verify Fuseki is Running

```bash
# Test Fuseki endpoint
curl -s http://localhost:3330/$/ping
# Should return: {"status": "ok"}
```

---

## Optimization 1: Batch Writes via Transactions

### What is Batch Write Optimization?

Batch writes buffer multiple triple operations during a transaction and flush them in bulk using Cypher's `UNWIND` clause, reducing database round trips from N to ~1.

**Performance:** 100-1000x faster for bulk operations

**Test File:** [FalkorDBTransactionHandlerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/FalkorDBTransactionHandlerTest.java)

### Limitations

- Only works within Jena transactions (`model.begin()` / `model.commit()`)
- Operations outside transactions are sent immediately
- Maximum batch size is 1000 operations

### Demo 1.1: Load Data with Batch Writes

```bash
# Load sample social network data (100 people with relationships)
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @data/social_network.ttl

# Expected: Success (no output means success in Fuseki)
```

### Demo 1.2: Bulk Insert with Multiple Triples

```bash
# Insert multiple people at once
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

INSERT DATA {
  ex:alice a foaf:Person ;
    foaf:name "Alice" ;
    foaf:age 30 ;
    foaf:email "alice@example.org" .
    
  ex:bob a foaf:Person ;
    foaf:name "Bob" ;
    foaf:age 35 ;
    foaf:email "bob@example.org" .
    
  ex:charlie a foaf:Person ;
    foaf:name "Charlie" ;
    foaf:age 28 .
    
  ex:alice foaf:knows ex:bob .
  ex:bob foaf:knows ex:charlie .
}'
```

### Demo 1.3: Verify Data Was Inserted

```bash
# Query to count inserted people
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT (COUNT(?person) AS ?count)
WHERE {
  ?person a foaf:Person .
}'
```

**Expected output:**
```json
{
  "results": {
    "bindings": [
      { "count": { "value": "103", "datatype": "..." } }
    ]
  }
}
```

### What to Expect in Jaeger

**Trace Name:** `POST /falkor/update`

**Spans to look for:**
1. **HTTP Request Span** (`FusekiTracingFilter`)
   - Duration: Total request time
   - Attributes: `http.method=POST`, `http.route=/falkor/update`

2. **Transaction Commit Span** (`FalkorDBTransaction.commit`)
   - Duration: Time to flush buffered operations
   - Attributes: `rdf.triple_count=11`, `falkordb.operation=commit`

3. **Batch Flush Spans**:
   - `FalkorDBTransaction.flushLiteralBatch` (for properties like name, age, email)
   - `FalkorDBTransaction.flushTypeBatch` (for rdf:type triples)
   - `FalkorDBTransaction.flushRelationshipBatch` (for foaf:knows relationships)

**Jaeger URL:** http://localhost:16686/trace/{trace-id}

---
## Optimization 2: Query Pushdown (SPARQL to Cypher)

### What is Query Pushdown?

Query pushdown translates SPARQL Basic Graph Patterns (BGPs) to native Cypher queries, executing in a single database operation instead of N+1 round trips.

**Performance:** Nx-N²x fewer database calls

**Test Files:** 
- [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java)
- [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)

### Limitations

- Only supports Basic Graph Patterns (BGPs)
- Variable predicates supported only in single-triple patterns
- Complex nested patterns may fall back to standard evaluation

### Demo 2.1: Simple Query with Concrete Subject and Predicate

```bash
# Find all friends of Alice
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX ex: <http://example.org/>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?friend ?name
WHERE {
  ex:alice foaf:knows ?friend .
  ?friend foaf:name ?name .
}'
```

**Expected output:**
```json
{
  "results": {
    "bindings": [
      { "friend": {"value": "http://example.org/bob"}, "name": {"value": "Bob"} }
    ]
  }
}
```

**Generated Cypher (visible in Jaeger):**
```cypher
MATCH (s:Resource {uri: $p0})-[:`http://xmlns.com/foaf/0.1/knows`]->(friend:Resource)
MATCH (friend)
WHERE friend.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
RETURN friend.uri AS friend, friend.`http://xmlns.com/foaf/0.1/name` AS name
```

### Demo 2.2: Friends of Friends (2-hop traversal)

```bash
# Find friends of friends of Alice
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX ex: <http://example.org/>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?fof ?name
WHERE {
  ex:alice foaf:knows ?friend .
  ?friend foaf:knows ?fof .
  ?fof foaf:name ?name .
}'
```

**Generated Cypher:**
```cypher
MATCH (:Resource {uri: $p0})-[:`http://xmlns.com/foaf/0.1/knows`]->(:Resource)
      -[:`http://xmlns.com/foaf/0.1/knows`]->(fof:Resource)
WHERE fof.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
RETURN fof.uri AS fof, fof.`http://xmlns.com/foaf/0.1/name` AS name
```

### Demo 2.3: Variable Predicates

```bash
# Get all properties of Alice
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX ex: <http://example.org/>
SELECT ?property ?value
WHERE {
  ex:alice ?property ?value .
}'
```

**Generated Cypher (triple UNION for relationships, properties, and types):**
```cypher
# Part 1: Relationships
MATCH (s:Resource {uri: $p0})-[_r]->(o:Resource)
RETURN s.uri AS s, type(_r) AS property, o.uri AS value
UNION ALL
# Part 2: Properties
MATCH (s:Resource {uri: $p0})
UNWIND keys(s) AS _propKey
WITH s, _propKey WHERE _propKey <> 'uri'
RETURN s.uri AS s, _propKey AS property, s[_propKey] AS value
UNION ALL
# Part 3: Types (rdf:type from labels)
MATCH (s:Resource {uri: $p0})
UNWIND labels(s) AS _label
WITH s, _label WHERE _label <> 'Resource'
RETURN s.uri AS s, 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type' AS property, _label AS value
```

### Demo 2.4: Type-Based Query

```bash
# Find all people
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name
WHERE {
  ?person a foaf:Person .
  ?person foaf:name ?name .
}
LIMIT 10'
```

**Generated Cypher:**
```cypher
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
WHERE person.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
RETURN person.uri AS person, person.`http://xmlns.com/foaf/0.1/name` AS name
LIMIT 10
```

### What to Expect in Jaeger

**Trace Name:** `GET /falkor/query`

**Spans to look for:**
1. **HTTP Request Span** - Query request handling
2. **Compilation Span** (`SparqlToCypherCompiler.translate`)
   - Attributes: 
     - `falkordb.optimization.type=BGP_PUSHDOWN`
     - `sparql.bgp.triple_count=2`
     - `falkordb.cypher.query=<generated Cypher>`
3. **Execution Span** (`FalkorDBOpExecutor.execute`)
   - Attributes:
     - `falkordb.result_count=<number of results>`
     - `falkordb.fallback=false` (if optimization succeeded)
4. **Cypher Execution Span** (`TracedGraph.query`)
   - Actual database query execution

**Jaeger URL:** http://localhost:16686/trace/{trace-id}

---

## Optimization 3: Variable Objects

### What is Variable Object Optimization?

When a variable object could be either a literal or URI, the compiler generates a UNION query to fetch both types efficiently in a single database call.

**Performance:** 2x fewer round trips

**Test Files:**
- [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) (see `testVariableObject*` methods)
- [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)

### Limitations

- Only works for single-triple patterns with variable objects
- Multi-triple patterns require the object variable to be used as a subject elsewhere (closed-chain pattern)

### Demo 3.1: Query Mixed Data Types

```bash
# First, add both literal and resource values for the same predicate
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:document1 ex:hasValue "literal text" .
  ex:document2 ex:hasValue ex:resource1 .
}'

# Now query both types of values
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX ex: <http://example.org/>
SELECT ?doc ?value
WHERE {
  ?doc ex:hasValue ?value .
}'
```

**Generated Cypher (dual UNION):**
```cypher
# Part 1: Relationships (resource values)
MATCH (doc:Resource)-[:`http://example.org/hasValue`]->(value:Resource)
RETURN doc.uri AS doc, value.uri AS value
UNION ALL
# Part 2: Properties (literal values)
MATCH (doc:Resource)
WHERE doc.`http://example.org/hasValue` IS NOT NULL
RETURN doc.uri AS doc, doc.`http://example.org/hasValue` AS value
```

### Demo 3.2: Closed-Chain Pattern

```bash
# Find mutual friends (bidirectional relationships)
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person1 ?person2
WHERE {
  ?person1 foaf:knows ?person2 .
  ?person2 foaf:knows ?person1 .
}
LIMIT 5'
```

**Generated Cypher:**
```cypher
MATCH (person1:Resource)-[:`http://xmlns.com/foaf/0.1/knows`]->(person2:Resource)
MATCH (person2)-[:`http://xmlns.com/foaf/0.1/knows`]->(person1)
RETURN person1.uri AS person1, person2.uri AS person2
LIMIT 5
```

### What to Expect in Jaeger

**Spans to look for:**
- `SparqlToCypherCompiler.translate` with `falkordb.optimization.type=VARIABLE_OBJECT_PUSHDOWN`
- Cypher query will contain `UNION ALL` for dual-path retrieval

---
## Optimization 4: OPTIONAL Patterns

### What is OPTIONAL Pattern Optimization?

SPARQL OPTIONAL patterns are translated to Cypher OPTIONAL MATCH clauses, returning all required matches with NULL for missing optional data in a single query.

**Performance:** Nx fewer round trips (avoids N+1 queries)

**Test Files:**
- [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) (see `testOptional*` methods)
- [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)

### Limitations

- Variable predicates in OPTIONAL patterns not yet supported (will fall back)
- Complex nested OPTIONAL patterns may fall back to standard evaluation

### Demo 4.1: Basic OPTIONAL with Email

```bash
# Query all people with optional email addresses
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX ex: <http://example.org/>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name ?email
WHERE {
  ?person a foaf:Person .
  ?person foaf:name ?name .
  OPTIONAL { ?person foaf:email ?email }
}
ORDER BY ?name
LIMIT 10'
```

**Generated Cypher:**
```cypher
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
WHERE person.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
OPTIONAL MATCH (person)
WHERE person.`http://xmlns.com/foaf/0.1/email` IS NOT NULL
RETURN person.uri AS person, 
       person.`http://xmlns.com/foaf/0.1/name` AS name,
       person.`http://xmlns.com/foaf/0.1/email` AS email
ORDER BY name
LIMIT 10
```

**Expected output:**
- Alice has email: "alice@example.org"
- Bob has email: "bob@example.org"
- Charlie has NULL (no email)

### Demo 4.2: Multiple OPTIONAL Clauses

```bash
# Query people with optional email and phone
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:alice ex:phone "+1-555-1234" .
}'

curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX ex: <http://example.org/>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name ?email ?phone
WHERE {
  ?person a foaf:Person .
  ?person foaf:name ?name .
  OPTIONAL { ?person foaf:email ?email }
  OPTIONAL { ?person ex:phone ?phone }
}
ORDER BY ?name
LIMIT 5'
```

### Demo 4.3: OPTIONAL with FILTER

```bash
# Find young people (age < 35) with optional email
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name ?age ?email
WHERE {
  ?person a foaf:Person .
  ?person foaf:name ?name .
  ?person foaf:age ?age .
  FILTER(?age < 35)
  OPTIONAL { ?person foaf:email ?email }
}
ORDER BY ?age'
```

### What to Expect in Jaeger

**Spans to look for:**
- `SparqlToCypherCompiler.translateWithOptional` with `falkordb.optimization.type=OPTIONAL_PUSHDOWN`
- `FalkorDBOpExecutor.executeOptional` with query execution details
- Cypher query contains `OPTIONAL MATCH` clauses

---

## Optimization 5: UNION Patterns

### What is UNION Pattern Optimization?

SPARQL UNION patterns are translated to Cypher UNION queries, combining results from alternative query patterns in a single database call.

**Performance:** Nx fewer round trips (avoids N separate queries)

**Test Files:**
- [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) (see `testUnion*` methods)
- [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)

### Limitations

- Variable predicates not supported in UNION branches (will fall back)
- Both branches must compile successfully to Cypher
- MINUS (set difference) not yet optimized

### Demo 5.1: Type Union (Students or Teachers)

```bash
# First, add some students and teachers
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:student1 a ex:Student ; ex:name "John Student" .
  ex:student2 a ex:Student ; ex:name "Jane Student" .
  ex:teacher1 a ex:Teacher ; ex:name "Prof. Smith" .
  ex:teacher2 a ex:Teacher ; ex:name "Dr. Jones" .
}'

# Query both students and teachers
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX ex: <http://example.org/>
SELECT ?person ?name
WHERE {
  { ?person a ex:Student }
  UNION
  { ?person a ex:Teacher }
  ?person ex:name ?name .
}
ORDER BY ?name'
```

### Demo 5.2: Relationship Alternatives

```bash
# Find all connections (friends or colleagues)
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:alice ex:worksWith ex:david .
  ex:bob ex:worksWith ex:eve .
}'

curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX ex: <http://example.org/>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?connection
WHERE {
  { ?person foaf:knows ?connection }
  UNION
  { ?person ex:worksWith ?connection }
}
LIMIT 10'
```

### What to Expect in Jaeger

**Spans to look for:**
- `SparqlToCypherCompiler.translateUnion` with `falkordb.optimization.type=UNION_PUSHDOWN`
- `FalkorDBOpExecutor.executeUnion` with execution details
- Cypher query contains `UNION` keyword

---

## Optimization 6: FILTER Expressions

### What is FILTER Optimization?

SPARQL FILTER expressions are translated to Cypher WHERE clauses, eliminating client-side filtering and reducing data transfer.

**Performance:** Reduces data transfer, enables database indexes, eliminates client-side filtering

**Test Files:**
- [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) (see `testFilterWith*` methods)
- [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)

### Limitations

- Some SPARQL filter functions (`regex()`, `str()`, `bound()`, `isURI()`) not yet supported
- Complex nested filters may fall back to standard evaluation

### Demo 6.1: Numeric Comparison

```bash
# Find people older than 30
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name ?age
WHERE {
  ?person a foaf:Person .
  ?person foaf:name ?name .
  ?person foaf:age ?age .
  FILTER(?age > 30)
}
ORDER BY ?age'
```

### Demo 6.2: Range Filter (AND operator)

```bash
# Find people between 25 and 35 years old
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name ?age
WHERE {
  ?person a foaf:Person .
  ?person foaf:name ?name .
  ?person foaf:age ?age .
  FILTER(?age >= 25 && ?age <= 35)
}
ORDER BY ?age'
```

### What to Expect in Jaeger

**Spans to look for:**
- `SparqlToCypherCompiler.translateWithFilter` with `falkordb.optimization.type=FILTER_PUSHDOWN`
- `FalkorDBOpExecutor.executeFilter` with execution details
- Cypher query contains WHERE clause with filter conditions

---

## Optimization 7: Aggregations (GROUP BY)

### What is Aggregation Optimization?

SPARQL aggregation queries (GROUP BY with COUNT, SUM, AVG, MIN, MAX) are translated to native Cypher aggregation queries, enabling database-side computation and reducing data transfer.

**Performance:** 200-1000x less data transfer, enables native database aggregation

**Test Files:**
- [AggregationToCypherTranslatorTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/AggregationToCypherTranslatorTest.java)
- [FalkorDBAggregationPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBAggregationPushdownTest.java)

### Limitations

- Only supports aggregations over Basic Graph Patterns (BGPs)
- Complex subpatterns (FILTER, OPTIONAL, UNION within GROUP BY) not yet supported
- HAVING clause not yet optimized

### Demo 7.1: COUNT with GROUP BY

```bash
# Count people by type
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
SELECT ?type (COUNT(?person) AS ?count)
WHERE {
  ?person rdf:type ?type .
}
GROUP BY ?type
ORDER BY DESC(?count)'
```

**Generated Cypher:**
```cypher
MATCH (person:Resource)
UNWIND labels(person) AS type
WHERE type <> 'Resource'
RETURN type, count(person) AS count
ORDER BY count DESC
```

### Demo 7.2: Multiple Aggregations

```bash
# Get comprehensive age statistics
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT 
  (COUNT(?person) AS ?count)
  (AVG(?age) AS ?avgAge)
  (MIN(?age) AS ?minAge)
  (MAX(?age) AS ?maxAge)
  (SUM(?age) AS ?totalAge)
WHERE {
  ?person a foaf:Person .
  ?person foaf:age ?age .
}'
```

### Demo 7.3: COUNT DISTINCT

```bash
# Count distinct ages
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT (COUNT(DISTINCT ?age) AS ?distinctAges)
WHERE {
  ?person a foaf:Person .
  ?person foaf:age ?age .
}'
```

### What to Expect in Jaeger

**Spans to look for:**
- `FalkorDBOpExecutor.execute(OpGroup)` with aggregation execution
- `AggregationToCypherTranslator.translate` for aggregation compilation
- Cypher query contains aggregation functions (count, sum, avg, min, max)

---

## Optimization 8: Magic Property (Direct Cypher)

### What is Magic Property?

The magic property (`falkor:cypher`) allows direct execution of Cypher queries within SPARQL for maximum control and performance.

**Use Cases:** Variable length paths, complex patterns, aggregations, graph algorithms

**Test Files:**
- [CypherQueryFuncTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/pfunction/CypherQueryFuncTest.java)
- [MagicPropertyDocExamplesTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/pfunction/MagicPropertyDocExamplesTest.java)

### Limitations

- Must return results with variable names matching SPARQL query
- Requires understanding of Cypher query language
- No validation of Cypher syntax before execution

### Demo 8.1: Simple Cypher Query

```bash
# Find all friends of Alice using Cypher
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
PREFIX ex: <http://example.org/>
SELECT ?friend ?name
WHERE {
  (?friend ?name) falkor:cypher """
    MATCH (:Resource {uri: "http://example.org/alice"})
          -[:`http://xmlns.com/foaf/0.1/knows`]->(friend:Resource)
    WHERE friend.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
    RETURN friend.uri AS friend, friend.`http://xmlns.com/foaf/0.1/name` AS name
  """
}'
```

### Demo 8.2: Aggregation with Cypher

```bash
# Count friends per person using Cypher
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?friendCount
WHERE {
  (?person ?friendCount) falkor:cypher """
    MATCH (p:Resource)-[:`http://xmlns.com/foaf/0.1/knows`]->(:Resource)
    WHERE p.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
    RETURN p.uri AS person, count(*) AS friendCount
    ORDER BY friendCount DESC
    LIMIT 10
  """
}'
```

### Demo 8.3: Friends of Friends with Path Length

```bash
# Find friends of friends (2-hop) using Cypher
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
PREFIX ex: <http://example.org/>
SELECT ?fof ?name
WHERE {
  (?fof ?name) falkor:cypher """
    MATCH (:Resource {uri: "http://example.org/alice"})
          -[:`http://xmlns.com/foaf/0.1/knows`*2..2]->(fof:Resource)
    WHERE fof.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
    RETURN DISTINCT fof.uri AS fof, fof.`http://xmlns.com/foaf/0.1/name` AS name
  """
}'
```

### What to Expect in Jaeger

**Spans to look for:**
- `CypherQueryFunc.execute` with direct Cypher execution
- Attributes:
  - `falkordb.cypher.query=<Cypher query>`
  - `falkordb.cypher.result_count=<number of results>`
  - `falkordb.cypher.var_count=<number of variables>`

---

## Viewing Traces in Jaeger

### Access Jaeger UI

Open your browser and navigate to:
```
http://localhost:16686
```

### Search for Traces

1. **Select Service**: Choose `jena-falkordb-demo` from the Service dropdown
2. **Operation**: Select specific operations like `GET /falkor/query` or `POST /falkor/update`
3. **Lookback**: Set to `Last Hour` or `Last 15 minutes`
4. **Click**: "Find Traces"

### Understanding Trace Hierarchy

Each trace shows a hierarchy of spans:

```
├── HTTP Request (FusekiTracingFilter)
│   ├── Query Compilation (SparqlToCypherCompiler.translate)
│   ├── Query Execution (FalkorDBOpExecutor.execute)
│   │   └── Cypher Execution (TracedGraph.query)
│   │       └── Database Call (Redis)
│   └── Result Processing
```

### Key Attributes to Look For

**HTTP Request Spans:**
- `http.method`: GET or POST
- `http.route`: /falkor/query or /falkor/update
- `sparql.query`: The SPARQL query (truncated)

**Compilation Spans:**
- `falkordb.optimization.type`: Type of optimization (BGP_PUSHDOWN, OPTIONAL_PUSHDOWN, etc.)
- `falkordb.cypher.query`: Generated Cypher query
- `sparql.bgp.triple_count`: Number of triples in pattern

**Execution Spans:**
- `falkordb.result_count`: Number of results returned
- `falkordb.fallback`: Whether optimization fell back to standard evaluation

**Transaction Spans:**
- `rdf.triple_count`: Number of triples committed
- `falkordb.batch_size`: Size of batch operations

### Example Trace URLs

After running queries, you'll see trace IDs. Click on any trace to view details:

```
http://localhost:16686/trace/<trace-id>
```

---

## Running Tests

### Run All Tests

```bash
# Run all tests with Java 21
# Make sure you are in the project root directory
cd jena-falkordb-adapter
export JAVA_HOME=/usr/lib/jvm/msopenjdk-21-amd64
export PATH=$JAVA_HOME/bin:$PATH
mvn clean test
```

### Run Specific Test Classes

```bash
# Test batch writes
mvn test -Dtest=FalkorDBTransactionHandlerTest

# Test query pushdown
mvn test -Dtest=FalkorDBQueryPushdownTest

# Test aggregations
mvn test -Dtest=FalkorDBAggregationPushdownTest

# Test OPTIONAL patterns
mvn test -Dtest=SparqlToCypherCompilerTest#testOptional*

# Test UNION patterns
mvn test -Dtest=SparqlToCypherCompilerTest#testUnion*

# Test FILTER expressions
mvn test -Dtest=SparqlToCypherCompilerTest#testFilterWith*
```

### Run Integration Tests

```bash
# Run Fuseki integration tests
mvn test -pl jena-fuseki-falkordb

# Run with tracing enabled
OTEL_TRACING_ENABLED=true mvn test
```

### Verify Build

```bash
# Clean build with all tests
mvn clean install

# Expected output at the end:
# [INFO] BUILD SUCCESS
# [INFO] Total time: XX:XX min
```

---

## Troubleshooting

### Issue: FalkorDB Not Running

**Symptoms:** Connection refused errors, tests failing

**Solution:**
```bash
# Check if FalkorDB is running
docker ps | grep falkordb

# If not running, start it
docker-compose -f docker-compose-tracing.yaml up -d falkordb

# Test connection
redis-cli -p 6379 PING
# Should return: PONG
```

### Issue: Jaeger Not Showing Traces

**Symptoms:** No traces appear in Jaeger UI

**Solution:**
```bash
# 1. Check Jaeger is running
docker ps | grep jaeger

# 2. Verify OTLP endpoint is correct
curl http://localhost:14269/
# Should return health check response

# 3. Check environment variables are set
echo $OTEL_TRACING_ENABLED
echo $OTEL_EXPORTER_OTLP_ENDPOINT

# 4. Restart Fuseki with correct environment variables
export OTEL_TRACING_ENABLED=true
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=jena-falkordb-demo
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

### Issue: Java Version Mismatch

**Symptoms:** Build errors related to Java version

**Solution:**
```bash
# Check current Java version
java -version

# Should show Java 21. If not:
export JAVA_HOME=/usr/lib/jvm/msopenjdk-21-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Verify
java -version
```

### Issue: Port Already in Use

**Symptoms:** "Address already in use" errors

**Solution:**
```bash
# Find process using port 3330 (Fuseki)
lsof -i :3330

# Kill the process
kill -9 <PID>

# Or use a different port
export FUSEKI_PORT=3331
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

### Issue: Curl Commands Not Working

**Symptoms:** Connection errors, timeout

**Solution:**
```bash
# 1. Verify Fuseki is running
curl http://localhost:3330/$/ping

# 2. Check if data endpoint exists
curl http://localhost:3330/$/datasets

# 3. Try simple query first
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=SELECT * WHERE { ?s ?p ?o } LIMIT 1'
```

### Issue: Tests Failing

**Symptoms:** Test failures during mvn test

**Solution:**
```bash
# 1. Ensure FalkorDB is running and clean
docker-compose -f docker-compose-tracing.yaml down -v
docker-compose -f docker-compose-tracing.yaml up -d

# 2. Clean and rebuild
mvn clean install -DskipTests

# 3. Run tests again
mvn test

# 4. Run specific failing test for more details
mvn test -Dtest=<TestClassName> -X
```

---

## Summary

This demo guide covered all 8 optimizations in the Jena-FalkorDB Adapter:

1. ✅ **Batch Writes** - 100-1000x faster bulk operations
2. ✅ **Query Pushdown** - Nx-N²x fewer database calls
3. ✅ **Variable Objects** - 2x fewer round trips for mixed data
4. ✅ **OPTIONAL Patterns** - Nx fewer queries for partial data
5. ✅ **UNION Patterns** - Nx fewer queries for alternatives
6. ✅ **FILTER Expressions** - Database-side filtering
7. ✅ **Aggregations** - 200-1000x less data transfer
8. ✅ **Magic Property** - Direct Cypher for maximum control

### Next Steps

1. **Explore Samples**: Check the [`samples/`](samples/) directory for complete working examples
2. **Read Documentation**: 
   - [OPTIMIZATIONS.md](OPTIMIZATIONS.md) - Detailed optimization guide
   - [TRACING.md](TRACING.md) - OpenTelemetry tracing documentation
   - [MAGIC_PROPERTY.md](MAGIC_PROPERTY.md) - Magic property documentation
3. **Run Tests**: See [Running Tests](#running-tests) section
4. **Build Your Application**: Start with simple use cases and expand

### Test File Links

- **Batch Writes**: [FalkorDBTransactionHandlerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/FalkorDBTransactionHandlerTest.java)
- **Query Pushdown**: [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)
- **Compiler Tests**: [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java)
- **Aggregations**: [FalkorDBAggregationPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBAggregationPushdownTest.java)
- **Magic Property**: [CypherQueryFuncTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/pfunction/CypherQueryFuncTest.java)

### Resources

- **Repository**: https://github.com/FalkorDB/jena-falkordb-adapter
- **FalkorDB**: https://www.falkordb.com/
- **Apache Jena**: https://jena.apache.org/
- **Jaeger**: https://www.jaegertracing.io/

---

**Happy Coding! 🚀** You have successfully completed the comprehensive demo guide for the Jena-FalkorDB Adapter!
