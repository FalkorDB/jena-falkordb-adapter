# Proof of Concept - Jena-FalkorDB Adapter

This document demonstrates key features of the Jena-FalkorDB adapter with practical examples using curl commands.

## Prerequisites

1. **Install Java and Maven using SDKMAN**:
   ```bash
   # Install SDKMAN
   curl -s "https://get.sdkman.io" | bash
   source "$HOME/.sdkman/bin/sdkman-init.sh"
   
   # Install Java 25.0.1-graal and Maven 3.9.11 (from .sdkmanrc)
   sdk env install
   ```

2. **Start FalkorDB with tracing**:
   ```bash
   docker-compose -f docker-compose-tracing.yaml up -d
   ```

3. **Build and Start Fuseki Server**:
   ```bash
   mvn clean install
   java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
     --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
   ```

4. **Verify Fuseki is running**:
   ```bash
   curl -s http://localhost:3330/#/ping
   ```

> **Note:** docker-compose must be running for tests to pass.

---

## Loading Data on Startup

You can configure Fuseki to automatically load data files when the server starts by adding a `ja:data` property to your dataset configuration. This is useful for initializing your database with seed data or test data.

### How to Load Data on Startup

1. **Edit the configuration file** (`config-falkordb.ttl`) and add the `ja:data` property to the RDF dataset definition:

```turtle
# RDF Dataset wrapping the inference model
# This is needed because GeoSPARQL expects a Dataset, not a Model
:dataset_rdf rdf:type ja:RDFDataset ;
    ja:defaultGraph :inf_model ;
    # Load data files on startup (can specify multiple files)
    ja:data <file:data/fathers_father_sample.ttl> ;
    ja:data <file:data/social_network.ttl> .
```

2. **Start Fuseki with the modified config file**:

```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
     --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

The data files will be automatically loaded into the database when the server starts.

### Important Notes

- **File paths**: Use `file:` prefix for local files. Paths can be absolute or relative to the working directory where you start Fuseki.
- **Multiple files**: You can specify multiple `ja:data` properties to load multiple files.
- **File formats**: Supported formats include Turtle (`.ttl`), RDF/XML (`.rdf`), N-Triples (`.nt`), and others. The format is auto-detected from the file extension.
- **Inference rules**: If you have inference rules enabled (like in the default config), inferred triples will be materialized automatically as the data is loaded.
- **One-time loading**: Data is loaded only once at startup. If the database already contains the data from a previous run, it will be loaded again (creating duplicates). For persistent data, consider loading data manually via the REST API after the first startup.

### Example: Loading Sample Data

To load the fathers_father_sample.ttl file on startup, modify the `:dataset_rdf` section in your config file:

```turtle
:dataset_rdf rdf:type ja:RDFDataset ;
    ja:defaultGraph :inf_model ;
    ja:data <file:data/fathers_father_sample.ttl> .
```

Then start the server normally:

```bash
mvn clean install
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
     --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

The data will be loaded automatically, and inference rules will be applied immediately (forward chaining). You can verify the data is loaded by querying:

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }'
```

---

## Accessing Fuseki

### Fuseki Web UI

Apache Jena Fuseki provides a web-based user interface for managing datasets and running SPARQL queries.

**Access the Fuseki UI:**
- URL: **http://localhost:3330/**
- The UI provides:
  - Dataset management and statistics
  - Interactive SPARQL query editor with syntax highlighting
  - SPARQL update interface
  - File upload capabilities
  - Server information and configuration

**Screenshot of the Fuseki UI:**

![Apache Jena Fuseki UI](https://github.com/user-attachments/assets/31d1aedd-83ec-4827-8c87-f304d46a3116)

The standard Apache Jena Fuseki UI provides a clean, modern interface for interacting with your SPARQL endpoints. The interface includes:
- **Navigation menu** - Access to datasets, management, and help sections
- **Dataset browser** - View and manage available datasets
- **Server status indicator** - Monitor server health and connectivity
- **Query interface** - Interactive SPARQL query editor (accessible via the dataset actions)

**Quick Start with the UI:**
1. Open your browser to http://localhost:3330/
2. Click on a dataset name (e.g., `falkor`) to access its query interface
3. In the query tab, enter a SPARQL query in the editor
4. Click **"Execute"** to run the query
5. View results in table, JSON, or other formats

### REST API Endpoints

The Fuseki server exposes standard SPARQL Protocol endpoints:

| Endpoint                              | Method | Purpose | Content-Type |
|---------------------------------------|--------|---------|--------------|
| `http://localhost:3330/falkor/query`  | GET/POST | SPARQL SELECT/ASK queries | `application/sparql-query` |
| `http://localhost:3330/falkor/update` | POST | SPARQL UPDATE operations | `application/sparql-update` |
| `http://localhost:3330/falkor/data`   | GET/POST/PUT/DELETE | Graph Store Protocol (upload/download data) | `text/turtle`, `application/rdf+xml`, etc. |
| `http://localhost:3330/#/ping`        | GET | Health check endpoint | - |
| `http://localhost:3330/#/stats`       | GET | Server statistics | `application/json` |

