# UNION Patterns - Examples

UNION patterns are translated to Cypher UNION queries, which combine results from alternative query patterns in a single database call instead of requiring separate queries.

## Files

- `UnionPatternsExample.java`: Complete examples with 7 use cases
- `queries.sparql`: SPARQL UNION query patterns with generated Cypher
- `data-example.ttl`: Sample data with different types and relationships

## Key Features

### What UNION Does
Combines results from multiple alternative query patterns, returning all matches from any branch.

### Translation to Cypher
```sparql
# SPARQL
SELECT ?person WHERE {
    { ?person rdf:type foaf:Student }
    UNION
    { ?person rdf:type foaf:Teacher }
}
```

```cypher
# Generated Cypher
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Student`)
RETURN person.uri AS person
UNION
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Teacher`)
RETURN person.uri AS person
```

## Supported Patterns

| Pattern | Supported | Example |
|---------|-----------|---------|
| Type patterns | ✅ | `{ ?s rdf:type <TypeA> } UNION { ?s rdf:type <TypeB> }` |
| Relationship patterns | ✅ | `{ ?s foaf:knows ?o } UNION { ?s foaf:worksWith ?o }` |
| Property patterns | ✅ | `{ ?s foaf:email ?v } UNION { ?s foaf:phone ?v }` |
| Concrete subjects | ✅ | `{ <alice> foaf:knows ?f } UNION { <bob> foaf:knows ?f }` |
| Multi-triple patterns | ✅ | `{ ?s rdf:type <Student> . ?s foaf:age 20 } UNION { ?s rdf:type <Teacher> . ?s foaf:age 30 }` |
| Nested UNION | ✅ | `{ ... } UNION { { ... } UNION { ... } }` |
| Variable predicates | ❌ | Each branch must have concrete predicates |

## Example Use Cases

### Use Case 1: Multiple Types
Find all people who are either students or teachers:
```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person WHERE {
    { ?person rdf:type foaf:Student }
    UNION
    { ?person rdf:type foaf:Teacher }
}
```
**Result**: All students and teachers combined

### Use Case 2: Alternative Relationships
Find all connections (friends or colleagues):
```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

SELECT ?person ?connection WHERE {
    { ?person foaf:knows ?connection }
    UNION
    { ?person ex:worksWith ?connection }
}
```
**Result**: All friend and colleague relationships

### Use Case 3: Contact Information
Get any available contact method:
```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?contact WHERE {
    { ?person foaf:email ?contact }
    UNION
    { ?person foaf:phone ?contact }
}
```
**Result**: All email addresses and phone numbers

### Use Case 4: Specific People
Get friends of specific people:
```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?friend WHERE {
    { <http://example.org/alice> foaf:knows ?friend }
    UNION
    { <http://example.org/bob> foaf:knows ?friend }
}
```
**Result**: Friends of Alice or Bob

### Use Case 5: Age-Based Queries
Find students aged 20 or teachers aged 30:
```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

SELECT ?person WHERE {
    { ?person rdf:type ex:Student . ?person foaf:age 20 }
    UNION
    { ?person rdf:type ex:Teacher . ?person foaf:age 30 }
}
```
**Result**: Specific age groups by role

## Performance Benefits

| Scenario | Without UNION Pushdown | With UNION Pushdown | Improvement |
|----------|----------------------|---------------------|-------------|
| 2 alternative types | 2 queries + merge | 1 query | 2x fewer calls |
| N alternative patterns | N queries + merge | 1 query | Nx fewer calls |
| UNION with relationships | N queries + merge | 1 query | Nx fewer calls |
| Nested UNION (3 branches) | 3 queries + merge | 1 query | 3x fewer calls |

### Why It's Fast
1. **Single roundtrip**: One database query for all alternatives
2. **Native Cypher UNION**: Database-level optimization
3. **No client-side merging**: Results combined by FalkorDB
4. **Efficient execution**: Can use indexes on each branch

## Running the Example

```bash
# Start FalkorDB
docker run -p 6379:6379 -d falkordb/falkordb:latest

# Build the project
mvn clean install

# Run the example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.UnionPatternsExample"
```

