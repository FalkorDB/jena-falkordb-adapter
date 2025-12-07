# Variable Objects Optimization - Examples

This directory contains examples demonstrating the **Variable Objects optimization** in FalkorDB Jena Adapter.

## What is Variable Objects Optimization?

When a SPARQL query has a variable object (e.g., `?s <pred> ?o`), the object `?o` could be either:
- A **literal** value stored as a node property
- A **URI** resource connected via a relationship

Instead of querying these separately, the Variable Objects optimization uses a single **UNION query** to fetch both types efficiently.

## Files in this Directory

### 1. `VariableObjectExample.java`
A complete Java example demonstrating:
- Querying both properties and relationships with variable objects
- Variable subject and variable object patterns
- Relationship-only queries with variable objects

**Run the example:**
```bash
cd jena-falkordb-adapter
mvn compile exec:java -Dexec.mainClass="com.falkordb.samples.VariableObjectExample"
```

### 2. `queries.sparql`
SPARQL query examples showing:
- Basic variable object queries
- Mixed data type retrieval
- Aggregations over variable objects
- Patterns that trigger the optimization

### 3. `data-example.ttl`
Sample RDF data in Turtle format for testing the queries.

## How the Optimization Works

### Before (Standard Evaluation)
```
Query 1: Get relationships  -> SELECT WHERE { ?s <pred> ?o . ?o ?p2 ?o2 }
Query 2: Get properties     -> SELECT WHERE { ?s <pred> ?o . FILTER(isLiteral(?o)) }
= 2+ database round trips
```

### After (Variable Objects Optimization)
```
Single UNION Query:
  MATCH (s)-[:`predicate`]->(o:Resource) RETURN s.uri, o.uri
  UNION ALL
  MATCH (s) WHERE s.`predicate` IS NOT NULL RETURN s.uri, s.`predicate`
= 1 database round trip
```

## Key Benefits

✅ **Single Query Execution**: One database round-trip instead of multiple  
✅ **Handles Mixed Data**: Automatically retrieves both relationships and properties  
✅ **Efficient**: Uses native Cypher operations optimized by FalkorDB  
✅ **Transparent**: Works automatically for single-triple BGPs with variable objects  

## Performance Gains

- **2x fewer round trips** for queries that fetch both types
- **Nx faster** for large result sets
- **Native Cypher execution** eliminates triple-by-triple iteration

## Testing

Unit tests: [`SparqlToCypherCompilerTest.java`](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java)
- `testSingleVariableObjectCompilesWithUnion()`
- `testConcreteSubjectWithVariableObjectCompilesWithUnion()`
- `testVariableObjectOptimizationStructure()`

Integration tests: [`FalkorDBQueryPushdownTest.java`](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)
- `testVariableObjectOptimizationBothTypes()`
- `testVariableObjectOptimizationMixedResults()`
- `testVariableObjectOptimizationVariableSubject()`

## Documentation

For complete documentation, see:
- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#variable-object-support) - Detailed explanation
- [README.md](../../README.md) - Project overview

## Limitations

- Only supports **single-triple patterns** (e.g., `?s <pred> ?o`)
- Multi-triple patterns with variable objects require the object to be used as a subject elsewhere (closed-chain pattern)
- Complex patterns with FILTER, OPTIONAL, etc., fall back to standard evaluation

## Related Optimizations

- **Variable Predicates**: Query all properties, relationships, and types with `?s ?p ?o`
- **Closed-Chain Variables**: Multi-triple patterns where objects are used as subjects
- **Transaction Batching**: Bulk write operations using `UNWIND`
- **Magic Property**: Direct Cypher execution with `falkor:cypher`

See [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md) for details on all optimizations.
