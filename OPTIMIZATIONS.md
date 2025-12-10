# FalkorDB Jena Adapter Optimizations

This document describes the performance optimizations implemented in the FalkorDB Jena Adapter to improve both read and write operations.

> **📖 Complete Demo Guide**: For hands-on examples with curl commands, Jaeger tracing, and step-by-step setup, see [DEMO.md](DEMO.md)

## Overview

The adapter implements three major optimization strategies:

1. **Batch Writes via Transactions** - Buffers multiple triple operations and flushes them in bulk using Cypher's `UNWIND`
2. **Query Pushdown** - Translates SPARQL Basic Graph Patterns (BGPs) to native Cypher queries
3. **Magic Property** - Allows direct execution of Cypher queries within SPARQL

## Complete Examples

Comprehensive code examples for all optimizations are available in the [`samples/`](samples/) directory:

- **[Batch Writes Examples](samples/batch-writes/)**: Java code, SPARQL queries, sample data, and README
- **[Query Pushdown Examples](samples/query-pushdown/)**: Variable predicates, closed-chain, multi-hop patterns
- **[Variable Objects Examples](samples/variable-objects/)**: Query both properties and relationships
- **[Magic Property Examples](samples/magic-property/)**: Direct Cypher execution patterns

Each example includes complete working code in multiple formats. See [`samples/README.md`](samples/README.md) for details.

## 1. Batch Writes via Transactions

> **Tests**: See [FalkorDBTransactionHandlerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/FalkorDBTransactionHandlerTest.java)  
> **Examples**: See [samples/batch-writes/](samples/batch-writes/)

### The Problem

By default, Jena's `Graph.add(Triple t)` method is "chatty" - it sends one database command per triple. When loading large datasets, this results in thousands of individual network round-trips:

```java
// Without optimization: 1000 triples = 1000 database calls
for (Triple t : triples) {
    graph.add(t);  // Each call hits the database
}
```

### The Solution

The `FalkorDBTransactionHandler` buffers triple operations during a transaction and flushes them in bulk on commit using Cypher's `UNWIND` clause:

```java
// With optimization: 1000 triples = ~1 database call
model.begin(ReadWrite.WRITE);
try {
    for (Triple t : triples) {
        model.add(t);  // Buffered in memory
    }
    model.commit();  // Single bulk operation
} finally {
    model.end();
}
```

### Implementation Details

- **Buffering**: Add and delete operations are collected in Java lists during a transaction
- **Batching**: Operations are grouped by type (literals, types, relationships) and executed in batches of up to 1000
- **UNWIND**: Uses Cypher's `UNWIND` for efficient bulk operations:

```cypher
UNWIND range(0, size($subjects)-1) AS i
WITH $subjects[i] AS subj, $objects[i] AS obj
MERGE (s:Resource {uri: subj})
MERGE (o:Resource {uri: obj})
MERGE (s)-[r:`predicate`]->(o)
```

### Usage

```java
Model model = FalkorDBModelFactory.createModel("myGraph");

// Start a transaction
model.begin(ReadWrite.WRITE);
try {
    // All these adds are buffered
    model.add(statement1);
    model.add(statement2);
    model.add(statement3);
    // ... add many more statements
    
    model.commit();  // Bulk flush to FalkorDB
} catch (Exception e) {
    model.abort();  // Discard buffered operations
} finally {
    model.end();
}
```

### Performance Gains

| Operation | Without Batching | With Batching | Improvement |
|-----------|-----------------|---------------|-------------|
| 100 triples | 100 round trips | 1 round trip | 100x fewer calls |
| 1000 triples | 1000 round trips | 1 round trip | 1000x fewer calls |
| 10000 triples | 10000 round trips | 10 round trips | 1000x fewer calls |

## 2. Query Pushdown (SPARQL to Cypher)

> **Tests**: See [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) and [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java)  
> **Examples**: See [samples/query-pushdown/](samples/query-pushdown/) and [samples/variable-objects/](samples/variable-objects/)

### The Problem

By default, Jena evaluates SPARQL queries using a "triple-by-triple" approach:

```sparql
SELECT ?fof WHERE {
    <http://example.org/alice> <http://example.org/knows> ?friend .
    ?friend <http://example.org/knows> ?fof .
}
```

Gets executed as:
1. `find(Alice, knows, ?friend)` → Returns N triples
2. For each `?friend`, `find(?friend, knows, ?fof)` → N more queries

This results in N+1 database round trips.

### The Solution

The query pushdown mechanism translates SPARQL BGPs to a single Cypher `MATCH` query:

```cypher
MATCH (:Resource {uri: "http://example.org/alice"})
      -[:`http://example.org/knows`]->(:Resource)
      -[:`http://example.org/knows`]->(fof:Resource)
RETURN fof.uri AS fof
```

This executes as a single database operation.

### Implementation Components

1. **SparqlToCypherCompiler**: Translates SPARQL BGPs to Cypher MATCH clauses
2. **FalkorDBOpExecutor**: Custom OpExecutor that intercepts BGP operations
3. **FalkorDBQueryEngineFactory**: Factory to register the custom query engine

### Registration

Query pushdown is **automatically enabled** when the adapter is loaded via Jena's SPI subsystem. The `FalkorDBQueryEngineFactory` is registered during initialization, so all SPARQL queries against FalkorDB models use pushdown by default:

```java
// Query pushdown is automatically enabled - no registration needed!
Model model = FalkorDBModelFactory.createModel("myGraph");
Query query = QueryFactory.create("SELECT ...");
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    // Automatically uses query pushdown for supported patterns
}

// Optionally disable if needed
FalkorDBQueryEngineFactory.unregister();
```

**Note**: In prior versions, explicit registration via `FalkorDBQueryEngineFactory.register()` was required. This is no longer necessary as registration happens automatically during adapter initialization.

### Supported Patterns

Currently, the pushdown optimizer supports:

| Pattern Type | Supported | Example |
|-------------|-----------|---------|
| Concrete subject + predicate + object | ✅ | `<uri1> <pred> <uri2>` |
| rdf:type with concrete type | ✅ | `?s rdf:type <Type>` |
| Concrete literal values | ✅ | `?s <pred> "value"` |
| Variable predicates (single triple) | ✅ | `<uri> ?p ?o` (uses UNION for properties + relationships) |
| Variable objects (single triple) | ✅ | `?s <pred> ?o` (uses UNION for properties + relationships) |
| Closed-chain variable objects | ✅ | `?a <pred> ?b . ?b <pred> ?a` (mutual references) |
| OPTIONAL patterns | ✅ | `OPTIONAL { ?s ?p ?o }` (uses Cypher OPTIONAL MATCH) |
| FILTER expressions | ✅ | `FILTER(?age < 30)` (translates to Cypher WHERE clause) |
| UNION patterns | ✅ | `{ ?s rdf:type <TypeA> } UNION { ?s rdf:type <TypeB> }` (alternative patterns) |

#### Variable Predicate Support

Variable predicates are supported for single-triple patterns. The compiler generates a UNION query
that fetches relationships, node properties, **and types from labels**:

```sparql
# SPARQL: Get all properties of a resource
SELECT ?p ?o WHERE {
    <http://example.org/person/jane> ?p ?o .
}
```

```cypher
# Compiled Cypher (using triple UNION):
# Part 1: Relationships (edges)
MATCH (s:Resource {uri: $p0})-[_r]->(o:Resource)
RETURN s.uri AS s, type(_r) AS p, o.uri AS o
UNION ALL
# Part 2: Properties (node attributes)
MATCH (s:Resource {uri: $p0})
UNWIND keys(s) AS _propKey
WITH s, _propKey WHERE _propKey <> 'uri'
RETURN s.uri AS s, _propKey AS p, s[_propKey] AS o
UNION ALL
# Part 3: Types (node labels as rdf:type)
MATCH (s:Resource {uri: $p0})
UNWIND labels(s) AS _label
WITH s, _label WHERE _label <> 'Resource'
RETURN s.uri AS s, 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type' AS p, _label AS o
```

This three-part UNION ensures that all triple patterns are retrieved, including:
- Relationships between resources (Part 1)
- Literal properties on nodes (Part 2)
- `rdf:type` triples derived from node labels (Part 3)

#### Variable Object Support

> **Tests**: See [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) (`testSingleVariableObjectCompilesWithUnion`, `testConcreteSubjectWithVariableObjectCompilesWithUnion`, `testVariableObjectOptimizationStructure`, `testVariableObjectConcreteSubjectParameters`, `testVariableObjectReturnsCorrectVariables`) and [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java) (`testVariableObjectOptimization*`)

Variable objects are supported for single-triple patterns. When a variable object `?o` could be either a URI (relationship target) or a literal (property value), the compiler generates a UNION query similar to variable predicates:

```sparql
# SPARQL: Get all values of a specific predicate
SELECT ?value WHERE {
    <http://example.org/person/alice> <http://xmlns.com/foaf/0.1/knows> ?value .
}
```

```cypher
# Compiled Cypher (using dual UNION):
# Part 1: Relationships (edges)
MATCH (s:Resource {uri: $p0})-[:`http://xmlns.com/foaf/0.1/knows`]->(o:Resource)
RETURN s.uri AS _s, o.uri AS value
UNION ALL
# Part 2: Properties (node attributes)
MATCH (s:Resource {uri: $p0})
WHERE s.`http://xmlns.com/foaf/0.1/knows` IS NOT NULL
RETURN s.uri AS _s, s.`http://xmlns.com/foaf/0.1/knows` AS value
```

This two-part UNION retrieves both:
- Resource relationships (Part 1): When the predicate connects two nodes via an edge
- Literal properties (Part 2): When the predicate is stored as a node property

**Key Benefits:**
- ✅ **Single query execution**: One database round-trip instead of multiple queries
- ✅ **Handles mixed data**: Automatically retrieves both relationships and properties
- ✅ **Efficient**: Uses native Cypher operations optimized by FalkorDB
- ✅ **Transparent**: Works automatically for single-triple BGPs with variable objects

**Example with Mixed Data:**

```java
// Add both property and relationship values for the same predicate
var alice = model.createResource("http://example.org/person/alice");
var bob = model.createResource("http://example.org/person/bob");
var prop = model.createProperty("http://example.org/value");

