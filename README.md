# Jena-FalkorDB Adapter

A Java adapter that enables Apache Jena to work with FalkorDB graph database, allowing you to use SPARQL queries on data stored in FalkorDB.

## Features

- ✅ Use Apache Jena's RDF API with FalkorDB backend
- ✅ Execute SPARQL queries on FalkorDB data
- ✅ Automatic translation of RDF triples to Cypher operations
- ✅ Connection pooling for better performance
- ✅ Easy-to-use factory pattern for model creation

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

```bash
# Clone the repository (or create project structure)
mkdir jena-falkordb-adapter
cd jena-falkordb-adapter

# Copy the provided files:
# - pom.xml
# - src/main/java/com/example/jena/falkordb/FalkorDBGraph.java
# - src/main/java/com/example/jena/falkordb/FalkorDBModelFactory.java
# - src/main/java/com/example/jena/falkordb/Main.java

# Build the project
mvn clean install
```

### 3. Run the Demo

```bash
mvn exec:java -Dexec.mainClass="com.falkordb.jena.Main"
```

Or build and run the JAR:

```bash
mvn clean package
java -jar target/jena-falkordb-adapter-1.0-SNAPSHOT.jar
```

## Project Structure

```
jena-falkordb-adapter/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            └── com/
                └── example/
                    └── jena/
                        └── falkordb/
                            ├── FalkorDBGraph.java          # Core graph implementation
                            ├── FalkorDBModelFactory.java   # Factory for creating models
                            └── Main.java                   # Demo application
```

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

## Configuration

### Connection Pool Settings

The adapter uses Jedis connection pooling. Default settings:
- Max Total Connections: 10
- Max Idle Connections: 5
- Min Idle Connections: 1

To customize, modify `FalkorDBGraph.java` constructor.

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
3. **Literal Storage**: Literals are currently stored as node properties rather than separate nodes
4. **Complex Queries**: Advanced SPARQL features (OPTIONAL, UNION, nested queries) may not work as expected
5. **Inference**: RDFS/OWL reasoning is not yet implemented

## Architecture

### How It Works

1. **RDF to Property Graph Mapping**:
   - RDF subjects → FalkorDB nodes with `uri` property
   - RDF predicates → FalkorDB relationships or node properties
   - RDF objects (URIs) → FalkorDB nodes
   - RDF objects (literals) → Node properties

2. **Query Translation**:
   - SPARQL queries are parsed by Jena
   - Triple patterns are translated to Cypher MATCH clauses
   - Results are converted back to RDF triples

3. **Connection Management**:
   - Jedis connection pool for efficient resource usage
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

1. **Use Connection Pooling**: Already configured by default
2. **Batch Operations**: Group multiple adds together
3. **Index URIs**: Create indexes in FalkorDB for frequently queried URIs
4. **Limit Result Sets**: Use LIMIT in SPARQL queries
5. **Close Resources**: Always close models and query executions

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

## Changelog

### Version 1.0-SNAPSHOT
- Initial release
- Basic RDF to FalkorDB mapping
- SPARQL query support
- Connection pooling
- Factory pattern for model creation

---

**Note**: This is an educational/experimental project. For production use, consider additional testing, error handling, and performance optimization based on your specific requirements.