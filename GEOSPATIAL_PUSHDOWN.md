# Geospatial Query Pushdown to FalkorDB

This document describes the geospatial query pushdown optimization that translates GeoSPARQL queries to FalkorDB's native geospatial functions.

> **📖 Complete Guide**: For hands-on examples with curl commands, Jaeger tracing, and step-by-step setup, see [DEMO.md](DEMO.md)

## Overview

The Jena-FalkorDB Adapter now supports pushing down geospatial queries from GeoSPARQL to FalkorDB's native `point()` and `distance()` functions. This enables efficient spatial queries with database-side computation instead of client-side evaluation.

### Key Benefits

- ✅ **Database-side spatial computation**: Leverage FalkorDB's native geospatial functions
- ✅ **Reduced data transfer**: Query results computed in the database
- ✅ **Better performance**: Native graph database spatial operations
- ✅ **GeoSPARQL compatibility**: Use standard GeoSPARQL vocabulary and functions
- ✅ **Automatic translation**: SPARQL queries automatically compiled to Cypher
- ✅ **OpenTelemetry tracing**: Full observability of geospatial operations

## FalkorDB Geospatial Capabilities

FalkorDB provides native support for geospatial data through:

### Point Function

Create a point with latitude and longitude:

```cypher
CREATE (:Location {
  name: 'London',
  position: point({latitude: 51.5074, longitude: -0.1278})
})
```

### Distance Function

Calculate distance between two points (in meters):

```cypher
MATCH (a:Location {name: 'London'}), (b:Location {name: 'Paris'})
RETURN distance(a.position, b.position) as distanceMeters
```

### Point Properties

Access point coordinates:

```cypher
MATCH (loc:Location {name: 'London'})
RETURN loc.position.latitude, loc.position.longitude
```

## Supported GeoSPARQL Functions

The adapter currently supports translation of the following GeoSPARQL functions:

| GeoSPARQL Function | FalkorDB Translation | Description |
|--------------------|---------------------|-------------|
| `geof:distance` | `distance(point1, point2)` | Calculate distance between two points |
| `geof:sfWithin` | Distance-based range check | Check if a point is within a region |
| `geof:sfContains` | Spatial containment logic | Check if a region contains a point |
| `geof:sfIntersects` | Spatial intersection logic | Check if two geometries intersect |

### Supported Geometry Types

| Geometry Type | Support Level | Translation | Bounding Box |
|--------------|--------------|-------------|--------------|
| POINT | ✅ Full support | Exact coordinates translated to `point({latitude, longitude})` | Single point |
| POLYGON | ✅ Full support | Complete bounding box calculated; center point used as approximation | ✅ Min/max lat/lon |
| LINESTRING | ✅ Full support | Complete bounding box calculated; center point used as approximation | ✅ Min/max lat/lon |
| MULTIPOINT | ✅ Full support | Complete bounding box calculated; center point used as approximation | ✅ Min/max lat/lon |

**Bounding Box Support:**  
For POLYGON, LINESTRING, and MULTIPOINT geometries, the adapter calculates the complete bounding box (minimum and maximum latitude/longitude values). The center point of this bounding box is used as a representative location for the `point()` function. The bounding box parameters are stored and can be used for range queries:

- `{prefix}_minLat` - Minimum latitude of bounding box
- `{prefix}_maxLat` - Maximum latitude of bounding box
- `{prefix}_minLon` - Minimum longitude of bounding box
- `{prefix}_maxLon` - Maximum longitude of bounding box
- `{prefix}_lat` - Center latitude (for point() expression)
- `{prefix}_lon` - Center longitude (for point() expression)

## How It Works

### 1. WKT Parsing

The adapter parses Well-Known Text (WKT) geometries to extract coordinates:

```java
String wkt = "POINT(-0.1278 51.5074)";
Double lat = GeoSPARQLToCypherTranslator.extractLatitude(wkt);  // 51.5074
Double lon = GeoSPARQLToCypherTranslator.extractLongitude(wkt); // -0.1278
```