// Alice has a literal value
alice.addProperty(prop, "literal value");
// Bob has a resource value  
var resource = model.createResource("http://example.org/resource1");
bob.addProperty(prop, resource);

// Query both - automatically uses UNION optimization
String sparql = """
    SELECT ?s ?o WHERE {
        ?s <http://example.org/value> ?o .
    }
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution solution = results.nextSolution();
        // Will retrieve both Alice->literal and Bob->resource
        System.out.println(solution.get("s") + " -> " + solution.get("o"));
    }
}
```

**Limitations:**
- Only supports single-triple patterns (multi-triple patterns with variable objects fall back to standard evaluation)
- Multi-triple patterns require the object variable to be used as a subject elsewhere (see Closed-Chain Variable Objects)

#### Closed-Chain Variable Objects

When a variable object is also used as a subject in another triple pattern, the compiler
recognizes it as a resource (not a potential literal) and generates a pushdown query:

```sparql
# SPARQL: Find mutual friends
SELECT ?a ?b WHERE {
    ?a <http://example.org/knows> ?b .
    ?b <http://example.org/knows> ?a .
}
```

```cypher
# Compiled Cypher:
MATCH (a:Resource)-[:`http://example.org/knows`]->(b:Resource)
MATCH (b)-[:`http://example.org/knows`]->(a)
RETURN a.uri AS a, b.uri AS b
```

When a pattern cannot be pushed down, the optimizer automatically falls back to standard Jena evaluation.

#### OPTIONAL Patterns

> **Tests**: See [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) (unit tests for OPTIONAL pattern compilation) and [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java) (integration tests for OPTIONAL execution)  
> **Examples**: See [samples/optional-patterns/](samples/optional-patterns/)

OPTIONAL patterns are supported and translated to Cypher OPTIONAL MATCH clauses. This allows returning all matches from the required pattern with NULL values for optional data that doesn't exist, all in a single query.

**The Problem:**

Without OPTIONAL pattern pushdown, SPARQL OPTIONAL requires multiple database queries:

```sparql
# SPARQL: Find all persons with optional email
SELECT ?person ?email WHERE {
    ?person a foaf:Person .
    OPTIONAL { ?person foaf:email ?email }
}
```

Gets executed as:
1. Query 1: `find all persons of type Person` → Returns N persons
2. For each person, Query 2: `find(?person, email, ?email)` → N more queries

This results in N+1 database round trips.

**The Solution:**

The query pushdown mechanism translates SPARQL OPTIONAL patterns to Cypher OPTIONAL MATCH:

```cypher
# Compiled Cypher:
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
OPTIONAL MATCH (person)-[:`http://xmlns.com/foaf/0.1/email`]->(email:Resource)
RETURN person.uri AS person, email.uri AS email
```

This executes as a single database operation, with NULL returned for persons without email.

**Supported OPTIONAL Patterns:**

| Pattern Type | Supported | Example |
|-------------|-----------|---------|
| Basic OPTIONAL relationship | ✅ | `OPTIONAL { ?s foaf:email ?email }` |
| OPTIONAL literal property | ✅ | `OPTIONAL { ?s foaf:age ?age }` |
| Multiple OPTIONAL clauses | ✅ | Multiple separate OPTIONAL blocks |
| OPTIONAL with multiple triples | ✅ | `OPTIONAL { ?s foaf:knows ?f . ?f foaf:name ?n }` |
| Concrete subjects | ✅ | `OPTIONAL { <alice> foaf:email ?email }` |
| FILTER in required part | ✅ | `FILTER(?age < 30)` before OPTIONAL |
| Variable predicates in OPTIONAL | ❌ (fallback) | Not yet supported |

**FILTER Support**: FILTER expressions in the required pattern are translated to Cypher WHERE clauses. Supported operators include comparisons (`<`, `<=`, `>`, `>=`, `=`, `<>`), logical operators (`AND`, `OR`, `NOT`), and work with both literal properties and node variables.

**Example 1: Basic OPTIONAL with Relationship**

```sparql
# SPARQL: All persons with optional email
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?email WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL { ?person foaf:email ?email }
}
```

```cypher
# Generated Cypher:
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
WHERE person.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
OPTIONAL MATCH (person)-[:`http://xmlns.com/foaf/0.1/email`]->(email:Resource)
RETURN person.uri AS person, 
       person.`http://xmlns.com/foaf/0.1/name` AS name, 
       email.uri AS email
```

**Example 2: Multiple OPTIONAL Clauses**

```sparql
# SPARQL: All persons with any available contact info
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?email ?phone WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL { ?person foaf:email ?email }
    OPTIONAL { ?person foaf:phone ?phone }
}
```

```cypher
# Generated Cypher:
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
WHERE person.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
OPTIONAL MATCH (person)-[:`http://xmlns.com/foaf/0.1/email`]->(email:Resource)
OPTIONAL MATCH (person)
WHERE person.`http://xmlns.com/foaf/0.1/phone` IS NOT NULL
RETURN person.uri AS person, 
       person.`http://xmlns.com/foaf/0.1/name` AS name, 
       email.uri AS email, 
       person.`http://xmlns.com/foaf/0.1/phone` AS phone
```

**Example 3: OPTIONAL with Literal Property**

```sparql
# SPARQL: All persons with optional age
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?age WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL { ?person foaf:age ?age }
}
```

```cypher
# Generated Cypher:
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
WHERE person.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
OPTIONAL MATCH (person)
WHERE person.`http://xmlns.com/foaf/0.1/age` IS NOT NULL
RETURN person.uri AS person, 
       person.`http://xmlns.com/foaf/0.1/name` AS name, 
       person.`http://xmlns.com/foaf/0.1/age` AS age
```

**Example 4: OPTIONAL with Multiple Triples**

```sparql
# SPARQL: All persons with optional friend information
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?friend ?friendName WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    OPTIONAL { 
        ?person foaf:knows ?friend .
        ?friend foaf:name ?friendName .
    }
}
```

```cypher
# Generated Cypher:
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
WHERE person.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
OPTIONAL MATCH (person)-[:`http://xmlns.com/foaf/0.1/knows`]->(friend:Resource)
OPTIONAL MATCH (friend)
WHERE friend.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
RETURN person.uri AS person, 
       person.`http://xmlns.com/foaf/0.1/name` AS name, 
       friend.uri AS friend, 
       friend.`http://xmlns.com/foaf/0.1/name` AS friendName
```

**Example 5: OPTIONAL with FILTER**

```sparql
# SPARQL: Young persons (age < 35) with optional email
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?name ?age ?email WHERE {
    ?person a foaf:Person .
    ?person foaf:name ?name .
    ?person foaf:age ?age .
    FILTER(?age < 35)
    OPTIONAL { ?person foaf:email ?email }
}
```

```cypher
# Generated Cypher:
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
WHERE person.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
  AND person.`http://xmlns.com/foaf/0.1/age` IS NOT NULL
WHERE person.`http://xmlns.com/foaf/0.1/age` < 35
OPTIONAL MATCH (person)-[:`http://xmlns.com/foaf/0.1/email`]->(email:Resource)
RETURN person.uri AS person, 
       person.`http://xmlns.com/foaf/0.1/name` AS name, 
       person.`http://xmlns.com/foaf/0.1/age` AS age, 
       email.uri AS email
```

The FILTER is translated to a Cypher WHERE clause and applied on the required pattern before the OPTIONAL MATCH, ensuring only persons under 35 are returned, with their email if available. This maintains the performance benefits of pushdown while applying the filter at the database level.

**Key Benefits:**

- ✅ **Single query execution**: One database round-trip for all data
- ✅ **NULL handling**: Returns NULL for missing optional data (not empty result set)
- ✅ **Native optimization**: Uses Cypher's built-in OPTIONAL MATCH
- ✅ **Transparent**: Works automatically for supported OPTIONAL patterns
- ✅ **Efficient**: Maintains index usage on required patterns

**Java Usage Example:**

```java
// Setup: Create persons with partial information
Model model = FalkorDBModelFactory.createModel("myGraph");

var alice = model.createResource("http://example.org/person/alice");
var bob = model.createResource("http://example.org/person/bob");
var email = model.createProperty("http://xmlns.com/foaf/0.1/email");

alice.addProperty(RDF.type, model.createResource("http://xmlns.com/foaf/0.1/Person"));
alice.addProperty(FOAF.name, "Alice");
alice.addProperty(email, model.createResource("mailto:alice@example.org"));

bob.addProperty(RDF.type, model.createResource("http://xmlns.com/foaf/0.1/Person"));
bob.addProperty(FOAF.name, "Bob");
// Bob has no email

// Query with OPTIONAL - automatically uses pushdown
String sparql = """
    PREFIX foaf: <http://xmlns.com/foaf/0.1/>
    SELECT ?person ?name ?email WHERE {
        ?person a foaf:Person .
        ?person foaf:name ?name .
        OPTIONAL { ?person foaf:email ?email }
    }
    ORDER BY ?name
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution solution = results.nextSolution();
        String name = solution.getLiteral("name").getString();
        // Check if email is bound (not NULL)
        if (solution.contains("email")) {
            String emailValue = solution.getResource("email").getURI();
            System.out.println(name + " - " + emailValue);
        } else {
            System.out.println(name + " - (no email)");
        }
    }
}

