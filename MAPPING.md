# RDF to FalkorDB Graph Mapping

This document describes the mapping between RDF (Resource Description Framework) data and its representation in FalkorDB as a property graph. For each RDF construct, we provide:

- The RDF representation in Triple notation and Turtle syntax
- The generated FalkorDB Graph using Cypher notation
- curl expressions for Fuseki execution
- Jena API code snippets
- Analysis of pros and cons (indexes, space, performance)
- Links to system tests validating the mapping

## Table of Contents

1. [Overview](#overview)
2. [Basic Resource with Literal Property](#1-basic-resource-with-literal-property)
3. [Resource with rdf:type](#2-resource-with-rdftype)
4. [Resource to Resource Relationship](#3-resource-to-resource-relationship)
5. [Multiple Properties on Same Resource](#4-multiple-properties-on-same-resource)
6. [Multiple Types on Same Resource](#5-multiple-types-on-same-resource)
7. [Typed Literals](#6-typed-literals)
8. [Language-Tagged Literals](#7-language-tagged-literals)
9. [Complex Graph with Mixed Triples](#8-complex-graph-with-mixed-triples)
10. [Performance Considerations](#performance-considerations)
11. [System Tests](#system-tests)

---

## Overview

The Jena-FalkorDB adapter maps RDF triples to FalkorDB's property graph model using the following conventions:

| RDF Construct | FalkorDB Representation |
|---------------|------------------------|
| Subject (URI) | Node with label `Resource` and `uri` property |
| Predicate + Literal Object | Node property (predicate URI as property name) |
| Predicate + Resource Object | Relationship (predicate URI as relationship type) |
| `rdf:type` | Additional label on the node |

### Key Design Decisions

1. **Literals as Properties**: Literal values are stored directly as node properties, not as separate nodes. This reduces graph complexity and improves query performance.

2. **Types as Labels**: `rdf:type` triples add labels to nodes, enabling efficient type-based filtering.

3. **Automatic Indexing**: An index is automatically created on `Resource.uri` for fast lookups.

4. **Backtick Notation**: URIs are used directly as property names and relationship types using Cypher's backtick notation.

---

## 1. Basic Resource with Literal Property

A simple RDF triple with a subject, predicate, and literal value.

### RDF Triple Notation

```
<http://example.org/person1> <http://example.org/name> "John Doe" .
```

### Turtle Syntax

```turtle
@prefix ex: <http://example.org/> .

ex:person1 ex:name "John Doe" .
```

### Generated FalkorDB Graph (Cypher)

```cypher
MERGE (s:Resource {uri: "http://example.org/person1"})
SET s.`http://example.org/name` = "John Doe"
```

**Result Structure:**
```cypher
(:Resource {
  uri: "http://example.org/person1",
  `http://example.org/name`: "John Doe"
})
```

### Fuseki curl Expression

**Insert data:**
```bash
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:person1 ex:name "John Doe" .
}'
```

**Query data:**
```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=PREFIX ex: <http://example.org/>
SELECT ?name WHERE {
  ex:person1 ex:name ?name .
}'
```

### Jena API Code

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

// Create a FalkorDB-backed model
Model model = FalkorDBModelFactory.createDefaultModel();

try {
    // Create resource and property
    Resource person = model.createResource("http://example.org/person1");
    Property name = model.createProperty("http://example.org/name");
    
    // Add literal property
    person.addProperty(name, "John Doe");
    
    // Verify: model now contains 1 triple
    assert model.size() == 1;
    assert person.getProperty(name).getString().equals("John Doe");
} finally {
    model.close();
}
```

### Pros and Cons

| Aspect | Analysis |
|--------|----------|
| **Space** | ✅ Efficient - literal stored as property, not separate node |
| **Performance** | ✅ Direct property access, no relationship traversal needed |
| **Indexing** | ✅ URI indexed automatically; property values not indexed by default |
| **Query** | ✅ Simple property lookup in Cypher |
| **Flexibility** | ⚠️ Single value per property-name; multi-valued requires arrays or separate triples |

### System Test

See: [`RDFMappingTest.testBasicLiteralProperty()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L65)

---

## 2. Resource with rdf:type

Adding a type to a resource creates a label on the node.

### RDF Triple Notation

```
<http://example.org/person1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://example.org/Person> .
```

### Turtle Syntax

```turtle
@prefix ex: <http://example.org/> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

ex:person1 rdf:type ex:Person .

# Or using the shorthand 'a' for rdf:type:
ex:person1 a ex:Person .
```

### Generated FalkorDB Graph (Cypher)

```cypher
MERGE (s:Resource:`http://example.org/Person` {uri: "http://example.org/person1"})
```

**Result Structure:**
```cypher
(:Resource:`http://example.org/Person` {
  uri: "http://example.org/person1"
})
```

### Fuseki curl Expression

**Insert data:**
```bash
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
INSERT DATA {
  ex:person1 rdf:type ex:Person .
}'
```

**Query data:**
```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=PREFIX ex: <http://example.org/>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
SELECT ?person WHERE {
  ?person rdf:type ex:Person .
}'
```

### Jena API Code

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

Model model = FalkorDBModelFactory.createDefaultModel();

try {
    // Create resource and type
    Resource person = model.createResource("http://example.org/person1");
    Resource personType = model.createResource("http://example.org/Person");
    
    // Add rdf:type
    person.addProperty(RDF.type, personType);
    
    // Verify
    assert model.size() == 1;
    assert model.contains(person, RDF.type, personType);
} finally {
    model.close();
}
```

### Pros and Cons

| Aspect | Analysis |
|--------|----------|
| **Space** | ✅ Minimal - type stored as label, not relationship |
| **Performance** | ✅ Label-based filtering is highly efficient in graph DBs |
| **Indexing** | ✅ Labels are natively indexed in FalkorDB |
| **Query** | ✅ Can use label matching: `MATCH (n:Resource:\`http://example.org/Person\`)` |
| **Multiple Types** | ⚠️ Multiple labels supported, but cardinality limits may apply |

### System Test

See: [`RDFMappingTest.testRdfType()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L113)

---

## 3. Resource to Resource Relationship

When the object of a triple is another resource (not a literal), a relationship is created.

### RDF Triple Notation

```
<http://example.org/alice> <http://example.org/knows> <http://example.org/bob> .
```

### Turtle Syntax

```turtle
@prefix ex: <http://example.org/> .

ex:alice ex:knows ex:bob .
```

### Generated FalkorDB Graph (Cypher)

```cypher
MERGE (s:Resource {uri: "http://example.org/alice"})
MERGE (o:Resource {uri: "http://example.org/bob"})
MERGE (s)-[r:`http://example.org/knows`]->(o)
```

**Result Structure:**
```cypher
(:Resource {uri: "http://example.org/alice"})
  -[:`http://example.org/knows`]->
(:Resource {uri: "http://example.org/bob"})
```

### Fuseki curl Expression

**Insert data:**
```bash
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:alice ex:knows ex:bob .
}'
```

**Query data:**
```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=PREFIX ex: <http://example.org/>
SELECT ?known WHERE {
  ex:alice ex:knows ?known .
}'
```

### Jena API Code

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

Model model = FalkorDBModelFactory.createDefaultModel();

try {
    // Create resources
    Resource alice = model.createResource("http://example.org/alice");
    Resource bob = model.createResource("http://example.org/bob");
    Property knows = model.createProperty("http://example.org/knows");
    
    // Add relationship
    alice.addProperty(knows, bob);
    
    // Verify
    assert model.size() == 1;
    assert alice.getProperty(knows).getResource().equals(bob);
} finally {
    model.close();
}
```

### Pros and Cons

| Aspect | Analysis |
|--------|----------|
| **Space** | ✅ Relationship is native graph structure |
| **Performance** | ✅ Graph traversal is optimized for relationships |
| **Indexing** | ✅ URI on both endpoints indexed |
| **Query** | ✅ Efficient path queries: `MATCH (a)-[*1..3]->(b)` |
| **Bi-directional** | ⚠️ Relationships are directional; inverse must be added separately |

### System Test

See: [`RDFMappingTest.testResourceRelationship()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L159)

---

## 4. Multiple Properties on Same Resource

Multiple literal properties on the same resource are stored as separate properties on a single node.

### RDF Triple Notation

```
<http://example.org/person1> <http://example.org/name> "John Doe" .
<http://example.org/person1> <http://example.org/age> "30" .
<http://example.org/person1> <http://example.org/email> "john@example.org" .
```

### Turtle Syntax

```turtle
@prefix ex: <http://example.org/> .

ex:person1 
    ex:name "John Doe" ;
    ex:age "30" ;
    ex:email "john@example.org" .
```

### Generated FalkorDB Graph (Cypher)

```cypher
MERGE (s:Resource {uri: "http://example.org/person1"})
SET s.`http://example.org/name` = "John Doe"
SET s.`http://example.org/age` = "30"
SET s.`http://example.org/email` = "john@example.org"
```

**Result Structure:**
```cypher
(:Resource {
  uri: "http://example.org/person1",
  `http://example.org/name`: "John Doe",
  `http://example.org/age`: "30",
  `http://example.org/email`: "john@example.org"
})
```

### Fuseki curl Expression

**Insert data:**
```bash
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:person1 ex:name "John Doe" ;
             ex:age "30" ;
             ex:email "john@example.org" .
}'
```

**Query data:**
```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=PREFIX ex: <http://example.org/>
SELECT ?name ?age ?email WHERE {
  ex:person1 ex:name ?name ;
             ex:age ?age ;
             ex:email ?email .
}'
```

### Jena API Code

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

Model model = FalkorDBModelFactory.createDefaultModel();

try {
    // Create resource
    Resource person = model.createResource("http://example.org/person1");
    
    // Create properties
    Property name = model.createProperty("http://example.org/name");
    Property age = model.createProperty("http://example.org/age");
    Property email = model.createProperty("http://example.org/email");
    
    // Add multiple properties (fluent style)
    person.addProperty(name, "John Doe")
          .addProperty(age, "30")
          .addProperty(email, "john@example.org");
    
    // Verify: 3 triples on 1 node
    assert model.size() == 3;
} finally {
    model.close();
}
```

### Pros and Cons

| Aspect | Analysis |
|--------|----------|
| **Space** | ✅ Highly efficient - single node with multiple properties |
| **Performance** | ✅ Single node lookup retrieves all properties |
| **Indexing** | ⚠️ Individual properties not indexed by default |
| **Query** | ✅ Retrieve all properties in one query |
| **Schema** | ⚠️ No schema enforcement; property names must match exactly |

### System Test

See: [`RDFMappingTest.testMultipleProperties()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L216)

---

## 5. Multiple Types on Same Resource

A resource can have multiple `rdf:type` values, resulting in multiple labels.

### RDF Triple Notation

```
<http://example.org/person1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://example.org/Person> .
<http://example.org/person1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://example.org/Employee> .
<http://example.org/person1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://example.org/Developer> .
```

### Turtle Syntax

```turtle
@prefix ex: <http://example.org/> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

ex:person1 a ex:Person, ex:Employee, ex:Developer .
```

### Generated FalkorDB Graph (Cypher)

```cypher
MERGE (s:Resource:`http://example.org/Person`:`http://example.org/Employee`:`http://example.org/Developer` 
       {uri: "http://example.org/person1"})
```

**Result Structure:**
```cypher
(:Resource:`http://example.org/Person`:`http://example.org/Employee`:`http://example.org/Developer` {
  uri: "http://example.org/person1"
})
```

### Fuseki curl Expression

**Insert data:**
```bash
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:person1 a ex:Person, ex:Employee, ex:Developer .
}'
```

**Query data (find all Employees):**
```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=PREFIX ex: <http://example.org/>
SELECT ?person WHERE {
  ?person a ex:Employee .
}'
```

### Jena API Code

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

Model model = FalkorDBModelFactory.createDefaultModel();

try {
    // Create resource and types
    Resource person = model.createResource("http://example.org/person1");
    Resource personType = model.createResource("http://example.org/Person");
    Resource employeeType = model.createResource("http://example.org/Employee");
    Resource developerType = model.createResource("http://example.org/Developer");
    
    // Add multiple types
    person.addProperty(RDF.type, personType)
          .addProperty(RDF.type, employeeType)
          .addProperty(RDF.type, developerType);
    
    // Verify: 3 type triples
    assert model.size() == 3;
    
    // Query by any type works
    assert model.contains(person, RDF.type, employeeType);
} finally {
    model.close();
}
```

### Pros and Cons

| Aspect | Analysis |
|--------|----------|
| **Space** | ✅ Efficient - labels have minimal overhead |
| **Performance** | ✅ Label intersection queries are fast |
| **Indexing** | ✅ All labels are indexed |
| **Query** | ✅ Can query by any combination: `MATCH (n:Person:Employee)` |
| **Limits** | ⚠️ Graph DB may have limits on number of labels per node |

### System Test

See: [`RDFMappingTest.testMultipleTypes()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L277)

---

## 6. Typed Literals

RDF supports typed literals (e.g., integers, dates). These are stored with type information preserved.

### RDF Triple Notation

```
<http://example.org/person1> <http://example.org/age> "30"^^<http://www.w3.org/2001/XMLSchema#integer> .
<http://example.org/person1> <http://example.org/height> "1.75"^^<http://www.w3.org/2001/XMLSchema#double> .
<http://example.org/person1> <http://example.org/active> "true"^^<http://www.w3.org/2001/XMLSchema#boolean> .
```

### Turtle Syntax

```turtle
@prefix ex: <http://example.org/> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

ex:person1 
    ex:age "30"^^xsd:integer ;
    ex:height "1.75"^^xsd:double ;
    ex:active "true"^^xsd:boolean .
```

### Generated FalkorDB Graph (Cypher)

```cypher
MERGE (s:Resource {uri: "http://example.org/person1"})
SET s.`http://example.org/age` = "30"
SET s.`http://example.org/height` = "1.75"
SET s.`http://example.org/active` = "true"
```

> **Note**: Currently, typed literals are stored as their lexical string representation. Type information is preserved through the Jena API but stored as strings in FalkorDB.

**Result Structure:**
```cypher
(:Resource {
  uri: "http://example.org/person1",
  `http://example.org/age`: "30",
  `http://example.org/height`: "1.75",
  `http://example.org/active`: "true"
})
```

### Fuseki curl Expression

**Insert data:**
```bash
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
INSERT DATA {
  ex:person1 ex:age "30"^^xsd:integer ;
             ex:height "1.75"^^xsd:double ;
             ex:active "true"^^xsd:boolean .
}'
```

**Query data:**
```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=PREFIX ex: <http://example.org/>
SELECT ?age ?height ?active WHERE {
  ex:person1 ex:age ?age ;
             ex:height ?height ;
             ex:active ?active .
}'
```

### Jena API Code

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

Model model = FalkorDBModelFactory.createDefaultModel();

try {
    Resource person = model.createResource("http://example.org/person1");
    Property age = model.createProperty("http://example.org/age");
    Property height = model.createProperty("http://example.org/height");
    Property active = model.createProperty("http://example.org/active");
    
    // Add typed literals
    person.addProperty(age, model.createTypedLiteral(30));
    person.addProperty(height, model.createTypedLiteral(1.75));
    person.addProperty(active, model.createTypedLiteral(true));
    
    // Verify types are preserved in Jena
    assert person.getProperty(age).getInt() == 30;
    assert person.getProperty(height).getDouble() == 1.75;
    assert person.getProperty(active).getBoolean() == true;
} finally {
    model.close();
}
```

### Pros and Cons

| Aspect | Analysis |
|--------|----------|
| **Space** | ✅ Stored as string, minimal overhead |
| **Performance** | ⚠️ Type conversion happens at read time |
| **Indexing** | ⚠️ String comparison may not work for numeric ranges |
| **Query** | ⚠️ FILTER comparisons may require type casting |
| **Fidelity** | ✅ Original type information preserved through Jena |

### System Test

See: [`RDFMappingTest.testTypedLiterals()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L332)

---

## 7. Language-Tagged Literals

RDF supports language tags for literal values (e.g., labels in multiple languages).

### RDF Triple Notation

```
<http://example.org/paris> <http://www.w3.org/2000/01/rdf-schema#label> "Paris"@en .
<http://example.org/paris> <http://www.w3.org/2000/01/rdf-schema#label> "Parigi"@it .
<http://example.org/paris> <http://www.w3.org/2000/01/rdf-schema#label> "巴黎"@zh .
```

### Turtle Syntax

```turtle
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix ex: <http://example.org/> .

ex:paris 
    rdfs:label "Paris"@en ;
    rdfs:label "Parigi"@it ;
    rdfs:label "巴黎"@zh .
```

### Generated FalkorDB Graph (Cypher)

> **Note**: Language tags are currently stored as part of the string value or as separate properties. The exact representation depends on the implementation.

```cypher
MERGE (s:Resource {uri: "http://example.org/paris"})
SET s.`http://www.w3.org/2000/01/rdf-schema#label` = "Paris"
```

**Current Limitation**: Multiple values for the same property with different language tags may overwrite each other. Consider using qualified property names or separate properties for language variants.

### Fuseki curl Expression

**Insert data:**
```bash
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:paris rdfs:label "Paris"@en .
}'
```

**Query data:**
```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX ex: <http://example.org/>
SELECT ?label WHERE {
  ex:paris rdfs:label ?label .
}'
```

### Jena API Code

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDFS;

Model model = FalkorDBModelFactory.createDefaultModel();

try {
    Resource paris = model.createResource("http://example.org/paris");
    
    // Add language-tagged literals
    paris.addProperty(RDFS.label, model.createLiteral("Paris", "en"));
    
    // Verify
    var labelStmt = paris.getProperty(RDFS.label);
    assert labelStmt != null;
} finally {
    model.close();
}
```

### Pros and Cons

| Aspect | Analysis |
|--------|----------|
| **Space** | ⚠️ Multiple language values may require special handling |
| **Performance** | ⚠️ Language filtering requires post-processing |
| **Indexing** | ⚠️ Language variants not separately indexed |
| **Query** | ⚠️ LANGMATCHES filter works but not optimized |
| **Completeness** | ⚠️ Current implementation has limitations for multi-language support |

### System Test

See: [`RDFMappingTest.testLanguageTaggedLiterals()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L368)

---

## 8. Complex Graph with Mixed Triples

A real-world example combining all mapping types.

### RDF Triple Notation

```
<http://example.org/alice> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://example.org/Person> .
<http://example.org/alice> <http://example.org/name> "Alice Smith" .
<http://example.org/alice> <http://example.org/age> "30"^^<http://www.w3.org/2001/XMLSchema#integer> .
<http://example.org/alice> <http://example.org/knows> <http://example.org/bob> .
<http://example.org/bob> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://example.org/Person> .
<http://example.org/bob> <http://example.org/name> "Bob Jones" .
<http://example.org/bob> <http://example.org/worksAt> <http://example.org/acme> .
<http://example.org/acme> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://example.org/Company> .
<http://example.org/acme> <http://example.org/name> "ACME Corp" .
```

### Turtle Syntax

```turtle
@prefix ex: <http://example.org/> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

ex:alice a ex:Person ;
    ex:name "Alice Smith" ;
    ex:age "30"^^xsd:integer ;
    ex:knows ex:bob .

ex:bob a ex:Person ;
    ex:name "Bob Jones" ;
    ex:worksAt ex:acme .

ex:acme a ex:Company ;
    ex:name "ACME Corp" .
```

### Generated FalkorDB Graph (Cypher)

```cypher
# Alice node with type, properties, and relationship
MERGE (alice:Resource:`http://example.org/Person` {uri: "http://example.org/alice"})
SET alice.`http://example.org/name` = "Alice Smith"
SET alice.`http://example.org/age` = "30"

# Bob node with type and properties
MERGE (bob:Resource:`http://example.org/Person` {uri: "http://example.org/bob"})
SET bob.`http://example.org/name` = "Bob Jones"

# ACME node with type and properties
MERGE (acme:Resource:`http://example.org/Company` {uri: "http://example.org/acme"})
SET acme.`http://example.org/name` = "ACME Corp"

# Relationships
MERGE (alice)-[:`http://example.org/knows`]->(bob)
MERGE (bob)-[:`http://example.org/worksAt`]->(acme)
```

**Result Structure:**
```
(:Resource:`http://example.org/Person` {
  uri: "http://example.org/alice",
  `http://example.org/name`: "Alice Smith",
  `http://example.org/age`: "30"
})
  -[:`http://example.org/knows`]->
(:Resource:`http://example.org/Person` {
  uri: "http://example.org/bob",
  `http://example.org/name`: "Bob Jones"
})
  -[:`http://example.org/worksAt`]->
(:Resource:`http://example.org/Company` {
  uri: "http://example.org/acme",
  `http://example.org/name`: "ACME Corp"
})
```

### Fuseki curl Expression

**Insert data using Turtle file:**
```bash
curl -X POST http://localhost:3330/falkor/data \
  -H "Content-Type: text/turtle" \
  --data '@prefix ex: <http://example.org/> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

ex:alice a ex:Person ;
    ex:name "Alice Smith" ;
    ex:age "30"^^xsd:integer ;
    ex:knows ex:bob .

ex:bob a ex:Person ;
    ex:name "Bob Jones" ;
    ex:worksAt ex:acme .

ex:acme a ex:Company ;
    ex:name "ACME Corp" .'
```

**Complex query - Find people and their employers:**
```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=PREFIX ex: <http://example.org/>
SELECT ?personName ?companyName WHERE {
  ?person a ex:Person ;
          ex:name ?personName ;
          ex:worksAt ?company .
  ?company ex:name ?companyName .
}'
```

### Jena API Code

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

Model model = FalkorDBModelFactory.createDefaultModel();

try {
    // Define types
    Resource personType = model.createResource("http://example.org/Person");
    Resource companyType = model.createResource("http://example.org/Company");
    
    // Define properties
    Property name = model.createProperty("http://example.org/name");
    Property age = model.createProperty("http://example.org/age");
    Property knows = model.createProperty("http://example.org/knows");
    Property worksAt = model.createProperty("http://example.org/worksAt");
    
    // Create Alice
    Resource alice = model.createResource("http://example.org/alice")
        .addProperty(RDF.type, personType)
        .addProperty(name, "Alice Smith")
        .addProperty(age, model.createTypedLiteral(30));
    
    // Create Bob
    Resource bob = model.createResource("http://example.org/bob")
        .addProperty(RDF.type, personType)
        .addProperty(name, "Bob Jones");
    
    // Create ACME
    Resource acme = model.createResource("http://example.org/acme")
        .addProperty(RDF.type, companyType)
        .addProperty(name, "ACME Corp");
    
    // Add relationships
    alice.addProperty(knows, bob);
    bob.addProperty(worksAt, acme);
    
    // Verify total triples: 3 (alice) + 2 (bob) + 2 (acme) + 2 (relationships) = 9
    assert model.size() == 9;
    
    // Query using SPARQL
    String query = """
        PREFIX ex: <http://example.org/>
        SELECT ?name WHERE {
          ?person a ex:Person ;
                  ex:name ?name .
        }
        """;
    
    try (var qexec = org.apache.jena.query.QueryExecutionFactory.create(query, model)) {
        var results = qexec.execSelect();
        int count = 0;
        while (results.hasNext()) {
            var soln = results.nextSolution();
            System.out.println("Person: " + soln.getLiteral("name").getString());
            count++;
        }
        assert count == 2; // Alice and Bob
    }
} finally {
    model.close();
}
```

### System Test

See: [`RDFMappingTest.testComplexGraph()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L403)

---

## Performance Considerations

### Storage Efficiency Comparison

| Approach | Nodes | Relationships | Properties |
|----------|-------|---------------|------------|
| **Traditional (separate literal nodes)** | 3 per triple | 1 per triple | 1 per node |
| **Our Approach (literals as properties)** | 1 per subject | Only for resource objects | Multiple per node |

**Example: 3 literal properties on 1 subject**

Traditional: 4 nodes + 3 relationships = 7 graph elements  
Our approach: 1 node + 3 properties = 1 graph element with 4 properties

### Query Performance

| Query Type | Cypher Pattern | Performance |
|------------|----------------|-------------|
| Get literal property | `MATCH (n {uri: $uri}) RETURN n.property` | O(1) with index |
| Get all properties | `MATCH (n {uri: $uri}) RETURN n` | O(1) with index |
| Type filtering | `MATCH (n:Resource:\`type\`)` | O(n) label scan |
| Relationship traversal | `MATCH (a)-[r]->(b)` | O(edges) |
| Path query | `MATCH path = (a)-[*1..3]->(b)` | Optimized by FalkorDB |

### Indexing Strategy

The adapter automatically creates:

```cypher
CREATE INDEX FOR (r:Resource) ON (r.uri)
```

Additional indexes can be created manually for frequently queried properties:

```cypher
CREATE INDEX FOR (r:Resource) ON (r.`http://example.org/name`)
```

### Best Practices

1. **Use specific types**: Add `rdf:type` to enable label-based filtering
2. **Batch operations**: Use transactions for bulk inserts
3. **Limit SPARQL complexity**: For complex queries, consider the [Magic Property](MAGIC_PROPERTY.md) for Cypher pushdown
4. **Monitor performance**: Enable [OpenTelemetry Tracing](TRACING.md) for query analysis

---

## System Tests

All mappings are validated by comprehensive system tests that verify both the Jena API and Fuseki SPARQL interface.

### Test File Locations

- **Jena API Tests**: [`jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java)
- **Fuseki SPARQL Tests**: [`jena-fuseki-falkordb/src/test/java/com/falkordb/RDFMappingFusekiTest.java`](jena-fuseki-falkordb/src/test/java/com/falkordb/RDFMappingFusekiTest.java)

### Test Coverage

| Test Method | Mapping Tested | Jena API | Fuseki |
|-------------|---------------|----------|--------|
| `testBasicLiteralProperty` | Literal property | ✅ | ✅ |
| `testRdfType` | rdf:type as label | ✅ | ✅ |
| `testResourceRelationship` | Resource-to-resource | ✅ | ✅ |
| `testMultipleProperties` | Multiple properties | ✅ | ✅ |
| `testMultipleTypes` | Multiple types | ✅ | ✅ |
| `testTypedLiterals` | Typed literals | ✅ | ✅ |
| `testLanguageTaggedLiterals` | Language tags | ✅ | ✅ |
| `testComplexGraph` | Combined patterns | ✅ | ✅ |

### Running the Tests

```bash
# Run Jena API mapping tests
mvn test -pl jena-falkordb-adapter -Dtest=RDFMappingTest

# Run Fuseki SPARQL mapping tests
mvn test -pl jena-fuseki-falkordb -Dtest=RDFMappingFusekiTest

# Run all tests (requires FalkorDB running via Docker)
docker run -p 6379:6379 -d --name falkordb falkordb/falkordb:latest
mvn test
```

---

## Related Documentation

- [README.md](README.md) - Project overview and quick start
- [GETTING_STARTED.md](GETTING_STARTED.md) - Detailed setup guide
- [MAGIC_PROPERTY.md](MAGIC_PROPERTY.md) - Native Cypher query pushdown
- [TRACING.md](TRACING.md) - OpenTelemetry tracing setup
