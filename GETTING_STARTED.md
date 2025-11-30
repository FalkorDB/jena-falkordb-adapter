# Getting Started with Jena-FalkorDB

This guide will help you set up and start using the Jena-FalkorDB project in under 10 minutes.

> **Note**: This is a multi-module project containing:
> - **jena-falkordb-adapter** - Core adapter library for integrating Jena with FalkorDB
> - **jena-fuseki-falkordb** - Fuseki SPARQL server with FalkorDB backend

The adapter features efficient literal storage as node properties, automatic URI indexing, and rdf:type support with native labels for optimal performance! See the [README](README.md) for architecture details.

## Quick Start (3 Steps)

### Step 1: Start FalkorDB

```bash
docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
```

### Step 2: Build the Project

```bash
git clone https://github.com/FalkorDB/jena-falkordb-adapter.git
cd jena-falkordb-adapter
mvn clean install -DskipTests
```

### Step 3: Run the Demo or Fuseki Server

**Option A: Run the adapter demo**
```bash
java -jar jena-falkordb-adapter/target/jena-falkordb-adapter-0.2.0-SNAPSHOT.jar
```

**Option B: Run the Fuseki SPARQL server**
```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar
```
Then open http://localhost:3330/ in your browser.

That's it! You should see the demo running or the Fuseki server started.

---

## Detailed Setup Guide

### Prerequisites

- **Java**: Version 17 or higher (tested with 25)
  ```bash
  java -version
  ```

- **Maven**: Version 3.6 or higher
  ```bash
  mvn -version
  ```

- **Docker** (optional but recommended): For running FalkorDB
  ```bash
  docker --version
  ```

### Installation Options

#### Option A: Using the Setup Script (Linux/Mac)

1. Save all project files
2. Make the setup script executable:
   ```bash
   chmod +x setup.sh
   ```
3. Run the setup:
   ```bash
   ./setup.sh
   ```

The script will:
- Check prerequisites
- Start FalkorDB (if Docker is available)
- Create project structure
- Build the project

#### Option B: Manual Setup

1. **Install FalkorDB**

   Using Docker:
   ```bash
   docker run -d --name falkordb -p 6379:6379 falkordb/falkordb:latest
   ```

   Or download from: https://www.falkordb.com/

2. **Verify FalkorDB is running**
   ```bash
   docker ps | grep falkordb
   # Or test connection
   redis-cli -p 6379 PING
   # Should return: PONG
   ```

3. **Create project structure**
   ```bash
   mkdir -p jena-falkordb-adapter/src/main/java/com/example/jena/falkordb
   mkdir -p jena-falkordb-adapter/src/test/java/com/example/jena/falkordb
   cd jena-falkordb-adapter
   ```

4. **Copy project files**
   - `pom.xml` to project root
   - Java files to `src/main/java/com/example/jena/falkordb/`
   - Test files to `src/test/java/com/example/jena/falkordb/`

5. **Build the project**
   ```bash
   mvn clean install
   ```

---

## Running the Examples

### Example 1: Basic Demo

```bash
mvn exec:java -Dexec.mainClass="com.falkordb.jena.Main"
```

This runs through several examples:
- Adding RDF data
- SPARQL queries
- Iterating statements
- Complex queries

### Example 2: Quick Start Examples

```bash
mvn exec:java -Dexec.mainClass="com.falkordb.jena.QuickStart"
```

Shows:
- Basic triple addition
- SPARQL querying
- Social network creation
- Data export

### Example 3: Run Tests

```bash
mvn test
```

Runs the complete test suite.

---

## Your First Program

Create a new file `MyFirstProgram.java`:

```java
package com.falkordb.jena;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

public class MyFirstProgram {
    public static void main(String[] args) {
        // Connect to FalkorDB
        Model model = FalkorDBModelFactory.createDefaultModel();
        
        try {
            // Create a resource
            Resource john = model.createResource("http://example.org/john");
            
            // Add properties
            Property name = model.createProperty("http://example.org/name");
            john.addProperty(name, "John Doe");
            john.addProperty(RDF.type, 
                model.createResource("http://example.org/Person"));
            
            // Print results
            System.out.println("Added " + model.size() + " triples");
            System.out.println("Success!");
            
        } finally {
            model.close();
        }
    }
}
```

Run it:
```bash
mvn exec:java -Dexec.mainClass="com.falkordb.jena.MyFirstProgram"
```

---

## Common Use Cases

### Use Case 1: Building a Knowledge Base

