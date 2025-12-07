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

Query pushdown is **automatically enabled** when the adapter is loaded via Jena's SPI subsystem. The `FalkorDBQueryEngineFactory` is registered during initialization, so all SPARQL queries against FalkorDB models use pushdown by default:

```java
// Query pushdown is automatically enabled - no registration needed!
Model model = FalkorDBModelFactory.createModel("myGraph");
Query query = QueryFactory.create("SELECT ...");
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    // Automatically uses query pushdown for supported patterns
}

// Optionally disable if needed
FalkorDBQueryEngineFactory.unregister();
```

**Note**: In prior versions, explicit registration via `FalkorDBQueryEngineFactory.register()` was required. This is no longer necessary as registration happens automatically during adapter initialization.

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
that fetches relationships, node properties, **and types from labels**:

```sparql
# SPARQL: Get all properties of a resource
SELECT ?p ?o WHERE {
    <http://example.org/person/jane> ?p ?o .
}
```

```cypher
# Compiled Cypher (using triple UNION):
# Part 1: Relationships (edges)
MATCH (s:Resource {uri: $p0})-[_r]->(o:Resource)
RETURN s.uri AS s, type(_r) AS p, o.uri AS o
UNION ALL
# Part 2: Properties (node attributes)
MATCH (s:Resource {uri: $p0})
UNWIND keys(s) AS _propKey
WITH s, _propKey WHERE _propKey <> 'uri'
RETURN s.uri AS s, _propKey AS p, s[_propKey] AS o
UNION ALL
# Part 3: Types (node labels as rdf:type)
MATCH (s:Resource {uri: $p0})
UNWIND labels(s) AS _label
WITH s, _label WHERE _label <> 'Resource'
RETURN s.uri AS s, 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type' AS p, _label AS o
```

This three-part UNION ensures that all triple patterns are retrieved, including:
- Relationships between resources (Part 1)
- Literal properties on nodes (Part 2)
- `rdf:type` triples derived from node labels (Part 3)

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
// Query pushdown is automatically enabled - no registration needed!

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

The query pushdown can be extended to support additional SPARQL patterns. Below are implementation suggestions for each:

### Variable Objects (Querying Both Properties and Relationships)

**Challenge**: When a variable object `?o` in `?s <pred> ?o` could be either a URI (relationship target) or a literal (property value), we need to query both.

**Implementation Strategy**:
1. In `SparqlToCypherCompiler.translate()`, detect patterns where the object is a variable that isn't used as a subject elsewhere
2. Generate a UNION query similar to variable predicates:

```cypher
# Query relationships
MATCH (s:Resource {uri: $subj})-[:`predicate`]->(o:Resource)
RETURN s.uri AS s, o.uri AS o
UNION ALL
# Query properties
MATCH (s:Resource {uri: $subj})
WHERE s.`predicate` IS NOT NULL
RETURN s.uri AS s, s.`predicate` AS o
```

3. In the result iterator, determine the RDF node type based on whether the value is a URI string or literal

**Files to modify**:
- `SparqlToCypherCompiler.java`: Add `translateVariableObjectPattern()` method
- `FalkorDBOpExecutor.java`: Handle mixed URI/literal results in `convertToBinding()`

### OPTIONAL Patterns

**Challenge**: SPARQL `OPTIONAL { ... }` returns bindings even when the optional part doesn't match.

**Implementation Strategy**:
1. In `FalkorDBOpExecutor`, intercept `OpLeftJoin` (which represents OPTIONAL)
2. Translate to Cypher `OPTIONAL MATCH`:

```sparql
# SPARQL
SELECT ?person ?email WHERE {
    ?person rdf:type <Person> .
    OPTIONAL { ?person <email> ?email }
}
```

```cypher
# Cypher
MATCH (person:Resource:`Person`)
OPTIONAL MATCH (person)-[:`email`]->(email:Resource)
RETURN person.uri AS person, email.uri AS email
```

**Files to modify**:
- `FalkorDBOpExecutor.java`: Override `execute(OpLeftJoin, QueryIterator)` method
- `SparqlToCypherCompiler.java`: Add `translateOptionalPattern()` that generates `OPTIONAL MATCH`

### FILTER Expressions

**Challenge**: SPARQL `FILTER` expressions need to be translated to Cypher `WHERE` clauses.