// Output:
// Alice - mailto:alice@example.org
// Bob - (no email)
```

**Performance Comparison:**

| Scenario | Without OPTIONAL Pushdown | With OPTIONAL Pushdown | Improvement |
|----------|--------------------------|----------------------|-------------|
| 100 persons, 50 with email | 101 queries (1 + 100) | 1 query | 100x fewer calls |
| Multiple OPTIONAL fields (3) | N * 3 queries | 1 query | 3Nx fewer calls |
| Nested OPTIONAL | N * M queries | 1 query | NMx fewer calls |

**FILTER Support with OPTIONAL:**

FILTER expressions in the required pattern before OPTIONAL clauses are fully supported and translated to Cypher WHERE clauses. Supported operators include:

- **Comparison operators**: `<`, `<=`, `>`, `>=`, `=`, `<>` (not equal)
- **Logical operators**: `AND`, `OR`, `NOT`
- **Operands**: Variables (from literal properties), numeric literals, string literals, boolean literals

The FILTER is applied at the database level before the OPTIONAL MATCH, ensuring optimal performance:

```sparql
# SPARQL with FILTER
SELECT ?person ?name ?email WHERE {
    ?person foaf:name ?name .
    ?person foaf:age ?age .
    FILTER(?age >= 18 AND ?age < 65)
    OPTIONAL { ?person foaf:email ?email }
}
```

```cypher
# Generated Cypher with WHERE clause
MATCH (person:Resource)
WHERE person.`foaf:name` IS NOT NULL 
  AND person.`foaf:age` IS NOT NULL
WHERE (person.`foaf:age` >= 18 AND person.`foaf:age` < 65)
OPTIONAL MATCH (person)-[:email]->(email:Resource)
RETURN person.uri AS person, person.`foaf:name` AS name, email.uri AS email
```

**Limitations:**

- Variable predicates in OPTIONAL patterns not yet supported (will fall back)
- Complex nested OPTIONAL patterns may fall back to standard evaluation
- FILTER clauses within OPTIONAL blocks (not the required part) may not fully push down

**Complete Working Examples:**

See [samples/optional-patterns/](samples/optional-patterns/) for:
- Full Java code with 6 use cases
- SPARQL query patterns with generated Cypher
- Sample data with partial information
- Detailed README with best practices

### Complete Examples

#### Example 1: Query Person's Properties and Relationships

```java
// Setup: Create a person with both properties and relationships
Model model = FalkorDBModelFactory.createModel("myGraph");

var alice = model.createResource("http://example.org/person/alice");
var bob = model.createResource("http://example.org/person/bob");
var knows = model.createProperty("http://xmlns.com/foaf/0.1/knows");

// Alice has a name property (literal)
alice.addProperty(FOAF.name, "Alice");
// Alice knows Bob (relationship)
alice.addProperty(knows, bob);

// Query using variable object optimization
String sparql = """
    PREFIX foaf: <http://xmlns.com/foaf/0.1/>
    SELECT ?value WHERE {
        <http://example.org/person/alice> foaf:knows ?value .
    }
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution solution = results.nextSolution();
        // Will retrieve Bob (relationship target)
        System.out.println("Value: " + solution.get("value"));
    }
}
```

**Generated Cypher:**
```cypher
-- Part 1: Query relationships
MATCH (s:Resource {uri: $p0})-[:`http://xmlns.com/foaf/0.1/knows`]->(o:Resource)
RETURN s.uri AS _s, o.uri AS value
UNION ALL
-- Part 2: Query properties
MATCH (s:Resource {uri: $p0})
WHERE s.`http://xmlns.com/foaf/0.1/knows` IS NOT NULL
RETURN s.uri AS _s, s.`http://xmlns.com/foaf/0.1/knows` AS value
```

#### Example 2: Retrieve Mixed Data Types

```java
// Setup: Different resources with different value types
var person1 = model.createResource("http://example.org/person1");
var person2 = model.createResource("http://example.org/person2");
var valueProp = model.createProperty("http://example.org/hasValue");

// Person1 has a literal value
person1.addProperty(valueProp, "literal string");

// Person2 has a resource value
var resource = model.createResource("http://example.org/resource1");
person2.addProperty(valueProp, resource);

// Query retrieves both types automatically
String sparql = """
    SELECT ?subject ?object WHERE {
        ?subject <http://example.org/hasValue> ?object .
    }
    ORDER BY ?subject
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution solution = results.nextSolution();
        String subject = solution.getResource("subject").getURI();
        
        if (solution.get("object").isLiteral()) {
            String literal = solution.getLiteral("object").getString();
            System.out.println(subject + " has literal: " + literal);
        } else if (solution.get("object").isResource()) {
            String resource = solution.getResource("object").getURI();
            System.out.println(subject + " has resource: " + resource);
        }
    }
}