```java
Model kb = FalkorDBModelFactory.createModel("knowledge_base");

// Define concepts
Resource person = kb.createResource("http://example.org/Person");
Resource organization = kb.createResource("http://example.org/Organization");

// Add instances
Resource alice = kb.createResource("http://example.org/alice")
    .addProperty(RDF.type, person)
    .addProperty(RDFS.label, "Alice Johnson");

Resource acme = kb.createResource("http://example.org/acme")
    .addProperty(RDF.type, organization)
    .addProperty(RDFS.label, "ACME Corp");

// Add relationships
Property worksFor = kb.createProperty("http://example.org/worksFor");
alice.addProperty(worksFor, acme);
```

### Use Case 2: Data Integration

```java
Model integrated = FalkorDBModelFactory.createModel("integrated_data");

// Load from different sources
integrated.read("data/source1.ttl", "TURTLE");
integrated.read("data/source2.rdf", "RDF/XML");
integrated.read("http://example.org/data.ttl", "TURTLE");

// Query across all sources
String query = "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 100";
QueryExecution qexec = QueryExecutionFactory.create(query, integrated);
ResultSet results = qexec.execSelect();
ResultSetFormatter.out(System.out, results);
```

### Use Case 3: SPARQL Queries

```java
String sparql = 
    "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
    "SELECT ?person ?name ?age " +
    "WHERE { " +
    "  ?person a foaf:Person . " +
    "  ?person foaf:name ?name . " +
    "  ?person foaf:age ?age . " +
    "  FILTER(?age > 25) " +
    "} " +
    "ORDER BY DESC(?age)";

try (QueryExecution qexec = QueryExecutionFactory.create(sparql, model)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution soln = results.nextSolution();
        System.out.println(soln.get("name") + " - " + soln.get("age"));
    }
}
```

---

## Configuration

### Custom Connection Settings

```java
// Method 1: Direct parameters
Model model = FalkorDBModelFactory.createModel("localhost", 6379, "my_graph");

// Method 2: Builder pattern
Model model = FalkorDBModelFactory.builder()
    .host("192.168.1.100")
    .port(6379)
    .graphName("production_graph")
    .build();

// Method 3: Custom driver (NEW!)
Driver customDriver = FalkorDB.driver("localhost", 6379);
Model model = FalkorDBModelFactory.createModel(customDriver, "my_graph");
// Or with builder:
Model model = FalkorDBModelFactory.builder()
    .driver(customDriver)
    .graphName("my_graph")
    .build();
```

> **Pro Tip**: The adapter automatically creates an index on `Resource.uri` for optimal query performance!

### Environment Variables

Create `.env` file:
```properties
FALKORDB_HOST=localhost
FALKORDB_PORT=6379
FALKORDB_GRAPH=my_graph
```

---

## Troubleshooting

### Problem: Connection Refused

**Symptoms**: `Connection refused` or `Unable to connect to FalkorDB`

**Solutions**:
1. Check if FalkorDB is running:
   ```bash
   docker ps | grep falkordb
   ```

2. Test connection:
   ```bash
   redis-cli -p 6379 PING
   ```

3. Start FalkorDB:
   ```bash
   docker start falkordb
   # Or
   docker run -p 6379:6379 falkordb/falkordb:latest
   ```

### Problem: Build Errors

**Symptoms**: Maven compilation errors

**Solutions**:
1. Clean and rebuild:
   ```bash
   mvn clean install -U
   ```

2. Check Java version:
   ```bash
   java -version  # Should be 17 or higher
   ```

3. Delete Maven cache:
   ```bash
   rm -rf ~/.m2/repository
   mvn install
   ```

### Problem: Tests Failing

**Symptoms**: Tests fail with connection errors

**Solutions**:
1. Ensure FalkorDB is running before tests
2. Skip tests during build:
   ```bash
   mvn clean install -DskipTests
   ```

3. Run tests separately:
   ```bash
   mvn test
   ```

### Problem: Out of Memory

**Symptoms**: `OutOfMemoryError` when processing large datasets

**Solutions**:
1. Increase JVM heap:
   ```bash
   export MAVEN_OPTS="-Xmx2g"
   mvn exec:java -Dexec.mainClass="..."
   ```

2. Process data in batches:
   ```java
   model.begin();
   for (int i = 0; i < totalRecords; i += 1000) {
       // Process 1000 records
       if (i % 1000 == 0) {
           model.commit();
           model.begin();
       }
   }
   model.commit();
   ```

---

## GeoSPARQL Support

This project includes support for GeoSPARQL, which enables spatial queries on geographic data using SPARQL. The `jena-geosparql` module bundles Apache Jena's GeoSPARQL implementation with all dependencies.

### Building the GeoSPARQL Module

```bash
cd jena-geosparql
mvn clean package
```

This creates two JAR files:
- `jena-geosparql-0.2.0-SNAPSHOT.jar` - The module JAR
- `jena-geosparql-0.2.0-SNAPSHOT-jar-with-dependencies.jar` - JAR with all dependencies bundled

