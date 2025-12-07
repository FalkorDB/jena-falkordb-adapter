# FILTER Expression Optimization Examples

This directory contains comprehensive examples demonstrating FILTER expression optimization in the FalkorDB Jena adapter.

## Overview

The FalkorDB adapter automatically pushes FILTER expressions down to native Cypher WHERE clauses, eliminating the need for client-side filtering and reducing network round-trips. This optimization applies to various comparison and logical operators.

## Supported FILTER Operators

### Comparison Operators
- `<` - Less than
- `<=` - Less than or equal
- `>` - Greater than
- `>=` - Greater than or equal
- `=` - Equals
- `!=` (or `<>`) - Not equals

### Logical Operators
- `&&` (AND) - Logical AND
- `||` (OR) - Logical OR
- `!` (NOT) - Logical negation

### Supported Operands
- **Variables**: From literal properties or node variables
- **Numeric literals**: Integers, decimals, doubles
- **String literals**: Quoted strings
- **Boolean literals**: true, false

## Examples

### Example 1: Simple Numeric Comparison

Find all persons younger than 30:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?name ?age WHERE {
    ?person foaf:name ?name .
    ?person foaf:age ?age .
    FILTER(?age < 30)
}
```

**Compiled Cypher:**
```cypher
MATCH (person:Resource)
WHERE person.`foaf:name` IS NOT NULL
  AND person.`foaf:age` IS NOT NULL
  AND person.`foaf:age` < 30
RETURN person.`foaf:name` AS name, person.`foaf:age` AS age
```

### Example 2: Numeric Range with AND

Find persons of working age (18-65):

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?name ?age WHERE {
    ?person foaf:name ?name .
    ?person foaf:age ?age .
    FILTER(?age >= 18 && ?age < 65)
}
```

**Compiled Cypher:**
```cypher
MATCH (person:Resource)
WHERE person.`foaf:name` IS NOT NULL
  AND person.`foaf:age` IS NOT NULL
  AND (person.`foaf:age` >= 18 AND person.`foaf:age` < 65)
RETURN person.`foaf:name` AS name, person.`foaf:age` AS age
```

### Example 3: String Equality

Find a specific person by name:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?email WHERE {
    ?person foaf:name ?name .
    ?person foaf:email ?email .
    FILTER(?name = "Alice")
}
```

**Compiled Cypher:**
```cypher
MATCH (person:Resource)
WHERE person.`foaf:name` IS NOT NULL
  AND person.`foaf:email` IS NOT NULL
  AND person.`foaf:name` = 'Alice'
RETURN person.`foaf:email` AS email
```

### Example 4: NOT Operator

Find all adults (NOT minors):

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?name ?age WHERE {
    ?person foaf:name ?name .
    ?person foaf:age ?age .
    FILTER(! (?age < 18))
}
```

**Compiled Cypher:**
```cypher
MATCH (person:Resource)
WHERE person.`foaf:name` IS NOT NULL
  AND person.`foaf:age` IS NOT NULL
  AND NOT (person.`foaf:age` < 18)
RETURN person.`foaf:name` AS name, person.`foaf:age` AS age
```

### Example 5: OR Operator

Find persons at dependency ages (young or senior):

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?name ?age WHERE {
    ?person foaf:name ?name .
    ?person foaf:age ?age .
    FILTER(?age < 18 || ?age > 65)
}
```

**Compiled Cypher:**
```cypher
MATCH (person:Resource)
WHERE person.`foaf:name` IS NOT NULL
  AND person.`foaf:age` IS NOT NULL
  AND (person.`foaf:age` < 18 OR person.`foaf:age` > 65)
RETURN person.`foaf:name` AS name, person.`foaf:age` AS age
```

### Example 6: Not Equals

Find all persons except Bob:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?name WHERE {
    ?person foaf:name ?name .
    FILTER(?name != "Bob")
}
```

**Compiled Cypher:**
```cypher
MATCH (person:Resource)
WHERE person.`foaf:name` IS NOT NULL
  AND person.`foaf:name` <> 'Bob'
RETURN person.`foaf:name` AS name
```

## Running the Examples

### Prerequisites

1. **FalkorDB** running on localhost:6379:
   ```bash
   docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
   ```

2. **Java 21** or newer

3. **Maven** for building

### Compile and Run

From the repository root:

```bash
# Build the project
mvn clean package

# Run the examples
java -cp "jena-falkordb-adapter/target/*:jena-falkordb-adapter/target/lib/*" \
     com.falkordb.samples.FilterExpressionsExample
```

Or compile the example directly:

```bash
cd samples/filter-expressions
javac -cp "../../jena-falkordb-adapter/target/*:../../jena-falkordb-adapter/target/lib/*" \
     FilterExpressionsExample.java
     
java -cp ".:../../jena-falkordb-adapter/target/*:../../jena-falkordb-adapter/target/lib/*" \
     com.falkordb.samples.FilterExpressionsExample
```

## Performance Benefits

