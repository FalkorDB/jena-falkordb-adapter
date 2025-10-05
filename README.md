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


A Java adapter that enables Apache Jena to work with FalkorDB graph database, allowing you to use SPARQL queries on data stored in FalkorDB.

## Features

- ✅ Use Apache Jena's RDF API with FalkorDB backend
- ✅ Execute SPARQL queries on FalkorDB data
- ✅ Automatic translation of RDF triples to Cypher operations
- ✅ **Efficient literal storage as node properties** (not separate nodes)
- ✅ **rdf:type support with native graph labels**
- ✅ **Automatic URI indexing** for optimal query performance
- ✅ **Custom driver support** for advanced configuration
- ✅ Connection pooling for better performance
- ✅ Easy-to-use factory pattern for model creation
- ✅ Continuous integration with multi-version Java testing (11, 17, 21)
- ✅ Automated publishing to Maven Central

## Prerequisites

- Java 11 or higher
- Maven 3.6 or higher
- FalkorDB running (via Docker or direct installation)

## Setup

### 1. Install FalkorDB

Using Docker (recommended):

```bash
docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
```

Or install directly from: https://www.falkordb.com/

### 2. Clone and Build the Project

If you want to build the adapter from source, full build and run instructions are provided in the
"Developing / Building from source" section further down in this README. For a quick start using the
published artifact, see the "Using from Maven" section below.

## Using from Maven

If the project artifacts are published to Maven Central or OSSRH snapshots, you can add
the adapter as a dependency in your own project's `pom.xml`.

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
        <version>1.0-SNAPSHOT</version>
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


## Usage Examples

### Basic Usage

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

// Create a model backed by FalkorDB
Model model = FalkorDBModelFactory.createDefaultModel();