### 2. Cypher Point Generation

Extracted coordinates are converted to FalkorDB point() expressions:

```cypher
point({latitude: 51.5074, longitude: -0.1278})
```

### 3. Query Translation

GeoSPARQL FILTER expressions are translated to Cypher WHERE clauses:

```sparql
# GeoSPARQL Query
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>

SELECT ?loc1 ?loc2 ?dist WHERE {
  ?loc1 a :Location .
  ?loc2 a :Location .
  BIND(geof:distance(?loc1geom, ?loc2geom) AS ?dist)
  FILTER(?dist < 100000)  # Within 100km
}
```

```cypher
# Generated Cypher
MATCH (loc1:Resource:Location), (loc2:Resource:Location)
WHERE distance(loc1.position, loc2.position) < 100000
RETURN loc1.uri AS loc1, loc2.uri AS loc2, 
       distance(loc1.position, loc2.position) AS dist
```

## Usage Examples

### Example 1: Store Locations with Coordinates

```java
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;

Model model = FalkorDBModelFactory.createModel("geo_graph");

// Store location with latitude/longitude properties
Resource london = model.createResource("http://example.org/london");
london.addProperty(
    model.createProperty("http://example.org/name"), 
    "London");
london.addProperty(
    model.createProperty("http://example.org/latitude"), 
    model.createTypedLiteral(51.5074));
london.addProperty(
    model.createProperty("http://example.org/longitude"), 
    model.createTypedLiteral(-0.1278));
```

### Example 2: Store GeoSPARQL Data with WKT

```java
// Using standard GeoSPARQL vocabulary
Resource location = model.createResource("http://example.org/locations/london");
Resource geometry = model.createResource("http://example.org/geometries/london");

location.addProperty(
    model.createProperty("http://www.w3.org/2000/01/rdf-schema#label"), 
    "London");
location.addProperty(
    model.createProperty("http://www.opengis.net/ont/geosparql#hasGeometry"), 
    geometry);

geometry.addProperty(
    model.createProperty("http://www.opengis.net/ont/geosparql#asWKT"),
    model.createTypedLiteral(
        "POINT(-0.1278 51.5074)", 
        "http://www.opengis.net/ont/geosparql#wktLiteral"));
```

### Example 3: Query Locations by Distance

```java
String sparql = """
    PREFIX ex: <http://example.org/>
    PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
    
    SELECT ?city ?name ?distance WHERE {
      ?city ex:name ?name .
      ?city ex:latitude ?lat .
      ?city ex:longitude ?lon .
      FILTER(?lat >= 50 && ?lat <= 52)
      FILTER(?lon >= -1 && ?lon <= 1)
    }
    ORDER BY ?distance
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    while (results.hasNext()) {
        QuerySolution sol = results.next();
        System.out.println(sol.getLiteral("name").getString() + " - " +
                          sol.getLiteral("distance").getDouble() + " meters");
    }
}
```

### Example 4: Find Locations Within Bounding Box

```java
String sparql = """
    PREFIX ex: <http://example.org/>
    
    SELECT ?name ?lat ?lon WHERE {
      ?loc ex:name ?name .
      ?loc ex:latitude ?lat .
      ?loc ex:longitude ?lon .
      FILTER(?lat >= 51.0 && ?lat <= 52.0)
      FILTER(?lon >= -1.0 && ?lon <= 0.0)
    }
    """;

Query query = QueryFactory.create(sparql);
try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
    ResultSet results = qexec.execSelect();
    ResultSetFormatter.out(System.out, results, query);
}
```

## Geometry Type Examples

### Example 4: POINT Geometry

The most straightforward geometry type with exact coordinates:

**Turtle (TTL) Format:**
```turtle
@prefix ex: <http://example.org/> .
@prefix geo: <http://www.opengis.net/ont/geosparql#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

ex:london a ex:Location ;
    rdfs:label "London" ;
    geo:hasGeometry [
        geo:asWKT "POINT(-0.1278 51.5074)"^^geo:wktLiteral
    ] .
```

