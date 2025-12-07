# Getting Started with Jena-Fuseki-FalkorDB

This guide will help you set up and start using the Jena-Fuseki-FalkorDB SPARQL server in under 10 minutes.

## Overview

Jena-Fuseki-FalkorDB provides a SPARQL endpoint backed by FalkorDB graph database. It supports:

- **SPARQL Query**: Execute SELECT, ASK, CONSTRUCT, and DESCRIBE queries
- **SPARQL Update**: Execute INSERT, DELETE, and other update operations
- **Graph Store Protocol**: Read and write RDF data via HTTP
- **Configuration via Assembler**: Use TTL config files or environment variables
- **Automatic Query Optimization**: SPARQL patterns automatically translated to efficient Cypher queries
  - OPTIONAL patterns → Cypher OPTIONAL MATCH (Nx fewer queries)
  - Variable objects/predicates → UNION queries for mixed data
  - Transaction batching for bulk operations

## Quick Start (3 Steps)

### Step 1: Start FalkorDB

```bash
docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
```

### Step 2: Build and Run

**Option A: Run from the project root**
```bash
mvn clean install -DskipTests
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar
```

**Option B: Run with Maven exec plugin**
```bash
cd jena-fuseki-falkordb
mvn exec:java -Dexec.mainClass="com.falkordb.FalkorFuseki"
```

The server will start on port 3330 by default with the endpoint at `/falkor`.

### Step 3: Insert and Query Data

**Insert data:**
```bash
curl -X POST --data "INSERT DATA { <http://example/s> <http://example/p> 123 }" \
     -H "Content-Type: application/sparql-update" \
     http://localhost:3330/falkor/update
```

**Query data:**
```bash
curl -G --data-urlencode "query=SELECT * WHERE { ?s ?p ?o }" \
     http://localhost:3330/falkor/query
```

---

## Father-Son Example (from Requirements)

This example demonstrates a real-world use case modeling family relationships.

### Insert Father-Son Relationships

```bash
curl -X POST \
     -H "Content-Type: application/sparql-update" \
     --data '
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
INSERT DATA {
    ff:Jacob a ff:Male .
    ff:Isaac a ff:Male ;
        ff:father_of ff:Jacob .
    ff:Abraham a ff:Male ;
        ff:father_of ff:Isaac .
}' \
     http://localhost:3330/falkor/update
```

### Query Father-Son Relationships

```bash
curl -G --data-urlencode "query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?father ?son
WHERE {
    ?father ff:father_of ?son .
}" \
     http://localhost:3330/falkor/query
```

**Expected output:**
```json
{
  "head": { "vars": [ "father", "son" ] },
  "results": {
    "bindings": [
      {
        "father": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Isaac" },
        "son": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Jacob" }
      },
      {
        "father": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Abraham" },
        "son": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Isaac" }
      }
    ]
  }
}
```

### Query All Males

```bash
curl -G --data-urlencode "query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?person
WHERE {
    ?person a ff:Male .
}" \
     http://localhost:3330/falkor/query
```

---

## Configuration Options

### Option 1: Environment Variables (Default Mode)

Set environment variables before starting the server:

```bash
export FALKORDB_HOST=localhost
export FALKORDB_PORT=6379
export FALKORDB_GRAPH=my_knowledge_graph
export FUSEKI_PORT=3330

java -jar jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar
```

| Variable | Default | Description |
|----------|---------|-------------|
| `FALKORDB_HOST` | `localhost` | FalkorDB server hostname |
| `FALKORDB_PORT` | `6379` | FalkorDB server port |
| `FALKORDB_GRAPH` | `my_knowledge_graph` | FalkorDB graph name |
| `FUSEKI_PORT` | `3330` | Fuseki server port |
| `FUSEKI_CONFIG` | - | Path to config file (alternative to --config) |

### Option 2: Configuration File (Assembler Mode)

Create a TTL configuration file to customize the server:

