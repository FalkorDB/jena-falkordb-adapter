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
9. [Blank Nodes (Anonymous Resources)](#9-blank-nodes-anonymous-resources)
10. [Complex Graph with Mixed Triples](#10-complex-graph-with-mixed-triples)
11. [Performance Considerations](#performance-considerations)
12. [System Tests](#system-tests)

---

## Overview

The Jena-FalkorDB adapter maps RDF triples to FalkorDB's property graph model using the following conventions:

| RDF Construct | FalkorDB Representation |
|---------------|------------------------|
| Subject (URI) | Node with label `Resource` and `uri` property |
| Subject (Blank Node) | Node with label `Resource` and `uri` property prefixed with `_:` |
| Predicate + Literal Object | Node property (predicate URI as property name) |
| Predicate + Resource Object | Relationship (predicate URI as relationship type) |
| `rdf:type` | Additional label on the node |

### Key Design Decisions

1. **Literals as Properties**: Literal values are stored directly as node properties, not as separate nodes. This reduces graph complexity and improves query performance.

2. **Types as Labels**: `rdf:type` triples add labels to nodes, enabling efficient type-based filtering.

3. **Automatic Indexing**: An index is automatically created on `Resource.uri` for fast lookups.

4. **Backtick Notation**: URIs are used directly as property names and relationship types using Cypher's backtick notation.

5. **Datatype Preservation**: Custom datatypes (e.g., `geo:wktLiteral`, `xsd:dateTime`) are preserved using metadata properties with the suffix `__datatype`. These metadata properties are automatically excluded from triple counts and SPARQL query results.

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

#### Cons Elaboration

**Flexibility Limitation**: When a resource has multiple values for the same property, the property-based storage can be limiting:

```turtle
# RDF allows multiple values for the same predicate
ex:person1 ex:email "john@work.com" .
ex:person1 ex:email "john@personal.com" .
```

In the current implementation, the second value would overwrite the first since properties are stored as single values. Workarounds include:
- Using an array property (requires custom handling)
- Using different property names (e.g., `ex:workEmail`, `ex:personalEmail`)
- Storing as separate relationship-based triples (less efficient)

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

> **Query Pushdown Note**: When query pushdown is enabled (automatic by default), SPARQL queries with variable predicates (`?s ?p ?o`) retrieve `rdf:type` triples from node labels using Cypher's `labels()` function, ensuring all type information is included in query results.

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

#### Cons Elaboration

**Multiple Types Cardinality**: While FalkorDB supports multiple labels per node, there are practical limits:

```turtle
# A resource with many types (common in rich ontologies)
ex:john a ex:Person, ex:Employee, ex:Manager, ex:Author, 
          ex:Reviewer, ex:Contributor, ex:Admin, ex:User .
```

Potential issues:
- Graph databases may have internal limits on labels per node (typically 100+, but varies)
- Query performance may degrade with many labels due to label index overhead
- Label-based queries become complex: `MATCH (n:Person:Employee:Manager)` can be unwieldy

Workaround: For highly polymorphic resources, consider using a single primary type label and storing additional types as properties.

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

#### Cons Elaboration

**Bi-directional Relationships**: RDF relationships are stored as directed edges. To query in both directions, you need explicit inverse relationships:

```turtle
# Forward relationship only
ex:alice ex:knows ex:bob .

# To find "who knows alice", you need:
# Option 1: Query with reverse pattern (less efficient)
# Option 2: Add explicit inverse relationship
ex:bob ex:knownBy ex:alice .
```

Example query challenges:

```sparql
# This works - forward direction
SELECT ?friend WHERE { ex:alice ex:knows ?friend }

# This is harder - reverse direction requires full scan without inverse
SELECT ?person WHERE { ?person ex:knows ex:alice }
```

In Cypher, you can use undirected matching `(a)-[:knows]-(b)`, but SPARQL requires explicit patterns. Consider adding inverse properties for frequently queried reverse relationships.

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

#### Cons Elaboration

**Indexing Limitation**: While `Resource.uri` is automatically indexed, individual property values are not:

```cypher
# This query uses the URI index - FAST
MATCH (n:Resource {uri: "http://example.org/person1"}) RETURN n

# This query requires full property scan - SLOW for large graphs
MATCH (n:Resource) WHERE n.`http://example.org/name` = "John Doe" RETURN n
```

To improve property query performance, create custom indexes:
```cypher
CREATE INDEX FOR (n:Resource) ON (n.`http://example.org/name`)
```

**Schema Enforcement**: Property names are exact string matches with no validation:

```turtle
# These are THREE different properties due to typos/inconsistency:
ex:person1 ex:name "John" .
ex:person1 ex:Name "John" .       # Capital N - different property!
ex:person1 ex:fullName "John" .   # Different property entirely
```

Unlike SQL databases, there's no schema to catch these inconsistencies. Use consistent vocabularies (e.g., FOAF, Schema.org) to minimize issues.

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

#### Cons Elaboration

**Label Limits**: Similar to section 2, excessive types per resource can cause issues:

```turtle
# An entity classified in a deep ontology hierarchy
ex:widget123 a ex:Product, ex:PhysicalItem, ex:Sellable, ex:Shippable,
               ex:Electronics, ex:Computer, ex:Laptop, ex:GamingLaptop,
               ex:PortableDevice, ex:Battery, ex:Warranty, ex:Discountable .
```

Impact examples:
- Memory: Each label adds memory overhead to the node
- Index maintenance: More labels = more index entries to update on changes
- Query planning: Complex label combinations may confuse the query optimizer

```cypher
# Simple query - efficient
MATCH (n:Resource:`http://example.org/Product`) RETURN n

# Complex multi-label query - may be slower
MATCH (n:Resource:`http://example.org/Product`:`http://example.org/Electronics`:`http://example.org/GamingLaptop`) 
RETURN n
```

### System Test

See: [`RDFMappingTest.testMultipleTypes()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L277)

---

## 6. Typed Literals

RDF supports typed literals (e.g., integers, dates, geometry data). Type information is preserved by storing the datatype URI alongside the literal value.

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
SET s.`http://example.org/age` = 30
SET s.`http://example.org/height` = 1.75
SET s.`http://example.org/active` = true
```

> **Datatype Preservation**: For primitive types (integers, doubles, booleans), values are stored natively in FalkorDB. For custom datatypes (e.g., `geo:wktLiteral`, `xsd:dateTime`), the datatype URI is stored as a metadata property `<predicate>__datatype` alongside the lexical value, enabling accurate round-trip preservation.

**Result Structure:**
```cypher
(:Resource {
  uri: "http://example.org/person1",
  `http://example.org/age`: 30,
  `http://example.org/height`: 1.75,
  `http://example.org/active`: true
})
```

**Example with custom datatype (GeoSPARQL geometry):**
```cypher
# Storing WKT geometry literal with geo:wktLiteral datatype
MERGE (s:Resource {uri: "http://example.org/location1"})
SET s.`http://www.opengis.net/ont/geosparql#asWKT` = "POINT(-0.118 51.509)"
SET s.`http://www.opengis.net/ont/geosparql#asWKT__datatype` = "http://www.opengis.net/ont/geosparql#wktLiteral"
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
| **Space** | ✅ Primitive types stored natively; custom datatypes add metadata property |
| **Performance** | ✅ Primitive types (int, double, boolean) stored as native FalkorDB types |
| **Indexing** | ✅ Numeric types support proper ordering and range queries |
| **Query** | ✅ Type-aware comparisons work correctly for primitive types |
| **Fidelity** | ✅ Custom datatypes fully preserved (e.g., geo:wktLiteral, xsd:dateTime) |
| **Custom Types** | ✅ GeoSPARQL and other custom datatypes work correctly |

#### Implementation Details

**Primitive Type Storage**: Common XSD types (int, double, float, boolean) are stored as their native FalkorDB equivalents:

```java
// Integer stored as FalkorDB integer (not string)
person.addProperty(age, model.createTypedLiteral(30));
// Stored in FalkorDB as: `http://example.org/age` = 30 (integer type)

// Boolean stored as FalkorDB boolean
person.addProperty(active, model.createTypedLiteral(true));
// Stored in FalkorDB as: `http://example.org/active` = true (boolean type)
```

**Custom Datatype Preservation**: For non-primitive datatypes (geometry literals, dates, etc.), the datatype URI is stored as metadata:

```java
// GeoSPARQL WKT literal with geo:wktLiteral datatype
String wktLiteralURI = "http://www.opengis.net/ont/geosparql#wktLiteral";
RDFDatatype wktDatatype = TypeMapper.getInstance().getSafeTypeByName(wktLiteralURI);
Literal wktLiteral = model.createTypedLiteral("POINT(-0.118 51.509)", wktDatatype);
geometry.addProperty(hasGeometry, wktLiteral);

// Stored in FalkorDB as:
// `http://www.opengis.net/ont/geosparql#asWKT` = "POINT(-0.118 51.509)"
// `http://www.opengis.net/ont/geosparql#asWKT__datatype` = "http://www.opengis.net/ont/geosparql#wktLiteral"
```

On retrieval, the adapter automatically reconstructs the typed literal:
- Checks for `<predicate>__datatype` property
- Uses `TypeMapper` to get the correct datatype
- Creates properly typed literal with preserved datatype URI

This ensures custom datatypes like `geo:wktLiteral` are preserved exactly, preventing errors during GeoSPARQL spatial index initialization.

**Indexing - Numeric Range Queries**: Primitive numeric types now support proper ordering:

```turtle
ex:product1 ex:price "9.99"^^xsd:decimal .
ex:product2 ex:price "10.00"^^xsd:decimal .
ex:product3 ex:price "100.00"^^xsd:decimal .
```

```sparql
# Numeric comparisons work correctly
SELECT ?product WHERE {
  ?product ex:price ?price .
  FILTER(?price > 10.00)
}
# Returns product2 and product3 correctly
```

### System Test

See: 
- [`RDFMappingTest.testTypedLiterals()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L332) - Tests primitive datatype preservation
- [`RDFMappingTest.testCustomDatatypePreservation()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L677) - Tests GeoSPARQL geometry datatype preservation

### GeoSPARQL Geometry Literals Example

A common use case for custom datatypes is GeoSPARQL geometry data:

```turtle
@prefix geo: <http://www.opengis.net/ont/geosparql#> .
@prefix ex: <http://example.org/> .

ex:location1 geo:asWKT "POINT(-0.118 51.509)"^^geo:wktLiteral .
```

**Stored in FalkorDB:**
```cypher
(:Resource {
  uri: "http://example.org/location1",
  `http://www.opengis.net/ont/geosparql#asWKT`: "POINT(-0.118 51.509)",
  `http://www.opengis.net/ont/geosparql#asWKT__datatype`: "http://www.opengis.net/ont/geosparql#wktLiteral"
})
```

**Retrieved via Jena API:**
```java
Statement stmt = location.getProperty(hasGeometry);
Literal wktLiteral = stmt.getLiteral();
// wktLiteral.getDatatypeURI() returns "http://www.opengis.net/ont/geosparql#wktLiteral"
// NOT "http://www.w3.org/2001/XMLSchema#string"
```

This preservation is critical for GeoSPARQL functionality, as the spatial index initialization scans for geometry literals and requires them to have proper geometry datatypes (not `xsd:string`).

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

#### Cons Elaboration

**Space - Multiple Language Values**: Since properties are single-valued, multiple languages for the same predicate conflict:

```turtle
# These are meant to be the same label in different languages
ex:paris rdfs:label "Paris"@en .
ex:paris rdfs:label "Parigi"@it .
ex:paris rdfs:label "巴黎"@zh .
```

In property-based storage, only the last value is retained. Workarounds:
- Use language-specific properties: `ex:label_en`, `ex:label_it`
- Store language variants as an array (requires custom handling)

**Performance - Language Filtering**: LANGMATCHES queries require scanning all values:

```sparql
# This requires post-processing, not index lookup
SELECT ?label WHERE {
  ex:paris rdfs:label ?label .
  FILTER(LANGMATCHES(LANG(?label), "en"))
}
```

**Indexing**: Language variants cannot be efficiently indexed because:
- Property values don't preserve language tags in FalkorDB storage
- No native language-aware index exists

**Query - LANGMATCHES**: While SPARQL's LANGMATCHES works in Jena, it operates on post-retrieval data:

```sparql
# Works but slow for large datasets
SELECT ?city ?label WHERE {
  ?city a ex:City ;
        rdfs:label ?label .
  FILTER(LANGMATCHES(LANG(?label), "fr"))
}
```

**Completeness**: The current implementation stores only the lexical form, losing the language tag:

```java
// Language tag may be lost in round-trip
paris.addProperty(RDFS.label, model.createLiteral("Paris", "en"));
String label = paris.getProperty(RDFS.label).getString(); // Returns "Paris"
// But: paris.getProperty(RDFS.label).getLanguage() may return "" or null
```

### System Test

See: [`RDFMappingTest.testLanguageTaggedLiterals()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L368)

---

## 9. Blank Nodes (Anonymous Resources)

Blank nodes (also called anonymous nodes or bnodes) are resources without a URI. They are used for intermediate nodes that don't need global identifiers.

### RDF Triple Notation

```
_:address <http://example.org/street> "123 Main St" .
_:address <http://example.org/city> "Springfield" .
<http://example.org/person1> <http://example.org/hasAddress> _:address .
```

### Turtle Syntax

```turtle
@prefix ex: <http://example.org/> .

# Using blank node syntax with []
ex:person1 ex:hasAddress [
    ex:street "123 Main St" ;
    ex:city "Springfield"
] .

# Or using explicit blank node label
_:addr ex:street "456 Oak Ave" ;
       ex:city "Shelbyville" .
ex:person2 ex:hasAddress _:addr .
```

### Generated FalkorDB Graph (Cypher)

```cypher
# Blank node stored with _: prefix in uri
MERGE (addr:Resource {uri: "_:address"})
SET addr.`http://example.org/street` = "123 Main St"
SET addr.`http://example.org/city` = "Springfield"

MERGE (p:Resource {uri: "http://example.org/person1"})
MERGE (addr:Resource {uri: "_:address"})
MERGE (p)-[:`http://example.org/hasAddress`]->(addr)
```

**Result Structure:**
```cypher
(:Resource {uri: "http://example.org/person1"})
  -[:`http://example.org/hasAddress`]->
(:Resource {
  uri: "_:b0",
  `http://example.org/street`: "123 Main St",
  `http://example.org/city`: "Springfield"
})
```

> **Note**: Blank node labels are generated by Jena (e.g., `_:b0`, `_:b1`) and are session-specific. The same blank node in different parse sessions may have different labels. When stored in FalkorDB, blank nodes are represented as resources with URIs prefixed with `_:` (e.g., `uri: "_:b0"`). When retrieved, they may appear as regular resources with `_:` prefixed URIs rather than true Jena blank nodes.

### Fuseki curl Expression

**Insert data with blank nodes:**
```bash
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX ex: <http://example.org/>
INSERT DATA {
  ex:person1 ex:hasAddress [
    ex:street "123 Main St" ;
    ex:city "Springfield"
  ] .
}'
```

**Query blank node data:**
```bash
curl -X POST http://localhost:3330/falkor/query \
  -H "Accept: application/sparql-results+json" \
  --data-urlencode 'query=PREFIX ex: <http://example.org/>
SELECT ?street ?city WHERE {
  ex:person1 ex:hasAddress ?addr .
  ?addr ex:street ?street ;
        ex:city ?city .
}'
```

### Jena API Code

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.*;

Model model = FalkorDBModelFactory.createDefaultModel();

try {
    // Create a blank node for address
    Resource address = model.createResource(); // Creates anonymous resource (blank node)
    
    Property street = model.createProperty("http://example.org/street");
    Property city = model.createProperty("http://example.org/city");
    Property hasAddress = model.createProperty("http://example.org/hasAddress");
    
    // Add properties to blank node
    address.addProperty(street, "123 Main St");
    address.addProperty(city, "Springfield");
    
    // Link person to blank node
    Resource person = model.createResource("http://example.org/person1");
    person.addProperty(hasAddress, address);
    
    // Verify: 3 triples (2 address properties + 1 relationship)
    assert model.size() == 3;
    
    // Query the address through the relationship
    Resource addr = person.getProperty(hasAddress).getResource();
    assert addr.isAnon(); // Verify it's a blank node
    assert addr.getProperty(street).getString().equals("123 Main St");
} finally {
    model.close();
}
```

### Pros and Cons

| Aspect | Analysis |
|--------|----------|
| **Space** | ✅ Efficient - same storage as named resources |
| **Performance** | ✅ Same query performance as named resources |
| **Indexing** | ✅ URI property (with `_:` prefix) is indexed |
| **Identity** | ⚠️ Blank node labels are session-specific; not globally unique |
| **Reification** | ⚠️ Cannot reference blank nodes from outside the graph easily |
| **Use Case** | ✅ Ideal for intermediate/structural nodes (addresses, measurements, etc.) |

#### Cons Elaboration

**Identity - Session-Specific Labels**: Blank node identifiers are not stable across sessions:

```java
// Session 1: Parse Turtle file
Model model1 = parseFile("data.ttl");
Resource addr1 = model1.getResource("_:b0");  // Blank node labeled "_:b0"

// Session 2: Parse same file again
Model model2 = parseFile("data.ttl");
Resource addr2 = model2.getResource("_:b0");  // Different blank node, same label!

// These are NOT the same resource - labels are session-local
```

Example of the problem:

```turtle
# File: person.ttl
ex:john ex:hasAddress [
    ex:street "123 Main St"
] .
```

```java
// First parse assigns _:b0
// Second parse might assign _:b1 or reuse _:b0 for different node
// Cannot reliably reference blank nodes by their labels across loads
```

**Reification - External References**: Blank nodes cannot be referenced from outside:

```turtle
# Graph A
ex:john ex:hasAddress _:addr .
_:addr ex:street "123 Main St" .

# Graph B - CANNOT reference _:addr from Graph A!
# This does NOT work:
_:addr ex:city "Springfield" .  # This creates a NEW blank node in Graph B
```

Use cases where this is problematic:
- Distributed systems sharing data across multiple graphs
- Incremental updates to blank node properties from external sources
- Cross-graph queries joining on blank nodes

Workaround: Use skolemized URIs (convert blank nodes to unique URIs) when cross-graph references are needed:

```turtle
# Instead of blank node, use generated URI
ex:john ex:hasAddress ex:addr_12345 .
ex:addr_12345 ex:street "123 Main St" .
# Now ex:addr_12345 can be referenced from anywhere
```

### System Test

See: [`RDFMappingTest.testBlankNodes()`](jena-falkordb-adapter/src/test/java/com/falkordb/jena/RDFMappingTest.java#L386)

---

## 10. Complex Graph with Mixed Triples

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
| `testBlankNodes` | Blank nodes (anonymous resources) | ✅ | ✅ |
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