**JSON-LD Format:**
```json
{
  "@context": {
    "ex": "http://example.org/",
    "geo": "http://www.opengis.net/ont/geosparql#",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#"
  },
  "@id": "ex:london",
  "@type": "ex:Location",
  "rdfs:label": "London",
  "geo:hasGeometry": {
    "geo:asWKT": {
      "@value": "POINT(-0.1278 51.5074)",
      "@type": "geo:wktLiteral"
    }
  }
}
```

**RDF/XML Format:**
```xml
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:ex="http://example.org/"
         xmlns:geo="http://www.opengis.net/ont/geosparql#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
  <ex:Location rdf:about="http://example.org/london">
    <rdfs:label>London</rdfs:label>
    <geo:hasGeometry>
      <rdf:Description>
        <geo:asWKT rdf:datatype="http://www.opengis.net/ont/geosparql#wktLiteral">POINT(-0.1278 51.5074)</geo:asWKT>
      </rdf:Description>
    </geo:hasGeometry>
  </ex:Location>
</rdf:RDF>
```

**Parsing Result:**
- Exact coordinates: latitude = 51.5074, longitude = -0.1278
- Generated Cypher: `point({latitude: 51.5074, longitude: -0.1278})`

### Example 5: POLYGON Geometry

Represents a closed area with complete bounding box calculation:

**Turtle (TTL) Format:**
```turtle
@prefix ex: <http://example.org/> .
@prefix geo: <http://www.opengis.net/ont/geosparql#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

ex:hyde_park a ex:Park ;
    rdfs:label "Hyde Park" ;
    geo:hasGeometry [
        geo:asWKT "POLYGON((-0.1791 51.5074, -0.1791 51.5123, -0.1626 51.5123, -0.1626 51.5074, -0.1791 51.5074))"^^geo:wktLiteral
    ] .
```

**JSON-LD Format:**
```json
{
  "@context": {
    "ex": "http://example.org/",
    "geo": "http://www.opengis.net/ont/geosparql#",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#"
  },
  "@id": "ex:hyde_park",
  "@type": "ex:Park",
  "rdfs:label": "Hyde Park",
  "geo:hasGeometry": {
    "geo:asWKT": {
      "@value": "POLYGON((-0.1791 51.5074, -0.1791 51.5123, -0.1626 51.5123, -0.1626 51.5074, -0.1791 51.5074))",
      "@type": "geo:wktLiteral"
    }
  }
}
```

**RDF/XML Format:**
```xml
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:ex="http://example.org/"
         xmlns:geo="http://www.opengis.net/ont/geosparql#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
  <ex:Park rdf:about="http://example.org/hyde_park">
    <rdfs:label>Hyde Park</rdfs:label>
    <geo:hasGeometry>
      <rdf:Description>
        <geo:asWKT rdf:datatype="http://www.opengis.net/ont/geosparql#wktLiteral">POLYGON((-0.1791 51.5074, -0.1791 51.5123, -0.1626 51.5123, -0.1626 51.5074, -0.1791 51.5074))</geo:asWKT>
      </rdf:Description>
    </geo:hasGeometry>
  </ex:Park>
</rdf:RDF>
```

**Parsing Result:**
- Bounding box: minLat = 51.5074, maxLat = 51.5123, minLon = -0.1791, maxLon = -0.1626
- Center point: latitude = 51.50985, longitude = -0.17085
- Generated Cypher: `point({latitude: 51.50985, longitude: -0.17085})`
- Bounding box parameters stored for range queries

### Example 6: LINESTRING Geometry

Represents a route or path with bounding box calculation:

**Turtle (TTL) Format:**
```turtle
@prefix ex: <http://example.org/> .
@prefix geo: <http://www.opengis.net/ont/geosparql#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

ex:thames_path a ex:Route ;
    rdfs:label "Thames Path" ;
    geo:hasGeometry [
        geo:asWKT "LINESTRING(-0.1278 51.5074, -0.0759 51.5048, -0.0277 51.5033, 0.0000 51.4934)"^^geo:wktLiteral
    ] .
```