### Running Fuseki with GeoSPARQL

**Option A: Use the bundled configuration**

```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar --config config-geosparql.ttl
```

**Option B: Use the standalone GeoSPARQL JAR with Apache Jena Fuseki**

1. Copy the JAR with dependencies to Fuseki's lib directory:
   ```bash
   cp jena-geosparql/target/jena-geosparql-0.2.0-SNAPSHOT-jar-with-dependencies.jar /path/to/fuseki/lib/
   ```

2. Create a GeoSPARQL configuration file (see `jena-fuseki-falkordb/src/main/resources/config-geosparql.ttl` for an example)

3. Start Fuseki with the config:
   ```bash
   java -Xmx4G -cp fuseki-server.jar:lib/* org.apache.jena.fuseki.main.cmds.FusekiServerCmd --config config-geosparql.ttl
   ```

### GeoSPARQL Configuration Example

```turtle
@prefix geosparql:  <http://jena.apache.org/geosparql#> .
@prefix fuseki:     <http://jena.apache.org/fuseki#> .
@prefix ja:         <http://jena.hpl.hp.com/2005/11/Assembler#> .
@prefix :           <#> .

[] a fuseki:Server ;
  fuseki:services (:service) .

:service a fuseki:Service ;
  fuseki:name "dataset" ;
  fuseki:endpoint [ fuseki:operation fuseki:query ; ] ;
  fuseki:endpoint [ fuseki:operation fuseki:update ; ] ;
  fuseki:dataset :dataset_geosparql .

:dataset_geosparql a geosparql:GeosparqlDataset ;
  geosparql:inference true ;
  geosparql:queryRewrite true ;
  geosparql:indexEnabled true ;
  geosparql:dataset :dataset_base .

:dataset_base a ja:MemoryDataset .
```

### Inserting Geospatial Data

Insert geometric data using WKT (Well-Known Text) format:

```sparql
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX sf: <http://www.opengis.net/ont/sf#>
PREFIX ex: <http://example.org/>

INSERT DATA {
  # Rectangle (Polygon)
  ex:cityPark a geo:Feature ;
    rdfs:label "City Park" ;
    geo:hasGeometry ex:cityParkGeom .
  ex:cityParkGeom a sf:Polygon ;
    geo:asWKT "POLYGON((-0.15 51.50, -0.15 51.52, -0.10 51.52, -0.10 51.50, -0.15 51.50))"^^geo:wktLiteral .

  # Point (center of a circular area)
  ex:station a geo:Feature ;
    rdfs:label "Central Station" ;
    geo:hasGeometry ex:stationGeom .
  ex:stationGeom a sf:Point ;
    geo:asWKT "POINT(-0.125 51.508)"^^geo:wktLiteral .

  # Circle approximation (16-point polygon)
  ex:garden a geo:Feature ;
    rdfs:label "Circular Garden" ;
    geo:hasGeometry ex:gardenGeom .
  ex:gardenGeom a sf:Polygon ;
    geo:asWKT "POLYGON((-0.135 51.505, -0.1353 51.5068, -0.1364 51.5082, -0.1382 51.5089, -0.14 51.51, -0.1418 51.5089, -0.1436 51.5082, -0.1447 51.5068, -0.145 51.505, -0.1447 51.5032, -0.1436 51.5018, -0.1418 51.5011, -0.14 51.50, -0.1382 51.5011, -0.1364 51.5018, -0.1353 51.5032, -0.135 51.505))"^^geo:wktLiteral .
}
```

### Spatial Queries

**Query 1: Find all features within a bounding box**

```sparql
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
PREFIX ex: <http://example.org/>

SELECT ?feature ?label WHERE {
  ?feature a geo:Feature ;
    rdfs:label ?label ;
    geo:hasGeometry ?geom .
  ?geom geo:asWKT ?wkt .
  FILTER(geof:sfWithin(?wkt, "POLYGON((-0.2 51.4, -0.2 51.6, 0 51.6, 0 51.4, -0.2 51.4))"^^geo:wktLiteral))
}
```

**Query 2: Find features that contain a specific point**

```sparql
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
PREFIX sf: <http://www.opengis.net/ont/sf#>

SELECT ?feature ?label WHERE {
  ?feature a geo:Feature ;
    rdfs:label ?label ;
    geo:hasGeometry ?geom .
  ?geom a sf:Polygon ;
    geo:asWKT ?wkt .
  FILTER(geof:sfContains(?wkt, "POINT(-0.13 51.51)"^^geo:wktLiteral))
}
```

**Query 3: Find intersecting geometries**