```bash
java -jar jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar --config config-falkordb.ttl
```

**Example configuration file (`config-falkordb.ttl`):**

```turtle
@prefix :        <#> .
@prefix falkor:  <http://falkordb.com/jena/assembler#> .
@prefix fuseki:  <http://jena.apache.org/fuseki#> .
@prefix ja:      <http://jena.hpl.hp.com/2005/11/Assembler#> .
@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

# Fuseki server configuration
[] rdf:type fuseki:Server ;
   fuseki:services ( :service ) .

# The FalkorDB-backed service
:service rdf:type fuseki:Service ;
    fuseki:name "dataset" ;
    fuseki:endpoint [ fuseki:operation fuseki:query ; fuseki:name "query" ] ;
    fuseki:endpoint [ fuseki:operation fuseki:update ; fuseki:name "update" ] ;
    fuseki:endpoint [ fuseki:operation fuseki:gsp-rw ; fuseki:name "data" ] ;
    fuseki:dataset :dataset_rdf .

# RDF Dataset wrapping FalkorDB model
:dataset_rdf rdf:type ja:RDFDataset ;
    ja:defaultGraph :falkor_db_model .

# FalkorDB-backed model configuration
:falkor_db_model rdf:type falkor:FalkorDBModel ;
    falkor:host "localhost" ;
    falkor:port 6379 ;
    falkor:graphName "my_graph" .
```

---

## SPARQL Endpoint Reference

Once the server is running, the following endpoints are available:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/falkor/query` | GET/POST | SPARQL Query endpoint |
| `/falkor/update` | POST | SPARQL Update endpoint |
| `/falkor/data` | GET/PUT/POST/DELETE | Graph Store Protocol |

### Query Examples

**SELECT query:**
```bash
curl -G --data-urlencode "query=SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10" \
     http://localhost:3330/falkor/query
```

**ASK query:**
```bash
curl -G --data-urlencode "query=ASK { <http://example/s> ?p ?o }" \
     http://localhost:3330/falkor/query
```

**CONSTRUCT query:**
```bash
curl -G --data-urlencode "query=CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o } LIMIT 10" \
     -H "Accept: text/turtle" \
     http://localhost:3330/falkor/query
```

### Update Examples

**INSERT DATA:**
```bash
curl -X POST \
     -H "Content-Type: application/sparql-update" \
     --data 'INSERT DATA { <http://example/subject> <http://example/predicate> "value" }' \
     http://localhost:3330/falkor/update
```

**DELETE DATA:**
```bash
curl -X POST \
     -H "Content-Type: application/sparql-update" \
     --data 'DELETE DATA { <http://example/subject> <http://example/predicate> "value" }' \
     http://localhost:3330/falkor/update
```

**DELETE WHERE:**
```bash
curl -X POST \
     -H "Content-Type: application/sparql-update" \
     --data 'DELETE WHERE { ?s ?p ?o }' \
     http://localhost:3330/falkor/update
```

---

## Social Network Example

A more comprehensive example modeling a social network with FOAF vocabulary.

### Insert Social Network Data

```bash
curl -X POST \
     -H "Content-Type: application/sparql-update" \
     --data '
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>
INSERT DATA {
    ex:alice a foaf:Person ;
        foaf:name "Alice" ;
        foaf:age 30 ;
        foaf:knows ex:bob .
    ex:bob a foaf:Person ;
        foaf:name "Bob" ;
        foaf:age 35 ;
        foaf:knows ex:charlie .
    ex:charlie a foaf:Person ;
        foaf:name "Charlie" ;
        foaf:age 25 .
}' \
     http://localhost:3330/falkor/update
```

### Query All People

```bash
curl -G --data-urlencode "query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name ?age
WHERE {
    ?person a foaf:Person ;
        foaf:name ?name ;
        foaf:age ?age .
}
ORDER BY ?name" \
     http://localhost:3330/falkor/query
