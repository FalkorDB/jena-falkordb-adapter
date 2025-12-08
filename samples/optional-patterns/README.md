# OPTIONAL Patterns - Examples

OPTIONAL patterns are translated to Cypher OPTIONAL MATCH clauses, which return NULL for missing optional data in a single query instead of requiring separate queries.

## Files

- `OptionalPatternsExample.java`: Complete examples with 6 use cases
- `queries.sparql`: SPARQL OPTIONAL query patterns with generated Cypher
- `data-example.ttl`: Sample data with partial information (some people missing email, age, etc.)

## Key Features

### What OPTIONAL Does
Returns all matches from the required pattern, with NULLs for optional data that doesn't exist.

### Translation to Cypher
```sparql
# SPARQL
SELECT ?person ?email WHERE {
    ?person a foaf:Person .
    OPTIONAL { ?person foaf:email ?email }
}
```

```cypher
# Generated Cypher
MATCH (person:Resource:`foaf:Person`)
OPTIONAL MATCH (person)-[:email]->(email:Resource)
RETURN person.uri AS person, email.uri AS email
```

## Supported Patterns

| Pattern | Supported | Example |
|---------|-----------|---------|
| Basic OPTIONAL relationship | ✅ | `OPTIONAL { ?s foaf:email ?email }` |
| OPTIONAL literal property | ✅ | `OPTIONAL { ?s foaf:age ?age }` |
| Multiple OPTIONAL clauses | ✅ | Multiple OPTIONAL blocks |
| OPTIONAL with multiple triples | ✅ | `OPTIONAL { ?s foaf:knows ?f . ?f foaf:name ?n }` |
| Concrete subjects | ✅ | `OPTIONAL { <alice> foaf:email ?email }` |
| FILTER in required part | ✅ | `FILTER(?age < 30)` before OPTIONAL |
| Variable predicates in OPTIONAL | ❌ | Not yet supported |

**FILTER Support**: FILTER expressions are translated to Cypher WHERE clauses. Supported: `<`, `<=`, `>`, `>=`, `=`, `<>`, `AND`, `OR`, `NOT`.

## Example Use Cases

### Use Case 1: Contact Information
Find all people with any available contact methods:
```sparql
SELECT ?person ?name ?email ?phone WHERE {
    ?person foaf:name ?name .
    OPTIONAL { ?person foaf:email ?email }
    OPTIONAL { ?person foaf:phone ?phone }
}
```
**Result**: All persons returned, with NULL for missing contact info

### Use Case 2: Friend Networks
Get social network with optional friend details:
```sparql
SELECT ?person ?friend ?friendName WHERE {
    ?person foaf:name ?name .
    OPTIONAL { 
        ?person foaf:knows ?friend .
        ?friend foaf:name ?friendName .
    }
}
```
**Result**: All persons, showing friends if they have any

### Use Case 3: Filtered with Optional
Find specific subset with optional additional data:
```sparql
SELECT ?person ?age ?email WHERE {
    ?person foaf:age ?age .
    FILTER(?age < 30)
    OPTIONAL { ?person foaf:email ?email }
}
```
**Result**: Young persons with email if available

## Performance Benefits

| Scenario | Without OPTIONAL Pushdown | With OPTIONAL Pushdown | Improvement |
|----------|--------------------------|----------------------|-------------|
| 100 persons, 50 with email | 101 queries (1 + 100) | 1 query | 100x faster |
| Multiple OPTIONAL fields | N * M queries | 1 query | NMx faster |
| Nested OPTIONAL | N * M * K queries | 1 query | NMKx faster |

### Why It's Fast
1. **Single roundtrip**: One database query for all data
2. **Native OPTIONAL MATCH**: Cypher's built-in optimization
3. **NULL handling**: Returns NULL instead of empty result set
4. **Index usage**: Can still use indexes on required patterns

## Running the Example

```bash
# Start FalkorDB
docker run -p 6379:6379 -d falkordb/falkordb:latest

# Build the project
mvn clean install

# Run the example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.OptionalPatternsExample"
```

## Common Patterns

### Pattern 1: All-or-Nothing
```sparql
# Get all persons, with email if they have one
SELECT ?person ?email WHERE {
    ?person a foaf:Person .
    OPTIONAL { ?person foaf:email ?email }
}
```