// Output:
// http://example.org/person1 has literal: literal string
// http://example.org/person2 has resource: http://example.org/resource1
```

#### Example 3: Variable Subject and Object

```java
// Setup: Multiple people with names
var alice = model.createResource("http://example.org/person/alice");
var bob = model.createResource("http://example.org/person/bob");

alice.addProperty(FOAF.name, "Alice");
bob.addProperty(FOAF.name, "Bob");

// Both subject and object are variables - uses variable object optimization
String sparql = """
    PREFIX foaf: <http://xmlns.com/foaf/0.1/>
    SELECT ?person ?name WHERE {
        ?person foaf:name ?name .
    }
    ORDER BY ?name
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution solution = results.nextSolution();
        String person = solution.getResource("person").getURI();
        String name = solution.getLiteral("name").getString();
        System.out.println(person + " is named " + name);
    }
}

// Output:
// http://example.org/person/alice is named Alice
// http://example.org/person/bob is named Bob
```

#### Example 4: Integration with Transactions (Bulk Load + Query)

```java
Model model = FalkorDBModelFactory.createModel("myGraph");

// Bulk load with transaction optimization
model.begin(ReadWrite.WRITE);
try {
    for (int i = 0; i < 100; i++) {
        var person = model.createResource("http://example.org/person/" + i);
        person.addProperty(FOAF.name, "Person " + i);
        
        if (i > 0) {
            var previous = model.createResource("http://example.org/person/" + (i-1));
            person.addProperty(FOAF.knows, previous);
        }
    }
    model.commit();  // Bulk flush using UNWIND
} catch (Exception e) {
    model.abort();
    throw e;
} finally {
    model.end();
}

// Query with variable object optimization
String sparql = """
    PREFIX foaf: <http://xmlns.com/foaf/0.1/>
    SELECT ?person ?value WHERE {
        ?person foaf:knows ?value .
    }
    LIMIT 10
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    int count = 0;
    while (results.hasNext()) {
        results.nextSolution();
        count++;
    }
    System.out.println("Found " + count + " relationships");
}
```

### Performance Gains

| Query Type | Standard SPARQL | With Pushdown | Improvement |
|------------|----------------|---------------|-------------|
| Variable object queries | 2+ queries (property + relationship) | 1 UNION query | 2x fewer round trips |
| Friends of Friends | ~N+1 round trips | 1 round trip | Nx fewer calls |
| 3-hop traversal | ~N² round trips | 1 round trip | N²x fewer calls |
| Type queries | Multiple queries | 1 query | Significant |
| Mixed data retrieval | Separate queries for each type | Single UNION query | 2x-3x fewer calls |
| OPTIONAL patterns | N+1 queries (required + N optional) | 1 query | Nx fewer calls |
| Multiple OPTIONAL fields | N * M queries | 1 query | NMx fewer calls |

## 3. Magic Property (Direct Cypher Execution)

> **Tests**: See [CypherQueryFuncTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/pfunction/CypherQueryFuncTest.java) and [MagicPropertyDocExamplesTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/pfunction/MagicPropertyDocExamplesTest.java)  
> **Examples**: See [samples/magic-property/](samples/magic-property/)  
> **Full Documentation**: See [MAGIC_PROPERTY.md](MAGIC_PROPERTY.md)

For maximum control, you can execute native Cypher queries directly within SPARQL using the magic property `falkor:cypher`:

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

See [MAGIC_PROPERTY.md](MAGIC_PROPERTY.md) for detailed documentation.

## OpenTelemetry Tracing

All optimization components are fully instrumented with comprehensive OpenTelemetry tracing:

### Traced Components

1. **SparqlToCypherCompiler** - Compilation operations
   - Span: `SparqlToCypherCompiler.translate` - Basic BGP compilation
   - Span: `SparqlToCypherCompiler.translateWithFilter` - FILTER compilation
   - Span: `SparqlToCypherCompiler.translateWithOptional` - OPTIONAL compilation
   - Shows: Input SPARQL patterns, output Cypher queries, optimization type, parameters

2. **FalkorDBOpExecutor** - Query execution
   - Span: `FalkorDBOpExecutor.execute` - BGP execution
   - Span: `FalkorDBOpExecutor.executeFilter` - FILTER execution
   - Span: `FalkorDBOpExecutor.executeOptional` - OPTIONAL execution
   - Shows: Triple counts, Cypher queries, fallback decisions, result counts

3. **FalkorDBTransactionHandler** - Batch write operations
   - Span: `FalkorDBTransaction.commit` - Transaction commit with bulk flush
   - Span: `FalkorDBTransaction.flushLiteralBatch` - Literal property batching
   - Span: `FalkorDBTransaction.flushTypeBatch` - Type label batching
   - Span: `FalkorDBTransaction.flushRelationshipBatch` - Relationship batching
   - Shows: Batch sizes, Cypher UNWIND queries, triple counts

4. **CypherQueryFunc** - Magic property execution
   - Span: `CypherQueryFunc.execute` - Direct Cypher query execution
   - Shows: Cypher queries, result counts, variable mappings

### Trace Attributes

Each optimization span includes:
- **Input**: SPARQL patterns (truncated for readability)
- **Output**: Complete Cypher queries
- **Type**: Optimization type (BGP_PUSHDOWN, FILTER_PUSHDOWN, OPTIONAL_PUSHDOWN, etc.)
- **Timing**: Automatic duration tracking via span lifecycle
- **Metadata**: Triple counts, parameter counts, variable counts, batch sizes

### Enabling Tracing

Enable tracing to visualize performance in Jaeger:

```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

Then view traces at `http://localhost:16686`.

See [TRACING.md](TRACING.md) for complete documentation on trace attributes and visualization.

## Optimizations with Inference Models (InfGraph)

When using Jena's inference/reasoning capabilities with `InfModel` (which wraps the base model in an `InfGraph`), optimization behavior is different to preserve inference semantics:

### Query Pushdown with Inference

The behavior of query pushdown with inference depends on the **chaining mode**:

#### Forward Chaining (Eager Inference) ✅ Pushdown Works!

With forward chaining, inferred triples are **materialized immediately** into the base FalkorDB graph when data is inserted. This means:

1. **Inferred triples physically exist** in FalkorDB alongside base triples
2. **Queries can use pushdown** on both base and inferred triples
3. **Full optimization benefits** - aggregations, filters, and spatial queries all push down

```java
// Forward chaining with config-falkordb.ttl
// Inferred triples are materialized into FalkorDB immediately
// Query pushdown works on all triples (base + inferred)

// When you insert:
// :abraham :father_of :isaac
// :isaac :father_of :jacob

// Forward rules immediately materialize:
// :abraham :grandfather_of :jacob  ← This triple exists in FalkorDB!

// Queries use pushdown on materialized triples
String query = "SELECT ?gf ?gc WHERE { ?gf :grandfather_of ?gc }";
// ✅ Pushes down to Cypher: MATCH (gf)-[:grandfather_of]->(gc) RETURN gf, gc
```

**When to use:** Production deployments where query performance is critical and the inference closure is manageable.

#### Backward Chaining (Lazy Inference) ❌ Pushdown Disabled

With backward chaining, inferred triples are computed **on-demand** during query evaluation. Query pushdown is intentionally disabled because:

1. **Inferred triples don't exist** in FalkorDB - they're derived at query time
2. **Pushdown would miss inferences** - would only see base triples
3. **Standard Jena evaluation required** to apply backward chaining rules

```java
// Backward chaining (NOT supported in current config)
// Inferred triples computed on-demand during queries
// Query pushdown disabled for InfModel

Model baseModel = FalkorDBModelFactory.createModel("myGraph");
Reasoner reasoner = new GenericRuleReasoner(backwardRules);
InfModel infModel = ModelFactory.createInfModel(reasoner, baseModel);

// Queries against infModel use standard Jena evaluation (no pushdown)
```

**Note:** The current `config-falkordb.ttl` uses **forward chaining only**, so all queries benefit from pushdown on materialized triples.

### Optimization Support by Inference Mode