```

### Query Who Knows Whom

```bash
curl -G --data-urlencode "query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?friend
WHERE {
    ?person foaf:knows ?friend .
}" \
     http://localhost:3330/falkor/query
```

### Query with OPTIONAL Patterns

OPTIONAL patterns allow retrieving all required data with NULL for missing optional values in a single efficient query. This is automatically optimized to Cypher OPTIONAL MATCH:

```bash
# Insert test data with some people having emails and some not
curl -X POST \
     -H "Content-Type: application/sparql-update" \
     --data '
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>
INSERT DATA {
    ex:alice a foaf:Person ;
        foaf:name "Alice" ;
        foaf:email <mailto:alice@example.org> .
    ex:bob a foaf:Person ;
        foaf:name "Bob" .
    ex:charlie a foaf:Person ;
        foaf:name "Charlie" ;
        foaf:email <mailto:charlie@example.org> .
}' \
     http://localhost:3330/falkor/update

# Query all people with optional email addresses
curl -G --data-urlencode "query=
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name ?email
WHERE {
    ?person a foaf:Person ;
        foaf:name ?name .
    OPTIONAL { ?person foaf:email ?email }
}
ORDER BY ?name" \
     http://localhost:3330/falkor/query
```

**Result:** Returns all three people with their emails where available (Alice and Charlie have emails, Bob shows NULL)

**Performance:** Single query instead of N+1 queries (1 for persons + N for optional emails). See [samples/optional-patterns/](../samples/optional-patterns/) for more examples.

---

## Troubleshooting

### Connection Refused

**Problem:** `Connection refused` error when starting the server

**Solution:** Ensure FalkorDB is running:
```bash
docker ps | grep falkordb
# If not running, start it:
docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
```

### Config File Not Found

**Problem:** `Config file not found` error

**Solution:** Verify the config file path is correct and accessible:
```bash
# Check if file exists
ls -la config-falkordb.ttl

# Use absolute path if needed
java -jar jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar --config /full/path/to/config-falkordb.ttl
```

### Query Returns Empty Results

**Problem:** Queries return no results even after inserting data

**Solution:** 
1. Verify data was inserted successfully
2. Check graph name matches between insert and query
3. Try a simple SELECT query first:
```bash
curl -G --data-urlencode "query=SELECT * WHERE { ?s ?p ?o } LIMIT 10" \
     http://localhost:3330/falkor/query
```

### Verify Data in FalkorDB Directly

```bash
redis-cli -p 6379
> GRAPH.QUERY my_knowledge_graph "MATCH (n) RETURN n LIMIT 10"
```

---

## Command Line Reference

```
FalkorDB Fuseki Server

Usage:
  java -jar jena-fuseki-falkordb.jar [options]

Options:
  --config <file>  Path to TTL configuration file
  --help           Show help message

Examples:
  # Start with default settings
  java -jar jena-fuseki-falkordb.jar

  # Start with config file
  java -jar jena-fuseki-falkordb.jar --config config-falkordb.ttl

  # Start with environment variables
  FALKORDB_HOST=192.168.1.100 java -jar jena-fuseki-falkordb.jar
```

---

## Inference with Rules

The server supports inference using Jena's rule-based reasoning system. You can configure custom rules to automatically infer new triples from existing data.

### Grandfather Example

The server includes example rules for inferring grandfather relationships. Given:
- Abraham ff:father_of Isaac
- Isaac ff:father_of Jacob

The rule will infer:
- Abraham ff:grandfather_of Jacob

### Using Inference Configuration

Use the inference configuration file to enable rule-based reasoning:

```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar --config jena-fuseki-falkordb/src/main/resources/config-falkordb-inference.ttl
```

**Example inference configuration (`config-falkordb-inference.ttl`):**

```turtle
@prefix :        <#> .
@prefix falkor:  <http://falkordb.com/jena/assembler#> .
@prefix fuseki:  <http://jena.apache.org/fuseki#> .
@prefix ja:      <http://jena.hpl.hp.com/2005/11/Assembler#> .
@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .

