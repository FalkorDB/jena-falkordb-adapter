# FalkorDB Jena Adapter Optimizations

This document describes the performance optimizations implemented in the FalkorDB Jena Adapter to improve both read and write operations.

## Overview

The adapter implements two major optimization strategies:

1. **Batch Writes via Transactions** - Buffers multiple triple operations and flushes them in bulk using Cypher's `UNWIND`
2. **Query Pushdown** - Translates SPARQL Basic Graph Patterns (BGPs) to native Cypher queries
3. **Magic Property** - Allows direct execution of Cypher queries within SPARQL

## 1. Batch Writes via Transactions

> **Tests**: See [FalkorDBTransactionHandlerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/FalkorDBTransactionHandlerTest.java)

### The Problem

By default, Jena's `Graph.add(Triple t)` method is "chatty" - it sends one database command per triple. When loading large datasets, this results in thousands of individual network round-trips:

```java
// Without optimization: 1000 triples = 1000 database calls
for (Triple t : triples) {
    graph.add(t);  // Each call hits the database
}
```

### The Solution

The `FalkorDBTransactionHandler` buffers triple operations during a transaction and flushes them in bulk on commit using Cypher's `UNWIND` clause:

```java
// With optimization: 1000 triples = ~1 database call
model.begin(ReadWrite.WRITE);
try {
    for (Triple t : triples) {
        model.add(t);  // Buffered in memory
    }
    model.commit();  // Single bulk operation
} finally {
    model.end();
}
```

### Implementation Details

- **Buffering**: Add and delete operations are collected in Java lists during a transaction
- **Batching**: Operations are grouped by type (literals, types, relationships) and executed in batches of up to 1000
- **UNWIND**: Uses Cypher's `UNWIND` for efficient bulk operations:

```cypher
UNWIND range(0, size($subjects)-1) AS i
WITH $subjects[i] AS subj, $objects[i] AS obj
MERGE (s:Resource {uri: subj})
MERGE (o:Resource {uri: obj})
MERGE (s)-[r:`predicate`]->(o)
```

### Usage

```java
Model model = FalkorDBModelFactory.createModel("myGraph");

// Start a transaction
model.begin(ReadWrite.WRITE);
try {
    // All these adds are buffered
    model.add(statement1);
    model.add(statement2);
    model.add(statement3);
    // ... add many more statements
    
    model.commit();  // Bulk flush to FalkorDB
} catch (Exception e) {
    model.abort();  // Discard buffered operations
} finally {
    model.end();
}
```

### Performance Gains

| Operation | Without Batching | With Batching | Improvement |
|-----------|-----------------|---------------|-------------|
| 100 triples | 100 round trips | 1 round trip | 100x fewer calls |
| 1000 triples | 1000 round trips | 1 round trip | 1000x fewer calls |
| 10000 triples | 10000 round trips | 10 round trips | 1000x fewer calls |

## 2. Query Pushdown (SPARQL to Cypher)

> **Tests**: See [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) and [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)

### The Problem

By default, Jena evaluates SPARQL queries using a "triple-by-triple" approach:

```sparql
SELECT ?fof WHERE {
    <http://example.org/alice> <http://example.org/knows> ?friend .
    ?friend <http://example.org/knows> ?fof .
}
```

Gets executed as:
1. `find(Alice, knows, ?friend)` → Returns N triples
2. For each `?friend`, `find(?friend, knows, ?fof)` → N more queries

This results in N+1 database round trips.

### The Solution

The query pushdown mechanism translates SPARQL BGPs to a single Cypher `MATCH` query:

```cypher
MATCH (:Resource {uri: "http://example.org/alice"})
      -[:`http://example.org/knows`]->(:Resource)
      -[:`http://example.org/knows`]->(fof:Resource)
RETURN fof.uri AS fof
```

This executes as a single database operation.

### Implementation Components

1. **SparqlToCypherCompiler**: Translates SPARQL BGPs to Cypher MATCH clauses
2. **FalkorDBOpExecutor**: Custom OpExecutor that intercepts BGP operations
3. **FalkorDBQueryEngineFactory**: Factory to register the custom query engine

### Registration

To enable query pushdown, register the factory at application startup:

```java
// Enable query pushdown globally
FalkorDBQueryEngineFactory.register();

// Now all SPARQL queries against FalkorDB models use pushdown
Model model = FalkorDBModelFactory.createModel("myGraph");
Query query = QueryFactory.create("SELECT ...");
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    // ...
}

