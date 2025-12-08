# Aggregations Optimization

This example demonstrates the **aggregations optimization** in the FalkorDB Jena Adapter. SPARQL aggregation queries (GROUP BY with COUNT, SUM, AVG, MIN, MAX, etc.) are automatically translated to native Cypher aggregation queries for efficient database-side computation.

## Table of Contents
- [Overview](#overview)
- [How It Works](#how-it-works)
- [Supported Aggregations](#supported-aggregations)
- [Examples](#examples)
- [Performance Benefits](#performance-benefits)
- [Running the Example](#running-the-example)
- [Testing](#testing)

## Overview

Without aggregations pushdown, SPARQL aggregation queries would require:
1. Fetching all matching triples from the database
2. Performing grouping and aggregation on the client side
3. Processing potentially large result sets in memory

With aggregations pushdown, the adapter:
1. Translates SPARQL GROUP BY and aggregations to Cypher
2. Executes aggregation directly in FalkorDB
3. Returns only the aggregated results

**Result**: Significantly reduced data transfer and faster query execution through database-side aggregation.

## How It Works

The optimization consists of three main components:

### 1. OpGroup Interceptor (`FalkorDBOpExecutor`)

The custom OpExecutor intercepts `OpGroup` operations (SPARQL GROUP BY with aggregations) and attempts to push them down to FalkorDB:

```java
@Override
protected QueryIterator execute(final OpGroup opGroup, final QueryIterator input) {
    // Extract the underlying BGP
    Op subOp = opGroup.getSubOp();
    if (!(subOp instanceof OpBGP)) {
        return super.execute(opGroup, input); // Fall back
    }

    // Compile BGP to Cypher
    SparqlToCypherCompiler.CompilationResult bgpCompilation = 
        SparqlToCypherCompiler.translate(bgp);

    // Translate aggregations
    AggregationToCypherTranslator.AggregationResult aggResult =
        AggregationToCypherTranslator.translate(
            aggregations, groupVars, bgpCompilation.variableMapping());

    // Build complete query with aggregation
    String cypherQuery = buildAggregationQuery(
        bgpCompilation.cypherQuery(), aggResult.returnClause());

    // Execute on FalkorDB
    ResultSet results = falkorGraph.query(cypherQuery, parameters);
    ...
}
```

### 2. Aggregation Translator (`AggregationToCypherTranslator`)

Translates SPARQL aggregation expressions to Cypher:

| SPARQL Aggregation | Cypher Aggregation |
|--------------------|-------------------|
| `COUNT(?var)`      | `count(var)`      |
| `SUM(?var)`        | `sum(var)`        |
| `AVG(?var)`        | `avg(var)`        |
| `MIN(?var)`        | `min(var)`        |
| `MAX(?var)`        | `max(var)`        |
| `COUNT(DISTINCT ?var)` | `count(DISTINCT var)` |

### 3. Query Builder

Combines the BGP's MATCH clause with aggregation RETURN clause:

```cypher
# Base BGP query:
MATCH (person:Resource)-[:`http://xmlns.com/foaf/0.1/age`]->(age:Resource)
RETURN person.uri AS person, age.uri AS age

# Becomes aggregation query:
MATCH (person:Resource)
WHERE person.`http://xmlns.com/foaf/0.1/age` IS NOT NULL
RETURN count(person) AS count, avg(person.`http://xmlns.com/foaf/0.1/age`) AS avgAge
```

## Supported Aggregations

### Core Aggregations

- ✅ **COUNT(?var)** - Count non-null values
- ✅ **COUNT(DISTINCT ?var)** - Count unique values
- ✅ **COUNT(*)** - Count all rows
- ✅ **SUM(?var)** - Sum of numeric values
- ✅ **AVG(?var)** - Average of numeric values
- ✅ **MIN(?var)** - Minimum value
- ✅ **MAX(?var)** - Maximum value

### GROUP BY Support

- ✅ **Single variable grouping** - `GROUP BY ?type`
- ✅ **Multiple variable grouping** - `GROUP BY ?type ?category`
- ✅ **No GROUP BY** - Global aggregations (COUNT(*), SUM, etc.)

### Limitations

Currently, aggregations pushdown has these limitations:

1. **BGP-only subpatterns**: The GROUP BY must be over a Basic Graph Pattern (BGP). Complex patterns with FILTER, OPTIONAL, or UNION in the subpattern are not yet supported.
2. **No HAVING clause**: HAVING filters on aggregated values are not yet optimized.
3. **Simple aggregation expressions**: Aggregations must be over simple variables, not complex expressions.

When the optimizer cannot push down an aggregation, it falls back to standard Jena evaluation.

## Examples

### Example 1: COUNT with GROUP BY

Count entities by type:

**SPARQL:**
```sparql
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?type (COUNT(?entity) AS ?count)
WHERE {
    ?entity rdf:type ?type .
}
GROUP BY ?type
ORDER BY DESC(?count)
```

**Generated Cypher:**
```cypher
MATCH (entity:Resource)
UNWIND labels(entity) AS type
WHERE type <> 'Resource'
RETURN type, count(entity) AS count
ORDER BY count DESC
```

**Result**: Single database query with aggregation performed natively.

### Example 2: AVG with GROUP BY

Calculate average age by person type:

**SPARQL:**
```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

SELECT ?type (AVG(?age) AS ?avgAge)
WHERE {
    ?person rdf:type ?type .
    ?person ex:age ?age .
}
GROUP BY ?type
```

**Generated Cypher:**
```cypher
MATCH (person:Resource)
WHERE person.`http://example.org/age` IS NOT NULL
UNWIND labels(person) AS type
WHERE type <> 'Resource'
RETURN type, avg(person.`http://example.org/age`) AS avgAge
```

### Example 3: Multiple Aggregations

Get count, sum, average, min, and max in a single query:

**SPARQL:**
```sparql
PREFIX ex: <http://example.org/>

SELECT 
    (COUNT(?item) AS ?count)
    (SUM(?price) AS ?total)
    (AVG(?price) AS ?avgPrice)
    (MIN(?price) AS ?minPrice)
    (MAX(?price) AS ?maxPrice)
WHERE {
    ?item ex:price ?price .
}
```

**Generated Cypher:**
```cypher
MATCH (item:Resource)
WHERE item.`http://example.org/price` IS NOT NULL
RETURN 
    count(item) AS count,
    sum(item.`http://example.org/price`) AS total,
    avg(item.`http://example.org/price`) AS avgPrice,
    min(item.`http://example.org/price`) AS minPrice,
    max(item.`http://example.org/price`) AS maxPrice
```

### Example 4: COUNT DISTINCT

Count unique types:

**SPARQL:**
```sparql
SELECT (COUNT(DISTINCT ?type) AS ?uniqueTypes)
WHERE {
    ?entity rdf:type ?type .
}
```

**Generated Cypher:**
```cypher
MATCH (entity:Resource)
UNWIND labels(entity) AS type
WHERE type <> 'Resource'
RETURN count(DISTINCT type) AS uniqueTypes
```

### Example 5: SUM by Category

Sum prices by product category:

**SPARQL:**
```sparql
PREFIX ex: <http://example.org/>

SELECT ?category (SUM(?price) AS ?totalPrice)
WHERE {
    ?product ex:category ?category .
    ?product ex:price ?price .
}
GROUP BY ?category
ORDER BY DESC(?totalPrice)
```

**Generated Cypher:**
```cypher
MATCH (product:Resource)
WHERE product.`http://example.org/category` IS NOT NULL
  AND product.`http://example.org/price` IS NOT NULL
RETURN 
    product.`http://example.org/category` AS category,
    sum(product.`http://example.org/price`) AS totalPrice
ORDER BY totalPrice DESC
```

## Performance Benefits

### Scenario 1: Simple Counting

**Without pushdown:**
```
1. Fetch all triples: ?entity rdf:type ?type  (N database calls)
2. Group by type in memory  (Client-side processing)
3. Count each group  (Client-side processing)
```

**With pushdown:**
```
1. Execute aggregation query in database  (1 database call)
2. Return aggregated results  (Small result set)
```

**Improvement**: N× fewer database calls + reduced data transfer

### Scenario 2: Complex Aggregations

For a dataset with 10,000 entities and 50 types:

| Operation | Without Pushdown | With Pushdown | Improvement |
|-----------|-----------------|---------------|-------------|
| **Data Transfer** | 10,000 triples | 50 rows | 200x less |
| **Database Calls** | ~10,000 | 1 | 10,000x fewer |
| **Memory Usage** | Store all triples | Store aggregates | 200x less |
| **Processing** | Client-side | Database-side | Native optimization |

### Scenario 3: Multiple Aggregations

**Query:** Count, sum, avg, min, max over 100,000 items

| Metric | Without Pushdown | With Pushdown |
|--------|-----------------|---------------|
| Data Retrieved | 100,000 triples | 1 row |
| Network Time | ~10 seconds | ~10ms |
| Processing Time | ~5 seconds | ~100ms |
| **Total Time** | **~15 seconds** | **~110ms** |

**Improvement**: ~136× faster

## Running the Example

### Prerequisites

1. **Start FalkorDB:**
   ```bash
   docker run -p 6379:6379 -d falkordb/falkordb:latest
   ```

2. **Build the project:**
   ```bash
   cd jena-falkordb-adapter
   mvn clean install
   ```

### Run the Example

```bash
# From project root
cd samples/aggregations
javac -cp "../../jena-falkordb-adapter/target/*:$(find ~/.m2/repository -name '*.jar' | tr '\n' ':')" AggregationsExample.java
java -cp ".:../../jena-falkordb-adapter/target/*:$(find ~/.m2/repository -name '*.jar' | tr '\n' ':')" AggregationsExample
```

Or use Maven:
```bash
mvn exec:java -Dexec.mainClass="com.falkordb.samples.AggregationsExample"
```

### Load Sample Data

The example includes sample data demonstrating:
- People with ages grouped by type (Student, Teacher)
- Products with prices grouped by category
- Organizations with employee counts

## Testing

Aggregations optimization is tested in:

### Unit Tests

Test aggregation translation:
- [AggregationToCypherTranslatorTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/AggregationToCypherTranslatorTest.java)

Run unit tests:
```bash
mvn test -pl jena-falkordb-adapter -Dtest=AggregationToCypherTranslatorTest
```

### Integration Tests

Test end-to-end execution with FalkorDB:
- [FalkorDBAggregationPushdownTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBAggregationPushdownTest.java)

Run integration tests:
```bash
mvn test -pl jena-falkordb-adapter -Dtest=FalkorDBAggregationPushdownTest
```

## OpenTelemetry Tracing

All aggregation operations are fully instrumented with OpenTelemetry:

### Traced Operations

- **`FalkorDBOpExecutor.executeGroup`**: OpGroup execution
  - Attributes: `falkordb.group_vars`, `falkordb.aggregations`, `falkordb.cypher.query`
  - Shows: Compilation, execution, and fallback decisions

### Enabling Tracing

```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

View traces at `http://localhost:16686`

See [TRACING.md](../../TRACING.md) for complete documentation.

## Common Patterns

### Pattern 1: Statistical Summary

Get comprehensive statistics in one query:

```sparql
SELECT 
    (COUNT(?x) AS ?count)
    (AVG(?x) AS ?mean)
    (MIN(?x) AS ?min)
    (MAX(?x) AS ?max)
WHERE { ?item ex:value ?x }
```

### Pattern 2: Top-N Groups

Find top categories by count:

```sparql
SELECT ?category (COUNT(?item) AS ?count)
WHERE { 
    ?item ex:category ?category 
}
GROUP BY ?category
ORDER BY DESC(?count)
LIMIT 10
```

### Pattern 3: Conditional Aggregation

Use FILTER in the WHERE clause (not yet in aggregations):

```sparql
SELECT ?type (AVG(?age) AS ?avgAge)
WHERE {
    ?person rdf:type ?type .
    ?person ex:age ?age .
    FILTER(?age >= 18)
}
GROUP BY ?type
```

## Best Practices

1. **Use specific variables**: `COUNT(?person)` instead of `COUNT(*)` when possible for better clarity
2. **Order results**: Use `ORDER BY` to get sorted aggregated results
3. **Limit results**: Use `LIMIT` for large group-by results
4. **Monitor with tracing**: Enable OpenTelemetry to verify pushdown is working
5. **Check fallback logs**: Review logs if queries seem slow - may indicate fallback to standard evaluation

## Troubleshooting

### Issue: Aggregation not pushed down

**Symptoms**: Query is slow, logs show "GROUP pushdown failed, falling back"

**Causes**:
1. Complex subpattern (FILTER, OPTIONAL, UNION in GROUP BY clause)
2. Unsupported aggregation function
3. Non-BGP subpattern

**Solutions**:
1. Simplify the subpattern to a basic BGP
2. Check supported aggregations list
3. Review logs for specific error message

### Issue: Incorrect results

**Symptoms**: Aggregation returns unexpected values

**Causes**:
1. Data type mismatch (e.g., summing strings)
2. NULL values in aggregation
3. Incorrect GROUP BY variables

**Solutions**:
1. Verify data types in the database
2. Use FILTER to exclude NULLs if needed
3. Double-check GROUP BY variables

## Using curl with Fuseki

You can use curl to load data and execute aggregation queries via the Fuseki SPARQL endpoint.

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
  --data-binary @samples/aggregations/data-example.ttl \
  http://localhost:3330/falkor/data
```

### Executing Aggregation Queries with curl

**Example 1: COUNT with GROUP BY**

Count entities by type:

```bash
curl -G \
  --data-urlencode "query=PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?type (COUNT(?entity) AS ?count)
WHERE {
    ?entity rdf:type ?type .
}
GROUP BY ?type
ORDER BY DESC(?count)" \
  http://localhost:3330/falkor/query
```

**Example 2: AVG with GROUP BY**

Calculate average age by person type:

```bash
curl -G \
  --data-urlencode "query=PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

SELECT ?type (AVG(?age) AS ?avgAge)
WHERE {
    ?person rdf:type ?type .
    ?person ex:age ?age .
}
GROUP BY ?type" \
  http://localhost:3330/falkor/query
```

**Example 3: Multiple Aggregations**

Get comprehensive statistics in one query:

```bash
curl -G \
  --data-urlencode "query=PREFIX ex: <http://example.org/>

SELECT 
    (COUNT(?item) AS ?count)
    (SUM(?price) AS ?total)
    (AVG(?price) AS ?avgPrice)
    (MIN(?price) AS ?minPrice)
    (MAX(?price) AS ?maxPrice)
WHERE {
    ?item ex:price ?price .
}" \
  http://localhost:3330/falkor/query
```

**Example 4: COUNT DISTINCT**

Count unique types in the database:

```bash
curl -G \
  --data-urlencode "query=PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT (COUNT(DISTINCT ?type) AS ?uniqueTypes)
WHERE {
    ?entity rdf:type ?type .
}" \
  http://localhost:3330/falkor/query
```

**Example 5: SUM by Category**

Sum prices by product category:

```bash
curl -G \
  --data-urlencode "query=PREFIX ex: <http://example.org/>

SELECT ?category (SUM(?price) AS ?totalPrice)
WHERE {
    ?product ex:category ?category .
    ?product ex:price ?price .
}
GROUP BY ?category
ORDER BY DESC(?totalPrice)" \
  http://localhost:3330/falkor/query
```

**Example 6: MIN and MAX**

Find cheapest and most expensive items:

```bash
curl -G \
  --data-urlencode "query=PREFIX ex: <http://example.org/>

SELECT 
    (MIN(?price) AS ?cheapest)
    (MAX(?price) AS ?mostExpensive)
WHERE {
    ?item ex:price ?price .
}" \
  http://localhost:3330/falkor/query
```

**Example 7: COUNT(*)**

Count all triples:

```bash
curl -G \
  --data-urlencode "query=SELECT (COUNT(*) AS ?totalTriples)
WHERE {
    ?s ?p ?o .
}" \
  http://localhost:3330/falkor/query
```

**Example 8: Aggregation with FILTER**

Average age of adults only:

```bash
curl -G \
  --data-urlencode "query=PREFIX ex: <http://example.org/>

SELECT (AVG(?age) AS ?avgAdultAge)
WHERE {
    ?person ex:age ?age .
    FILTER(?age >= 18)
}" \
  http://localhost:3330/falkor/query
```

**Example 9: Multiple GROUP BY variables**

Count by type and category:

```bash
curl -G \
  --data-urlencode "query=PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX ex: <http://example.org/>

SELECT ?type ?category (COUNT(?item) AS ?count)
WHERE {
    ?item rdf:type ?type .
    ?item ex:category ?category .
}
GROUP BY ?type ?category
ORDER BY ?type ?category" \
  http://localhost:3330/falkor/query
```

## See Also

- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#aggregations) - Complete optimization guide
- [Query Pushdown Examples](../query-pushdown/) - Basic query translation
- [FILTER Examples](../filter-expressions/) - Filter expression optimization
- [Magic Property](../magic-property/) - Direct Cypher for complex aggregations
- [Fuseki GETTING_STARTED.md](../../jena-fuseki-falkordb/GETTING_STARTED.md) - Fuseki setup and usage

## Contributing

To add more aggregation examples:
1. Add queries to `queries.sparql`
2. Update `AggregationsExample.java` with use cases
3. Add corresponding data to `data-example.ttl`
4. Update this README with explanations

## License

Part of the FalkorDB Jena Adapter project (MIT License).