| Optimization | Forward Chaining<br/>(config-falkordb.ttl) | Backward Chaining<br/>(Not configured) |
|-------------|------------------|-------|
| **Query Pushdown** | ✅ **Fully Enabled** | ❌ Disabled |
| **Aggregation Pushdown** | ✅ **Fully Enabled** | ❌ Disabled |
| **Spatial Query Pushdown** | ✅ **Fully Enabled** | ❌ Disabled |
| **Transaction Batching** | ✅ Enabled | ✅ Enabled |
| **Magic Property (`falkor:cypher`)** | ✅ Enabled | ✅ Enabled |
| **Automatic Indexing** | ✅ Enabled | ✅ Enabled |

### Example: Query Pushdown with Forward Inference

With the three-layer `config-falkordb.ttl`, queries on inferred triples use pushdown:

```java
// Using Fuseki with config-falkordb.ttl (forward chaining)
String sparqlEndpoint = "http://localhost:3330/falkor/query";

// Query for grandfather relationships (inferred triples!)
String query = """
    PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
    SELECT ?grandfather ?grandchild
    WHERE {
        ?grandfather ff:grandfather_of ?grandchild .
    }
    """;

// ✅ This query uses pushdown!
// Compiles to: MATCH (gf)-[:grandfather_of]->(gc) RETURN gf.uri AS grandfather, gc.uri AS grandchild
try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
        .query(query).build()) {
    ResultSet results = qexec.execSelect();
    // Results include all materialized grandfather relationships
}
```

### Example: Aggregation Pushdown on Inferred Triples

```java
// Count grandfather relationships (uses aggregation pushdown!)
String countQuery = """
    PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
    SELECT (COUNT(?grandchild) AS ?count)
    WHERE {
        ?grandfather ff:grandfather_of ?grandchild .
    }
    """;

// ✅ Pushes aggregation to Cypher:
// MATCH (gf)-[:grandfather_of]->(gc) RETURN count(gc) AS count
```

### Example: Spatial Query with Inferred Data

```java
// GeoSPARQL query on data with forward inference
String spatialQuery = """
    PREFIX geo: <http://www.opengis.net/ont/geosparql#>
    PREFIX ex: <http://example.org/>
    SELECT ?person ?location
    WHERE {
        ex:alice ex:relative ?person .  # Inferred relationship
        ?person geo:hasGeometry/geo:asWKT ?location .  # Spatial data
    }
    """;

// ✅ Spatial predicates push down to FalkorDB
// Both inferred relatives and spatial data use optimized queries
```

### Example: Direct Cypher with Inference

For performance-critical queries on the base data, use the magic property:

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

# This works with InfModel - accesses raw FalkorDB graph
SELECT ?person (COUNT(?friend) AS ?friendCount) WHERE {
    (?person ?friendCount) falkor:cypher '''
        MATCH (p:Resource)-[:`http://example.org/knows`]->(:Resource)
        RETURN p.uri AS person, count(*) AS friendCount
    '''
}
```

The magic property unwraps the InfGraph to access the underlying FalkorDB graph, allowing direct Cypher execution.

### Performance Recommendations for Inference

1. **Use selective inference**: Apply rules only to relevant subsets of data
2. **Cache inferred models**: Inference is compute-intensive; cache results when possible
3. **Use magic property for base data**: When you need raw performance on base triples
4. **Separate base and inferred queries**: Query base data with magic property, inferred data with SPARQL
5. **Monitor with tracing**: Use OpenTelemetry to identify slow inference operations

### Testing

Inference integration is tested in:
- [GrandfatherInferenceSystemTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/GrandfatherInferenceSystemTest.java) - Forward chaining inference
- [GrandfatherInferenceSystemTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/GrandfatherInferenceSystemTest.java) - Forward chaining inference
- [GeoSPARQLPOCSystemTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/GeoSPARQLPOCSystemTest.java) - GeoSPARQL spatial queries

## Best Practices

### For Writes

1. **Use transactions for bulk loads**: Always wrap bulk operations in transactions
2. **Batch size**: The adapter uses batches of 1000 operations internally
3. **Error handling**: Use try-finally to ensure `model.end()` is called

### For Reads

1. **Use concrete values when possible**: Queries with concrete URIs can be pushed down
2. **Use magic property for complex traversals**: When you need path patterns or aggregations
3. **Monitor with tracing**: Use OpenTelemetry to identify slow queries

### Example: Optimal Bulk Load

```java
Model model = FalkorDBModelFactory.createModel("myGraph");

// Load RDF file efficiently
model.begin(ReadWrite.WRITE);
try {
    RDFDataMgr.read(model, "large-dataset.ttl");
    model.commit();
} catch (Exception e) {
    model.abort();
    throw e;
} finally {
    model.end();
}
```

### Example: Optimal Query

```java
// Query pushdown is automatically enabled - no registration needed!

// For complex graph traversals, use magic property
String sparql = """
    PREFIX falkor: <http://falkordb.com/jena#>
    SELECT ?person ?connections WHERE {
        (?person ?connections) falkor:cypher '''
            MATCH (p:Resource)-[r:`http://example.org/knows`]->(:Resource)
            RETURN p.uri AS person, count(r) AS connections
            ORDER BY connections DESC
            LIMIT 10
        '''
    }
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    // Process results
}
```

## 4. Geospatial Query Pushdown

> **Status**: ✅ **IMPLEMENTED**  
> **Tests**: See [GeoSPARQLToCypherTranslatorTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/GeoSPARQLToCypherTranslatorTest.java) and [FalkorDBGeospatialPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBGeospatialPushdownTest.java)  
> **Examples**: See [samples/geospatial-pushdown/](samples/geospatial-pushdown/)  
> **Full Documentation**: See [GEOSPATIAL_PUSHDOWN.md](GEOSPATIAL_PUSHDOWN.md)

### The Problem

GeoSPARQL queries typically require:
1. Fetching all spatial data from the database
2. Parsing WKT geometries on the client
3. Performing spatial computations in memory
4. Filtering results client-side

This results in high data transfer and slow spatial queries.

### The Solution

The geospatial pushdown mechanism translates GeoSPARQL functions to FalkorDB's native `point()` and `distance()` functions:

**GeoSPARQL Query:**
```sparql
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
PREFIX ex: <http://example.org/>

SELECT ?city ?distance WHERE {
  ?city ex:latitude ?lat .
  ?city ex:longitude ?lon .
  FILTER(?lat >= 51.0 && ?lat <= 52.0)
  FILTER(?lon >= -1.0 && ?lon <= 0.0)
}
```

**Generated Cypher:**
```cypher
MATCH (city:Resource)
WHERE city.`http://example.org/latitude` IS NOT NULL
  AND city.`http://example.org/longitude` IS NOT NULL
  AND city.`http://example.org/latitude` >= 51.0
  AND city.`http://example.org/latitude` <= 52.0
  AND city.`http://example.org/longitude` >= -1.0
  AND city.`http://example.org/longitude` <= 0.0
RETURN city.uri AS city,
       city.`http://example.org/latitude` AS lat,
       city.`http://example.org/longitude` AS lon
```

### Supported GeoSPARQL Functions

| GeoSPARQL Function | FalkorDB Translation | Description |
|--------------------|---------------------|-------------|
| `geof:distance` | `distance(point1, point2)` | Calculate distance between two points (meters) |
| `geof:sfWithin` | Distance-based range check | Check if a point is within a region |
| `geof:sfContains` | Spatial containment logic | Check if a region contains a point |
| `geof:sfIntersects` | Spatial intersection logic | Check if two geometries intersect |

### Supported Geometry Types

- ✅ **POINT** - Full support with latitude/longitude
- ⚠️ **POLYGON** - Partial support (bounding box approximation)
- ❌ **LINESTRING** - Planned for future release
- ❌ **MULTIPOINT** - Planned for future release

### Performance Gains

| Query Type | Without Pushdown | With Pushdown | Improvement |
|------------|-----------------|---------------|-------------|
| Distance calculation (100 points) | 100 queries + computation | 1 query | 100x fewer calls |
| Bounding box query (1000 points) | All data fetched + filtering | Database filtering | Minimal data transfer |
| Nearby locations (radius search) | Client-side distance calc | Database-side distance | Native optimization |

### Usage Example

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.query.*;

Model model = FalkorDBModelFactory.createModel("geo_graph");

// Store locations with coordinates
Resource london = model.createResource("http://example.org/london");
london.addProperty(model.createProperty("http://example.org/name"), "London");
london.addProperty(model.createProperty("http://example.org/latitude"), 
    model.createTypedLiteral(51.5074));
london.addProperty(model.createProperty("http://example.org/longitude"), 
    model.createTypedLiteral(-0.1278));

Resource paris = model.createResource("http://example.org/paris");
paris.addProperty(model.createProperty("http://example.org/name"), "Paris");
paris.addProperty(model.createProperty("http://example.org/latitude"), 
    model.createTypedLiteral(48.8566));
paris.addProperty(model.createProperty("http://example.org/longitude"), 
    model.createTypedLiteral(2.3522));

// Query locations within bounding box
String sparql = """
    PREFIX ex: <http://example.org/>
    SELECT ?name ?lat ?lon WHERE {
      ?loc ex:name ?name .
      ?loc ex:latitude ?lat .
      ?loc ex:longitude ?lon .
      FILTER(?lat >= 48.0 && ?lat <= 52.0)
      FILTER(?lon >= -1.0 && ?lon <= 3.0)
    }
    ORDER BY ?name
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution sol = results.next();
        System.out.println(sol.getLiteral("name").getString() + " at " +
                          sol.getLiteral("lat").getDouble() + ", " +
                          sol.getLiteral("lon").getDouble());
    }
}
```

### OpenTelemetry Tracing

Geospatial operations are fully instrumented:

- **Span**: `GeoSPARQLToCypherTranslator.translateGeoFunction`
- **Attributes**: 
  - `falkordb.geospatial.function` - Function name (distance, sfWithin, etc.)
  - `falkordb.geospatial.geometry_type` - Geometry type (POINT, POLYGON, etc.)
  - `falkordb.cypher.expression` - Generated Cypher expression

### WKT Parsing

The adapter supports parsing WKT (Well-Known Text) geometries:

```java
// Parse POINT geometry
String wkt = "POINT(-0.1278 51.5074)";
Double lat = GeoSPARQLToCypherTranslator.extractLatitude(wkt);  // 51.5074
Double lon = GeoSPARQLToCypherTranslator.extractLongitude(wkt); // -0.1278

