[![CI](https://github.com/FalkorDB/jena-falkordb-adapter/actions/workflows/ci.yml/badge.svg)](https://github.com/FalkorDB/jena-falkordb-adapter/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.falkordb/jena-falkordb-adapter.svg)](https://central.sonatype.com/artifact/com.falkordb/jena-falkordb-adapter)
[![license](https://img.shields.io/github/license/FalkorDB/jena-falkordb-adapter.svg)](https://github.com/FalkorDB/jena-falkordb-adapter/blob/master/LICENSE)
[![Release](https://img.shields.io/github/release/FalkorDB/jena-falkordb-adapter.svg)](https://github.com/FalkorDB/jena-falkordb-adapter/releases/latest)
[![Javadocs](https://www.javadoc.io/badge/com.falkordb/jena-falkordb-adapter.svg)](https://www.javadoc.io/doc/com.falkordb/jena-falkordb-adapter)

[![Discord](https://img.shields.io/discord/1146782921294884966?style=flat-square)](https://discord.gg/ErBEqN9E)
[![Discuss the project](https://img.shields.io/badge/discussions-FalkorDB-brightgreen.svg)](https://github.com/FalkorDB/FalkorDB/discussions)
[![codecov](https://codecov.io/gh/FalkorDB/jena-falkordb-adapter/graph/badge.svg?token=kQg1yvyp0u)](https://codecov.io/gh/FalkorDB/jena-falkordb-adapter)

# Jena-FalkorDB Adapter

[![Try Free](https://img.shields.io/badge/Try%20Free-FalkorDB%20Cloud-FF8101?labelColor=FDE900&style=for-the-badge&link=https://app.falkordb.cloud)](https://app.falkordb.cloud)

## What is Jena-FalkorDB Adapter?

The Jena-FalkorDB Adapter is a high-performance integration layer that bridges the Apache Jena RDF framework with FalkorDB, a graph database powered by GraphBLAS. This adapter enables you to:

- **Execute SPARQL queries** on data stored in FalkorDB graph database
- **Use Apache Jena's RDF API** with FalkorDB as the backend storage
- **Leverage automatic query optimization** with SPARQL-to-Cypher translation
- **Deploy production-ready SPARQL endpoints** with the included Fuseki server integration

The adapter provides an efficient mapping between RDF triples and property graph structures, with features like automatic URI indexing, efficient literal storage as node properties, and native support for rdf:type as graph labels.

## Table of Contents

- [What is Jena-FalkorDB Adapter?](#what-is-jena-falkordb-adapter)
- [Project Structure](#project-structure)
  - [Module Overview](#module-overview)
  - [Module Details](#module-details)
- [Features](#features)
- [Quick Start](#quick-start)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Using from Maven](#using-from-maven)
- [How to Run Locally](#how-to-run-locally)
- [Deploying to Standalone Fuseki Server](#deploying-to-standalone-fuseki-server)
- [Usage Examples](#usage-examples)
- [Examples and Samples](#examples-and-samples)
- [How to Run the Examples](#how-to-run-the-examples)
- [Documentation](#documentation)
- [Performance Tips](#performance-tips)
- [Troubleshooting](#troubleshooting)
- [Advanced Usage](#advanced-usage)
- [Testing](#testing)
- [Contributing](#contributing)
- [Releasing to Maven Central](#releasing-to-maven-central)
- [CI/CD](#cicd)
- [License](#license)
- [Support](#support)

## Project Structure

This is a multi-module Maven project consisting of four main modules:

### Module Overview

| Module | Description | Use Case |
|--------|-------------|----------|
| **[jena-falkordb-adapter](jena-falkordb-adapter/)** | Core adapter library that integrates Apache Jena with FalkorDB | Use this as a dependency in your Java applications to work with RDF data stored in FalkorDB |
| **[jena-falkordb-assembler](jena-falkordb-assembler/)** | Jena Assembler integration for FalkorDB | Enables declarative configuration of FalkorDB models using Jena Assembler configuration files (TTL format) |
| **[jena-geosparql](jena-geosparql/)** | GeoSPARQL support module | Bundles Apache Jena's GeoSPARQL implementation with all dependencies for spatial queries on geographic data |
| **[jena-fuseki-falkordb](jena-fuseki-falkordb/)** | Apache Jena Fuseki server with FalkorDB backend | Deploy as a standalone SPARQL endpoint server for querying FalkorDB via HTTP |

### Module Details

#### jena-falkordb-adapter (Core Library)

The core adapter library that provides:
- `FalkorDBGraph` - Custom Jena `Graph` implementation backed by FalkorDB
- `FalkorDBModelFactory` - Factory methods for creating RDF models
- Query pushdown engine for SPARQL-to-Cypher translation
- Transaction support with batch write optimizations
- Magic property (`falkor:cypher`) for direct Cypher execution

**When to use:** Include this as a Maven dependency when building Java applications that need to work with RDF data in FalkorDB.

#### jena-falkordb-assembler (Configuration)

Provides Jena Assembler vocabulary and assembler for FalkorDB models, allowing you to configure FalkorDB-backed models declaratively using Turtle (TTL) configuration files instead of programmatic configuration.

**When to use:** Use this when you want to configure your FalkorDB models using declarative configuration files, especially in Fuseki server deployments.

#### jena-geosparql (Spatial Queries)

Bundles Apache Jena's GeoSPARQL implementation with all dependencies, enabling spatial queries on geographic data using SPARQL. Supports WKT (Well-Known Text) format for geometric data and spatial functions like `sfContains`, `sfWithin`, `sfIntersects`, etc.

**When to use:** Include this module when you need to perform spatial queries on geographic data stored in FalkorDB.

#### jena-fuseki-falkordb (SPARQL Server)

A standalone SPARQL endpoint server built on Apache Jena Fuseki with FalkorDB as the backend. Provides:
- Web-based UI for query execution at `http://localhost:3330/`
- REST API endpoints for SPARQL query, update, and Graph Store Protocol
- Support for rule-based inference and reasoning
- Environment variable and TTL file-based configuration

**When to use:** Deploy this when you need a production-ready SPARQL endpoint that can be accessed via HTTP by multiple clients.

## Features

- ✅ Use Apache Jena's RDF API with FalkorDB backend
- ✅ Execute SPARQL queries on FalkorDB data
- ✅ Automatic translation of RDF triples to Cypher operations
- ✅ **Efficient literal storage as node properties** (not separate nodes)
- ✅ **rdf:type support with native graph labels**
- ✅ **Automatic URI indexing** for optimal query performance
- ✅ **Custom driver support** for advanced configuration
- ✅ **Automatic query pushdown** - SPARQL to Cypher translation enabled by default
  - Variable objects (queries both properties and relationships with UNION)
  - Variable predicates (queries all properties, relationships, and types)
  - Closed-chain patterns (mutual references)
  - OPTIONAL patterns (returns all required matches with NULL for missing optional data)
  - UNION patterns (alternative query patterns in single database call)
  - Aggregations (GROUP BY with COUNT, SUM, AVG, MIN, MAX - computed database-side)
  - **Geospatial queries** (GeoSPARQL to FalkorDB point() and distance() functions)
- ✅ **Magic property (falkor:cypher)** for direct Cypher execution within SPARQL
- ✅ **Fuseki SPARQL server** with FalkorDB backend
- ✅ **Inference support** with rule-based reasoning (RDFS/OWL rules)
- ✅ Connection pooling for better performance
- ✅ Easy-to-use factory pattern for model creation
- ✅ Continuous integration with Java 25 testing
- ✅ Automated publishing to Maven Central

## Quick Start

> **📖 Complete Demo Guide**: For a comprehensive walkthrough of all optimizations with curl commands and Jaeger tracing, see [DEMO.md](DEMO.md)

## Prerequisites

### Install Java and Maven

Install Java 21.0.5-graal and Maven 3.9.11 using SDKMAN (matching versions in `.sdkmanrc`):

```bash
# Install SDKMAN if not already installed
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install Java and Maven from project's .sdkmanrc
sdk env install

# Verify installations
java -version  # Should show: java version "21.0.5" ... (GraalVM)
mvn -version   # Should show: Apache Maven 3.9.11
```

### Install FalkorDB with Tracing

Start FalkorDB and Jaeger using docker-compose (required for development and testing):

```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

This starts:
- **FalkorDB** on `localhost:6379`
- **Jaeger UI** on `http://localhost:16686` (for viewing traces)

> **Note:** The docker-compose setup is required for building and running tests.

### 2. Clone and Build the Project

If you want to build the adapter from source, full build and run instructions are provided in the
"Developing / Building from source" section further down in this README. For a quick start using the
published artifact, see the "Using from Maven" section below.

## Using from Maven

If the project artifacts are published to Maven Central or OSSRH snapshots, you can add
the adapter as a dependency in your own project's `pom.xml`.

### Adapter Library

For the snapshot (development) version:

```xml
<repositories>
    <repository>
        <id>ossrh</id>
        <url>https://oss.sonatype.org/content/repositories/snapshots</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.falkordb</groupId>
        <artifactId>jena-falkordb-adapter</artifactId>
        <version>0.2.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

For a released version available on Maven Central (replace `x.y.z` with the release):

```xml
<dependencies>
    <dependency>
        <groupId>com.falkordb</groupId>
        <artifactId>jena-falkordb-adapter</artifactId>
        <version>x.y.z</version>
    </dependency>
</dependencies>
```

### Fuseki Server with FalkorDB

If you want to run a Fuseki SPARQL endpoint with FalkorDB backend:

```xml
<dependencies>
    <dependency>
        <groupId>com.falkordb</groupId>
        <artifactId>jena-fuseki-falkordb</artifactId>
        <version>x.y.z</version>
    </dependency>
</dependencies>
```

Gradle (Groovy) example:

```groovy
repositories {
    mavenCentral()
    maven { url 'https://oss.sonatype.org/content/repositories/snapshots' }
}

dependencies {
    implementation 'com.falkordb:jena-falkordb-adapter:1.0-SNAPSHOT'
}
```

Gradle (Kotlin DSL) example:

```kotlin
repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

dependencies {
    implementation("com.falkordb:jena-falkordb-adapter:1.0-SNAPSHOT")
}
```

Note: snapshots live in the OSSRH snapshots repository; once a release is published to Maven Central you can rely on `mavenCentral()` without adding extra repositories.

## How to Run Locally

The fastest way to get started with the Jena-FalkorDB Adapter:

**Step 1:** Clone the project
```bash
git clone https://github.com/FalkorDB/jena-falkordb-adapter.git
cd jena-falkordb-adapter
```

**Step 2:** Start FalkorDB with Tracing
```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

> **Note:** The docker-compose must be running for tests to pass.

**Step 3:** Build the project
```bash
# Install Java and Maven using SDKMAN (see Prerequisites)
sdk env install

mvn clean install
```

**Step 4:** Run the Fuseki server with config file
```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

The Fuseki server will start at http://localhost:3330/ with the SPARQL endpoint at `/falkor`.

**Accessing the Fuseki Web UI:**
- Open your browser and navigate to **http://localhost:3330/**
- The web interface provides a query editor, dataset management, and server statistics
- You can run SPARQL queries directly from the browser UI

See [GETTING_STARTED.md](GETTING_STARTED.md) for detailed setup instructions, troubleshooting, and more examples.

## Deploying to Standalone Fuseki Server

The `jena-fuseki-falkordb` module can be deployed as a standalone SPARQL endpoint server. There are several deployment options:

### Deployment Option 1: Using Configuration File (Recommended)

The recommended approach uses the included [config-falkordb.ttl](jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl) file which implements a three-layer onion architecture:

- **Layer 1 (Outer)**: GeoSPARQL Dataset - handles spatial queries with indexing and optimization
- **Layer 2 (Middle)**: Inference Model - applies forward chaining rules for eager inference (e.g., grandfather relationships)
- **Layer 3 (Core)**: FalkorDB Model - physical storage layer connecting to Redis/FalkorDB

**Step 1:** Start FalkorDB with tracing:
```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

**Step 2:** Start the server with the included configuration:
```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

The server will be available at `http://localhost:3330/falkor` with full support for:
- SPARQL queries with GeoSPARQL spatial functions
- Forward chaining inference (e.g., grandfather relationships are automatically materialized)
- Persistent storage in FalkorDB

**Accessing the Fuseki Web UI:**
Open your browser and navigate to **http://localhost:3330/** to access the web interface for interactive querying and dataset management.

See the full [config-falkordb.ttl](jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl) file for the complete configuration and architecture details.

### Deployment Option 3: Integrating with Existing Fuseki

To integrate with an existing Apache Jena Fuseki installation:

**Step 1:** Copy the required JARs to Fuseki's lib directory:
```bash
# Copy the adapter and its dependencies
cp jena-falkordb-adapter/target/jena-falkordb-adapter-0.2.0-SNAPSHOT.jar \
   /path/to/fuseki/lib/

cp jena-falkordb-assembler/target/jena-falkordb-assembler-0.2.0-SNAPSHOT.jar \
   /path/to/fuseki/lib/

cp jena-geosparql/target/jena-geosparql-0.2.0-SNAPSHOT.jar \
   /path/to/fuseki/lib/

# Copy JFalkorDB dependency
cp ~/.m2/repository/com/falkordb/jfalkordb/0.6.0/jfalkordb-0.6.0.jar \
   /path/to/fuseki/lib/
```

**Step 2:** Copy the configuration file and rules:
```bash
cp jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl /path/to/fuseki/
cp -r rules /path/to/fuseki/
```

**Step 3:** Start Fuseki with the configuration:
```bash
cd /path/to/fuseki
./fuseki-server --config config-falkordb.ttl
```

### Deployment Option 4: Docker Deployment

Create a `Dockerfile` for containerized deployment:

```dockerfile
FROM openjdk:21-slim

# Install FalkorDB client tools (optional)
RUN apt-get update && apt-get install -y redis-tools && rm -rf /var/lib/apt/lists/*

# Copy the JAR and config file
COPY jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar /app/fuseki.jar
COPY jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl /app/config-falkordb.ttl
COPY rules /app/rules

# Environment variables
ENV FALKORDB_HOST=falkordb
ENV FALKORDB_PORT=6379
ENV FALKORDB_GRAPH=knowledge_graph
ENV FUSEKI_PORT=3330

EXPOSE 3330

CMD ["java", "-jar", "/app/fuseki.jar", "--config", "/app/config-falkordb.ttl"]
```

**Docker Compose** example with FalkorDB:

```yaml
services:
  falkordb:
    image: falkordb/falkordb:latest
    ports:
      - "6379:6379"
    volumes:
      - falkordb-data:/data

  fuseki:
    build: .
    ports:
      - "3330:3330"
    environment:
      - FALKORDB_HOST=falkordb
      - FALKORDB_PORT=6379
      - FALKORDB_GRAPH=knowledge_graph
      - FUSEKI_PORT=3330
    depends_on:
      - falkordb

volumes:
  falkordb-data:
```

Run with:
```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

### Testing Your Deployment

After deployment, test the endpoint:

```bash
# Insert data
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: application/sparql-update" \
  --data 'INSERT DATA { <http://example/s> <http://example/p> "value" }'

# Query data
curl -G http://localhost:3330/falkor/query \
  --data-urlencode 'query=SELECT * WHERE { ?s ?p ?o } LIMIT 10'
```

See [jena-fuseki-falkordb/GETTING_STARTED.md](jena-fuseki-falkordb/GETTING_STARTED.md) for more deployment examples, configuration options, and troubleshooting.

## Usage Examples

### Basic Usage

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

// Create a model backed by FalkorDB
var model = FalkorDBModelFactory.createDefaultModel();

try {
    // Create and add RDF data
    var person = model.createResource("http://example.org/alice");
    var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
    
    person.addProperty(name, "Alice");
    person.addProperty(RDF.type, model.createResource("http://xmlns.com/foaf/0.1/Person"));
    
    System.out.println("Data added to FalkorDB!");
    
} finally {
    model.close();
}
```

### Custom Connection Settings

```java
// Using builder pattern
var model = FalkorDBModelFactory.builder()
    .host("localhost")
    .port(6379)
    .graphName("my_graph")
    .build();

// Or direct method
var model = FalkorDBModelFactory.createModel("localhost", 6379, "my_graph");
```

### Using Custom Driver

For advanced configuration (authentication, timeouts, SSL, etc.), you can provide your own FalkorDB driver:

```java
import com.falkordb.Driver;
import com.falkordb.FalkorDB;

// Create a custom configured driver
var customDriver = FalkorDB.driver("localhost", 6379);
// Configure your driver as needed...

// Use with factory
var model = FalkorDBModelFactory.createModel(customDriver, "my_graph");

// Or use with builder
var model = FalkorDBModelFactory.builder()
    .driver(customDriver)
    .graphName("my_graph")
    .build();

// Or directly with the graph
var graph = new FalkorDBGraph(customDriver, "my_graph");
var model = ModelFactory.createModelForGraph(graph);

// Don't forget to close the driver when done
customDriver.close();
```

### Understanding the Storage Model

The adapter uses an efficient storage model that maps RDF to property graphs:

#### Literals as Properties
```java
// When you add a literal property:
person.addProperty(name, "Alice");

// It's stored in FalkorDB as:
// MERGE (s:Resource {uri: "http://example.org/person1"}) 
// SET s.`http://xmlns.com/foaf/0.1/name` = "Alice"

// Not as a separate node! This is much more efficient.
```

#### rdf:type as Labels
```java
// When you add a type:
person.addProperty(RDF.type, personType);

// It creates a label on the node:
// MERGE (s:Resource:`http://xmlns.com/foaf/0.1/Person` {uri: "http://example.org/alice"})

// You can then query by type efficiently
// Query pushdown automatically retrieves rdf:type from node labels
```

#### Resources as Relationships
```java
// When you connect resources:
alice.addProperty(knows, bob);

// It creates a relationship:
// MERGE (s:Resource {uri: "...alice"})
// MERGE (o:Resource {uri: "...bob"})
// MERGE (s)-[r:`http://xmlns.com/foaf/0.1/knows`]->(o)
```

This storage model provides:
- ✅ **Better Performance**: Literals stored as properties reduce graph complexity
- ✅ **Efficient Queries**: Labels enable fast type-based filtering
- ✅ **Automatic Query Pushdown**: SPARQL patterns automatically compiled to Cypher
- ✅ **Readable URIs**: Backtick notation preserves URIs without encoding
- ✅ **Automatic Indexing**: `Resource.uri` is indexed automatically

### SPARQL Queries

```java
import org.apache.jena.query.*;

var model = FalkorDBModelFactory.createDefaultModel();

var sparqlQuery = """
    PREFIX foaf: <http://xmlns.com/foaf/0.1/>
    SELECT ?name ?age
    WHERE {
      ?person foaf:name ?name .
      ?person foaf:age ?age .
    }
    ORDER BY ?age""";

try (var qexec = QueryExecutionFactory.create(sparqlQuery, model)) {
    var results = qexec.execSelect();
    
    while (results.hasNext()) {
        var solution = results.nextSolution();
        var name = solution.getLiteral("name").getString();
        var age = solution.getLiteral("age").getInt();
        System.out.println(name + " is " + age + " years old");
    }
}

model.close();
```

### Adding Complex Data

```java
var model = FalkorDBModelFactory.createDefaultModel();

// Define properties
var foafName = model.createProperty("http://xmlns.com/foaf/0.1/name");
var foafKnows = model.createProperty("http://xmlns.com/foaf/0.1/knows");
var foafAge = model.createProperty("http://xmlns.com/foaf/0.1/age");

// Create resources
var alice = model.createResource("http://example.org/alice")
    .addProperty(foafName, "Alice")
    .addProperty(foafAge, model.createTypedLiteral(30));

var bob = model.createResource("http://example.org/bob")
    .addProperty(foafName, "Bob")
    .addProperty(foafAge, model.createTypedLiteral(35));

// Add relationships
alice.addProperty(foafKnows, bob);

model.close();
```

## Why This Adapter is Efficient

### Storage Comparison

**Traditional Approach** (separate nodes for literals):
```cypher
// 3 nodes + 2 relationships for simple properties
(person:Resource {uri: "..."})
(name:Literal {value: "Alice"})
(age:Literal {value: "30"})
(person)-[:name]->(name)
(person)-[:age]->(age)
```

**Our Approach** (properties on nodes):
```cypher
// 1 node with properties
(person:Resource:Person {
  uri: "...",
  `http://xmlns.com/foaf/0.1/name`: "Alice",
  `http://xmlns.com/foaf/0.1/age`: 30
})
```

**Result**: 
- ⚡ **3x fewer nodes**
- ⚡ **Direct property access** (no relationship traversal)
- ⚡ **Native type filtering** with labels
- ⚡ **Automatic indexing** on URI

## Configuration

### Connection Pool Settings

The adapter uses JFalkorDB driver with connection pooling. Default settings are optimized for most use cases.

To customize connection settings, create your own driver and pass it to the factory (see "Using Custom Driver" section above).

### Graph Names

Each model is associated with a FalkorDB graph name. You can use different graph names for different datasets:

```java
var graph1 = FalkorDBModelFactory.createModel("users_graph");
var graph2 = FalkorDBModelFactory.createModel("products_graph");
```

## Limitations

This is a basic implementation with some limitations:

1. **Query Translation**: Not all SPARQL features are fully translated to Cypher
2. **Performance**: Translation overhead may impact performance for large datasets
3. **Complex Queries**: Some advanced SPARQL features may not work as expected or will fall back to standard evaluation:
   - ✅ OPTIONAL patterns are supported and optimized
   - ✅ UNION patterns are supported and optimized
   - ❌ MINUS (set difference) not yet optimized
   - ❌ Variable predicates in UNION branches not supported
   - ❌ Some deeply nested or complex query patterns may fall back

**Note on Inference/Reasoning**: Inference is supported via Jena's rule-based reasoning. When using `InfModel` (inference models), query pushdown is intentionally disabled to preserve inference semantics, but transaction batching and magic property optimizations remain available. See [OPTIMIZATIONS.md](OPTIMIZATIONS.md#optimizations-with-inference-models-infgraph) for details.

## Architecture

### How It Works

1. **RDF to Property Graph Mapping**:
   - RDF subjects → FalkorDB nodes (`:Resource` label) with `uri` property
   - RDF predicates → FalkorDB relationships (for resources) or node properties (for literals)
   - RDF objects (URIs) → FalkorDB nodes with relationships
   - **RDF objects (literals) → Stored as properties directly on subject nodes** (efficient!)
   - **rdf:type → Creates labels on nodes** (e.g., `:Resource:Person`)
   - **Automatic index on Resource.uri** for fast lookups

2. **Storage Optimizations**:
   - **Literals as Properties**: Instead of creating separate nodes for literal values, they are stored as properties on the subject node using the predicate URI as the property name
   - **Backtick Notation**: URIs can be used directly as property names and relationship types using Cypher backtick notation
   - **Type Labels**: `rdf:type` triples create native graph labels, enabling efficient type-based queries
   - **Parameterized Queries**: All Cypher queries use parameters to prevent injection and improve performance

3. **Query Translation**:
   - SPARQL queries are parsed by Jena
   - Triple patterns are translated to Cypher MATCH clauses
   - Literal queries search node properties
   - Type queries search node labels
   - Results are converted back to RDF triples

4. **Connection Management**:
   - JFalkorDB driver with connection pooling
   - Custom driver support for advanced configuration
   - Automatic connection cleanup

## Troubleshooting

### Connection Issues

**Problem**: `Connection refused` error

**Solution**: Ensure FalkorDB is running:
```bash
docker ps  # Check if FalkorDB container is running
# Or
redis-cli -p 6379 PING  # Should return PONG
```

### Build Issues

**Problem**: Maven dependency errors

**Solution**: Clean and rebuild:
```bash
mvn clean install -U
```

### Query Issues

**Problem**: SPARQL query returns no results

**Solution**: 
1. Check that data was actually added to FalkorDB
2. Verify the graph name matches
3. Try a simpler query first

### FalkorDB Graph Check

To verify data in FalkorDB directly:

```bash
redis-cli -p 6379
> GRAPH.QUERY demo_graph "MATCH (n) RETURN n LIMIT 10"
```

## Advanced Usage

### Custom Graph Implementation

You can extend `FalkorDBGraph` to add custom behavior:

```java
public class CustomFalkorDBGraph extends FalkorDBGraph {
    
    public CustomFalkorDBGraph(String host, int port, String graphName) {
        super(host, port, graphName);
    }
    
    @Override
    public void performAdd(Triple triple) {
        // Add custom logic before/after adding
        System.out.println("Adding triple: " + triple);
        super.performAdd(triple);
    }
}
```

### Batch Operations

For better performance when adding many triples:

```java
var model = FalkorDBModelFactory.createDefaultModel();

// Disable autocommit if using transactions
model.begin();

try {
    // Add many triples
    for (var i = 0; i < 1000; i++) {
        var resource = model.createResource("http://example.org/item" + i);
        resource.addProperty(RDF.type, model.createResource("http://example.org/Item"));
        resource.addProperty(model.createProperty("http://example.org/index"), 
                           model.createTypedLiteral(i));
    }
    
    model.commit();
} catch (Exception e) {
    model.abort();
    throw e;
} finally {
    model.close();
}
```

### Working with Multiple Graphs

```java
// Create separate models for different domains
var peopleModel = FalkorDBModelFactory.createModel("people_graph");
var organizationsModel = FalkorDBModelFactory.createModel("orgs_graph");

try {
    // Add data to different graphs
    var alice = peopleModel.createResource("http://example.org/alice");
    alice.addProperty(FOAF.name, "Alice");
    
    var acmeCorp = organizationsModel.createResource("http://example.org/acme");
    acmeCorp.addProperty(RDFS.label, "ACME Corporation");
    
} finally {
    peopleModel.close();
    organizationsModel.close();
}
```

## Testing

### Unit Tests

Create tests in `src/test/java`:

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class FalkorDBGraphTest {
    
    private Model model;
    
    @BeforeEach
    public void setUp() {
        model = FalkorDBModelFactory.createModel("test_graph");
    }
    
    @AfterEach
    public void tearDown() {
        model.close();
    }
    
    @Test
    public void testAddTriple() {
        var subject = model.createResource("http://example.org/test");
        var predicate = model.createProperty("http://example.org/prop");
        
        subject.addProperty(predicate, "test value");
        
        assertTrue(model.contains(subject, predicate));
    }
}
```

Run tests:
```bash
mvn test
```

## Examples and Samples

Comprehensive examples for all optimizations are available in the [`samples/`](samples/) directory:

- **[Batch Writes](samples/batch-writes/)**: Transaction batching for bulk operations (100-1000x faster)
- **[Query Pushdown](samples/query-pushdown/)**: SPARQL to Cypher translation (Nx-N²x improvement)
- **[Variable Objects](samples/variable-objects/)**: Query both properties and relationships (2x fewer round trips)
- **[OPTIONAL Patterns](samples/optional-patterns/)**: Efficient optional data retrieval (Nx fewer round trips)
- **[UNION Patterns](samples/union-patterns/)**: Alternative query patterns (Nx fewer round trips)
- **[Filter Expressions](samples/filter-expressions/)**: Database-side filtering (reduces data transfer)
- **[Aggregations](samples/aggregations/)**: GROUP BY with COUNT, SUM, AVG, MIN, MAX (200-1000x less data transfer)
- **[Magic Property](samples/magic-property/)**: Direct Cypher execution for maximum control

Each example includes:
- ✅ Complete Java code with multiple use cases
- ✅ SPARQL query patterns and syntax
- ✅ Sample RDF data in Turtle format
- ✅ Detailed README with explanations

See [`samples/README.md`](samples/README.md) for quick start guide.

## How to Run the Examples

The examples demonstrate all the optimization features of the adapter. Follow these steps to run them:

### Prerequisites

1. **Start FalkorDB:**
   ```bash
   docker run -p 6379:6379 -d falkordb/falkordb:latest
   ```

2. **Build the project:**
   ```bash
   cd jena-falkordb-adapter
   mvn clean install
   ```

### Running Individual Examples

Each example can be run using Maven exec plugin:

```bash
# Batch Writes Example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.BatchWriteExample"

# Query Pushdown Example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.QueryPushdownExample"

# Variable Objects Example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.VariableObjectExample"

# OPTIONAL Patterns Example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.OptionalPatternsExample"

# UNION Patterns Example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.UnionPatternsExample"

# Filter Expressions Example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.FilterExpressionsExample"

# Aggregations Example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.AggregationsExample"

# Magic Property Example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.MagicPropertyExample"
```

### Loading Sample Data

Each example directory contains sample data files (`data-example.ttl`). To load them:

```java
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.rdf.model.Model;
import com.falkordb.jena.FalkorDBModelFactory;

Model model = FalkorDBModelFactory.createDefaultModel();
model.begin(ReadWrite.WRITE);
try {
    RDFDataMgr.read(model, "samples/batch-writes/data-example.ttl");
    model.commit();
} finally {
    model.end();
}
```

### Running with Fuseki Server

You can also run examples via the Fuseki server. The Fuseki server provides a web-based UI for interactive querying:

**Step 1:** Start Fuseki with FalkorDB backend:
```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

**Step 2:** Access the Fuseki Web UI:
- Open your browser and navigate to **http://localhost:3330/**
- The web interface provides a query editor, dataset management, and server statistics
- Select the `/falkor` dataset to run queries

**Step 3:** Load sample data via HTTP:
```bash
curl -X POST http://localhost:3330/falkor/update \
  -H "Content-Type: text/turtle" \
  --data-binary @samples/batch-writes/data-example.ttl
```

**Step 3:** Execute SPARQL queries from the samples:
```bash
# Example: Query from optional-patterns sample
curl -G http://localhost:3330/falkor/query \
  --data-urlencode "query=$(cat samples/optional-patterns/queries.sparql | head -n 20)"
```

### Example Output

Each example will output:
- Description of what it's demonstrating
- The SPARQL query being executed
- The generated Cypher query (if applicable)
- Query results
- Performance metrics (execution time, number of database calls)

**Example output from Query Pushdown:**
```
=== Query Pushdown Example ===

Running query: Find all people and their ages
SPARQL:
  SELECT ?person ?age WHERE {
    ?person a <http://xmlns.com/foaf/0.1/Person> .
    ?person <http://xmlns.com/foaf/0.1/age> ?age .
  }

Generated Cypher:
  MATCH (s:Resource:Person) 
  RETURN s.uri AS s, s.`foaf:age` AS age

Results:
  Person: http://example.org/alice, Age: 30
  Person: http://example.org/bob, Age: 35

Database calls: 1 (instead of N+1 without optimization)
Execution time: 15ms
```

### Interactive Exploration

For interactive exploration, use the Fuseki web UI:

1. Start Fuseki:
   ```bash
   java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
     --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
   ```
2. Open browser and navigate to: **http://localhost:3330/**
3. Select the `/falkor` dataset from the dataset list
4. Navigate to the query editor tab
5. Load queries from `samples/*/queries.sparql`
6. Execute and view results in the browser

### Tracing and Observability

To see how queries are optimized with detailed tracing:

1. Start Jaeger for tracing:
   ```bash
   docker-compose -f docker-compose-tracing.yaml up -d
   ```

2. Run examples with tracing enabled:
   ```bash
   export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
   mvn exec:java -Dexec.mainClass="com.falkordb.samples.QueryPushdownExample"
   ```

3. View traces in Jaeger UI: http://localhost:16686/

See [DEMO.md](DEMO.md) for complete step-by-step demo with curl commands and [TRACING.md](TRACING.md) for observability documentation.

## Documentation

This project includes comprehensive documentation covering all aspects of the adapter:

### Core Documentation

| Document | Description |
|----------|-------------|
| **[README.md](README.md)** (this file) | Project overview, quick start, features, and usage examples |
| **[GETTING_STARTED.md](GETTING_STARTED.md)** | Detailed setup guide, installation, first program, use cases, and troubleshooting |
| **[DEMO.md](DEMO.md)** | Complete hands-on demo with curl commands, Jaeger tracing, and step-by-step examples for all optimizations |

### Optimization & Performance

| Document | Description |
|----------|-------------|
| **[OPTIMIZATIONS.md](OPTIMIZATIONS.md)** | Comprehensive guide to all performance optimizations: batch writes, query pushdown, variable objects, OPTIONAL patterns, UNION patterns, filter expressions, and aggregations |
| **[MAGIC_PROPERTY.md](MAGIC_PROPERTY.md)** | Documentation for the `falkor:cypher` magic property that allows direct Cypher execution within SPARQL queries |

### Technical Documentation

| Document | Description |
|----------|-------------|
| **[MAPPING.md](MAPPING.md)** | Detailed explanation of how RDF triples are mapped to property graph structures in FalkorDB |
| **[TRACING.md](TRACING.md)** | OpenTelemetry integration for observability, tracing, and performance monitoring with Jaeger |

### Module-Specific Documentation

| Document | Description |
|----------|-------------|
| **[jena-fuseki-falkordb/GETTING_STARTED.md](jena-fuseki-falkordb/GETTING_STARTED.md)** | Guide for deploying and using the Fuseki SPARQL server with FalkorDB backend |
| **[samples/README.md](samples/README.md)** | Overview of all code examples with performance metrics and usage patterns |

### Examples by Optimization

Each optimization has dedicated examples in the `samples/` directory with complete code:

| Example Directory | What It Demonstrates | Performance Gain |
|-------------------|---------------------|------------------|
| **[samples/batch-writes/](samples/batch-writes/)** | Transaction batching for bulk operations | 100-1000x faster |
| **[samples/query-pushdown/](samples/query-pushdown/)** | SPARQL to Cypher translation | Nx-N²x improvement |
| **[samples/variable-objects/](samples/variable-objects/)** | Querying both properties and relationships | 2x fewer round trips |
| **[samples/optional-patterns/](samples/optional-patterns/)** | Efficient optional data retrieval | Nx fewer round trips |
| **[samples/union-patterns/](samples/union-patterns/)** | Alternative query patterns with UNION | Nx fewer round trips |
| **[samples/filter-expressions/](samples/filter-expressions/)** | Database-side filtering | Reduces data transfer |
| **[samples/aggregations/](samples/aggregations/)** | GROUP BY with aggregation functions | 200-1000x less data transfer |
| **[samples/magic-property/](samples/magic-property/)** | Direct Cypher execution | Maximum control |

### Quick Links to Key Topics

- **Getting Started**: [GETTING_STARTED.md](GETTING_STARTED.md)
- **How to Deploy**: [Deploying to Standalone Fuseki](#deploying-to-standalone-fuseki-server)
- **Running Examples**: [How to Run the Examples](#how-to-run-the-examples)
- **Performance Tuning**: [OPTIMIZATIONS.md](OPTIMIZATIONS.md)
- **Troubleshooting**: [GETTING_STARTED.md#troubleshooting](GETTING_STARTED.md#troubleshooting)
- **API Reference**: [Javadocs](https://www.javadoc.io/doc/com.falkordb/jena-falkordb-adapter)
- **Storage Architecture**: [MAPPING.md](MAPPING.md)
- **Observability**: [TRACING.md](TRACING.md)
- **Magic Property**: [MAGIC_PROPERTY.md](MAGIC_PROPERTY.md)
- **Complete Demo**: [DEMO.md](DEMO.md)

### External Resources

- [Apache Jena Documentation](https://jena.apache.org/documentation/)
- [FalkorDB Documentation](https://docs.falkordb.com/)
- [SPARQL 1.1 Query Language](https://www.w3.org/TR/sparql11-query/)
- [Cypher Query Language](https://neo4j.com/docs/cypher-manual/current/)
- [GitHub Repository](https://github.com/FalkorDB/jena-falkordb-adapter)
- [Issue Tracker](https://github.com/FalkorDB/jena-falkordb-adapter/issues)
- [Discussions](https://github.com/FalkorDB/FalkorDB/discussions)

## Performance Tips

1. **Automatic Query Pushdown**: SPARQL queries are automatically compiled to efficient Cypher - no configuration needed!
2. **OPTIONAL Patterns**: Use `OPTIONAL { }` for partial data - automatically translated to Cypher OPTIONAL MATCH (N× fewer queries)
3. **Automatic Indexing**: The adapter automatically creates an index on `Resource.uri` for optimal performance
4. **Efficient Literal Storage**: Literals are stored as node properties, not separate nodes, reducing graph traversal
5. **Use Connection Pooling**: Already configured by default with JFalkorDB driver
6. **Batch Operations**: Use transactions for bulk loads (see [batch-writes examples](samples/batch-writes/))
7. **Type-Based Queries**: Leverage `rdf:type` labels for efficient filtering (automatically used by query pushdown)
8. **Limit Result Sets**: Use LIMIT in SPARQL queries to reduce data transfer
9. **Close Resources**: Always close models, query executions, and custom drivers
10. **Parameterized Queries**: All queries use parameters internally for better performance and security

## Docker Compose Setup

Create `docker-compose.yml` for easy setup:

```yaml
version: '3.8'

services:
  falkordb:
    image: falkordb/falkordb:latest
    ports:
      - "6379:6379"
    volumes:
      - falkordb-data:/data
    command: ["--requirepass", "your_password_here"]  # Optional

volumes:
  falkordb-data:
```

Run with:
```bash
docker-compose up -d
```

## Developing / Building from source

If you want to modify the adapter or build it locally from source, follow these steps.

1. Clone the repository and open it in your IDE of choice:

```bash
git clone https://github.com/FalkorDB/jena-falkordb-adapter.git
cd jena-falkordb-adapter
```

2. Build with Maven (Java 17+ is required):

```bash
mvn clean install
```

3. Run unit tests (requires FalkorDB running on localhost:6379):

```bash
docker run -p 6379:6379 -d --rm --name falkordb falkordb/falkordb:latest
mvn test
```

4. Build executable JARs and run the demo:

```bash
mvn clean package
# Run the adapter demo
java -jar jena-falkordb-adapter/target/jena-falkordb-adapter-0.2.0-SNAPSHOT.jar

# Run the Fuseki server with config file (recommended)
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

### Running the Fuseki Server

The Fuseki server module provides a standalone SPARQL endpoint. Always run with the config file:

```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

Then access:
- **Web UI**: http://localhost:3330/ (open in your browser for interactive querying)
- **SPARQL Endpoint**: http://localhost:3330/falkor

Developer notes:

- This is a multi-module Maven project with a parent POM and two submodules
- The adapter code lives under `jena-falkordb-adapter/src/main/java/com/falkordb/jena/`
- The Fuseki server code lives under `jena-fuseki-falkordb/src/main/java/com/falkordb/`
- Tests that interact with FalkorDB assume a local FalkorDB instance; tests use `FalkorDBGraph.clear()` to keep the test graph isolated.
- CI publishes snapshots to OSSRH; see `.github/workflows` for publishing details.

## Contributing

Contributions are welcome! Areas for improvement:

- Better SPARQL to Cypher translation
- Support for more SPARQL features
- Performance optimizations
- Additional test coverage
- Documentation improvements

## Releasing to Maven Central

This project is configured to publish releases to Maven Central. Here's how to create and publish a release:

### Release Prerequisites

1. **GPG Key**: You need a GPG key pair for signing artifacts

   ```bash
   # Generate a new GPG key (if you don't have one)
   gpg --gen-key
   
   # List your keys
   gpg --list-keys
   
   # Publish your public key to a keyserver
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   ```

2. **Maven Central Account**: Sign up at [https://central.sonatype.com/](https://central.sonatype.com/)

3. **Configure Maven Settings**: Add credentials to `~/.m2/settings.xml`

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>your-username</username>
         <password>your-password</password>
       </server>
     </servers>
   </settings>
   ```

### Release Process

1. **Update Version**: Remove `-SNAPSHOT` from version in `pom.xml`

   ```xml
   <version>0.2.0</version>
   ```

2. **Build and Test**: Ensure everything works

   ```bash
   mvn clean install
   ```

3. **Create Release Build**: Build with the release profile

   ```bash
   mvn clean install -Prelease
   ```

   This will:
   - Compile the code
   - Run all tests
   - Generate source and javadoc JARs
   - Sign all artifacts with GPG
   - You'll be prompted for your GPG passphrase

4. **Deploy to Maven Central**

   ```bash
   mvn deploy -Prelease
   ```

5. **Create Git Tag**

   ```bash
   git add pom.xml
   git commit -m "Release version 0.2.0"
   git tag -a v0.2.0 -m "Release version 0.2.0"
   git push origin main
   git push origin v0.2.0
   ```

6. **Create GitHub Release**: Go to GitHub and create a release from the tag

7. **Bump to Next SNAPSHOT**: Update `pom.xml` for next development cycle

   ```xml
   <version>0.3.0-SNAPSHOT</version>
   ```

   ```bash
   git add pom.xml
   git commit -m "Bump version to 0.3.0-SNAPSHOT"
   git push origin main
   ```

### Building Locally (Without GPG Signing)

For local development, you don't need GPG signing:

```bash
# Regular build without signing
mvn clean install

# Skip tests if needed
mvn clean install -DskipTests
```

The GPG signing only happens when you use the `-Prelease` profile, which is intended for publishing releases to Maven Central.

## License

This project is provided as-is for educational and development purposes.

## Resources

- [Apache Jena Documentation](https://jena.apache.org/documentation/)
- [FalkorDB Documentation](https://docs.falkordb.com/)
- [SPARQL 1.1 Query Language](https://www.w3.org/TR/sparql11-query/)
- [Cypher Query Language](https://neo4j.com/docs/cypher-manual/current/)
- [Magic Property Documentation](MAGIC_PROPERTY.md) - Native Cypher query pushdown
- [OpenTelemetry Tracing](TRACING.md) - Observability and tracing

## Support

For issues and questions:
1. Check the troubleshooting section
2. Review FalkorDB and Jena documentation
3. Create an issue in the project repository

## Example Use Cases

### 1. Knowledge Graph Application
```java
// Build a knowledge graph with entities and relationships
var kg = FalkorDBModelFactory.createModel("knowledge_graph");

var entity1 = kg.createResource("http://kg.example.org/entity1");
entity1.addProperty(RDFS.label, "Entity 1");
entity1.addProperty(RDF.type, kg.createResource("http://kg.example.org/Concept"));

// Query the knowledge graph
var query = "SELECT ?entity ?label WHERE { ?entity rdfs:label ?label }";
// ... execute query
```

### 2. Social Network
```java
var social = FalkorDBModelFactory.createModel("social_network");

var knows = social.createProperty("http://xmlns.com/foaf/0.1/knows");
var user1 = social.createResource("http://social.example.org/user1");
var user2 = social.createResource("http://social.example.org/user2");

user1.addProperty(knows, user2);
```

### 3. Data Integration
```java
// Integrate data from multiple sources using RDF
var integration = FalkorDBModelFactory.createModel("data_integration");

// Load data from different sources
integration.read("data1.ttl", "TURTLE");
integration.read("data2.rdf", "RDF/XML");

// Query integrated data with SPARQL
var query = "SELECT * WHERE { ?s ?p ?o } LIMIT 100";
```

## CI/CD

This project uses GitHub Actions for continuous integration and deployment:

### Continuous Integration

The CI workflow automatically runs on every push and pull request to the `main` branch:
- Uses Java version specified in `.sdkmanrc` (currently 25.0.1-graal)
- Runs full test suite against FalkorDB
- Performs code quality checks
- Uploads test results and build artifacts

See [`.github/workflows/ci.yml`](.github/workflows/ci.yml) for details.

### Publishing to Maven Central

Releases are automatically published to Maven Central when you create a GitHub release. The publish workflow:
- Builds and packages the library
- Generates source and javadoc JARs
- Signs artifacts with GPG
- Deploys to OSSRH/Maven Central

See [CICD_SETUP.md](CICD_SETUP.md) for detailed setup instructions.

## Changelog

### Version 1.0-SNAPSHOT (Latest)
- **New**: Literals stored as node properties instead of separate nodes (major performance improvement)
- **New**: rdf:type support with native graph labels
- **New**: Automatic URI indexing on Resource nodes
- **New**: Custom driver constructor and factory methods
- **New**: Builder pattern support for custom drivers
- **New**: Parameterized Cypher queries for security and performance
- **New**: Backtick notation for URIs in property names and relationships
- Improved: Enhanced test coverage with 16 comprehensive tests
- Improved: Better error handling and validation
- Initial release features:
  - Basic RDF to FalkorDB mapping
  - SPARQL query support
  - Connection pooling
  - CI/CD with GitHub Actions
  - Maven Central publishing support
  - Factory pattern for model creation

---

**Note**: This is an educational/experimental project. For production use, consider additional testing, error handling, and performance optimization based on your specific requirements.
