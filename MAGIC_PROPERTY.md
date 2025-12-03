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

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
SELECT ?friend WHERE {
  <http://example.org/alice> <http://example.org/knows> ?x .
  ?x <http://example.org/knows> ?friend .
}'
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

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?var1 ?var2 ... WHERE {
  (?var1 ?var2 ...) falkor:cypher """
    <CYPHER QUERY>
  """
}'
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

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?personName ?personAge WHERE {
  (?personName ?personAge) falkor:cypher """
    MATCH (p:Resource)
    WHERE p.`http://example.org/name` IS NOT NULL
    RETURN p.`http://example.org/name` AS personName,
           p.`http://example.org/age` AS personAge
  """
}'
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

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?name ?age WHERE {
  (?name ?age) falkor:cypher """
    MATCH (p:Resource)
    RETURN p.`http://example.org/name`, p.`http://example.org/age`
  """
}'
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

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?name WHERE {
  (?name) falkor:cypher """
    MATCH (p:Resource)
    WHERE p.`http://example.org/name` IS NOT NULL
    RETURN p.`http://example.org/name` AS name
  """
}'
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

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?friend WHERE {
  (?friend) falkor:cypher """
    MATCH (:Resource {uri: \"http://example.org/alice\"})
          -[:`http://example.org/knows`]->(:Resource)
          -[:`http://example.org/knows`]->(f:Resource)
    RETURN f.uri AS friend
  """
}'
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

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?type ?count WHERE {
  (?type ?count) falkor:cypher """
    MATCH (n:Resource)
    WITH labels(n) AS nodeLabels
    UNWIND nodeLabels AS label
    WHERE label <> \"Resource\"
    RETURN label AS type, count(*) AS count
    ORDER BY count DESC
  """
}'
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

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?start ?end ?pathLength WHERE {
  (?start ?end ?pathLength) falkor:cypher """
    MATCH path = (s:Resource)-[*1..3]->(e:Resource)
    WHERE s.uri = \"http://example.org/node1\"
    RETURN s.uri AS start, e.uri AS end, length(path) AS pathLength
  """
}'
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

## Performance Demonstration with Social Network Dataset

The repository includes a social network dataset (`data/social_network.ttl`) with 100 people and approximately 800 "knows" relationships. This dataset is designed to demonstrate the performance benefits of the magic property for graph traversal queries.

### Loading the Dataset

1. Start FalkorDB and Fuseki:
   ```bash
   docker run -p 6379:6379 -d --name falkordb falkordb/falkordb:latest
   java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-*.jar
   ```

2. Load the social network data via Fuseki's SPARQL endpoint:
   ```bash
   curl -X POST 'http://localhost:3330/falkor/data' \
     -H 'Content-Type: text/turtle' \
     --data-binary @data/social_network.ttl
   ```

### Performance Comparison: Friends of Friends Query

#### Standard SPARQL (N+1 queries)

This query finds friends of friends for person1:

```sparql
PREFIX social: <http://example.org/social#>

SELECT ?fof WHERE {
  social:person1 social:knows ?friend .
  ?friend social:knows ?fof .
}
```

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX social: <http://example.org/social#>
SELECT ?fof WHERE {
  social:person1 social:knows ?friend .
  ?friend social:knows ?fof .
}'
```

**How it executes internally:**
1. First query: Find all friends of person1 → Returns ~15 people
2. For each friend: Find their friends → 15 more queries
3. Total: 16 database round trips

#### Magic Property (Single query)

The same query using the magic property:

```sparql
PREFIX falkor: <http://falkordb.com/jena#>
PREFIX social: <http://example.org/social#>

SELECT ?fof WHERE {
  (?fof) falkor:cypher '''
    MATCH (:Resource {uri: "http://example.org/social#person1"})
          -[:`http://example.org/social#knows`]->(:Resource)
          -[:`http://example.org/social#knows`]->(fof:Resource)
    RETURN DISTINCT fof.uri AS fof
  '''
}
```

**Via curl:**

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

**How it executes:**
1. Single Cypher query with native graph traversal
2. Total: 1 database round trip

### More Complex Examples

#### 3-Hop Path Query: Friends of Friends of Friends

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?person WHERE {
  (?person) falkor:cypher '''
    MATCH (:Resource {uri: "http://example.org/social#person1"})
          -[:`http://example.org/social#knows`*1..3]->(p:Resource)
    RETURN DISTINCT p.uri AS person
  '''
}
```

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?person WHERE {
  (?person) falkor:cypher """
    MATCH (:Resource {uri: \"http://example.org/social#person1\"})
          -[:`http://example.org/social#knows`*1..3]->(p:Resource)
    RETURN DISTINCT p.uri AS person
  """
}'
```

This query would require exponentially more round trips with standard SPARQL but executes as a single optimized Cypher query.

#### Counting Connections

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?person ?friendCount WHERE {
  (?person ?friendCount) falkor:cypher '''
    MATCH (p:Resource)-[r:`http://example.org/social#knows`]->(:Resource)
    RETURN p.uri AS person, count(r) AS friendCount
    ORDER BY friendCount DESC
    LIMIT 10
  '''
}
```

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?person ?friendCount WHERE {
  (?person ?friendCount) falkor:cypher """
    MATCH (p:Resource)-[r:`http://example.org/social#knows`]->(:Resource)
    RETURN p.uri AS person, count(r) AS friendCount
    ORDER BY friendCount DESC
    LIMIT 10
  """
}'
```

This finds the 10 most connected people in the network.

#### Shortest Path Between Two People

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?pathLength WHERE {
  (?pathLength) falkor:cypher '''
    MATCH path = shortestPath(
      (:Resource {uri: "http://example.org/social#person1"})
      -[:`http://example.org/social#knows`*]->
      (:Resource {uri: "http://example.org/social#person100"})
    )
    RETURN length(path) AS pathLength
  '''
}
```

**Via curl:**

```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?pathLength WHERE {
  (?pathLength) falkor:cypher """
    MATCH path = shortestPath(
      (:Resource {uri: \"http://example.org/social#person1\"})
      -[:`http://example.org/social#knows`*]->
      (:Resource {uri: \"http://example.org/social#person100\"})
    )
    RETURN length(path) AS pathLength
  """
}'
```

### Viewing Performance in Jaeger

With OpenTelemetry tracing enabled, you can visualize the performance difference in Jaeger:

1. Start Jaeger: `docker-compose -f docker-compose-tracing.yaml up -d`
2. Execute both query variants
3. Open Jaeger UI at `http://localhost:16686`
4. Compare trace timelines:
   - Standard SPARQL shows multiple spans for each triple lookup
   - Magic property shows a single `CypherQueryFunc.execute` span

The trace attributes include:
- `falkordb.cypher.query` - The executed Cypher query
- `falkordb.cypher.result_count` - Number of results
- `falkordb.cypher.var_count` - Number of bound variables

### Expected Performance Gains

| Query Type | Standard SPARQL | Magic Property | Improvement |
|------------|----------------|----------------|-------------|
| Friends of Friends | ~16 round trips | 1 round trip | 16x fewer calls |
| 3-hop traversal | ~150+ round trips | 1 round trip | 150x+ fewer calls |
| Aggregations | N/A (requires post-processing) | 1 query | Native aggregation |
| Path queries | N/A (not expressible) | 1 query | Enables new capabilities |

The actual time savings depend on network latency and dataset size, but the reduction in round trips provides consistent performance improvements.