```sparql
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>

SELECT ?feature1 ?feature2 WHERE {
  ?feature1 a geo:Feature ; geo:hasGeometry ?geom1 .
  ?feature2 a geo:Feature ; geo:hasGeometry ?geom2 .
  ?geom1 geo:asWKT ?wkt1 .
  ?geom2 geo:asWKT ?wkt2 .
  FILTER(?feature1 != ?feature2)
  FILTER(geof:sfIntersects(?wkt1, ?wkt2))
}
```

### Sample Data File

A sample GeoSPARQL data file with circles and rectangles is available at:
`jena-fuseki-falkordb/src/main/resources/data/geo_shapes.ttl`

Load it using the Fuseki web interface or via curl:

```bash
curl -X POST -H "Content-Type: text/turtle" \
  --data-binary @jena-fuseki-falkordb/src/main/resources/data/geo_shapes.ttl \
  http://localhost:3030/dataset/data
```

### GeoSPARQL Functions

Common spatial functions available:

| Function | Description |
|----------|-------------|
| `geof:sfContains` | Tests if geometry A contains geometry B |
| `geof:sfWithin` | Tests if geometry A is within geometry B |
| `geof:sfIntersects` | Tests if geometries A and B intersect |
| `geof:sfOverlaps` | Tests if geometries A and B overlap |
| `geof:sfTouches` | Tests if geometries A and B touch |
| `geof:sfCrosses` | Tests if geometries A and B cross |
| `geof:sfEquals` | Tests if geometries A and B are equal |
| `geof:sfDisjoint` | Tests if geometries A and B are disjoint |
| `geof:distance` | Calculates distance between two geometries |
| `geof:buffer` | Creates a buffer around a geometry |

For more information, see the [Apache Jena GeoSPARQL Documentation](https://jena.apache.org/documentation/geosparql/).

---

## Next Steps

1. **Explore Examples**: Run all the examples in `QuickStart.java`

2. **Read the Docs**: 
   - [Apache Jena Documentation](https://jena.apache.org/documentation/)
   - [SPARQL Tutorial](https://jena.apache.org/tutorials/sparql.html)
   - [FalkorDB Documentation](https://docs.falkordb.com/)

3. **Build Your Application**: Start with a simple use case and expand

4. **Join the Community**:
   - Apache Jena mailing lists
   - FalkorDB Discord/Slack
   - Stack Overflow

---

## Useful Commands

### Docker Commands
```bash
# Start FalkorDB
docker run -d --name falkordb -p 6379:6379 falkordb/falkordb:latest

# Stop FalkorDB
docker stop falkordb

# View logs
docker logs falkordb

# Remove container
docker rm falkordb

# Run with persistent storage
docker run -d --name falkordb -p 6379:6379 \
  -v falkordb-data:/data \
  falkordb/falkordb:latest
```

### Maven Commands
```bash
# Clean build
mvn clean install

# Skip tests
mvn install -DskipTests

# Run specific class
mvn exec:java -Dexec.mainClass="com.falkordb.jena.Main"

# Run tests
mvn test

# Package as JAR
mvn package

# Run packaged JAR
java -jar target/jena-falkordb-adapter-1.0-SNAPSHOT.jar
```

### Redis/FalkorDB Commands
```bash
# Connect to FalkorDB
redis-cli -p 6379

# List all graphs
GRAPH.LIST

# Query a graph
GRAPH.QUERY demo_graph "MATCH (n) RETURN n LIMIT 10"

# Delete a graph
GRAPH.DELETE demo_graph

# Get graph info
GRAPH.EXPLAIN demo_graph "MATCH (n) RETURN n"
```

---

## FAQ

**Q: Can I use this in production?**
A: This is an educational/experimental adapter. For production, consider additional testing, error handling, and performance optimization.

**Q: Does it support all SPARQL features?**
A: No, this is a basic implementation. Some advanced SPARQL features may not work correctly.

**Q: Can I contribute?**
A: Absolutely! Contributions are welcome.

**Q: What's the performance like?**
A: There's translation overhead. For large datasets, consider native FalkorDB queries or optimize the adapter.

**Q: Can I use other graph databases?**
A: The same pattern can be adapted for other graph databases like Neo4j, Neptune, etc.

---

## Resources

- **Project README**: See `README.md` for detailed information
- **Code Examples**: Check `QuickStart.java` and `Main.java`
- **Tests**: Review `FalkorDBGraphTest.java` for usage patterns
- **Apache Jena**: https://jena.apache.org/
- **Apache Jena GeoSPARQL**: https://jena.apache.org/documentation/geosparql/
- **FalkorDB**: https://www.falkordb.com/
- **SPARQL**: https://www.w3.org/TR/sparql11-query/
- **GeoSPARQL Standard**: https://www.ogc.org/standards/geosparql

---

**Happy Coding! 🚀**