**Implementation Strategy**:
1. In `FalkorDBOpExecutor`, intercept `OpFilter`
2. Create a `FilterToCypherTranslator` to map SPARQL expressions to Cypher:

| SPARQL | Cypher |
|--------|--------|
| `FILTER(?x > 10)` | `WHERE x > 10` |
| `FILTER(regex(?name, "^A"))` | `WHERE name =~ "^A.*"` |
| `FILTER(bound(?x))` | `WHERE x IS NOT NULL` |
| `FILTER(isURI(?x))` | `WHERE x STARTS WITH "http"` |
| `FILTER(?x = "value")` | `WHERE x = "value"` |

```java
public class FilterToCypherTranslator extends ExprVisitorBase {
    @Override
    public void visit(E_Equals expr) {
        // Translate = to Cypher =
    }
    
    @Override
    public void visit(E_Regex expr) {
        // Translate regex() to Cypher =~
    }
}
```

**Files to modify**:
- Create new `FilterToCypherTranslator.java` in `com.falkordb.jena.query` package
- `FalkorDBOpExecutor.java`: Override `execute(OpFilter, QueryIterator)` method

### UNION Patterns

**Challenge**: SPARQL `UNION` combines results from alternative patterns.

**Implementation Strategy**:
1. In `FalkorDBOpExecutor`, intercept `OpUnion`
2. Compile each branch separately and combine with Cypher `UNION`:

```sparql
# SPARQL
SELECT ?person WHERE {
    { ?person rdf:type <Student> }
    UNION
    { ?person rdf:type <Teacher> }
}
```

```cypher
# Cypher
MATCH (person:Resource:`Student`)
RETURN person.uri AS person
UNION
MATCH (person:Resource:`Teacher`)
RETURN person.uri AS person
```

**Files to modify**:
- `FalkorDBOpExecutor.java`: Override `execute(OpUnion, QueryIterator)` method
- `SparqlToCypherCompiler.java`: Add `translateUnionPattern()` method

### Aggregations

**Challenge**: SPARQL aggregations (COUNT, SUM, AVG, etc.) with GROUP BY need Cypher equivalents.

**Implementation Strategy**:
1. In `FalkorDBOpExecutor`, intercept `OpGroup`
2. Translate SPARQL aggregations to Cypher:

| SPARQL | Cypher |
|--------|--------|
| `COUNT(?x)` | `count(x)` |
| `SUM(?x)` | `sum(x)` |
| `AVG(?x)` | `avg(x)` |
| `MIN(?x)` | `min(x)` |
| `MAX(?x)` | `max(x)` |
| `GROUP BY ?g` | `WITH ... GROUP BY g` |

```sparql
# SPARQL
SELECT ?type (COUNT(?person) AS ?count) WHERE {
    ?person rdf:type ?type
}
GROUP BY ?type
```

```cypher
# Cypher
MATCH (person:Resource)
UNWIND labels(person) AS type
WHERE type <> 'Resource'
RETURN type, count(person) AS count
```

**Files to modify**:
- `FalkorDBOpExecutor.java`: Override `execute(OpGroup, QueryIterator)` method
- Create new `AggregationToCypherTranslator.java` for expression translation

### General Implementation Notes

1. **Fallback Strategy**: Always implement a fallback to standard Jena evaluation when translation fails
2. **OpenTelemetry**: Add spans for new translation paths with attributes like `falkordb.pattern.type`
3. **Testing**: Create unit tests in `SparqlToCypherCompilerTest.java` for each new pattern type
4. **Integration Tests**: Add tests in `FalkorDBQueryPushdownTest.java` that verify end-to-end execution

```java
// Example test structure
@Test
@DisplayName("Test OPTIONAL pattern with pushdown")
public void testOptionalPatternPushdown() {
    // Add test data with and without optional values
    var person1 = model.createResource("http://example.org/person1");
    var person2 = model.createResource("http://example.org/person2");
    person1.addProperty(emailProp, "test@example.org");
    // person2 has no email
    
    String sparql = """
        SELECT ?person ?email WHERE {
            ?person a <Person> .
            OPTIONAL { ?person <email> ?email }
        }
        """;
    
    // Execute and verify both persons returned,
    // with email for person1 and null for person2
}
```