## Common Patterns

### Pattern 1: Type Union
```sparql
# Get all academic personnel (students or faculty)
SELECT ?person WHERE {
    { ?person rdf:type ex:Student }
    UNION
    { ?person rdf:type ex:Faculty }
}
```

### Pattern 2: Property Alternatives
```sparql
# Get any contact method
SELECT ?person ?contact WHERE {
    { ?person foaf:email ?contact }
    UNION
    { ?person foaf:phone ?contact }
    UNION
    { ?person foaf:mbox ?contact }
}
```

### Pattern 3: Relationship Alternatives
```sparql
# Get all social connections
SELECT ?person ?connection WHERE {
    { ?person foaf:knows ?connection }
    UNION
    { ?person ex:friendOf ?connection }
    UNION
    { ?person ex:colleagueOf ?connection }
}
```

### Pattern 4: Complex Alternatives
```sparql
# Get young students or experienced teachers
SELECT ?person ?info WHERE {
    { 
        ?person rdf:type ex:Student . 
        ?person foaf:age ?age .
        FILTER(?age < 25)
        BIND(?age AS ?info)
    }
    UNION
    { 
        ?person rdf:type ex:Teacher .
        ?person ex:experience ?years .
        FILTER(?years > 10)
        BIND(?years AS ?info)
    }
}
```

## UNION with DISTINCT

SPARQL UNION doesn't eliminate duplicates by default. If a result appears in multiple branches, it's returned multiple times:

```sparql
# Without DISTINCT - may return duplicates
SELECT ?person WHERE {
    { ?person rdf:type ex:Student }
    UNION
    { ?person rdf:type ex:Person }
}

# With DISTINCT - removes duplicates
SELECT DISTINCT ?person WHERE {
    { ?person rdf:type ex:Student }
    UNION
    { ?person rdf:type ex:Person }
}
```

**Note**: A student who is also a person will appear twice without DISTINCT.

## Nested UNION

Nested UNION patterns are supported:

```sparql
# Three-way union (flat or nested both work)
SELECT ?person WHERE {
    { ?person rdf:type ex:Student }
    UNION
    { { ?person rdf:type ex:Teacher } UNION { ?person rdf:type ex:Staff } }
}
```

This is equivalent to:
```sparql
SELECT ?person WHERE {
    { ?person rdf:type ex:Student }
    UNION
    { ?person rdf:type ex:Teacher }
    UNION
    { ?person rdf:type ex:Staff }
}
```

## Best Practices

1. **Use UNION for truly alternative patterns**: Don't use UNION if you need all patterns (use separate triple patterns instead)
2. **Consider DISTINCT**: If duplicates are possible and unwanted, use SELECT DISTINCT
3. **Keep branches similar**: UNION works best when branches return the same variables
4. **Avoid variable predicates**: Each branch must have concrete predicates
5. **Profile your queries**: Use OpenTelemetry to see execution time per branch

## Limitations

1. **Variable predicates not supported**: Each UNION branch must have concrete predicates
   ```sparql
   # This will fall back to standard evaluation
   SELECT ?s ?p ?o WHERE {
       { ?s ?p ?o }  # Variable predicate - not supported
       UNION
       { ?s foaf:name ?o }
   }
   ```

2. **Existing BGP limitations apply**: Each branch is compiled independently, so limitations for BGPs apply:
   - Multi-triple patterns with ambiguous variable objects require object to be used as subject
   - Complex patterns that can't compile individually cause entire UNION to fall back

3. **DISTINCT not automatic**: Use SELECT DISTINCT if you want to eliminate duplicate results

4. **Parameter conflicts handled transparently**: When branches use same parameter names, the compiler renames them automatically (e.g., `$p0` becomes `$p0_r`)

5. **Fallback on compilation failure**: If any branch fails to compile, the entire UNION falls back to standard evaluation

## When Pushdown Fails

The optimizer falls back to standard Jena evaluation when:
- One or both branches are not BGPs (contain FILTER, OPTIONAL, etc.)
- One or both branches contain unsupported patterns
- Any branch contains variable predicates
- Compilation errors occur