**Example REST API Usage:**

```bash
# Query via GET (URL-encoded)
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=SELECT * WHERE { ?s ?p ?o } LIMIT 10'

# Query via POST (request body)
curl -X POST http://localhost:3330/falkor/query \
  -H "Content-Type: application/sparql-query" \
  --data 'SELECT * WHERE { ?s ?p ?o } LIMIT 10'

# Upload data (Turtle format)
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @mydata.ttl

# SPARQL UPDATE
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'INSERT DATA { <http://example.org/subject> <http://example.org/predicate> "object" . }'

# Get server stats
curl http://localhost:3330/#/stats
```

**Available Result Formats:**
- `application/sparql-results+json` (JSON results - default)
- `application/sparql-results+xml` (XML results)
- `text/csv` (CSV format)
- `text/tab-separated-values` (TSV format)

Specify format with the `Accept` header:
```bash
curl -H "Accept: text/csv" http://localhost:3330/falkor/query?query=...
```

---

## 1. Load the Data (Batch Optimized for Large Files)

Batch loading uses transactions to optimize writes for large datasets. The adapter automatically buffers operations during a transaction and flushes them in bulk.

### Example: Load Social Network Data (100 people with ~800 relationships)

```bash
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @data/social_network.ttl
```

**Benefits:**
- 100-1000x faster for bulk operations
- Automatic batching within transactions
- Reduced database round trips

---

## 2. Query the Data

### 2.1 Query Without Magic Property (Standard SPARQL with Query Pushdown)

