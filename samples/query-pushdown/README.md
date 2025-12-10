# Query Pushdown - Examples

Query pushdown translates SPARQL BGPs to native Cypher queries, executing in a single database operation instead of N+1 queries.

## Files

- `QueryPushdownExample.java`: Complete examples (Variable Predicates, Closed-Chain, Type queries, Multi-hop)
- `queries.sparql`: SPARQL query patterns
- `data-example.ttl`: Sample social network data

## Key Patterns

### Variable Predicates
`?s ?p ?o` - Generates UNION for relationships, properties, and types

### Closed-Chain
`?a knows ?b . ?b knows ?a` - Bidirectional match for mutual connections

### Multi-Hop
`alice knows ?f1 . ?f1 knows ?f2` - 2-hop traversal in single query

## Performance
- Variable predicates: 3x fewer queries (single UNION)
- Friends of friends: Nx fewer calls (1 vs N+1)
- 3-hop traversal: N²x improvement

## Using curl with Fuseki

You can use curl to load data and execute queries via the Fuseki SPARQL endpoint.

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
  --data-binary @samples/query-pushdown/data-example.ttl \
  http://localhost:3330/falkor/data
```

### Executing Queries with curl

**Example 1: Variable Predicate - Get all properties of Alice**

This query retrieves all properties, relationships, and types for Alice:

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?p ?o WHERE {
    <http://example.org/person/alice> ?p ?o .
}" \
  http://localhost:3330/falkor/query
```

**Example 2: Closed-Chain Pattern - Find mutual friendships**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?a ?b WHERE {
    ?a foaf:knows ?b .
    ?b foaf:knows ?a .
}" \
  http://localhost:3330/falkor/query
```

**Example 3: Friends of Friends**

This demonstrates efficient 2-hop traversal:

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?fof WHERE {
    <http://example.org/person/alice> foaf:knows ?friend .
    ?friend foaf:knows ?fof .
}" \
  http://localhost:3330/falkor/query
```

**Example 4: Type-based query with properties**

```bash
curl -G \
  --data-urlencode "query=PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name WHERE {
    ?person rdf:type foaf:Person .
    ?person foaf:name ?name .
}" \
  http://localhost:3330/falkor/query
```

**Example 5: Multiple relationships**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?friend ?colleague WHERE {
    ?person foaf:knows ?friend .
    ?person <http://example.org/worksWith> ?colleague .
}" \
  http://localhost:3330/falkor/query
```

See [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#query-pushdown-sparql-to-cypher)