Check logs for "UNION pushdown failed, falling back" messages to identify issues.

## Combining with Other Optimizations

UNION works well with other optimizations:

### UNION + FILTER
```sparql
# Filter before UNION (more efficient)
SELECT ?person WHERE {
    ?person foaf:age ?age .
    FILTER(?age > 18)
    {
        { ?person rdf:type ex:Student }
        UNION
        { ?person rdf:type ex:Teacher }
    }
}
```

### UNION + OPTIONAL
```sparql
# OPTIONAL within UNION branches
SELECT ?person ?email WHERE {
    { 
        ?person rdf:type ex:Student .
        OPTIONAL { ?person foaf:email ?email }
    }
    UNION
    { 
        ?person rdf:type ex:Teacher .
        OPTIONAL { ?person foaf:email ?email }
    }
}
```

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
  --data-binary @samples/union-patterns/data-example.ttl \
  http://localhost:3330/falkor/data
```

### Executing Queries with curl

**Example 1: Basic UNION - Find all students or teachers**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?person ?name WHERE {
    { ?person rdf:type ex:Student }
    UNION
    { ?person rdf:type ex:Teacher }
    ?person foaf:name ?name .
}
ORDER BY ?name" \
  http://localhost:3330/falkor/query
```

**Example 2: UNION with relationships - Find all connections**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

SELECT ?person ?connection WHERE {
    { ?person foaf:knows ?connection }
    UNION
    { ?person ex:worksWith ?connection }
}
ORDER BY ?person" \
  http://localhost:3330/falkor/query
```

**Example 3: UNION with concrete subjects - Friends of specific people**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?friend WHERE {
    { <http://example.org/alice> foaf:knows ?friend }
    UNION
    { <http://example.org/bob> foaf:knows ?friend }
}" \
  http://localhost:3330/falkor/query
```

**Example 4: UNION with properties - Any contact method**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?contact WHERE {
    { ?person foaf:email ?contact }
    UNION
    { ?person foaf:phone ?contact }
}
ORDER BY ?person" \
  http://localhost:3330/falkor/query
```

**Example 5: Multi-triple UNION with FILTER**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?person WHERE {
    { ?person rdf:type ex:Student . ?person foaf:age 20 }
    UNION
    { ?person rdf:type ex:Teacher . ?person foaf:age 30 }
}" \
  http://localhost:3330/falkor/query
```

**Example 6: Nested UNION**

```bash
curl -G \
  --data-urlencode "query=PREFIX ex: <http://example.org/>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?person WHERE {
    { ?person rdf:type ex:Student }
    UNION
    { { ?person rdf:type ex:Teacher } UNION { ?person rdf:type ex:Staff } }
}" \
  http://localhost:3330/falkor/query
```

**Example 7: UNION with DISTINCT to eliminate duplicates**

```bash
curl -G \
  --data-urlencode "query=PREFIX ex: <http://example.org/>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT DISTINCT ?person WHERE {
    { ?person rdf:type ex:Student }
    UNION
    { ?person rdf:type ex:Person }
}" \
  http://localhost:3330/falkor/query
```

## See Also

- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#union-patterns) - Full documentation
- [FalkorDBQueryPushdownTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java) - Integration tests (`testUnion*` methods)
- [SparqlToCypherCompilerTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) - Unit tests (`testUnion*` methods)
- [Fuseki GETTING_STARTED.md](../../jena-fuseki-falkordb/GETTING_STARTED.md) - Fuseki setup and usage

## Performance Monitoring

Enable OpenTelemetry tracing to see UNION pattern execution:
```bash
docker-compose -f docker-compose-tracing.yaml up -d
```
Then view traces at `http://localhost:16686` to see:
- Query compilation (SPARQL to Cypher with UNION)
- UNION execution timing
- Result counts per branch
- Parameter handling

Look for spans named `FalkorDBOpExecutor.executeUnion` and `SparqlToCypherCompiler.translateUnion`.
