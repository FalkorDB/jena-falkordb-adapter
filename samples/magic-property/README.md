# Magic Property (Direct Cypher Execution) - Examples

Direct Cypher execution within SPARQL for maximum control and performance.

## Files

- `MagicPropertyExample.java`: Complete examples (simple queries, aggregations, paths, complex patterns)
- `queries.sparql`: Cypher query patterns within SPARQL
- `data-example.ttl`: Sample data

## Usage

```sparql
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?result WHERE {
    (?result) falkor:cypher '''
        MATCH (n:Resource)
        RETURN n.uri AS result
    '''
}
```

## Use Cases

- Variable length paths (`-[:knows*1..3]->`)
- Aggregations (`count()`, `sum()`, `avg()`)
- Complex WHERE conditions
- OPTIONAL MATCH patterns
- Performance-critical queries

## Benefits
- Full Cypher power within SPARQL
- No translation overhead
- Complex patterns not supported by pushdown
- Works with InfModel (unwraps to base graph)

## Using curl with Fuseki

You can use curl to load data and execute magic property queries via the Fuseki SPARQL endpoint.

### Prerequisites

First, start FalkorDB and Fuseki:

```bash
# Start FalkorDB
docker-compose -f docker-compose-tracing.yaml up -d

# In another terminal, start Fuseki (from project root)
mvn clean install -DskipTests
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

The Fuseki server will start on `http://localhost:3330` with the default endpoint at `/falkor`.

### Loading Data with curl

**Load the sample data file (`data-example.ttl`):**

```bash
curl -X POST \
  -H "Content-Type: text/turtle" \
  --data-binary @samples/magic-property/data-example.ttl \
  http://localhost:3330/falkor/data
```

### Executing Magic Property Queries with curl

**Example 1: Variable length paths**

Find all people connected to Alice within 1-3 hops:

```bash
curl -G \
  --data-urlencode "query=PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?connected WHERE {
    (?connected) falkor:cypher '''
        MATCH (start {uri: \"http://example.org/person/alice\"})
              -[:knows*1..3]->(end:Resource)
        RETURN end.uri AS connected
    '''
}" \
  http://localhost:3330/falkor/query
```

**Example 2: Aggregations**

Count friends for each person:

```bash
curl -G \
  --data-urlencode "query=PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?person ?friendCount WHERE {
    (?person ?friendCount) falkor:cypher '''
        MATCH (p:Resource)-[r:knows]->()
        RETURN p.uri AS person, count(r) AS friendCount
        ORDER BY friendCount DESC
    '''
}" \
  http://localhost:3330/falkor/query
```

**Example 3: Complex WHERE conditions**

Find people with complex age filters:

```bash
curl -G \
  --data-urlencode "query=PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?person WHERE {
    (?person) falkor:cypher '''
        MATCH (p:Resource)
        WHERE p.age > 25 AND p.age < 40
        RETURN p.uri AS person
    '''
}" \
  http://localhost:3330/falkor/query
```

**Example 4: OPTIONAL MATCH patterns**

Get people with optional manager information:

```bash
curl -G \
  --data-urlencode "query=PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?person ?manager WHERE {
    (?person ?manager) falkor:cypher '''
        MATCH (p:Resource)
        OPTIONAL MATCH (p)-[:managedBy]->(m:Resource)
        RETURN p.uri AS person, m.uri AS manager
    '''
}" \
  http://localhost:3330/falkor/query
```

**Example 5: Shortest path**

Find shortest path between two people:

```bash
curl -G \
  --data-urlencode "query=PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?pathLength WHERE {
    (?pathLength) falkor:cypher '''
        MATCH path = shortestPath(
          (start {uri: \"http://example.org/person/alice\"})-[:knows*]->
          (end {uri: \"http://example.org/person/charlie\"})
        )
        RETURN length(path) AS pathLength
    '''
}" \
  http://localhost:3330/falkor/query
```

**Example 6: Multiple return values**

Get person details with Cypher:

```bash
curl -G \
  --data-urlencode "query=PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?name ?age ?email WHERE {
    (?name ?age ?email) falkor:cypher '''
        MATCH (p:Resource {uri: \"http://example.org/person/alice\"})
        RETURN p.name AS name, p.age AS age, p.email AS email
    '''
}" \
  http://localhost:3330/falkor/query
```

**Example 7: Count all nodes by label**

```bash
curl -G \
  --data-urlencode "query=PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?label ?count WHERE {
    (?label ?count) falkor:cypher '''
        MATCH (n)
        UNWIND labels(n) AS label
        WHERE label <> \"Resource\"
        RETURN label, count(n) AS count
        ORDER BY count DESC
    '''
}" \
  http://localhost:3330/falkor/query
```

See [MAGIC_PROPERTY.md](../../MAGIC_PROPERTY.md) and [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#magic-property-direct-cypher-execution) for complete documentation.

Also see [Fuseki GETTING_STARTED.md](../../jena-fuseki-falkordb/GETTING_STARTED.md) for Fuseki setup and usage.