// Optionally disable
FalkorDBQueryEngineFactory.unregister();
```

### Supported Patterns

Currently, the pushdown optimizer supports:

| Pattern Type | Supported | Example |
|-------------|-----------|---------|
| Concrete subject + predicate + object | ✅ | `<uri1> <pred> <uri2>` |
| rdf:type with concrete type | ✅ | `?s rdf:type <Type>` |
| Concrete literal values | ✅ | `?s <pred> "value"` |
| Variable predicates (single triple) | ✅ | `<uri> ?p ?o` (uses UNION for properties + relationships) |
| Closed-chain variable objects | ✅ | `?a <pred> ?b . ?b <pred> ?a` (mutual references) |
| Variable objects (unknown type) | ❌ (fallback) | `?s <pred> ?o` where ?o might be literal |
| OPTIONAL, FILTER, UNION | ❌ (fallback) | Complex patterns |

#### Variable Predicate Support

Variable predicates are supported for single-triple patterns. The compiler generates a UNION query
that fetches both relationships and node properties:

```sparql
# SPARQL: Get all properties of a resource
SELECT ?p ?o WHERE {
    <http://example.org/person/jane> ?p ?o .
}
```

```cypher
# Compiled Cypher (using UNION):
MATCH (_n...:Resource {uri: $p0})-[_r]->(_o:Resource)
RETURN _n....uri AS _s, type(_r) AS p, _o.uri AS o
UNION ALL
MATCH (_n...:Resource {uri: $p0})
UNWIND keys(_n...) AS _propKey
WITH _n..., _propKey WHERE _propKey <> 'uri'
RETURN _n....uri AS _s, _propKey AS p, _n...[_propKey] AS o
```

#### Closed-Chain Variable Objects

When a variable object is also used as a subject in another triple pattern, the compiler
recognizes it as a resource (not a potential literal) and generates a pushdown query:

```sparql
# SPARQL: Find mutual friends
SELECT ?a ?b WHERE {
    ?a <http://example.org/knows> ?b .
    ?b <http://example.org/knows> ?a .
}
```

```cypher
# Compiled Cypher:
MATCH (a:Resource)-[:`http://example.org/knows`]->(b:Resource)
MATCH (b)-[:`http://example.org/knows`]->(a)
RETURN a.uri AS a, b.uri AS b
```

When a pattern cannot be pushed down, the optimizer automatically falls back to standard Jena evaluation.

### Performance Gains

| Query Type | Standard SPARQL | With Pushdown | Improvement |
|------------|----------------|---------------|-------------|
| Friends of Friends | ~N+1 round trips | 1 round trip | Nx fewer calls |
| 3-hop traversal | ~N² round trips | 1 round trip | N²x fewer calls |
| Type queries | Multiple queries | 1 query | Significant |

## 3. Magic Property (Direct Cypher Execution)

> **Tests**: See [CypherQueryFuncTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/pfunction/CypherQueryFuncTest.java) and [MagicPropertyDocExamplesTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/pfunction/MagicPropertyDocExamplesTest.java)

For maximum control, you can execute native Cypher queries directly within SPARQL using the magic property `falkor:cypher`:

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?friend WHERE {
    (?friend) falkor:cypher '''
        MATCH (:Resource {uri: "http://example.org/alice"})
              -[:`http://example.org/knows`]->(:Resource)
              -[:`http://example.org/knows`]->(f:Resource)
        RETURN f.uri AS friend
    '''
}
```

See [MAGIC_PROPERTY.md](MAGIC_PROPERTY.md) for detailed documentation.

## OpenTelemetry Tracing

All optimization components are instrumented with OpenTelemetry:

- **Transaction spans**: Track buffering and flush operations
- **Query pushdown spans**: Show compiled Cypher queries and fallback decisions
- **Result counts**: Monitor query efficiency

Enable tracing to visualize performance in Jaeger:

```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

Then view traces at `http://localhost:16686`.

## Best Practices

### For Writes

1. **Use transactions for bulk loads**: Always wrap bulk operations in transactions
2. **Batch size**: The adapter uses batches of 1000 operations internally
3. **Error handling**: Use try-finally to ensure `model.end()` is called

### For Reads

1. **Use concrete values when possible**: Queries with concrete URIs can be pushed down
2. **Use magic property for complex traversals**: When you need path patterns or aggregations
3. **Monitor with tracing**: Use OpenTelemetry to identify slow queries

### Example: Optimal Bulk Load

```java
Model model = FalkorDBModelFactory.createModel("myGraph");

// Load RDF file efficiently
model.begin(ReadWrite.WRITE);
try {
    RDFDataMgr.read(model, "large-dataset.ttl");
    model.commit();
} catch (Exception e) {
    model.abort();
    throw e;
} finally {
    model.end();
}
```

### Example: Optimal Query

```java
// Register pushdown engine
FalkorDBQueryEngineFactory.register();

// For complex graph traversals, use magic property
String sparql = """
    PREFIX falkor: <http://falkordb.com/jena#>
    SELECT ?person ?connections WHERE {
        (?person ?connections) falkor:cypher '''
            MATCH (p:Resource)-[r:`http://example.org/knows`]->(:Resource)
            RETURN p.uri AS person, count(r) AS connections
            ORDER BY connections DESC
            LIMIT 10
        '''
    }
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    // Process results
}
```

## Future Improvements

The query pushdown can be extended to support:

- Variable objects (querying both properties and relationships)
- OPTIONAL patterns
- FILTER expressions
- UNION patterns
- Aggregations

These would require more sophisticated SPARQL-to-Cypher translation.