**JSON-LD Format:**
```json
{
  "@context": {
    "ex": "http://example.org/",
    "geo": "http://www.opengis.net/ont/geosparql#",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#"
  },
  "@id": "ex:thames_path",
  "@type": "ex:Route",
  "rdfs:label": "Thames Path",
  "geo:hasGeometry": {
    "geo:asWKT": {
      "@value": "LINESTRING(-0.1278 51.5074, -0.0759 51.5048, -0.0277 51.5033, 0.0000 51.4934)",
      "@type": "geo:wktLiteral"
    }
  }
}
```

**RDF/XML Format:**
```xml
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:ex="http://example.org/"
         xmlns:geo="http://www.opengis.net/ont/geosparql#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
  <ex:Route rdf:about="http://example.org/thames_path">
    <rdfs:label>Thames Path</rdfs:label>
    <geo:hasGeometry>
      <rdf:Description>
        <geo:asWKT rdf:datatype="http://www.opengis.net/ont/geosparql#wktLiteral">LINESTRING(-0.1278 51.5074, -0.0759 51.5048, -0.0277 51.5033, 0.0000 51.4934)</geo:asWKT>
      </rdf:Description>
    </geo:hasGeometry>
  </ex:Route>
</rdf:RDF>
```

**Parsing Result:**
- Bounding box: minLat = 51.4934, maxLat = 51.5074, minLon = -0.1278, maxLon = 0.0000
- Center point: latitude = 51.5004, longitude = -0.0639
- Generated Cypher: `point({latitude: 51.5004, longitude: -0.0639})`
- Bounding box parameters stored for range queries

### Example 7: MULTIPOINT Geometry

Represents multiple discrete points with bounding box calculation:

**Turtle (TTL) Format:**
```turtle
@prefix ex: <http://example.org/> .
@prefix geo: <http://www.opengis.net/ont/geosparql#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

ex:european_capitals a ex:PointSet ;
    rdfs:label "European Capitals" ;
    geo:hasGeometry [
        geo:asWKT "MULTIPOINT((-0.1278 51.5074), (2.3522 48.8566), (13.4050 52.5200))"^^geo:wktLiteral
    ] .
```

**JSON-LD Format:**
```json
{
  "@context": {
    "ex": "http://example.org/",
    "geo": "http://www.opengis.net/ont/geosparql#",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#"
  },
  "@id": "ex:european_capitals",
  "@type": "ex:PointSet",
  "rdfs:label": "European Capitals",
  "geo:hasGeometry": {
    "geo:asWKT": {
      "@value": "MULTIPOINT((-0.1278 51.5074), (2.3522 48.8566), (13.4050 52.5200))",
      "@type": "geo:wktLiteral"
    }
  }
}
```

**RDF/XML Format:**
```xml
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:ex="http://example.org/"
         xmlns:geo="http://www.opengis.net/ont/geosparql#"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
  <ex:PointSet rdf:about="http://example.org/european_capitals">
    <rdfs:label>European Capitals</rdfs:label>
    <geo:hasGeometry>
      <rdf:Description>
        <geo:asWKT rdf:datatype="http://www.opengis.net/ont/geosparql#wktLiteral">MULTIPOINT((-0.1278 51.5074), (2.3522 48.8566), (13.4050 52.5200))</geo:asWKT>
      </rdf:Description>
    </geo:hasGeometry>
  </ex:PointSet>
</rdf:RDF>
```

**Parsing Result:**
- Bounding box: minLat = 48.8566, maxLat = 52.5200, minLon = -0.1278, maxLon = 13.4050
- Center point: latitude = 50.6883, longitude = 6.6386
- Generated Cypher: `point({latitude: 50.6883, longitude: 6.6386})`
- Bounding box parameters stored for range queries

### Example 8: Querying All Geometry Types