// Generate Cypher point() expression
Map<String, Object> params = new HashMap<>();
String pointExpr = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "geo", params);
// Returns: point({latitude: $geo_lat, longitude: $geo_lon})
// params: {geo_lat: 51.5074, geo_lon: -0.1278}
```

### Complete Documentation

For comprehensive documentation including:
- FalkorDB geospatial capabilities
- All supported functions and geometry types
- Data formats (Turtle, JSON-LD, RDF/XML)
- Advanced usage with magic property
- Performance comparisons
- Best practices

See **[GEOSPATIAL_PUSHDOWN.md](GEOSPATIAL_PUSHDOWN.md)**

## Future Improvements

The query pushdown can be extended to support additional SPARQL patterns. Below are implementation suggestions for each:

### OPTIONAL Patterns

> **Status**: ✅ **IMPLEMENTED**  
> **Tests**: See [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) (`testOptional*` methods) and [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java) (`testOptional*` methods)  
> **Examples**: See [samples/optional-patterns/](samples/optional-patterns/)  
> **Documentation**: See [OPTIONAL Patterns section](#optional-patterns) above for complete documentation

SPARQL `OPTIONAL` patterns are now automatically translated to Cypher `OPTIONAL MATCH` clauses. This allows returning all matches from the required pattern with NULL values for optional data, all in a single database query.

**Key Features:**
- ✅ Single query execution (no N+1 queries)
- ✅ NULL handling for missing optional data
- ✅ Multiple OPTIONAL clauses supported
- ✅ FILTER expressions in required patterns
- ✅ Literal properties and relationships
- ✅ Complete test coverage

**Implementation:**
- `FalkorDBOpExecutor.java`: `execute(OpLeftJoin, QueryIterator)` method intercepts OPTIONAL operations
- `SparqlToCypherCompiler.java`: `translateWithOptional()` method generates `OPTIONAL MATCH` clauses

### FILTER Expressions

> **Status**: ✅ **IMPLEMENTED**  
> **Tests**: See [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) (`testFilterWith*` methods) and [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java) (`testFilterWith*` methods)  
> **Examples**: See [samples/filter-expressions/](samples/filter-expressions/)

SPARQL `FILTER` expressions are now automatically translated to Cypher `WHERE` clauses, eliminating client-side filtering and reducing data transfer.

**Supported Operators:**

| SPARQL | Cypher | Example |
|--------|--------|---------|
| `FILTER(?x < 10)` | `WHERE x < 10` | Less than |
| `FILTER(?x <= 10)` | `WHERE x <= 10` | Less than or equal |
| `FILTER(?x > 10)` | `WHERE x > 10` | Greater than |
| `FILTER(?x >= 10)` | `WHERE x >= 10` | Greater than or equal |
| `FILTER(?x = "value")` | `WHERE x = 'value'` | Equals |
| `FILTER(?x != "value")` | `WHERE x <> 'value'` | Not equals |
| `FILTER(?x > 10 && ?x < 20)` | `WHERE (x > 10 AND x < 20)` | Logical AND |
| `FILTER(?x < 10 || ?x > 20)` | `WHERE (x < 10 OR x > 20)` | Logical OR |
| `FILTER(! (?x < 10))` | `WHERE NOT (x < 10)` | Logical NOT |

**Example:**

```sparql
# SPARQL with FILTER
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?age WHERE {
    ?person foaf:age ?age .
    FILTER(?age >= 18 && ?age < 65)
}
```

```cypher
# Generated Cypher with WHERE clause
MATCH (person:Resource)
WHERE person.`http://xmlns.com/foaf/0.1/age` IS NOT NULL
  AND (person.`http://xmlns.com/foaf/0.1/age` >= 18 
   AND person.`http://xmlns.com/foaf/0.1/age` < 65)
RETURN person.uri AS person, 
       person.`http://xmlns.com/foaf/0.1/age` AS age
```

**FILTER with UNION Queries:**

When a BGP uses variable object optimization (resulting in UNION), FILTER is automatically applied to each UNION branch:

```cypher
# UNION query with FILTER on each branch
MATCH (person:Resource)-[:`foaf:age`]->(age:Resource)
WHERE age.uri >= 18 
  AND age.uri < 65
RETURN person.uri AS person, age.uri AS age
UNION ALL
MATCH (person:Resource)
WHERE person.`foaf:age` IS NOT NULL
  AND person.`foaf:age` >= 18 
  AND person.`foaf:age` < 65