Find friends of friends of person1 using standard SPARQL. The adapter automatically translates this to an optimized Cypher query:

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
SELECT ?fof ?name
WHERE {
  social:person1 social:knows ?friend .
  ?friend social:knows ?fof .
  ?fof social:name ?name .
}
LIMIT 10'
```

---

### 2.2 Query With Magic Property (Direct Cypher Execution)

Use the `falkor:cypher` magic property for maximum control and performance. This allows you to write Cypher directly within SPARQL:

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
PREFIX social: <http://example.org/social#>
SELECT ?fof WHERE {
  (?fof) falkor:cypher """
    MATCH (:Resource {uri: \"http://example.org/social#person1\"})
          -[:`http://example.org/social#knows`]->(:Resource)
          -[:`http://example.org/social#knows`]->(fof:Resource)
    RETURN DISTINCT fof.uri AS fof
  """
}'
```

**Benefits:**
- Full Cypher power within SPARQL
- No translation overhead
- Access to advanced Cypher features (variable-length paths, complex patterns, etc.)

---

## 3. Load fathers_father_sample.ttl

This file contains a simple ontology demonstrating grandfather relationships through inference rules.

### Load the Data

```bash
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @data/fathers_father_sample.ttl
```

**Data Structure:**
```turtle
# Abraham is the father of Isaac
ff:Abraham ff:father_of ff:Isaac .

# Isaac is the father of Jacob  
ff:Isaac ff:father_of ff:Jacob .

# The grandfather_of relationship (Abraham -> Jacob) will be inferred
```

### Verify the Data Loaded

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?father ?child
WHERE {
  ?father ff:father_of ?child .
}'
```

**Expected Result:**
- Abraham → Isaac (Abraham is the father of Isaac)
- Isaac → Jacob (Isaac is the father of Jacob)

---

## 4. Query About grandfather_of Using Inference Rules

The `grandfather_of_fwd.rule` file contains a forward-chaining rule that infers grandfather relationships:

```
[rule_grandfather_of: 
    (?x ff:father_of ?y)  
    (?y ff:father_of ?z) 
    ->
    (?x ff:grandfather_of ?z)
]
```

This forward-chaining rule means: "When you see that X is the father of Y, and Y is the father of Z, then immediately materialize the triple that X is the grandfather of Z."

### 4.1 Configuration

The server is already running with [config-falkordb.ttl](jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl) (started in Prerequisites above), which implements forward chaining (eager inference) to materialize relationships immediately when data is added.

See the test [GrandfatherInferenceSystemTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/GrandfatherInferenceSystemTest.java) for a complete working example.

### 4.2 Load the Data

```bash
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @data/fathers_father_sample.ttl
```

### 4.3 Query for grandfather_of Relationships

Now query for grandfather relationships - the rule will automatically infer that Abraham is the grandfather of Jacob:

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?grandfather ?grandson
WHERE {
  ?grandfather ff:grandfather_of ?grandson .
}'
```

**Expected Result:**
```json
{
  "results": {
    "bindings": [
      {
        "grandfather": { "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Abraham" },
        "grandson": { "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Jacob" }
      }
    ]
  }
}
```

**What happened:**
1. The rule engine detected the pattern: `Abraham father_of Isaac` and `Isaac father_of Jacob`
2. Applied the backward-chaining rule: `(?x ff:grandfather_of ?z) <- (?x ff:father_of ?y) (?y ff:father_of ?z)`
3. Inferred: `Abraham grandfather_of Jacob`

### 4.4 Query All Relationships (Direct and Inferred)

Get both direct father relationships and inferred grandfather relationships:

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?person ?relation ?relative
WHERE {
  {
    ?person ff:father_of ?relative .
    BIND("father_of" AS ?relation)
  }
  UNION
  {
    ?person ff:grandfather_of ?relative .
    BIND("grandfather_of" AS ?relation)
  }
}
ORDER BY ?person ?relation'
```

**Expected Results:**
- Abraham **father_of** Isaac (direct)
- Abraham **grandfather_of** Jacob (inferred by rule)
- Isaac **father_of** Jacob (direct)

---

## Alternative: Using Magic Property with Custom Cypher for Grandfather Query

If you prefer not to use inference rules, you can achieve the same result using the magic property with a Cypher path query:

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?grandfather ?grandson WHERE {
  (?grandfather ?grandson) falkor:cypher """
    MATCH (gf:Resource)-[:`http://www.semanticweb.org/ontologies/2023/1/fathers_father#father_of`*2..2]->(gs:Resource)
    RETURN gf.uri AS grandfather, gs.uri AS grandson
  """
}'
```

This uses Cypher's variable-length path matching `*2..2` to find exactly 2-hop `father_of` relationships (i.e., grandfather relationships).

---

## 5. GeoSPARQL with Forward Inference

This section demonstrates the three-layer onion architecture combining **GeoSPARQL** spatial queries with **forward chaining inference** and **FalkorDB** storage. This powerful combination enables queries that span both materialized inferred relationships and geographic data.

### 5.1 Overview

The server is already running with [config-falkordb.ttl](jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl) (started in Prerequisites), which implements a three-layer onion architecture:

1. **GeoSPARQL Dataset (Outer Layer)** - Spatial query capabilities with indexing and optimization
2. **Inference Model (Middle Layer)** - Forward chaining (eager inference) materializes relationships immediately
3. **FalkorDB Model (Core Layer)** - Persistent graph database backend

See the tests for complete working examples:
- [GeoSPARQLPOCSystemTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/GeoSPARQLPOCSystemTest.java) - GeoSPARQL queries
- [GrandfatherInferenceSystemTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/GrandfatherInferenceSystemTest.java) - Forward chaining inference tests

### 5.2 Load Example Data (Social Network with Geographic Locations)

Load the example data that contains both social relationships and geographic coordinates (London locations):

```bash
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @samples/geosparql-with-inference/data-example.ttl
```

**Data Structure:**
- 5 people (Alice, Bob, Carol, Dave, Eve) with friendship connections
- Each person has a geographic location (London coordinates)
- 3 geographic features representing London districts
- Social network creates transitive paths (e.g., Alice → Bob → Carol)

### 5.3 Query: Find Friends with Their Locations

Query for all people Alice knows directly along with their geographic locations:

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX ex: <http://example.org/>
SELECT ?friendName ?location
WHERE {
  ex:alice social:knows ?friend .
  ?friend ex:name ?friendName ;
          geo:hasGeometry ?geom .
  ?geom geo:asWKT ?location .
}
ORDER BY ?friendName'
```

**Expected Result:**
- Returns Bob (Alice's direct friend)
- Includes WKT point coordinates in London

**What happened:**
1. The GeoSPARQL layer handles spatial data queries
2. The query retrieves both social relationships and geographic locations
3. Both features work together seamlessly

### 5.4 Query: Check Direct Friend Connection (ASK Query)

Check if Bob is Alice's direct friend using an ASK query:

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
PREFIX ex: <http://example.org/>
ASK {
  ex:alice social:knows ex:bob .
}'
```

**Expected Result:**
```json
{
  "boolean": true
}
```

Bob is Alice's direct friend.

### 5.5 Query: Find Direct Friends with Occupations and Locations

Query for people in Bob's direct network with their occupations and geographic locations:

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX ex: <http://example.org/>
SELECT ?friendName ?occupation ?location
WHERE {
  ex:bob social:knows ?friend .
  ?friend ex:name ?friendName ;
          ex:occupation ?occupation ;
          geo:hasGeometry ?geom .
  ?geom geo:asWKT ?location .
}
ORDER BY ?friendName'
```

**Expected Result:**
- Returns Bob's direct friends (Carol, Dave) with their occupations and coordinates
- Demonstrates combining social relationships, standard properties, and spatial data

### 5.6 Query: Geographic Features with People

Query all geographic features (regions) and people in the dataset:

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX ex: <http://example.org/>
SELECT ?featureName ?personName ?personLocation
WHERE {
  ?feature a geo:Feature ;
           ex:name ?featureName ;
           geo:hasGeometry ?featureGeom .
  ?person a social:Person ;
          ex:name ?personName ;
          geo:hasGeometry ?personGeom .
  ?personGeom geo:asWKT ?personLocation .
}
ORDER BY ?featureName ?personName'
```

**Expected Result:**
- Lists all combinations of geographic features (Central London, North London, Tech Hub) with people
- Shows how spatial features and point geometries coexist

### 5.8 Advanced: Count People in Extended Networks by Feature

Use aggregation to count people associated with geographic features:

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX ex: <http://example.org/>
SELECT ?featureName (COUNT(DISTINCT ?person) as ?peopleCount)
WHERE {
  ?feature a geo:Feature ;
           ex:name ?featureName .
  ?person a social:Person ;
          geo:hasGeometry ?personGeom .
}
GROUP BY ?featureName
ORDER BY DESC(?peopleCount)'
```

### 5.9 More Example Queries

The `samples/geosparql-with-inference/queries.sparql` file contains 10 additional example queries demonstrating various combinations of inference and spatial data, including:

- Finding shortest inferred paths with locations
- Nested inference patterns
- Spatial feature descriptions
- Complex filtering with both inference and geography

Load and execute them using curl:

```bash
# Example: Run query from file
QUERY=$(cat samples/geosparql-with-inference/queries.sparql | head -20 | tail -15)
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode "query=$QUERY"
```

### 5.10 Key Benefits

✅ **Forward Inference**: Rules eagerly materialize inferred relationships immediately when data is added  
✅ **Spatial Queries**: Full GeoSPARQL support for points, polygons, and spatial functions  
✅ **Combined Queries**: Seamlessly mix materialized inference and spatial predicates  
✅ **Performance**: FalkorDB backend with spatial indexing and **full query pushdown** on materialized triples  
✅ **Standards Compliant**: Uses standard SPARQL, GeoSPARQL, and Jena inference

> **🚀 Performance Note**: Because forward chaining materializes inferred triples into FalkorDB, ALL query optimizations work:
> - Query pushdown translates SPARQL to efficient Cypher
> - Aggregations (COUNT, SUM, etc.) execute in the database
> - Spatial queries use FalkorDB's native graph capabilities
> - No performance penalty for querying inferred vs. base triples!

---

## 6. Observability with Jaeger Tracing

The Jena-FalkorDB adapter includes comprehensive OpenTelemetry tracing integration, allowing you to visualize and analyze query execution, optimization strategies, and database operations in real-time using Jaeger.

### 6.1 Accessing Jaeger UI

If you started the services with `docker-compose-tracing.yaml` (as described in Prerequisites), Jaeger is available at:

**Jaeger UI: http://localhost:16686/**

The UI provides:
- **Service Overview**: View all services and their trace statistics
- **Trace Search**: Search and filter traces by service, operation, duration, tags
- **Trace Details**: Deep-dive into individual traces showing spans, timing, and metadata
- **Service Dependencies**: Visualize service-to-service communication

### 6.2 Understanding Traces

Each SPARQL query or update operation creates a distributed trace that shows:

1. **HTTP Request Span** - The incoming HTTP request to Fuseki
2. **SPARQL Query/Update Span** - The SPARQL parsing and execution
3. **Query Optimization Spans** - Which optimizations were applied
4. **Database Operation Spans** - Individual Cypher queries sent to FalkorDB
5. **Result Processing Spans** - Data transformation and result building

### 6.3 Viewing Traces for Standard Queries

After running a query (e.g., the friends-of-friends query from section 2.1):

```bash
curl -G http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
SELECT ?fof ?name
WHERE {
  social:person1 social:knows ?friend .
  ?friend social:knows ?fof .
  ?fof social:name ?name .
}
LIMIT 10'
```

**To view this trace in Jaeger:**

1. Open http://localhost:16686/
2. Select **Service**: `fuseki-server` or `jena-falkordb-adapter`
3. Click **Find Traces**
4. Click on the most recent trace to view details

**What you'll see:**
- ⏱️ **Total duration**: End-to-end query execution time
- 🔍 **Query pushdown span**: Shows the translated Cypher query
- 📊 **Database execution span**: Time spent in FalkorDB
- 🏷️ **Tags**: SPARQL query text, operation type, result count

### 6.4 Viewing Traces for Magic Property Queries

Magic property queries bypass standard SPARQL translation and execute Cypher directly. This is visible in the trace:

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
PREFIX social: <http://example.org/social#>
SELECT ?fof WHERE {
  (?fof) falkor:cypher """
    MATCH (:Resource {uri: \"http://example.org/social#person1\"})
          -[:`http://example.org/social#knows`]->(:Resource)
          -[:`http://example.org/social#knows`]->(fof:Resource)
    RETURN DISTINCT fof.uri AS fof
  """
}'
```

**To view magic property traces in Jaeger:**

1. Open http://localhost:16686/
2. Select **Service**: `fuseki-server`
3. In **Tags** section, add filter: `query.type = magic_property` (if available)
4. Click **Find Traces**
5. Select the trace for your query

**What you'll see in the trace:**
- 🎯 **Magic Property Detection Span**: Shows when `falkor:cypher` was recognized
- ⚡ **Direct Cypher Execution Span**: The Cypher query executed without translation
  - **Span name**: `cypher.execute` or `magic_property.execute`
  - **Tags**: 
    - `cypher.query`: The exact Cypher query executed
    - `cypher.params`: Any parameters passed to the query
    - `optimization.type`: "magic_property"
- 📈 **Performance comparison**: Compare with equivalent pushdown query to see the difference

**Key difference from standard queries:**
- **No translation overhead**: You won't see a "query translation" span
- **Direct execution**: Single span for Cypher execution
- **Lower latency**: Typically faster as it skips SPARQL-to-Cypher translation

### 6.5 Viewing Traces for Batch Updates

Batch updates (e.g., bulk data loading) are optimized using transactions. The trace shows buffering and bulk flushing:

```bash
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @data/social_network.ttl
```

**To view batch update traces in Jaeger:**

1. Open http://localhost:16686/
2. Select **Service**: `fuseki-server`
3. Filter by **Min Duration**: Set to a higher value (e.g., 100ms) to find bulk operations
4. Click **Find Traces**
5. Look for traces with multiple child spans

**What you'll see in the trace:**
- 🚀 **Batch Write Transaction Span**: Shows the overall transaction
  - **Duration**: Total time for the entire batch operation
  - **Tags**:
    - `transaction.type`: "write"
    - `batch.size`: Number of triples buffered
    - `optimization.applied`: "batch_write"
- 📦 **Buffer Operations**: Individual triple adds buffered in memory
  - **Span name**: `buffer.add` or `triple.queue`
  - **Count**: Shows how many triples were buffered
- 💾 **Bulk Flush Span**: When buffered operations are sent to FalkorDB
  - **Span name**: `cypher.bulk_execute` or `batch.flush`
  - **Tags**:
    - `cypher.query`: The batched Cypher CREATE statement
    - `triple.count`: Number of triples in this batch
    - `batch.index`: Which batch number (if multiple flushes)
- ✅ **Commit Span**: Transaction commit operation

**Performance insights:**
- **Before optimization**: Each triple would show as a separate span
- **After optimization**: Spans are grouped, showing 100-1000x fewer database calls
- **Throughput**: Check the `triple.count` vs `duration` to see triples/second

### 6.6 Searching and Filtering Traces

Use Jaeger's powerful search capabilities:

**Filter by operation type:**
- `operation=sparql.query` - SELECT/ASK queries
- `operation=sparql.update` - INSERT/DELETE/UPDATE operations
- `operation=batch.write` - Bulk data loading

**Filter by optimization:**
- `optimization=query_pushdown` - Queries translated to Cypher
- `optimization=aggregation_pushdown` - Aggregations executed in database
- `optimization=magic_property` - Direct Cypher execution
- `optimization=batch_write` - Batched write operations
- `optimization=geospatial_pushdown` - Spatial queries pushed to FalkorDB

**Filter by performance:**
- **Min Duration**: Find slow queries (e.g., >500ms)
- **Max Duration**: Find fast queries (e.g., <100ms)
- **Lookback**: Time range (last hour, last 6 hours, etc.)

**Example searches:**

```
# Find all magic property queries
Service: fuseki-server
Operation: sparql.query
Tags: optimization=magic_property

# Find slow batch updates
Service: fuseki-server
Operation: sparql.update
Min Duration: 1s

# Find geospatial queries
Service: fuseki-server
Tags: query.type=geospatial
```

### 6.7 Analyzing Trace Details

Click on any span in a trace to see:

**Span Information:**
- **Operation Name**: What operation was performed
- **Duration**: How long it took
- **Start Time**: When it started relative to the trace

**Tags (Metadata):**
- `query.sparql`: Original SPARQL query
- `query.cypher`: Translated Cypher query (for pushdown)
- `optimization.applied`: Which optimizations were used
- `result.count`: Number of results returned
- `error`: Error message if the operation failed

**Logs (Events):**
- Query parsing started/completed
- Optimization applied
- Database connection acquired/released
- Results processed

**Process (Service Info):**
- Service name and version
- Host information
- Configuration details

### 6.8 Comparing Query Strategies

Use Jaeger to compare different approaches:

**Example: Magic Property vs Standard Query**

1. Run the same query with magic property (section 2.2)
2. Run the same query without magic property (section 2.1)
3. Open both traces in Jaeger
4. Compare:
   - Total duration
   - Number of spans
   - Database operation count
   - Translation overhead

**Typical observations:**
- Magic property: ~10-30% faster due to no translation
- Magic property: Fewer spans (simpler execution path)
- Standard query: More optimization spans visible
- Both: Similar database execution time (same Cypher query ultimately)

### 6.9 Troubleshooting with Traces

Traces help identify performance bottlenecks:

**Problem: Slow queries**
- Look for long database execution spans → optimize Cypher query
- Look for many small spans → consider batching
- Look for missing pushdown → check query pattern compatibility

**Problem: High latency**
- Check HTTP request span for network issues
- Check translation spans for complex query parsing
- Check for missing indexes in FalkorDB

**Problem: Failed operations**
- Look for spans with `error=true` tag
- Check logs within the span for error messages
- Trace the error back through parent spans to find root cause

### 6.10 Best Practices

**For development:**
- Keep Jaeger open while testing queries
- Use magic property for complex graph traversals
- Monitor trace durations to catch regressions

**For production:**
- Set sampling rate appropriately (not 100% if high traffic)
- Use trace IDs in logs for correlation
- Set up alerts on trace duration thresholds
- Export traces to long-term storage for analysis

**Trace retention:**
- By default, Jaeger stores traces in memory (limited retention)
- For production, configure Jaeger with persistent storage (Elasticsearch, Cassandra)
- See [Jaeger documentation](https://www.jaegertracing.io/docs/latest/deployment/) for storage options

---

## Summary

This POC demonstrates:

1. ✅ **Batch Loading**: Optimized data loading using transactions (100-1000x faster)
2. ✅ **Query Without Magic Property**: Automatic SPARQL-to-Cypher translation with query pushdown
3. ✅ **Query With Magic Property**: Direct Cypher execution for maximum control
4. ✅ **Loading fathers_father_sample.ttl**: Simple family relationship data
5. ✅ **Inference with Rules**: Forward-chaining rules to eagerly materialize grandfather relationships
6. ✅ **GeoSPARQL with Forward Inference**: Combining spatial queries with materialized inferred relationships
7. ✅ **Observability with Jaeger**: Real-time tracing of queries, optimizations, and database operations

### Key Features Showcased

- **Performance**: Batch writes, query pushdown, magic property
- **Flexibility**: Standard SPARQL or direct Cypher
- **Intelligence**: Rule-based inference with forward chaining (eager materialization)
- **Compatibility**: Works with standard Jena reasoning and rules

### Next Steps

- Explore [DEMO.md](DEMO.md) for comprehensive examples of all 8 optimizations
- Read [OPTIMIZATIONS.md](OPTIMIZATIONS.md) for technical details
- Check [MAGIC_PROPERTY.md](MAGIC_PROPERTY.md) for advanced Cypher patterns
- Review [samples/](samples/) directory for complete working examples

---

## Tests and Code References

All features demonstrated in this POC are thoroughly tested. Here are the key test files:

### Core Tests

- **[FusekiAssemblerConfigTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/FusekiAssemblerConfigTest.java)** - Tests Fuseki configuration with FalkorDB assembler
- **[GrandfatherInferenceSystemTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/GrandfatherInferenceSystemTest.java)** - Tests forward chaining inference with grandfather rules
- **[GeoSPARQLPOCSystemTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/GeoSPARQLPOCSystemTest.java)** - Tests GeoSPARQL with forward inference integration
- **[FusekiRestartWithDataTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/FusekiRestartWithDataTest.java)** - Tests server restart with existing data (zero errors)

### Optimization Tests

- **[FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)** - Tests SPARQL to Cypher query pushdown
- **[FalkorDBAggregationPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBAggregationPushdownTest.java)** - Tests aggregation pushdown optimization
- **[FalkorDBGeospatialPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBGeospatialPushdownTest.java)** - Tests geospatial query pushdown
- **[FalkorDBTransactionHandlerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/FalkorDBTransactionHandlerTest.java)** - Tests batch write optimizations

### Magic Property Tests

- **[CypherQueryFuncTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/pfunction/CypherQueryFuncTest.java)** - Tests magic property Cypher execution
- **[MagicPropertyDocExamplesTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/pfunction/MagicPropertyDocExamplesTest.java)** - Tests all magic property examples from documentation

### Tracing Tests

- **[FusekiTracingFilterTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/tracing/FusekiTracingFilterTest.java)** - Tests OpenTelemetry integration with Fuseki
- **[TracedGraphTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/tracing/TracedGraphTest.java)** - Tests OpenTelemetry tracing at graph level

### GeoSPARQL Tests

- **[GeoSPARQLIntegrationTest.java](jena-geosparql/src/test/java/com/falkordb/geosparql/GeoSPARQLIntegrationTest.java)** - Tests GeoSPARQL module integration
- **[SafeGeoSPARQLDatasetAssemblerTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/SafeGeoSPARQLDatasetAssemblerTest.java)** - Tests safe GeoSPARQL assembler error handling

Run all tests:
```bash
mvn clean test
```

Run specific test:
```bash
mvn test -Dtest=GrandfatherInferenceSystemTest
```

---

**Happy Exploring! 🚀**
