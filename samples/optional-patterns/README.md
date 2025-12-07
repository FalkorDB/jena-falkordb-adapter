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

## Best Practices

1. **Use OPTIONAL for truly optional data**: Don't use OPTIONAL if the data is required
2. **Order matters**: Put required patterns first, OPTIONAL patterns after
3. **Multiple OPTIONALs are independent**: Each OPTIONAL is evaluated separately
4. **FILTER before OPTIONAL**: Apply filters on required data before OPTIONAL patterns
5. **Check for NULL**: Always check if optional variables are bound before using them

## Limitations

- Variable predicates in OPTIONAL patterns not yet supported
- Very deeply nested OPTIONAL patterns may fall back to standard evaluation
- Complex filters within OPTIONAL blocks may not push down

## See Also

- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#optional-patterns) - Full documentation
- [FalkorDBQueryPushdownTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java) - Integration tests
- [SparqlToCypherCompilerTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) - Unit tests

## Performance Monitoring

Enable OpenTelemetry tracing to see OPTIONAL pattern execution:
```bash
docker-compose -f docker-compose-tracing.yaml up -d
```
Then view traces at `http://localhost:16686` to see:
- Query compilation (SPARQL to Cypher)
- OPTIONAL MATCH execution
- Result counts and timing
