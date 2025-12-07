# FalkorDB Jena Adapter Optimizations

This document describes the performance optimizations implemented in the FalkorDB Jena Adapter to improve both read and write operations.

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
| FILTER, UNION | ❌ (fallback) | Complex patterns |

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

**Limitations:**

- Variable predicates in OPTIONAL patterns not yet supported (will fall back)
- Complex nested OPTIONAL patterns may fall back to standard evaluation
- FILTER clauses within OPTIONAL blocks may not fully push down

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

All optimization components are instrumented with OpenTelemetry:

- **Transaction spans**: Track buffering and flush operations
- **Query pushdown spans**: Show compiled Cypher queries and fallback decisions
- **Result counts**: Monitor query efficiency

Enable tracing to visualize performance in Jaeger:

```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

Then view traces at `http://localhost:16686`.

## Optimizations with Inference Models (InfGraph)

When using Jena's inference/reasoning capabilities with `InfModel` (which wraps the base model in an `InfGraph`), optimization behavior is different to preserve inference semantics:

### Query Pushdown with InfGraph

Query pushdown is **intentionally disabled** for inference models. When you create an `InfModel`:

```java
// Create base FalkorDB model
Model baseModel = FalkorDBModelFactory.createModel("myGraph");

// Create inference model with rules
Reasoner reasoner = new GenericRuleReasoner(rules);
InfModel infModel = ModelFactory.createInfModel(reasoner, baseModel);

// Queries against infModel use standard Jena evaluation (no pushdown)
```

**Why disabled?** Query pushdown would bypass the inference layer, causing incorrect results. Inference requires:
1. Access to all base triples
2. Application of forward/backward chaining rules
3. Generation of inferred triples

Query pushdown operates directly on the graph database, which only contains base triples, not inferred ones.

### Available Optimizations for InfGraph

Even with query pushdown disabled, these optimizations **still work**:

| Optimization | InfGraph Support | Notes |
|-------------|------------------|-------|
| **Transaction Batching** | ✅ Enabled | Bulk writes to base model use `UNWIND` |
| **Magic Property (`falkor:cypher`)** | ✅ Enabled | Unwraps InfGraph to access raw FalkorDB graph |
| **Query Pushdown** | ❌ Disabled | Would bypass inference layer |
| **Automatic Indexing** | ✅ Enabled | Base graph maintains URI index |

### Example: Optimized Writes with Inference

```java
// Transaction batching still works with InfModel
InfModel infModel = ModelFactory.createInfModel(reasoner, baseModel);

infModel.begin(ReadWrite.WRITE);
try {
    // These writes are batched and flushed efficiently
    for (int i = 0; i < 1000; i++) {
        Resource person = infModel.createResource("http://example.org/person" + i);
        person.addProperty(RDF.type, personType);
        person.addProperty(name, "Person " + i);
    }
    infModel.commit();  // Single bulk operation to base graph
} finally {
    infModel.end();
}
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
- [FusekiInferenceIntegrationTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/FusekiInferenceIntegrationTest.java)
- [FusekiLazyInferenceIntegrationTest.java](jena-fuseki-falkordb/src/test/java/com/falkordb/FusekiLazyInferenceIntegrationTest.java)

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

## Future Improvements

The query pushdown can be extended to support additional SPARQL patterns. Below are implementation suggestions for each:

### OPTIONAL Patterns

**Challenge**: SPARQL `OPTIONAL { ... }` returns bindings even when the optional part doesn't match.

**Implementation Strategy**:
1. In `FalkorDBOpExecutor`, intercept `OpLeftJoin` (which represents OPTIONAL)
2. Translate to Cypher `OPTIONAL MATCH`:

```sparql
# SPARQL
SELECT ?person ?email WHERE {
    ?person rdf:type <Person> .
    OPTIONAL { ?person <email> ?email }
}
```

```cypher
# Cypher
MATCH (person:Resource:`Person`)
OPTIONAL MATCH (person)-[:`email`]->(email:Resource)
RETURN person.uri AS person, email.uri AS email
```

**Files to modify**:
- `FalkorDBOpExecutor.java`: Override `execute(OpLeftJoin, QueryIterator)` method
- `SparqlToCypherCompiler.java`: Add `translateOptionalPattern()` that generates `OPTIONAL MATCH`

### FILTER Expressions

**Challenge**: SPARQL `FILTER` expressions need to be translated to Cypher `WHERE` clauses.

**Implementation Strategy**:
1. In `FalkorDBOpExecutor`, intercept `OpFilter`
2. Create a `FilterToCypherTranslator` to map SPARQL expressions to Cypher:

| SPARQL | Cypher |
|--------|--------|
| `FILTER(?x > 10)` | `WHERE x > 10` |
| `FILTER(regex(?name, "^A"))` | `WHERE name =~ "^A.*"` |
| `FILTER(bound(?x))` | `WHERE x IS NOT NULL` |
| `FILTER(isURI(?x))` | `WHERE x STARTS WITH "http"` |
| `FILTER(?x = "value")` | `WHERE x = "value"` |

```java
public class FilterToCypherTranslator extends ExprVisitorBase {
    @Override
    public void visit(E_Equals expr) {
        // Translate = to Cypher =
    }
    
    @Override
    public void visit(E_Regex expr) {
        // Translate regex() to Cypher =~
    }
}
```

**Files to modify**:
- Create new `FilterToCypherTranslator.java` in `com.falkordb.jena.query` package
- `FalkorDBOpExecutor.java`: Override `execute(OpFilter, QueryIterator)` method

### UNION Patterns

**Challenge**: SPARQL `UNION` combines results from alternative patterns.

**Implementation Strategy**:
1. In `FalkorDBOpExecutor`, intercept `OpUnion`
2. Compile each branch separately and combine with Cypher `UNION`:

```sparql
# SPARQL
SELECT ?person WHERE {
    { ?person rdf:type <Student> }
    UNION
    { ?person rdf:type <Teacher> }
}
```

```cypher
# Cypher
MATCH (person:Resource:`Student`)
RETURN person.uri AS person
UNION
MATCH (person:Resource:`Teacher`)
RETURN person.uri AS person
```

**Files to modify**:
- `FalkorDBOpExecutor.java`: Override `execute(OpUnion, QueryIterator)` method
- `SparqlToCypherCompiler.java`: Add `translateUnionPattern()` method

### Aggregations

**Challenge**: SPARQL aggregations (COUNT, SUM, AVG, etc.) with GROUP BY need Cypher equivalents.

**Implementation Strategy**:
1. In `FalkorDBOpExecutor`, intercept `OpGroup`
2. Translate SPARQL aggregations to Cypher:

| SPARQL | Cypher |
|--------|--------|
| `COUNT(?x)` | `count(x)` |
| `SUM(?x)` | `sum(x)` |
| `AVG(?x)` | `avg(x)` |
| `MIN(?x)` | `min(x)` |
| `MAX(?x)` | `max(x)` |
| `GROUP BY ?g` | `WITH ... GROUP BY g` |

```sparql
# SPARQL
SELECT ?type (COUNT(?person) AS ?count) WHERE {
    ?person rdf:type ?type
}
GROUP BY ?type
```

```cypher
# Cypher
MATCH (person:Resource)
UNWIND labels(person) AS type
WHERE type <> 'Resource'
RETURN type, count(person) AS count
```

**Files to modify**:
- `FalkorDBOpExecutor.java`: Override `execute(OpGroup, QueryIterator)` method
- Create new `AggregationToCypherTranslator.java` for expression translation

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
