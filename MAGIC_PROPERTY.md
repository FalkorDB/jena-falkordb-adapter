# FalkorDB Magic Property (Cypher Query Pushdown)

The FalkorDB Jena adapter provides a "magic property" that allows you to execute native Cypher queries directly from within SPARQL queries. This feature provides a query pushdown mechanism that can significantly improve performance by bypassing the slower triple-by-triple matching engine.

## Why Use Magic Property?

By default, Jena translates SPARQL queries into many individual graph pattern matching operations. For example, a "Friends of Friends" query like:

```sparql
SELECT ?friend WHERE {
  <http://example.org/alice> <http://example.org/knows> ?x .
  ?x <http://example.org/knows> ?friend .
}
```

Gets executed as:
1. `find(Alice, knows, ?x)` → Returns N triples
2. For each result `?x`, `find(?x, knows, ?friend)` → N more queries

This can result in N+1 round trips to the database.

With the magic property, you can execute a single optimized Cypher query:

```cypher
MATCH (:Resource {uri: "http://example.org/alice"})
      -[:`http://example.org/knows`]->(:Resource)
      -[:`http://example.org/knows`]->(f:Resource)
RETURN f.uri AS friend
```

This reduces the operation to a single database call, dramatically improving performance for complex graph traversals.

## Syntax

The magic property `falkor:cypher` is used as follows:

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?var1 ?var2 ... WHERE {
  (?var1 ?var2 ...) falkor:cypher '''
    <CYPHER QUERY>
  '''
}
```

### Components:

1. **PREFIX**: Define the FalkorDB namespace
2. **Subject (variable list)**: A list of SPARQL variables that will be bound to the Cypher query results
3. **Predicate**: `falkor:cypher` - the magic property
4. **Object (query string)**: The Cypher query to execute, typically as a multi-line string literal

## Variable Binding

The magic property maps Cypher query results to SPARQL variables using two strategies:

### 1. Name Matching (Preferred)

If the Cypher `RETURN` clause uses `AS` aliases that match SPARQL variable names, they are automatically mapped:

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?personName ?personAge WHERE {
  (?personName ?personAge) falkor:cypher '''
    MATCH (p:Resource)
    WHERE p.`http://example.org/name` IS NOT NULL
    RETURN p.`http://example.org/name` AS personName,
           p.`http://example.org/age` AS personAge
  '''
}
```

### 2. Position Matching (Fallback)

If names don't match, variables are bound by position in the order they appear:

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?name ?age WHERE {
  (?name ?age) falkor:cypher '''
    MATCH (p:Resource)
    RETURN p.`http://example.org/name`, p.`http://example.org/age`
  '''
}
```

In this case:
- `?name` binds to the first column
- `?age` binds to the second column

## Examples

### Example 1: Simple Property Lookup

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?name WHERE {
  (?name) falkor:cypher '''
    MATCH (p:Resource)
    WHERE p.`http://example.org/name` IS NOT NULL
    RETURN p.`http://example.org/name` AS name
  '''
}
```

### Example 2: Friends of Friends

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

### Example 3: Aggregation

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?type ?count WHERE {
  (?type ?count) falkor:cypher '''
    MATCH (n:Resource)
    WITH labels(n) AS nodeLabels
    UNWIND nodeLabels AS label
    WHERE label <> "Resource"
    RETURN label AS type, count(*) AS count
    ORDER BY count DESC
  '''
}
```

### Example 4: Path Patterns

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?start ?end ?pathLength WHERE {
  (?start ?end ?pathLength) falkor:cypher '''
    MATCH path = (s:Resource)-[*1..3]->(e:Resource)
    WHERE s.uri = "http://example.org/node1"
    RETURN s.uri AS start, e.uri AS end, length(path) AS pathLength
  '''
}
```

## Data Type Mapping

| FalkorDB Type | SPARQL Type |
|---------------|-------------|
| String | Literal (or URI if starts with http:// or https://) |
| Integer/Long | xsd:integer |
| Double | xsd:double |
| Boolean | xsd:boolean |
| Node with `uri` property | URI Resource |
| Other | String literal |

## Important Notes

1. **Graph Label**: In FalkorDB, all RDF resources are stored with the `:Resource` label. Use this label in your Cypher patterns.

2. **Property Names**: RDF predicates become property names or relationship types in FalkorDB. Use backticks for URIs: `` `http://example.org/name` ``

3. **Performance**: The magic property is most beneficial for complex queries with multiple joins or path patterns. For simple triple patterns, standard SPARQL may be sufficient.

4. **Tracing**: When OpenTelemetry tracing is enabled, magic property executions create spans with the Cypher query and result count.

## Tracing in Jaeger

When tracing is enabled, you can see magic property executions in Jaeger:

1. Open Jaeger UI at `http://localhost:16686`
2. Select "jena-falkordb" service
3. Search for operations named `CypherQueryFunc.execute`
4. Inspect the trace to see:
   - `falkordb.cypher.query` - The executed Cypher query
   - `falkordb.cypher.result_count` - Number of results
   - `falkordb.cypher.var_count` - Number of bound variables

## Troubleshooting

### Query Returns Empty Results

1. Verify your Cypher query works directly in FalkorDB:
   ```bash
   redis-cli -h localhost -p 6379
   GRAPH.QUERY your_graph "YOUR CYPHER QUERY"
   ```

2. Check that property names use backticks for URIs

3. Ensure nodes have the `:Resource` label

### Error: "falkor:cypher can only be used with FalkorDBGraph"

This error occurs when trying to use the magic property with a non-FalkorDB graph. Ensure your dataset is backed by FalkorDB.

### Variables Not Binding

1. Check that RETURN clause column names match SPARQL variable names
2. Verify the number of variables matches the number of columns
3. Look for null values in the Cypher results