# Declare FalkorDBModel as a subclass of ja:Model for assembler compatibility
falkor:FalkorDBModel rdfs:subClassOf ja:Model .

# Fuseki server configuration
[] rdf:type fuseki:Server ;
   fuseki:services ( :service ) .

# Service with inference endpoint
:service rdf:type fuseki:Service ;
    fuseki:name "falkor" ;
    fuseki:endpoint [ fuseki:operation fuseki:query ; fuseki:name "query" ] ;
    fuseki:endpoint [ fuseki:operation fuseki:update ; fuseki:name "update" ] ;
    fuseki:dataset :dataset_rdf .

# RDF Dataset wrapping the inference model
:dataset_rdf rdf:type ja:RDFDataset ;
    ja:defaultGraph :model_inf .

# Inference model with Generic Rule Reasoner
:model_inf rdf:type ja:InfModel ;
    ja:baseModel :falkor_db_model ;
    ja:reasoner [
        ja:reasonerURL <http://jena.hpl.hp.com/2003/GenericRuleReasoner> ;
        ja:rulesFrom <file:rules/grandfather_of_bwd.rule> ;
    ] .

# FalkorDB-backed model configuration
:falkor_db_model rdf:type falkor:FalkorDBModel ;
    falkor:host "localhost" ;
    falkor:port 6379 ;
    falkor:graphName "knowledge_graph" .
```

### Insert Data and Query Inferred Results

**Insert father relationships:**

```bash
curl -X POST \
     -H "Content-Type: application/sparql-update" \
     --data '
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
INSERT DATA {
    ff:Jacob a ff:Male .
    ff:Isaac a ff:Male ;
        ff:father_of ff:Jacob .
    ff:Abraham a ff:Male ;
        ff:father_of ff:Isaac .
}' \
     http://localhost:3330/falkor/update
```

**Query inferred grandfather relationship:**

```bash
curl -G --data-urlencode "query=
PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
SELECT ?grandfather ?grandson
WHERE {
    ?grandfather ff:grandfather_of ?grandson .
}" \
     http://localhost:3330/falkor/query
```

**Expected output:**
```json
{
  "head": { "vars": [ "grandfather", "grandson" ] },
  "results": {
    "bindings": [
      {
        "grandfather": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Abraham" },
        "grandson": { "type": "uri", "value": "http://www.semanticweb.org/ontologies/2023/1/fathers_father#Jacob" }
      }
    ]
  }
}
```

### Available Rule Files

The server includes these rule files in `rules/`:

| File | Description |
|------|-------------|
| `grandfather_of_bwd.rule` | Backward-chaining rule for grandfather inference |
| `grandfather_of_fwd.rule` | Forward-chaining rule for grandfather inference |

### Rule File Format

Rules follow Jena's rule syntax. Example (backward-chaining):

```
@prefix ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#> .
@include <owlmicro>.

[rule_grandfather_of: 
    (?x ff:grandfather_of ?z)
    <- 
    (?x ff:father_of ?y)  
    (?y ff:father_of ?z) 
]
```

For more information on Jena rules, see the [Jena Inference documentation](https://jena.apache.org/documentation/inference/).

---

## Next Steps

- Read the [main README](../README.md) for more details on the adapter
- Explore the [configuration examples](src/main/resources/config-falkordb.ttl)
- Try the [inference configuration](src/main/resources/config-falkordb-inference.ttl) for rule-based reasoning
- Check the [Proposal document](../FusekiIntegration/Proposal.md) for architecture details
- Review the [Apache Jena Fuseki documentation](https://jena.apache.org/documentation/fuseki2/)
- Learn about [SPARQL query language](https://www.w3.org/TR/sparql11-query/)
- Learn about [Jena Inference](https://jena.apache.org/documentation/inference/) for rule-based reasoning
