# Variable Predicate in OPTIONAL Patterns - Examples

This directory contains comprehensive examples demonstrating **variable predicate optimization in OPTIONAL patterns** in the FalkorDB Jena Adapter.

## Overview

Variable predicates in OPTIONAL patterns allow you to query for **all properties** of a resource (relationships, literal properties, and types) in a single efficient query. The optimization uses a three-part UNION approach that wraps each part in OPTIONAL MATCH.

### The Problem

Without optimization, querying all properties of resources requires multiple separate queries:

```sparql
# Traditional approach requires 3+ separate queries:
# 1. Query for relationships
# 2. Query for properties  
# 3. Query for types
# Result: N × 3 database queries for N resources
```

### The Solution

With variable predicate optimization in OPTIONAL, a single query retrieves all property types:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?p ?o WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL {
        ?person ?p ?o .
    }
}
```

This generates a three-part Cypher UNION query:
1. **Part 1 (Relationships)**: `OPTIONAL MATCH (person)-[_r]->(o) RETURN type(_r) AS p, o.uri AS o`
2. **Part 2 (Properties)**: `OPTIONAL MATCH (person) UNWIND keys(person) AS p RETURN person[p] AS o`
3. **Part 3 (Types)**: `OPTIONAL MATCH (person) UNWIND labels(person) AS o RETURN 'rdf:type' AS p, o`

**Performance**: 300x improvement (1 query vs 300 queries for 100 resources)

## Files in this Directory

| File | Description |
|------|-------------|
| `OptionalPatternsVariablePredicateExample.java` | Complete Java example with 5 use cases |
| `queries.sparql` | Sample SPARQL queries with explanations |
| `data-example.ttl` | Sample data in Turtle format |
| `README.md` | This file |

## Quick Start

### 1. Compile and Run the Java Example

```bash
# Compile (from repository root)
mvn clean compile

# Run the example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.OptionalPatternsVariablePredicateExample" \
  -pl samples/optional-patterns-variable-predicate
```

### 2. Use with Fuseki and config-falkordb.ttl

Start Fuseki with forward-chaining inference:

```bash
# Start FalkorDB
docker run -d --name falkordb -p 6379:6379 falkordb/falkordb:latest

# Start Fuseki with config-falkordb.ttl
cd FusekiIntegration
./fuseki-server --config=config-falkordb.ttl
```

Load sample data:

```bash
# Load data via HTTP
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data-binary @data-example.ttl
```

Query via HTTP:

```bash
# Query with variable predicate in OPTIONAL
curl -X POST http://localhost:3330/falkor/query \
  -H "Content-Type: application/sparql-query" \
  --data-binary @queries.sparql
```

## Examples Included

### Example 1: Basic Variable Predicate in OPTIONAL

Query all properties of persons:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?p ?o WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL {
        ?person ?p ?o .
    }
}
```

**Returns**:
- Relationships (e.g., `foaf:knows` → Bob)
- Literal properties (e.g., `foaf:age` → 25)
- Types (e.g., `rdf:type` → Person)

### Example 2: Variable Predicate with Concrete Subject

Query all properties of a specific person:

```sparql
SELECT ?p ?o WHERE {
    <http://example.org/person/alice> a foaf:Person .
    OPTIONAL {
        <http://example.org/person/alice> ?p ?o .
    }
}
```

**Use Case**: Retrieving complete profile data for a single resource.

### Example 3: Variable Predicate with FILTER

Filter persons by age, then get all their properties:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?age ?p ?o WHERE {
    ?person a foaf:Person .
    ?person foaf:age ?age .
    FILTER(?age >= 25 && ?age < 35)
    OPTIONAL {
        ?person ?p ?o .
    }
}
```

**Key Feature**: FILTER is applied before OPTIONAL MATCH for efficiency.

### Example 4: Multiple Persons with Varying Data

Demonstrates handling persons with different amounts of data:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?p ?o WHERE {
    ?person a foaf:Person .
    OPTIONAL {
        ?person ?p ?o .
    }
}
```

**Result**: All persons returned, even those with minimal data. Missing optional data returns NULL.

### Example 5: Complex Scenario with Mixed Data Types

Analyzes property distribution across all persons:

- Counts relationships (edges between resources)
- Counts literal properties (strings, numbers, etc.)
- Counts types (rdf:type triples from labels)

## Key Features

✅ **Three-Part UNION**: Queries relationships, properties, and types
✅ **NULL Handling**: Returns NULL when optional data doesn't exist  
✅ **Single Query**: One database round-trip instead of N×3 queries
✅ **FILTER Support**: Works seamlessly with FILTER expressions
✅ **OPTIONAL MATCH**: Uses native Cypher OPTIONAL MATCH for efficiency
✅ **Preserves Required Variables**: All required variables in every UNION branch