try {
    // Create and add RDF data
    Resource person = model.createResource("http://example.org/alice");
    Property name = model.createProperty("http://xmlns.com/foaf/0.1/name");
    
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
Model model = FalkorDBModelFactory.builder()
    .host("localhost")
    .port(6379)
    .graphName("my_graph")
    .build();

// Or direct method
Model model = FalkorDBModelFactory.createModel("localhost", 6379, "my_graph");
```

### Using Custom Driver

For advanced configuration (authentication, timeouts, SSL, etc.), you can provide your own FalkorDB driver:

```java
import com.falkordb.Driver;
import com.falkordb.FalkorDB;

// Create a custom configured driver
Driver customDriver = FalkorDB.driver("localhost", 6379);
// Configure your driver as needed...

// Use with factory
Model model = FalkorDBModelFactory.createModel(customDriver, "my_graph");

// Or use with builder
Model model = FalkorDBModelFactory.builder()
    .driver(customDriver)
    .graphName("my_graph")
    .build();

// Or directly with the graph
FalkorDBGraph graph = new FalkorDBGraph(customDriver, "my_graph");
Model model = ModelFactory.createModelForGraph(graph);

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
- ✅ **Readable URIs**: Backtick notation preserves URIs without encoding
- ✅ **Automatic Indexing**: `Resource.uri` is indexed automatically

### SPARQL Queries

```java
import org.apache.jena.query.*;

Model model = FalkorDBModelFactory.createDefaultModel();

String sparqlQuery = 
    "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
    "SELECT ?name ?age " +
    "WHERE { " +
    "  ?person foaf:name ?name . " +
    "  ?person foaf:age ?age . " +
    "} " +
    "ORDER BY ?age";

try (QueryExecution qexec = QueryExecutionFactory.create(sparqlQuery, model)) {
    ResultSet results = qexec.execSelect();
    
    while (results.hasNext()) {
        QuerySolution solution = results.nextSolution();
        String name = solution.getLiteral("name").getString();
        int age = solution.getLiteral("age").getInt();
        System.out.println(name + " is " + age + " years old");
    }
}

model.close();
```

### Adding Complex Data

```java
Model model = FalkorDBModelFactory.createDefaultModel();

// Define properties
Property foafName = model.createProperty("http://xmlns.com/foaf/0.1/name");
Property foafKnows = model.createProperty("http://xmlns.com/foaf/0.1/knows");
Property foafAge = model.createProperty("http://xmlns.com/foaf/0.1/age");

// Create resources
Resource alice = model.createResource("http://example.org/alice")
    .addProperty(foafName, "Alice")
    .addProperty(foafAge, model.createTypedLiteral(30));

Resource bob = model.createResource("http://example.org/bob")
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
Model graph1 = FalkorDBModelFactory.createModel("users_graph");
Model graph2 = FalkorDBModelFactory.createModel("products_graph");
```

## Limitations

This is a basic implementation with some limitations:

1. **Query Translation**: Not all SPARQL features are fully translated to Cypher
2. **Performance**: Translation overhead may impact performance for large datasets
3. **Complex Queries**: Advanced SPARQL features (OPTIONAL, UNION, nested queries) may not work as expected
4. **Inference**: RDFS/OWL reasoning is not yet implemented

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
Model model = FalkorDBModelFactory.createDefaultModel();

// Disable autocommit if using transactions
model.begin();

try {
    // Add many triples
    for (int i = 0; i < 1000; i++) {
        Resource resource = model.createResource("http://example.org/item" + i);
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
Model peopleModel = FalkorDBModelFactory.createModel("people_graph");
Model organizationsModel = FalkorDBModelFactory.createModel("orgs_graph");

try {
    // Add data to different graphs
    Resource alice = peopleModel.createResource("http://example.org/alice");
    alice.addProperty(FOAF.name, "Alice");
    
    Resource acmeCorp = organizationsModel.createResource("http://example.org/acme");
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
        Resource subject = model.createResource("http://example.org/test");
        Property predicate = model.createProperty("http://example.org/prop");
        
        subject.addProperty(predicate, "test value");
        
        assertTrue(model.contains(subject, predicate));
    }
}
```

Run tests:
```bash
mvn test
```

## Performance Tips

1. **Automatic Indexing**: The adapter automatically creates an index on `Resource.uri` for optimal performance
2. **Efficient Literal Storage**: Literals are stored as node properties, not separate nodes, reducing graph traversal
3. **Use Connection Pooling**: Already configured by default with JFalkorDB driver
4. **Batch Operations**: Group multiple adds together when possible
5. **Type-Based Queries**: Leverage `rdf:type` labels for efficient filtering
6. **Limit Result Sets**: Use LIMIT in SPARQL queries to reduce data transfer
7. **Close Resources**: Always close models, query executions, and custom drivers
8. **Parameterized Queries**: All queries use parameters internally for better performance and security

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

2. Build with Maven (Java 11+ is required):

```bash
mvn clean install
```

3. Run unit tests (requires FalkorDB running on localhost:6379):

```bash
docker run -p 6379:6379 -d --rm --name falkordb falkordb/falkordb:latest
mvn test
```

4. Build an executable JAR and run the demo:

```bash
mvn clean package
java -jar target/jena-falkordb-adapter-1.0-SNAPSHOT.jar
```

Developer notes:

- The main adapter code lives under `src/main/java/com/falkordb/jena/`.
- Tests that interact with FalkorDB assume a local FalkorDB instance; tests use `FalkorDBGraph.clear()` to keep the test graph isolated.
- CI publishes snapshots to OSSRH; see `.github/workflows` for publishing details.

## Contributing

Contributions are welcome! Areas for improvement:

- Better SPARQL to Cypher translation
- Support for more SPARQL features
- Performance optimizations
- Additional test coverage
- Documentation improvements

## License

This project is provided as-is for educational and development purposes.

## Resources

- [Apache Jena Documentation](https://jena.apache.org/documentation/)
- [FalkorDB Documentation](https://docs.falkordb.com/)
- [SPARQL 1.1 Query Language](https://www.w3.org/TR/sparql11-query/)
- [Cypher Query Language](https://neo4j.com/docs/cypher-manual/current/)

## Support

For issues and questions:
1. Check the troubleshooting section
2. Review FalkorDB and Jena documentation
3. Create an issue in the project repository

## Example Use Cases

### 1. Knowledge Graph Application
```java
// Build a knowledge graph with entities and relationships
Model kg = FalkorDBModelFactory.createModel("knowledge_graph");

Resource entity1 = kg.createResource("http://kg.example.org/entity1");
entity1.addProperty(RDFS.label, "Entity 1");
entity1.addProperty(RDF.type, kg.createResource("http://kg.example.org/Concept"));

// Query the knowledge graph
String query = "SELECT ?entity ?label WHERE { ?entity rdfs:label ?label }";
// ... execute query
```

### 2. Social Network
```java
Model social = FalkorDBModelFactory.createModel("social_network");

Property knows = social.createProperty("http://xmlns.com/foaf/0.1/knows");
Resource user1 = social.createResource("http://social.example.org/user1");
Resource user2 = social.createResource("http://social.example.org/user2");

user1.addProperty(knows, user2);
```

### 3. Data Integration
```java
// Integrate data from multiple sources using RDF
Model integration = FalkorDBModelFactory.createModel("data_integration");

// Load data from different sources
integration.read("data1.ttl", "TURTLE");
integration.read("data2.rdf", "RDF/XML");

// Query integrated data with SPARQL
String query = "SELECT * WHERE { ?s ?p ?o } LIMIT 100";
```

## CI/CD

This project uses GitHub Actions for continuous integration and deployment:

### Continuous Integration

The CI workflow automatically runs on every push and pull request to the `main` branch:
- Tests against Java 11, 17, and 21
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
