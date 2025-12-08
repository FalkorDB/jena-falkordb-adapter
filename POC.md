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

The repository includes a pre-configured file `config-falkordb-lazy-inference.ttl` that uses FalkorDB as the backend with lazy inference rules. This configuration:

- Uses FalkorDB (not in-memory) for persistent graph storage
- Applies backward-chaining inference rules for lazy, on-demand inference
- Provides all standard Fuseki endpoints at `/falkor`

**For this grandfather example, you can create a custom configuration based on the lazy inference pattern**:

The repository includes `config-falkordb-lazy-inference.ttl` as a reference configuration for lazy inference. To use it with the grandfather example, copy the config file and modify the rule file reference:

1. Copy the config: `cp jena-fuseki-falkordb/src/main/resources/config-falkordb-lazy-inference.ttl my-grandfather-config.ttl`
2. Edit `my-grandfather-config.ttl` and change this line:
   ```turtle
   ja:rulesFrom <file:rules/friend_of_friend_bwd.rule> ;
   ```
   to:
   ```turtle
   ja:rulesFrom <file:rules/grandfather_of_bwd.rule> ;
   ```
3. Start Fuseki with your custom config:
   ```bash
   java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
     --config my-grandfather-config.ttl
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

## Summary

This POC demonstrates:

1. ✅ **Batch Loading**: Optimized data loading using transactions (100-1000x faster)
2. ✅ **Query Without Magic Property**: Automatic SPARQL-to-Cypher translation with query pushdown
3. ✅ **Query With Magic Property**: Direct Cypher execution for maximum control
4. ✅ **Loading fathers_father_sample.ttl**: Simple family relationship data
5. ✅ **Inference with Rules**: Backward-chaining rules to infer grandfather relationships

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