## Performance Comparison

| Scenario | Without Optimization | With Optimization | Improvement |
|----------|---------------------|-------------------|-------------|
| 100 resources, 3 property types | 300 queries | 1 query | **300x** |
| Query with FILTER | 300 queries + filter | 1 query with WHERE | **300x + filter pushdown** |
| Mixed data types | Separate queries | Single UNION query | **3x minimum** |

## Cypher Query Generation

For the basic example, the compiler generates:

```cypher
-- Required pattern
MATCH (person:Resource:`foaf:Person`)
WHERE person.`foaf:name` IS NOT NULL

-- Part 1: Relationships
OPTIONAL MATCH (person)-[_r]->(o:Resource)
RETURN person.uri AS person, person.`foaf:name` AS name, type(_r) AS p, o.uri AS o

UNION ALL

-- Part 2: Properties
MATCH (person:Resource:`foaf:Person`)
WHERE person.`foaf:name` IS NOT NULL
OPTIONAL MATCH (person)
UNWIND keys(person) AS _propKey
WITH person, _propKey WHERE _propKey <> 'uri'
RETURN person.uri AS person, person.`foaf:name` AS name, _propKey AS p, person[_propKey] AS o

UNION ALL

-- Part 3: Types (from labels)
MATCH (person:Resource:`foaf:Person`)
WHERE person.`foaf:name` IS NOT NULL
OPTIONAL MATCH (person)
UNWIND labels(person) AS _label
WITH person, _label WHERE _label <> 'Resource'
RETURN person.uri AS person, person.`foaf:name` AS name, 
       'http://www.w3.org/1999/02/22-rdf-syntax-ns#type' AS p, _label AS o
```

## Testing

### Unit Tests

Comprehensive unit tests in `SparqlToCypherCompilerTest.java`:

- `testOptionalWithVariablePredicateCompilesWithUnion()`
- `testOptionalVariablePredicateThreePartUnion()`
- `testOptionalVariablePredicateConcreteSubject()`
- `testOptionalVariablePredicateReturnsCorrectVariables()`
- `testOptionalVariablePredicateWithFilter()`
- And 4 more...

Run with:
```bash
mvn test -Dtest=SparqlToCypherCompilerTest -pl jena-falkordb-adapter
```

### Integration Tests

Integration tests in `FalkorDBQueryPushdownTest.java`:

- `testOptionalVariablePredicateReturnsAllTripleTypes()`
- `testOptionalVariablePredicateReturnsNullForNoData()`
- `testOptionalVariablePredicateWithFilter()`
- And 3 more...

Run with FalkorDB running:
```bash
docker run -d -p 6379:6379 falkordb/falkordb:latest
mvn test -Dtest=FalkorDBQueryPushdownTest -pl jena-falkordb-adapter
```

## Limitations

1. **Single triple only**: Only single-triple patterns with variable predicates in OPTIONAL are supported
2. **Multiple triples**: Multiple triples with variable predicates in the same OPTIONAL block will fall back
3. **Complex patterns**: Complex nested OPTIONAL patterns may fall back to standard evaluation

## Best Practices

### ✅ Do

- Use variable predicates in OPTIONAL for comprehensive data retrieval
- Combine with FILTER for efficient filtering
- Use with concrete subjects for focused queries
- Leverage with Fuseki and config-falkordb.ttl for inference

### ❌ Don't

- Use multiple triples with variable predicates in same OPTIONAL (not yet supported)
- Expect optimization for complex nested OPTIONAL patterns
- Use when you only need specific predicates (use concrete predicates instead)

## OpenTelemetry Tracing

The optimization is fully instrumented with OpenTelemetry tracing:

- **Span**: `SparqlToCypherCompiler.translateWithOptional`
- **Attributes**: Input SPARQL, output Cypher, triple count, parameter count
- **Type**: `OPTIONAL_PUSHDOWN`

Enable tracing:
```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

View traces at: http://localhost:16686

## Related Documentation

- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#variable-predicates-in-optional) - Complete optimization guide
- [TRACING.md](../../TRACING.md) - OpenTelemetry tracing details
- [samples/optional-patterns/](../optional-patterns/) - Basic OPTIONAL patterns
- [samples/query-pushdown/](../query-pushdown/) - General query pushdown examples

## Support

- **Issues**: https://github.com/FalkorDB/jena-falkordb-adapter/issues
- **Documentation**: See [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md)
- **Tests**: See `SparqlToCypherCompilerTest.java` and `FalkorDBQueryPushdownTest.java`