RETURN person.uri AS person, person.`foaf:age` AS age
```

**Performance Benefits:**
- ✅ Eliminates client-side filtering
- ✅ Reduces data transfer (only matching rows returned)
- ✅ Enables use of database indexes
- ✅ Single query execution (no post-processing)

**Limitations:**
- Some SPARQL filter functions (`regex()`, `str()`, `bound()`, `isURI()`) not yet supported
- Complex nested filters may fall back to standard evaluation

**Implementation:**
- `FalkorDBOpExecutor.java`: Intercepts `OpFilter` operations
- `SparqlToCypherCompiler.java`: `translateWithFilter()` method converts expressions to Cypher WHERE

### UNION Patterns

> **Status**: ✅ **IMPLEMENTED**  
> **Tests**: See [SparqlToCypherCompilerTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/SparqlToCypherCompilerTest.java) (`testUnion*` methods) and [FalkorDBQueryPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBQueryPushdownTest.java) (`testUnion*` methods)  
> **Examples**: See [samples/union-patterns/](samples/union-patterns/)  
> **Documentation**: See [UNION Patterns section](#union-patterns) below for complete documentation

SPARQL `UNION` patterns are now automatically translated to Cypher `UNION` queries. This allows combining results from alternative query patterns in a single database query, avoiding multiple round trips.

**The Problem:**

Without UNION pattern pushdown, SPARQL UNION requires executing each branch separately and combining results on the client side:

```sparql
# SPARQL: Find all people who are either students or teachers
SELECT ?person WHERE {
    { ?person rdf:type foaf:Student }
    UNION
    { ?person rdf:type foaf:Teacher }
}
```

Gets executed as:
1. Query 1: `find all persons of type Student` → Returns N students
2. Query 2: `find all persons of type Teacher` → Returns M teachers
3. Client combines results

This results in 2 database round trips plus client-side merging.

**The Solution:**

The query pushdown mechanism translates SPARQL UNION patterns to a single Cypher `UNION` query:

```cypher
# Compiled Cypher:
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Student`)
RETURN person.uri AS person
UNION
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Teacher`)
RETURN person.uri AS person
```

This executes as a single database operation, with FalkorDB handling the union internally.

**Supported UNION Patterns:**

| Pattern Type | Supported | Example |
|-------------|-----------|---------|
| Type patterns | ✅ | `{ ?s rdf:type <TypeA> } UNION { ?s rdf:type <TypeB> }` |
| Relationship patterns | ✅ | `{ ?s foaf:knows ?o } UNION { ?s foaf:worksWith ?o }` |
| Property patterns | ✅ | `{ ?s foaf:email ?v } UNION { ?s foaf:phone ?v }` |
| Concrete subjects | ✅ | `{ <alice> foaf:knows ?f } UNION { <bob> foaf:knows ?f }` |
| Multi-triple patterns | ✅ | `{ ?s rdf:type <Student> . ?s foaf:age 20 } UNION { ?s rdf:type <Teacher> . ?s foaf:age 30 }` |
| Nested UNION | ✅ | `{ ... } UNION { { ... } UNION { ... } }` |
| UNION with variable predicates | ❌ (fallback) | Requires each branch to avoid variable predicates |
| UNION with unsupported patterns | ❌ (fallback) | Falls back if any branch can't compile |

**Example 1: Basic Type UNION**

```sparql
# SPARQL with UNION
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person WHERE {
    { ?person rdf:type foaf:Student }
    UNION
    { ?person rdf:type foaf:Teacher }
}
```

```cypher
# Generated Cypher with UNION
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Student`)
RETURN person.uri AS person
UNION
MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Teacher`)
RETURN person.uri AS person
```

**Example 2: UNION with Relationships**

```sparql
# SPARQL: Find all connections (friends or colleagues)
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

