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

## Using curl with Fuseki

You can use curl to load data and execute queries via the Fuseki SPARQL endpoint.

### Prerequisites

First, start FalkorDB and Fuseki:

```bash
# Start FalkorDB
docker run -p 6379:6379 -it --rm falkordb/falkordb:latest

# In another terminal, start Fuseki (from project root)
mvn clean install -DskipTests
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar
```

The Fuseki server will start on `http://localhost:3330` with the default endpoint at `/falkor`.

### Loading Data with curl

**Load the sample data file (`data-example.ttl`):**

```bash
curl -X POST \
  -H "Content-Type: text/turtle" \
  --data-binary @samples/variable-objects/data-example.ttl \
  http://localhost:3330/falkor/data
```

### Executing Queries with curl

**Example 1: Query all values for a specific predicate**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?value WHERE {
    <http://example.org/person/alice> foaf:knows ?value .
}" \
  http://localhost:3330/falkor/query
```

**Example 2: Variable subject and object**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name WHERE {
    ?person foaf:name ?name .
}
ORDER BY ?name" \
  http://localhost:3330/falkor/query
```

**Example 3: Query relationships only**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?friend WHERE {
    ?person foaf:knows ?friend .
}" \
  http://localhost:3330/falkor/query
```

**Example 4: Mixed data types**

```bash
curl -G \
  --data-urlencode "query=SELECT ?subject ?value WHERE {
    ?subject <http://example.org/hasValue> ?value .
}" \
  http://localhost:3330/falkor/query
```

**Example 5: Combining with FILTER**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?value WHERE {
    ?person foaf:knows ?value .
    FILTER(isLiteral(?value))
}" \
  http://localhost:3330/falkor/query
```

**Example 6: Multiple predicates**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

SELECT ?person ?name ?friend WHERE {
    ?person foaf:name ?name .
    ?person foaf:knows ?friend .
}" \
  http://localhost:3330/falkor/query
```

**Example 7: Concrete subject with variable object**

```bash
curl -G \
  --data-urlencode "query=PREFIX ex: <http://example.org/>

SELECT ?value WHERE {
    <http://example.org/person/alice> ex:property ?value .
}" \
  http://localhost:3330/falkor/query
```

**Example 8: Count results (aggregation over variable objects)**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT (COUNT(?friend) AS ?friendCount) WHERE {
    <http://example.org/person/alice> foaf:knows ?friend .
}" \
  http://localhost:3330/falkor/query
```

## Documentation

For complete documentation, see:
- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#variable-object-support) - Detailed explanation
- [README.md](../../README.md) - Project overview
- [Fuseki GETTING_STARTED.md](../../jena-fuseki-falkordb/GETTING_STARTED.md) - Fuseki setup and usage

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