**SPARQL Query:**
```sparql
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?location ?label ?wkt WHERE {
  ?location geo:hasGeometry ?geom .
  ?location rdfs:label ?label .
  ?geom geo:asWKT ?wkt .
}
ORDER BY ?label
```

**Java Code:**
```java
import com.falkordb.jena.FalkorDBModelFactory;
import com.falkordb.jena.query.GeoSPARQLToCypherTranslator;
import org.apache.jena.rdf.model.*;
import org.apache.jena.query.*;

Model model = FalkorDBModelFactory.createModel("geo_graph");

// Parse and extract bounding box from any WKT geometry
String polygonWKT = "POLYGON((-0.1791 51.5074, -0.1791 51.5123, -0.1626 51.5123, -0.1626 51.5074, -0.1791 51.5074))";
double[] bbox = GeoSPARQLToCypherTranslator.extractBoundingBox(polygonWKT);

System.out.println("Bounding Box:");
System.out.println("  Min Latitude: " + bbox[0]);
System.out.println("  Max Latitude: " + bbox[1]);
System.out.println("  Min Longitude: " + bbox[2]);
System.out.println("  Max Longitude: " + bbox[3]);

// Extract center point
Double centerLat = GeoSPARQLToCypherTranslator.extractLatitude(polygonWKT);
Double centerLon = GeoSPARQLToCypherTranslator.extractLongitude(polygonWKT);

System.out.println("Center Point:");
System.out.println("  Latitude: " + centerLat);
System.out.println("  Longitude: " + centerLon);
```

**Output:**
```
Bounding Box:
  Min Latitude: 51.5074
  Max Latitude: 51.5123
  Min Longitude: -0.1791
  Max Longitude: -0.1626
Center Point:
  Latitude: 51.50985
  Longitude: -0.17085
```

## Advanced Usage

### Direct Cypher with Magic Property

For maximum control over geospatial queries, use the `falkor:cypher` magic property:

```sparql
PREFIX falkor: <http://falkordb.com/jena#>

SELECT ?city ?distance WHERE {
    (?city ?distance) falkor:cypher '''
        MATCH (london:Resource {uri: "http://example.org/london"}),
              (other:Resource)
        WHERE other.uri <> london.uri
          AND distance(
                point({
                  latitude: london.`http://example.org/latitude`, 
                  longitude: london.`http://example.org/longitude`
                }),
                point({
                  latitude: other.`http://example.org/latitude`, 
                  longitude: other.`http://example.org/longitude`
                })
              ) < 100000
        RETURN other.uri AS city, 
               distance(
                 point({
                   latitude: london.`http://example.org/latitude`, 
                   longitude: london.`http://example.org/longitude`
                 }),
                 point({
                   latitude: other.`http://example.org/latitude`, 
                   longitude: other.`http://example.org/longitude`
                 })
               ) AS distance
        ORDER BY distance
    '''
}
```

## Performance Comparison

| Scenario | Without Pushdown | With Pushdown | Improvement |
|----------|-----------------|---------------|-------------|
| Distance calculation (100 points) | 100 queries + client computation | 1 query | 100x fewer calls |
| Nearby locations (radius search) | All points fetched + filtering | Database-side filtering | Minimal data transfer |
| Bounding box query (1000 points) | 1000 triples fetched | Only matching points | Reduced network traffic |

## OpenTelemetry Tracing

All geospatial operations are fully instrumented with OpenTelemetry:

### Traced Components

1. **GeoSPARQLToCypherTranslator** - WKT parsing and translation
   - Span: `GeoSPARQLToCypherTranslator.translateGeoFunction`
   - Attributes: `falkordb.geospatial.function`, `falkordb.geospatial.geometry_type`

2. **SparqlToCypherCompiler** - Query compilation with geospatial FILTERs
   - Includes geospatial function detection and translation

### Enabling Tracing

```bash
# Start Jaeger
docker-compose -f docker-compose-tracing.yaml up -d

# Set environment variable
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

# Run your application
java -jar your-app.jar
```

View traces at `http://localhost:16686`

## Data Formats

### Turtle (TTL) Format

```turtle
@prefix ex: <http://example.org/> .
@prefix geo: <http://www.opengis.net/ont/geosparql#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

ex:london a ex:Location ;
    rdfs:label "London" ;
    ex:latitude 51.5074 ;
    ex:longitude -0.1278 .

ex:paris a ex:Location ;
    rdfs:label "Paris" ;
    ex:latitude 48.8566 ;
    ex:longitude 2.3522 .
```

### JSON-LD Format

```json
{
  "@context": {
    "ex": "http://example.org/",
    "geo": "http://www.opengis.net/ont/geosparql#",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#"
  },
  "@graph": [
    {
      "@id": "ex:london",
      "@type": "ex:Location",
      "rdfs:label": "London",
      "ex:latitude": 51.5074,
      "ex:longitude": -0.1278
    },
    {
      "@id": "ex:paris",
      "@type": "ex:Location",
      "rdfs:label": "Paris",
      "ex:latitude": 48.8566,
      "ex:longitude": 2.3522
    }
  ]
}
```

### RDF/XML Format

```xml
<?xml version="1.0"?>
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
         xmlns:ex="http://example.org/"
         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
  
  <ex:Location rdf:about="http://example.org/london">
    <rdfs:label>London</rdfs:label>
    <ex:latitude rdf:datatype="http://www.w3.org/2001/XMLSchema#double">
      51.5074
    </ex:latitude>
    <ex:longitude rdf:datatype="http://www.w3.org/2001/XMLSchema#double">
      -0.1278
    </ex:longitude>
  </ex:Location>
  
  <ex:Location rdf:about="http://example.org/paris">
    <rdfs:label>Paris</rdfs:label>
    <ex:latitude rdf:datatype="http://www.w3.org/2001/XMLSchema#double">
      48.8566
    </ex:latitude>
    <ex:longitude rdf:datatype="http://www.w3.org/2001/XMLSchema#double">
      2.3522
    </ex:longitude>
  </ex:Location>
  
</rdf:RDF>
```

## Testing

### Unit Tests

Tests for WKT parsing and Cypher generation:

- [GeoSPARQLToCypherTranslatorTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/GeoSPARQLToCypherTranslatorTest.java)
  - Tests WKT POINT parsing
  - Tests WKT POLYGON parsing
  - Tests coordinate extraction
  - Tests parameter generation

### Integration Tests

End-to-end tests with FalkorDB:

- [FalkorDBGeospatialPushdownTest.java](jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBGeospatialPushdownTest.java)
  - Tests storing locations with coordinates
  - Tests querying locations by bounding box
  - Tests GeoSPARQL vocabulary compatibility
  - Tests WKT parsing in real queries

### Running Tests

```bash
# Run all geospatial tests
mvn test -Dtest=GeoSPARQLToCypherTranslatorTest,FalkorDBGeospatialPushdownTest

# Run specific test
mvn test -Dtest=GeoSPARQLToCypherTranslatorTest#testParsePointWKT
```

## Limitations

### Current Limitations

1. **POLYGON Support**: Polygons are approximated using first coordinate or bounding box
2. **Distance Units**: FalkorDB distance() returns meters; unit conversion needed for miles/km
3. **Spatial Functions**: Limited set of GeoSPARQL functions currently supported
4. **3D Coordinates**: Height/elevation not yet supported in point() function
5. **CRS Support**: Currently assumes WGS-84 coordinate reference system

### Planned Enhancements

- [ ] Full POLYGON support with proper containment checks
- [ ] LINESTRING geometry support
- [ ] MULTIPOINT and MULTIPOLYGON support
- [ ] Additional spatial functions (buffer, convexHull, etc.)
- [ ] Coordinate reference system (CRS) transformation
- [ ] 3D coordinate support

## Best Practices

### 1. Store Coordinates Efficiently

```java
// Good: Store as typed literals
location.addProperty(latProp, model.createTypedLiteral(51.5074));
location.addProperty(lonProp, model.createTypedLiteral(-0.1278));

// Avoid: String literals
location.addProperty(latProp, "51.5074");  // Requires parsing
```

### 2. Use Appropriate Precision

```java
// Good: Reasonable precision (5-6 decimal places ≈ 1-10 meter accuracy)
double lat = 51.5074;  // 4 decimal places
double lon = -0.1278;

// Avoid: Excessive precision
double lat = 51.50740000000000123456;  // Unnecessary precision
```

### 3. Index Coordinate Properties

When using FalkorDB directly, create indexes on coordinate properties for faster queries:

```cypher
CREATE INDEX FOR (n:Location) ON (n.latitude)
CREATE INDEX FOR (n:Location) ON (n.longitude)
```

### 4. Use Bounding Box Pre-filtering

For large datasets, use bounding box filters before distance calculations:

```sparql
# Good: Filter by bounding box first
SELECT ?loc ?dist WHERE {
  ?loc ex:latitude ?lat .
  ?loc ex:longitude ?lon .
  FILTER(?lat >= 51.0 && ?lat <= 52.0)  # Bounding box
  FILTER(?lon >= -1.0 && ?lon <= 0.0)
  # Then calculate distance for filtered results
}
```

## Resources

- **FalkorDB Geospatial Documentation**: https://docs.falkordb.com/cypher/functions.html#point-functions
- **GeoSPARQL Standard**: https://www.ogc.org/standard/geosparql/
- **Apache Jena GeoSPARQL**: https://jena.apache.org/documentation/geosparql/
- **WKT Format Specification**: https://en.wikipedia.org/wiki/Well-known_text_representation_of_geometry

## Examples

Complete working examples are available in the [samples/](samples/) directory:

- [samples/geospatial-pushdown/](samples/geospatial-pushdown/) - Distance queries and spatial operations

Each example includes:
- ✅ Complete Java code with multiple use cases
- ✅ SPARQL query patterns
- ✅ Sample data in Turtle/JSON-LD/RDF-XML formats
- ✅ Detailed README with explanations

## Configuration with SafeGeosparqlDataset

The included `config-falkordb.ttl` uses `falkor:SafeGeosparqlDataset` to handle server restarts gracefully:

```turtle
:geospatial_dataset rdf:type falkor:SafeGeosparqlDataset ;
    geosparql:inference            true ;
    geosparql:queryRewrite         true ;
    geosparql:indexEnabled         true ;
    geosparql:applyDefaultGeometry false ;
    geosparql:srsUri               <http://www.opengis.net/def/crs/OGC/1.3/CRS84> ;
    geosparql:dataset :dataset_rdf .
```

### Why SafeGeosparqlDataset?

When restarting Fuseki with existing data that contains both geometry and non-geometry literals (like person names, ages, etc.), the standard GeoSPARQL assembler tries to build a spatial index and fails with:

```
org.apache.jena.datatypes.DatatypeFormatException: Unrecognised Geometry Datatype: 
http://www.w3.org/2001/XMLSchema#string
```

The `SafeGeosparqlDataset` catches this error and falls back gracefully:

- ✅ **Server starts successfully** even with mixed data types
- ✅ **All GeoSPARQL features preserved** (inference, query rewriting, spatial functions)
- ✅ **Spatial queries work** via FalkorDB native functions (query pushdown)
- ✅ **No data loss** - all existing data remains accessible
- ✅ **Clear logging** - informative warnings for troubleshooting

For more details, see [RESTART_ISSUE_FIX.md](RESTART_ISSUE_FIX.md).

## See Also

- [OPTIMIZATIONS.md](OPTIMIZATIONS.md) - Overview of all performance optimizations
- [DEMO.md](DEMO.md) - Complete hands-on demo with curl commands
- [TRACING.md](TRACING.md) - OpenTelemetry tracing and observability
- [MAGIC_PROPERTY.md](MAGIC_PROPERTY.md) - Direct Cypher execution
- [RESTART_ISSUE_FIX.md](RESTART_ISSUE_FIX.md) - Fix for server restart issues with mixed data