SELECT ?person ?connection WHERE {
    { ?person foaf:knows ?connection }
    UNION
    { ?person ex:worksWith ?connection }
}
```

```cypher
# Generated Cypher
MATCH (person:Resource)-[:`http://xmlns.com/foaf/0.1/knows`]->(connection:Resource)
RETURN person.uri AS person, connection.uri AS connection
UNION
MATCH (person:Resource)-[:`http://example.org/worksWith`]->(connection:Resource)
RETURN person.uri AS person, connection.uri AS connection
```

**Example 3: UNION with Concrete Subjects**

```sparql
# SPARQL: Get friends of either Alice or Bob
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?friend WHERE {
    { <http://example.org/alice> foaf:knows ?friend }
    UNION
    { <http://example.org/bob> foaf:knows ?friend }
}
```

```cypher
# Generated Cypher (parameterized)
MATCH (s:Resource {uri: $p0})-[:`http://xmlns.com/foaf/0.1/knows`]->(friend:Resource)
RETURN friend.uri AS friend
UNION
MATCH (s:Resource {uri: $p1})-[:`http://xmlns.com/foaf/0.1/knows`]->(friend:Resource)
RETURN friend.uri AS friend

# Parameters: {p0: "http://example.org/alice", p1: "http://example.org/bob"}
```

**Example 4: UNION with Multi-Triple Patterns**

```sparql
# SPARQL: Find all students aged 20 or teachers aged 30
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

SELECT ?person WHERE {
    { ?person rdf:type ex:Student . ?person foaf:age 20 }
    UNION
    { ?person rdf:type ex:Teacher . ?person foaf:age 30 }
}
```

```cypher
# Generated Cypher
MATCH (person:Resource:`http://example.org/Student`)
WHERE person.`http://xmlns.com/foaf/0.1/age` IS NOT NULL
  AND person.`http://xmlns.com/foaf/0.1/age` = $p0
RETURN person.uri AS person
UNION
MATCH (person:Resource:`http://example.org/Teacher`)
WHERE person.`http://xmlns.com/foaf/0.1/age` IS NOT NULL
  AND person.`http://xmlns.com/foaf/0.1/age` = $p1
RETURN person.uri AS person

# Parameters: {p0: 20, p1: 30}
```

**Example 5: UNION with Property Values**

```sparql
# SPARQL: Find any contact info (email or phone)
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?person ?contact WHERE {
    { ?person foaf:email ?contact }
    UNION
    { ?person foaf:phone ?contact }
}
```

```cypher
# Generated Cypher
MATCH (person:Resource)
WHERE person.`http://xmlns.com/foaf/0.1/email` IS NOT NULL
RETURN person.uri AS person, person.`http://xmlns.com/foaf/0.1/email` AS contact
UNION
MATCH (person:Resource)
WHERE person.`http://xmlns.com/foaf/0.1/phone` IS NOT NULL
RETURN person.uri AS person, person.`http://xmlns.com/foaf/0.1/phone` AS contact
```

**Key Benefits:**

- ✅ **Single query execution**: One database round-trip instead of N queries
- ✅ **Native FalkorDB optimization**: Database handles union efficiently
- ✅ **Handles overlapping results**: Returns all matches (duplicates possible without DISTINCT)
- ✅ **Transparent**: Works automatically for supported UNION patterns
- ✅ **Parameter handling**: Automatically renames conflicting parameters between branches

**Performance Comparison:**

| Scenario | Without UNION Pushdown | With UNION Pushdown | Improvement |
|----------|----------------------|---------------------|-------------|
| 2 alternative type queries | 2 queries | 1 query | 2x fewer calls |
| N alternative patterns | N queries | 1 query | Nx fewer calls |
| UNION with relationships | N queries + merge | 1 query | Nx fewer calls |
| Nested UNION (3 branches) | 3 queries + merge | 1 query | 3x fewer calls |

**Java Usage Example:**

```java
// Setup: Create people with different types
Model model = FalkorDBModelFactory.createModel("myGraph");

var alice = model.createResource("http://example.org/alice");
var bob = model.createResource("http://example.org/bob");
var charlie = model.createResource("http://example.org/charlie");

var studentType = model.createResource("http://example.org/Student");
var teacherType = model.createResource("http://example.org/Teacher");

alice.addProperty(RDF.type, studentType);
bob.addProperty(RDF.type, teacherType);
charlie.addProperty(RDF.type, studentType);

// Query with UNION - automatically uses pushdown
String sparql = """
    PREFIX ex: <http://example.org/>
    SELECT ?person WHERE {
        { ?person a ex:Student }
        UNION
        { ?person a ex:Teacher }
    }
    ORDER BY ?person
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution solution = results.nextSolution();
        String person = solution.getResource("person").getURI();
        System.out.println("Person: " + person);
    }
}

// Output:
// Person: http://example.org/alice
// Person: http://example.org/bob
// Person: http://example.org/charlie
```

**Limitations:**

The UNION pattern optimization has the following limitations:

1. **Variable predicates not supported**: Each UNION branch must have concrete predicates. Patterns like `{ ?s ?p ?o } UNION { ?s ?p2 ?o2 }` will fall back to standard evaluation.

2. **Existing BGP limitations apply**: Each UNION branch is compiled independently, so existing limitations for BGPs apply to each branch:
   - Multi-triple patterns with ambiguous variable objects require the object to be used as a subject
   - Complex patterns that can't be compiled individually will cause the entire UNION to fall back

3. **DISTINCT not automatic**: SPARQL UNION doesn't eliminate duplicates by default. If the same result appears in multiple branches, it will be returned multiple times. Use `SELECT DISTINCT` if needed:
   ```sparql
   SELECT DISTINCT ?person WHERE {
       { ?person rdf:type ex:Student }
       UNION
       { ?person rdf:type ex:Teacher }
   }
   ```

4. **No MINUS support yet**: SPARQL MINUS (set difference) is not yet optimized and will fall back to standard evaluation.

5. **Parameter conflicts handled but may affect readability**: When both branches use the same parameter names (e.g., for URIs), the compiler renames parameters in the right branch (e.g., `$p0` becomes `$p0_r`). This is transparent but may make debugging more complex.

6. **Fallback on compilation failure**: If any branch of the UNION cannot be compiled to Cypher, the entire UNION operation falls back to standard Jena evaluation. Check logs for "UNION pushdown failed, falling back" messages.

**When Pushdown Fails:**

The optimizer will fall back to standard Jena evaluation in these cases:

- One or both branches are not BGPs (e.g., contain FILTER, OPTIONAL)
- One or both branches contain unsupported patterns
- Any branch contains variable predicates
- Compilation errors occur

**Example of Fallback:**

```sparql
# This will fall back because of variable predicate
SELECT ?s ?p ?o WHERE {
    { ?s ?p ?o }  # Variable predicate - not supported
    UNION
    { ?s foaf:name ?o }
}
```

**Implementation:**
- `FalkorDBOpExecutor.java`: `execute(OpUnion, QueryIterator)` method intercepts UNION operations
- `SparqlToCypherCompiler.java`: `translateUnion()` method generates Cypher UNION queries
- Automatic parameter renaming to avoid conflicts between branches
- Full OpenTelemetry tracing support with span `FalkorDBOpExecutor.executeUnion`

### Aggregations

> **Status**: ✅ **IMPLEMENTED**  
> **Tests**: See [AggregationToCypherTranslatorTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/AggregationToCypherTranslatorTest.java) and [FalkorDBAggregationPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBAggregationPushdownTest.java)  
> **Examples**: See [samples/aggregations/](samples/aggregations/)  
> **Documentation**: See [Aggregations section](#aggregations-1) below for complete documentation

SPARQL aggregation queries (GROUP BY with COUNT, SUM, AVG, MIN, MAX, etc.) are now automatically translated to native Cypher aggregation queries, enabling database-side computation and significantly reducing data transfer.

**The Problem:**

Without aggregation pushdown, SPARQL aggregations require:
1. Fetching all matching triples from the database
2. Performing grouping and aggregation on the client side
3. Processing potentially large result sets in memory

This results in high network traffic and slow query execution.

**The Solution:**

The query pushdown mechanism translates SPARQL aggregations to Cypher:

```sparql
# SPARQL
SELECT ?type (COUNT(?person) AS ?count) WHERE {
    ?person rdf:type ?type
}
GROUP BY ?type
```

```cypher
# Generated Cypher
MATCH (person:Resource)
UNWIND labels(person) AS type
WHERE type <> 'Resource'
RETURN type, count(person) AS count
```

**Supported Aggregations:**

| SPARQL Aggregation | Cypher Aggregation | Example |
|--------------------|-------------------|---------|
| `COUNT(?x)` | `count(x)` | Count non-null values |
| `COUNT(DISTINCT ?x)` | `count(DISTINCT x)` | Count unique values |
| `COUNT(*)` | `count(*)` | Count all rows |
| `SUM(?x)` | `sum(x)` | Sum of numeric values |
| `SUM(DISTINCT ?x)` | `sum(DISTINCT x)` | Sum of unique values |
| `AVG(?x)` | `avg(x)` | Average of values |
| `AVG(DISTINCT ?x)` | `avg(DISTINCT x)` | Average of unique values |
| `MIN(?x)` | `min(x)` | Minimum value |
| `MAX(?x)` | `max(x)` | Maximum value |
| `GROUP BY ?g` | Implicit grouping in RETURN | Group by variable |

**Example 1: COUNT with GROUP BY**

```sparql
# SPARQL: Count entities by type
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?type (COUNT(?entity) AS ?count)
WHERE {
    ?entity rdf:type ?type .
}
GROUP BY ?type
ORDER BY DESC(?count)
```

```cypher
# Generated Cypher
MATCH (entity:Resource)
UNWIND labels(entity) AS type
WHERE type <> 'Resource'
RETURN type, count(entity) AS count
ORDER BY count DESC
```

**Example 2: Multiple Aggregations**

```sparql
# SPARQL: Get comprehensive statistics
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

```cypher
# Generated Cypher
MATCH (item:Resource)
WHERE item.`http://example.org/price` IS NOT NULL
RETURN 
    count(item) AS count,
    sum(item.`http://example.org/price`) AS total,
    avg(item.`http://example.org/price`) AS avgPrice,
    min(item.`http://example.org/price`) AS minPrice,
    max(item.`http://example.org/price`) AS maxPrice
```

**Example 3: AVG by Group**

```sparql
# SPARQL: Average age by person type
PREFIX ex: <http://example.org/>

SELECT ?type (AVG(?age) AS ?avgAge)
WHERE {
    ?person rdf:type ?type .
    ?person ex:age ?age .
}
GROUP BY ?type
```

```cypher
# Generated Cypher
MATCH (person:Resource)
WHERE person.`http://example.org/age` IS NOT NULL
UNWIND labels(person) AS type
WHERE type <> 'Resource'
RETURN type, avg(person.`http://example.org/age`) AS avgAge
```

**Key Benefits:**

- ✅ **Single query execution**: One database round-trip instead of fetching all data
- ✅ **Database-side computation**: Leverages FalkorDB's native aggregation functions
- ✅ **Reduced data transfer**: Returns only aggregated results, not all matching triples
- ✅ **Better performance**: For 10,000 entities grouped into 50 types, reduces data transfer by 200x
- ✅ **Transparent**: Works automatically for supported aggregation patterns

**Performance Comparison:**

| Scenario | Without Pushdown | With Pushdown | Improvement |
|----------|-----------------|---------------|-------------|
| Count 10K entities in 50 groups | 10K triples fetched | 50 rows returned | 200x less data |
| Multiple aggregations (5) | 10K × 5 operations | 1 query | 50,000x fewer ops |
| Network time (100K items) | ~10 seconds | ~10ms | 1000x faster |

**Limitations:**

- Only supports aggregations over Basic Graph Patterns (BGPs)
- Complex subpatterns (FILTER, OPTIONAL, UNION within GROUP BY) not yet supported
- HAVING clause not yet optimized
- Aggregation expressions must be over simple variables

**Implementation:**

- `FalkorDBOpExecutor.java`: `execute(OpGroup, QueryIterator)` method intercepts GROUP operations
- `AggregationToCypherTranslator.java`: Translates SPARQL aggregations to Cypher
- Automatic fallback to standard Jena evaluation when translation fails

**Complete Working Examples:**

See [samples/aggregations/](samples/aggregations/) for:
- Full Java code with multiple use cases
- SPARQL query patterns with generated Cypher
- Sample data demonstrating various aggregation scenarios
- Detailed README with performance analysis

### General Implementation Notes

1. **Fallback Strategy**: Always implement a fallback to standard Jena evaluation when translation fails
2. **OpenTelemetry**: Add spans for new translation paths with attributes like `falkordb.pattern.type`
3. **Testing**: Create unit tests in `SparqlToCypherCompilerTest.java` for each new pattern type
4. **Integration Tests**: Add tests in `FalkorDBQueryPushdownTest.java` that verify end-to-end execution

```java
// Example test structure
@Test
@DisplayName("Test OPTIONAL pattern with pushdown")
public void testOptionalPatternPushdown() {
    // Add test data with and without optional values
    var person1 = model.createResource("http://example.org/person1");
    var person2 = model.createResource("http://example.org/person2");
    person1.addProperty(emailProp, "test@example.org");
    // person2 has no email
    
    String sparql = """
        SELECT ?person ?email WHERE {
            ?person a <Person> .
            OPTIONAL { ?person <email> ?email }
        }
        """;
    
    // Execute and verify both persons returned,
    // with email for person1 and null for person2
}
```