| Scenario | Without FILTER Pushdown | With FILTER Pushdown | Improvement |
|----------|------------------------|---------------------|-------------|
| Age filter on 1000 persons | Retrieve all 1000, filter client-side | Filter at database (e.g., 100 matches) | 10x less data transfer |
| Complex AND/OR filters | Multiple client-side passes | Single database query | Nx fewer operations |
| Range queries | Full scan + filter | Native WHERE clause with indexes | Use of database indexes |

## How It Works

1. **Query Analysis**: The `FalkorDBOpExecutor` intercepts `OpFilter` operations in the SPARQL algebra tree

2. **Filter Translation**: The `SparqlToCypherCompiler.translateWithFilter()` method converts SPARQL FILTER expressions to Cypher WHERE clauses:
   - Comparison operators map directly to Cypher operators
   - Logical operators (AND, OR, NOT) map to Cypher equivalents
   - Variables are resolved to Cypher property expressions

3. **Query Execution**: The complete query (MATCH + WHERE) is sent to FalkorDB as a single Cypher query

4. **Fallback**: If a FILTER cannot be pushed down (unsupported operator, complex expression), the system automatically falls back to standard Jena evaluation

## FILTER with UNION Queries

When a BGP uses the variable object optimization (resulting in a UNION query), FILTER expressions are automatically added to each UNION branch:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?value WHERE {
    <http://example.org/alice> foaf:knows ?value .
    FILTER(?value != "Bob")
}
```

**Compiled Cypher (with UNION):**
```cypher
-- Part 1: Relationships
MATCH (s:Resource {uri: $p0})-[:`foaf:knows`]->(o:Resource)
WHERE o.uri <> 'Bob'
RETURN s.uri AS _s, o.uri AS value
UNION ALL
-- Part 2: Properties  
MATCH (s:Resource {uri: $p0})
WHERE s.`foaf:knows` IS NOT NULL
  AND s.`foaf:knows` <> 'Bob'
RETURN s.uri AS _s, s.`foaf:knows` AS value
```

## FILTER with OPTIONAL

FILTER expressions also work with OPTIONAL patterns (see [optional-patterns examples](../optional-patterns/)):

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name ?age ?email WHERE {
    ?person foaf:name ?name .
    ?person foaf:age ?age .
    FILTER(?age < 35)
    OPTIONAL { ?person foaf:email ?email }
}
```

The FILTER is applied to the required pattern before the OPTIONAL MATCH.

## Testing

Comprehensive tests for FILTER expressions are available:

- **Unit Tests**: [SparqlToCypherCompilerTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java)
  - `testFilterWithLessThan()` - Less than operator
  - `testFilterWithGreaterThanOrEqual()` - Greater than or equal
  - `testFilterWithEquals()` - Equality comparison
  - `testFilterWithNotEquals()` - Not equals operator
  - `testFilterWithAnd()` - AND logical operator
  - `testFilterWithOr()` - OR logical operator
  - `testFilterWithNot()` - NOT logical operator
  - `testFilterWithComplexExpression()` - Combined AND/OR
  - `testFilterWithNullExpression()` - Null filter handling

- **Integration Tests**: [FalkorDBQueryPushdownTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)
  - `testFilterWithLessThan()` - End-to-end test
  - `testFilterWithGreaterThanOrEqual()` - Range tests
  - `testFilterWithStringEquals()` - String comparisons
  - `testFilterWithAnd()` - Logical AND with data
  - `testFilterWithOr()` - Logical OR with data
  - `testFilterWithNot()` - Logical NOT with data
  - `testFilterWithComplexExpression()` - Complex filters
  - `testFilterWithNotEquals()` - Not equals with data

Run the tests:
```bash
# Run all FILTER tests
mvn test -Dtest=*Test#testFilter*

# Run specific test
mvn test -Dtest=SparqlToCypherCompilerTest#testFilterWithAnd
```

## Limitations

1. **Unsupported Expressions**: Some SPARQL filter functions (e.g., `regex()`, `str()`, `bound()`) are not yet supported and will fall back to standard evaluation

2. **Complex Nested Filters**: Deeply nested or complex filter expressions may fall back to standard evaluation

3. **Variable Object Ambiguity**: In multi-triple BGPs, variable objects that aren't used as subjects may cause fallback (use OPTIONAL or single-triple patterns for such cases)

## Related Documentation

- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#filter-expressions) - Full optimization documentation
- [Query Pushdown Examples](../query-pushdown/) - Basic BGP translation
- [OPTIONAL Patterns](../optional-patterns/) - FILTER with OPTIONAL
- [Variable Objects](../variable-objects/) - Variable object optimization

## See Also

- [SparqlToCypherCompiler.java](../../jena-falkordb-adapter/src/main/java/com/falkordb/jena/query/SparqlToCypherCompiler.java) - Compiler implementation
- [FalkorDBOpExecutor.java](../../jena-falkordb-adapter/src/main/java/com/falkordb/jena/query/FalkorDBOpExecutor.java) - Query execution with pushdown
