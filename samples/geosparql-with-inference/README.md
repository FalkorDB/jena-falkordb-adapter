# GeoSPARQL with Forward Inference Example

This example demonstrates how to combine **forward inference** (eager, forward chaining rules) with **GeoSPARQL** spatial queries using FalkorDB as the backend storage. This powerful combination enables queries that span both materialized inferred relationships and geographic data.

## Overview

The configuration [config-falkordb.ttl](../../jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl) implements a three-layer onion architecture:

1. **GeoSPARQL Dataset (Outer Layer)** - Spatial query capabilities with indexing and optimization
2. **Inference Model (Middle Layer)** - Forward chaining (eager inference) materializes relationships immediately
3. **FalkorDB Model (Core Layer)** - Persistent graph database backend

## Use Cases

This configuration is ideal for applications that need to:

- Find people in extended social networks with their geographic locations
- Query spatial relationships combined with inferred connections
- Analyze geographic distribution of connected entities
- Discover patterns in both social and spatial dimensions

## Dataset

The example dataset (`data-example.ttl`) contains:

- **Social Network**: 5 people (Alice, Bob, Carol, Dave, Eve) with friendship connections
- **Geographic Locations**: Point locations for each person in London
- **Geographic Features**: Polygon areas representing London districts (Central, North, Tech Hub)

### Social Network Structure

```
Alice -> Bob -> Carol
         Bob -> Dave -> Eve
```

With forward inference using `grandfather_of_fwd.rule`, when father_of relationships are inserted, grandfather_of relationships are automatically materialized.

### Geographic Data

All locations use real London coordinates:
- Westminster (Central): Alice
- Camden (North): Bob  
- Shoreditch (East): Carol
- Kings Cross (North Central): Dave
- Islington (North): Eve

## Running the Example

### Prerequisites

1. **Start FalkorDB with tracing**:
   ```bash
   docker-compose -f docker-compose-tracing.yaml up -d
   ```

2. **Install Java and Maven using SDKMAN**:
   ```bash
   curl -s "https://get.sdkman.io" | bash
   source "$HOME/.sdkman/bin/sdkman-init.sh"
   sdk env install
   ```

3. **Build the project**:
   ```bash
   mvn clean install
   ```

### Start Fuseki Server

```bash
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

The server will start on port 3330 with the service available at `/falkor`.

### Load the Example Data

Using curl:

```bash
curl -X POST \
  -H "Content-Type: text/turtle" \
  --data-binary @samples/geosparql-with-inference/data-example.ttl \
  http://localhost:3030/falkor/data
```

Or use Fuseki's web UI at `http://localhost:3030` to upload the file.

### Run Example Queries

The `queries.sparql` file contains 10 example queries demonstrating different combinations of inference and spatial queries.

#### Example 1: Find Direct Friends with Locations

```sparql
PREFIX social: <http://example.org/social#>
PREFIX geo:    <http://www.opengis.net/ont/geosparql#>
PREFIX ex:     <http://example.org/>

SELECT ?person1Name ?person2Name ?location
WHERE {
  ?person1 ex:name ?person1Name ;
           social:knows ?person2 .  # Direct relationships
  ?person2 ex:name ?person2Name ;
           geo:hasGeometry ?geom .
  ?geom geo:asWKT ?location .                     # GeoSPARQL location
  FILTER(?person1 = ex:alice)
}
ORDER BY ?person2Name
```

**Result**: Returns Alice's direct friends (Bob) with their geographic locations.

#### Example 2: Check Direct Connection

```sparql
PREFIX social: <http://example.org/social#>
PREFIX ex:     <http://example.org/>

ASK {
  ex:alice social:knows ex:bob .
}
```

**Result**: `true` - Bob is Alice's direct friend.

#### Example 3: People with Occupations and Locations

```sparql
PREFIX social: <http://example.org/social#>
PREFIX geo:    <http://www.opengis.net/ont/geosparql#>
PREFIX ex:     <http://example.org/>

SELECT ?sourceName ?friendName ?occupation ?location
WHERE {
  ?source ex:name ?sourceName ;
          social:knows ?friend .
  ?friend ex:name ?friendName ;
          ex:occupation ?occupation ;
          geo:hasGeometry ?geom .
  ?geom geo:asWKT ?location .
}
ORDER BY ?sourceName ?friendName
```

**Result**: Direct relationships with occupation and location information.

### Query via HTTP

You can also query programmatically:

```bash
# Query with inference and spatial data
curl -X POST http://localhost:3030/falkor/query \
  -H "Content-Type: application/sparql-query" \
  --data-binary @samples/geosparql-with-inference/queries.sparql
```

## Key Features Demonstrated

### 1. Forward Inference (Eager Materialization)

The `grandfather_of_fwd.rule` enables eager materialization of inferred relationships:

```
[rule: (?a :father_of ?b), (?b :father_of ?c) -> (?a :grandfather_of ?c)]
```

Relationships are materialized **immediately** when base triples are added (forward chaining).

### 2. GeoSPARQL Capabilities

- **Point geometries**: People's home locations
- **Polygon geometries**: Geographic regions (districts)
- **WKT literals**: Standard representation of geometries
- **Spatial indexing**: Enabled for performance

### 3. Combined Queries

Queries can mix both paradigms:

```sparql
# Find friends with their locations (spatial)
?person1 social:knows ?person2 .  # Direct relationships
?person2 geo:hasGeometry ?geom .  # Spatial
```

## Performance Considerations

- **Forward inference**: Inferred relationships are materialized immediately when data is inserted
- **Spatial indexing**: Enabled for efficient geometric operations
- **FalkorDB backend**: Provides persistent storage and graph query optimization

## Advanced Usage

### Custom Rules

You can modify `rules/grandfather_of_fwd.rule` or add custom forward chaining rules:

```
# Example: Infer colleague relationships
[colleague: (?a ex:workplace ?w), (?b ex:workplace ?w), notEqual(?a, ?b) 
 -> (?a ex:colleague ?b)]
```

### Spatial Functions

GeoSPARQL provides additional spatial functions (requires full GeoSPARQL support):

- `geof:sfWithin` - Check if a geometry is within another
- `geof:sfIntersects` - Check if geometries intersect
- `geof:distance` - Calculate distance between geometries
- `geof:buffer` - Create buffer zones

### Combining with Other Features

This configuration can be extended with:

- Additional inference rules (RDFS, OWL)
- Transaction support
- Access control
- Custom property functions

## Testing

The system tests provide additional examples and validate the configuration:

```bash
# Test forward chaining inference
mvn test -pl jena-fuseki-falkordb -Dtest=GrandfatherInferenceSystemTest

# Test GeoSPARQL queries
mvn test -pl jena-fuseki-falkordb -Dtest=GeoSPARQLPOCSystemTest
```

## Troubleshooting

**Issue**: Rules not working
- Check that `rules/friend_of_friend_bwd.rule` exists and is readable
- Verify the configuration file path is correct

**Issue**: Spatial queries not working
- Ensure GeoSPARQL initialization: `GeoSPARQLConfig.setupMemoryIndex()`
- Check that geometry literals use `geo:wktLiteral` datatype

**Issue**: FalkorDB connection failed
- Verify FalkorDB is running: `docker ps | grep falkordb`
- Check connection settings in configuration file

## References

- [Apache Jena Inference](https://jena.apache.org/documentation/inference/)
- [GeoSPARQL Specification](http://www.opengeospatial.org/standards/geosparql)
- [FalkorDB Documentation](https://docs.falkordb.com/)
- [Well-Known Text (WKT)](https://en.wikipedia.org/wiki/Well-known_text_representation_of_geometry)
