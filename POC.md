# Proof of Concept - Jena-FalkorDB Adapter

This document demonstrates key features of the Jena-FalkorDB adapter with practical examples using curl commands.

## Prerequisites

1. **Install Java and Maven using SDKMAN**:
   ```bash
   # Install SDKMAN
   curl -s "https://get.sdkman.io" | bash
   source "$HOME/.sdkman/bin/sdkman-init.sh"
   
   # Install Java 21.0.5-graal and Maven 3.9.11 (from .sdkmanrc)
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
   curl -s http://localhost:3330/$/ping
   ```

> **Note:** docker-compose must be running for tests to pass.

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

The `grandfather_of_bwd.rule` file contains a backward-chaining rule that infers grandfather relationships:

```
[rule_grandfather_of: 
    (?x ff:grandfather_of ?z)
    <- 
    (?x ff:father_of ?y)  
    (?y ff:father_of ?z) 
]
```

### 4.1 Configuration

The server is already running with [config-falkordb.ttl](jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl) (started in Prerequisites above), which implements forward chaining (eager inference) to materialize relationships.

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
✅ **Performance**: FalkorDB backend with spatial indexing and query pushdown on materialized triples  
✅ **Standards Compliant**: Uses standard SPARQL, GeoSPARQL, and Jena inference

---

## Summary

This POC demonstrates:

1. ✅ **Batch Loading**: Optimized data loading using transactions (100-1000x faster)
2. ✅ **Query Without Magic Property**: Automatic SPARQL-to-Cypher translation with query pushdown
3. ✅ **Query With Magic Property**: Direct Cypher execution for maximum control
4. ✅ **Loading fathers_father_sample.ttl**: Simple family relationship data
5. ✅ **Inference with Rules**: Forward-chaining rules to eagerly materialize grandfather relationships
6. ✅ **GeoSPARQL with Forward Inference**: Combining spatial queries with materialized inferred relationships

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

**Happy Exploring! 🚀**
