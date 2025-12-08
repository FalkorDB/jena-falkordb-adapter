# Proof of Concept - Jena-FalkorDB Adapter

This document demonstrates key features of the Jena-FalkorDB adapter with practical examples using curl commands.

## Prerequisites

1. **Start FalkorDB**:
   ```bash
   docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
   ```

2. **Build and Start Fuseki Server**:
   ```bash
   mvn clean install -DskipTests
   java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar
   ```

3. **Verify Fuseki is running**:
   ```bash
   curl -s http://localhost:3330/$/ping
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

**What happens:**
- SPARQL Basic Graph Pattern (BGP) is automatically translated to a single Cypher query
- No N+1 query problem
- Returns all friends-of-friends in one database operation

**Expected Cypher (generated automatically):**
```cypher
MATCH (:Resource {uri: "http://example.org/social#person1"})
      -[:`http://example.org/social#knows`]->(:Resource)
      -[:`http://example.org/social#knows`]->(fof:Resource)
WHERE fof.`http://example.org/social#name` IS NOT NULL
RETURN fof.uri AS fof, fof.`http://example.org/social#name` AS name
LIMIT 10
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

### 4.1 Setup Inference Model with Rules

The repository includes a pre-configured file `config-falkordb-lazy-inference.ttl` that uses FalkorDB as the backend with grandfather inference rules. This configuration:

- Uses FalkorDB (not in-memory) for persistent graph storage
- Applies backward-chaining inference rules for lazy, on-demand inference
- Provides all standard Fuseki endpoints at `/falkor`

**For this grandfather example, you can create a custom configuration based on the lazy inference pattern**:

```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb-lazy-inference.ttl
```

The configuration uses FalkorDB as the base model with inference layered on top, so your data is stored in FalkorDB and inference is computed on-demand using backward chaining (lazy inference).

### 4.2 Load the Data to the Inference Endpoint

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
curl -G http://localhost:3330/fathers/query \
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

## 5. GeoSPARQL with Lazy Inference

This section demonstrates combining **lazy inference** (backward chaining rules) with **GeoSPARQL** spatial queries. This powerful combination enables queries that span both inferred social relationships and geographic data.

### 5.1 Overview

The `config-falkordb-lazy-inference-with-geosparql.ttl` configuration stacks three layers:

1. **FalkorDB** - Persistent graph database backend
2. **Generic Rule Reasoner** - Lazy inference using backward chaining rules
3. **GeoSPARQL Dataset** - Spatial query capabilities with indexing

This enables queries like "Find all people I know transitively (via inference) who are within a specific geographic area (via GeoSPARQL)."

### 5.2 Start Fuseki with GeoSPARQL + Inference Configuration

```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb-lazy-inference-with-geosparql.ttl
```

The server will start on port 3030 with the service available at `/falkor`.

### 5.3 Load Example Data (Social Network with Geographic Locations)

Load the example data that contains both social relationships and geographic coordinates (London locations):

```bash
curl -X POST http://localhost:3030/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @samples/geosparql-with-inference/data-example.ttl
```

**Data Structure:**
- 5 people (Alice, Bob, Carol, Dave, Eve) with friendship connections
- Each person has a geographic location (London coordinates)
- 3 geographic features representing London districts
- Social network creates transitive paths (e.g., Alice → Bob → Carol)

### 5.4 Query: Find Transitive Friends with Their Locations

Query for all people Alice knows transitively (via lazy inference) along with their geographic locations:

```bash
curl -G http://localhost:3030/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX ex: <http://example.org/>
SELECT ?friendName ?location
WHERE {
  ex:alice social:knows_transitively ?friend .
  ?friend ex:name ?friendName ;
          geo:hasGeometry ?geom .
  ?geom geo:asWKT ?location .
}
ORDER BY ?friendName'
```

**Expected Result:**
- Returns Carol, Dave, and Eve (all transitive friends of Alice)
- Includes their WKT point coordinates in London

**What happened:**
1. The inference rule computed transitive `knows` relationships on-demand
2. GeoSPARQL extracted the geographic location for each friend
3. Both features worked together seamlessly

### 5.5 Query: Check Transitive Connection (ASK Query)

Check if Eve is in Alice's extended network using an ASK query with inference:

```bash
curl -G http://localhost:3030/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
PREFIX ex: <http://example.org/>
ASK {
  ex:alice social:knows_transitively ex:eve .
}'
```

**Expected Result:**
```json
{
  "boolean": true
}
```

Eve is reachable via the path: Alice → Bob → Dave → Eve

### 5.6 Query: Find People with Occupations and Locations

Query for people in Bob's extended network with their occupations and geographic locations:

```bash
curl -G http://localhost:3030/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX ex: <http://example.org/>
SELECT ?friendName ?occupation ?location
WHERE {
  ex:bob social:knows_transitively ?friend .
  ?friend ex:name ?friendName ;
          ex:occupation ?occupation ;
          geo:hasGeometry ?geom .
  ?geom geo:asWKT ?location .
}
ORDER BY ?friendName'
```

**Expected Result:**
- Returns Carol, Dave, and Eve with their occupations and coordinates
- Demonstrates combining inference, standard properties, and spatial data

### 5.7 Query: Geographic Features with People

Query all geographic features (regions) and people in the dataset:

```bash
curl -G http://localhost:3030/falkor/query \
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
curl -G http://localhost:3030/falkor/query \
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
curl -G http://localhost:3030/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode "query=$QUERY"
```

### 5.10 Key Benefits

✅ **Lazy Inference**: Rules compute transitive relationships on-demand, not upfront  
✅ **Spatial Queries**: Full GeoSPARQL support for points, polygons, and spatial functions  
✅ **Combined Queries**: Seamlessly mix inference and spatial predicates  
✅ **Performance**: FalkorDB backend with spatial indexing  
✅ **Standards Compliant**: Uses standard SPARQL, GeoSPARQL, and Jena inference

---

## Summary

This POC demonstrates:

1. ✅ **Batch Loading**: Optimized data loading using transactions (100-1000x faster)
2. ✅ **Query Without Magic Property**: Automatic SPARQL-to-Cypher translation with query pushdown
3. ✅ **Query With Magic Property**: Direct Cypher execution for maximum control
4. ✅ **Loading fathers_father_sample.ttl**: Simple family relationship data
5. ✅ **Inference with Rules**: Backward-chaining rules to infer grandfather relationships
6. ✅ **GeoSPARQL with Lazy Inference**: Combining spatial queries with transitive reasoning

### Key Features Showcased

- **Performance**: Batch writes, query pushdown, magic property
- **Flexibility**: Standard SPARQL or direct Cypher
- **Intelligence**: Rule-based inference with backward chaining
- **Compatibility**: Works with standard Jena reasoning and rules

### Next Steps

- Explore [DEMO.md](DEMO.md) for comprehensive examples of all 8 optimizations
- Read [OPTIMIZATIONS.md](OPTIMIZATIONS.md) for technical details
- Check [MAGIC_PROPERTY.md](MAGIC_PROPERTY.md) for advanced Cypher patterns
- Review [samples/](samples/) directory for complete working examples

---

**Happy Exploring! 🚀**