### Pattern 2: Multiple Optional Fields
```sparql
# Get person with any available contact info
SELECT ?person ?email ?phone ?fax WHERE {
    ?person a foaf:Person .
    OPTIONAL { ?person foaf:email ?email }
    OPTIONAL { ?person foaf:phone ?phone }
    OPTIONAL { ?person foaf:fax ?fax }
}
```

### Pattern 3: Optional Graph Traversal
```sparql
# Get person with optional friend-of-friend
SELECT ?person ?fof WHERE {
    ?person a foaf:Person .
    OPTIONAL {
        ?person foaf:knows ?friend .
        ?friend foaf:knows ?fof .
    }
}
```

## FILTER Support

FILTER expressions in the required pattern work seamlessly with OPTIONAL patterns:

```sparql
# Filter on required data before OPTIONAL
SELECT ?person ?name ?email WHERE {
    ?person foaf:name ?name .
    ?person foaf:age ?age .
    FILTER(?age >= 18 AND ?age < 65)
    OPTIONAL { ?person foaf:email ?email }
}
```

**Supported FILTER Operators:**
- Comparisons: `<`, `<=`, `>`, `>=`, `=`, `<>` 
- Logical: `AND`, `OR`, `NOT`
- Works with: literal properties, variables, numeric/string/boolean constants

The FILTER is translated to a Cypher WHERE clause and executed at the database level for optimal performance.

## Best Practices

1. **Use OPTIONAL for truly optional data**: Don't use OPTIONAL if the data is required
2. **Order matters**: Put required patterns first, OPTIONAL patterns after
3. **Multiple OPTIONALs are independent**: Each OPTIONAL is evaluated separately
4. **FILTER before OPTIONAL**: Apply filters on required data before OPTIONAL patterns for best performance
5. **Check for NULL**: Always check if optional variables are bound before using them

## Limitations

- Variable predicates in OPTIONAL patterns not yet supported
- Very deeply nested OPTIONAL patterns may fall back to standard evaluation
- FILTER clauses within OPTIONAL blocks (not in required part) may not fully push down

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
  --data-binary @samples/optional-patterns/data-example.ttl \
  http://localhost:3330/falkor/data
```

### Executing Queries with curl

**Example 1: Basic OPTIONAL - Find people with optional email**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?email WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL { ?person foaf:email ?email }
}
ORDER BY ?name" \
  http://localhost:3330/falkor/query
```

**Example 2: Multiple OPTIONAL clauses - Contact information**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?email ?phone WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL { ?person foaf:email ?email }
    OPTIONAL { ?person foaf:phone ?phone }
}
ORDER BY ?name" \
  http://localhost:3330/falkor/query
```

**Example 3: OPTIONAL with literal property**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?age WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL { ?person foaf:age ?age }
}
ORDER BY ?name" \
  http://localhost:3330/falkor/query
```

**Example 4: OPTIONAL with multiple triples - Friend information**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?friend ?friendName WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL { 
        ?person foaf:knows ?friend .
        ?friend foaf:name ?friendName .
    }
}
ORDER BY ?name" \
  http://localhost:3330/falkor/query
```

**Example 5: Concrete subject with OPTIONAL**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?email ?phone WHERE {
    <http://example.org/person/alice> a foaf:Person .
    OPTIONAL { <http://example.org/person/alice> foaf:email ?email }
    OPTIONAL { <http://example.org/person/alice> foaf:phone ?phone }
}" \
  http://localhost:3330/falkor/query
```

**Example 6: OPTIONAL with FILTER**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?age ?email WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    ?person foaf:age ?age .
    FILTER(?age >= 18 && ?age < 65)
    OPTIONAL { ?person foaf:email ?email }
}
ORDER BY ?age" \
  http://localhost:3330/falkor/query
```

## See Also

- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#optional-patterns) - Full documentation
- [FalkorDBQueryPushdownTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java) - Integration tests
- [SparqlToCypherCompilerTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) - Unit tests
- [Fuseki GETTING_STARTED.md](../../jena-fuseki-falkordb/GETTING_STARTED.md) - Fuseki setup and usage

## Performance Monitoring

Enable OpenTelemetry tracing to see OPTIONAL pattern execution:
```bash
docker-compose -f docker-compose-tracing.yaml up -d
```
Then view traces at `http://localhost:16686` to see:
- Query compilation (SPARQL to Cypher)
- OPTIONAL MATCH execution
- Result counts and timing
