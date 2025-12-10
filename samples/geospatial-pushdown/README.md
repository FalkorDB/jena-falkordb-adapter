# Geospatial Query Pushdown Examples

This directory contains examples demonstrating geospatial query pushdown to FalkorDB's native point() and distance() functions.

## Overview

The Jena-FalkorDB Adapter translates GeoSPARQL queries to FalkorDB's native geospatial functions, enabling efficient database-side spatial computations.

## Examples

### 1. Store and Query Locations

Store locations with latitude/longitude coordinates and query them with spatial filters.

**Benefits:**
- Database-side spatial filtering
- Reduced data transfer
- Native geospatial optimization

### 2. Distance Calculations

Calculate distances between locations using FalkorDB's distance() function.

**Benefits:**
- Accurate distance calculations (meters)
- Database-side computation
- Single query execution

### 3. Bounding Box Queries

Find locations within a rectangular region defined by lat/lon ranges.

**Benefits:**
- Efficient range filtering
- Index-friendly queries
- Minimal data transfer

## Running the Examples

### Prerequisites

1. FalkorDB running on localhost:6379
2. Java 21 or higher
3. Maven 3.6 or higher

### Start FalkorDB

```bash
docker-compose -f docker-compose-tracing.yaml up -d
```

### Build and Run

```bash
# From project root
mvn clean install

# Run geospatial example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.GeospatialPushdownExample"
```

## Supported Geometry Types

This example demonstrates **all 4 supported WKT geometry types**:

| Type | Example | Use Case | Bounding Box |
|------|---------|----------|--------------|
| POINT | `POINT(-0.1278 51.5074)` | Exact locations (cities, landmarks) | Single point |
| POLYGON | `POLYGON((lon1 lat1, ...))` | Areas, regions, parks, boundaries | ✅ Calculated |
| LINESTRING | `LINESTRING(lon1 lat1, ...)` | Routes, paths, roads, rivers | ✅ Calculated |
| MULTIPOINT | `MULTIPOINT((lon1 lat1), ...)` | Collections of discrete points | ✅ Calculated |

**Bounding Box Support:**  
For complex geometries (POLYGON, LINESTRING, MULTIPOINT), the adapter automatically:
1. Calculates the complete bounding box (min/max latitude/longitude)
2. Stores all bounding box parameters (minLat, maxLat, minLon, maxLon)
3. Computes the center point as a representative location
4. Enables efficient spatial filtering and range queries

## Sample Data

### POINT Geometries
Major European cities with exact coordinates:

- **London**: 51.5074°N, 0.1278°W
- **Paris**: 48.8566°N, 2.3522°E
- **Berlin**: 52.5200°N, 13.4050°E
- **Madrid**: 40.4168°N, 3.7038°W
- **Rome**: 41.9028°N, 12.4964°E

### POLYGON Geometries
- **Hyde Park (London)**: Rectangular park area
  - WKT: `POLYGON((-0.1791 51.5074, -0.1791 51.5123, -0.1626 51.5123, -0.1626 51.5074, -0.1791 51.5074))`
  - Bounding box: 51.5074°N-51.5123°N, 0.1791°W-0.1626°W
  - Center point: 51.50985°N, 0.17085°W

### LINESTRING Geometries
- **Thames Path**: Multi-segment route along the River Thames
  - WKT: `LINESTRING(-0.1278 51.5074, -0.0759 51.5048, -0.0277 51.5033, 0.0000 51.4934)`
  - Bounding box: 51.4934°N-51.5074°N, 0.1278°W-0°E
  - Center point: 51.5004°N, 0.0639°W

### MULTIPOINT Geometries
- **European Capitals**: Collection of three capital city locations
  - WKT: `MULTIPOINT((-0.1278 51.5074), (2.3522 48.8566), (13.4050 52.5200))`
  - Cities: London, Paris, Berlin
  - Bounding box: 48.8566°N-52.5200°N, 0.1278°W-13.4050°E
  - Center point: 50.6883°N, 6.6386°E

## Data Formats

### Turtle (TTL)

```turtle
@prefix ex: <http://example.org/> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

ex:london a ex:Location ;
    rdfs:label "London" ;
    ex:latitude 51.5074 ;
    ex:longitude -0.1278 ;
    ex:country "United Kingdom" ;
    ex:population 8982000 .

ex:paris a ex:Location ;
    rdfs:label "Paris" ;
    ex:latitude 48.8566 ;
    ex:longitude 2.3522 ;
    ex:country "France" ;
    ex:population 2161000 .
```

### JSON-LD

```json
{
  "@context": {
    "ex": "http://example.org/",
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#"
  },
  "@graph": [
    {
      "@id": "ex:london",
      "@type": "ex:Location",
      "rdfs:label": "London",
      "ex:latitude": 51.5074,
      "ex:longitude": -0.1278,
      "ex:country": "United Kingdom",
      "ex:population": 8982000
    }
  ]
}
```

## SPARQL Queries

### Query 1: All Locations

```sparql
PREFIX ex: <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?label ?lat ?lon ?country WHERE {
  ?loc a ex:Location ;
       rdfs:label ?label ;
       ex:latitude ?lat ;
       ex:longitude ?lon ;
       ex:country ?country .
}
ORDER BY ?label
```

### Query 2: Bounding Box

```sparql
PREFIX ex: <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?label ?lat ?lon WHERE {
  ?loc a ex:Location ;
       rdfs:label ?label ;
       ex:latitude ?lat ;
       ex:longitude ?lon .
  FILTER(?lat >= 48.0 && ?lat <= 52.0)
  FILTER(?lon >= -1.0 && ?lon <= 3.0)
}
ORDER BY ?label
```

**Generated Cypher:**
```cypher
MATCH (loc:Resource:Location)
WHERE loc.`http://example.org/latitude` IS NOT NULL
  AND loc.`http://example.org/longitude` IS NOT NULL
  AND loc.`http://example.org/latitude` >= 48.0
  AND loc.`http://example.org/latitude` <= 52.0
  AND loc.`http://example.org/longitude` >= -1.0
  AND loc.`http://example.org/longitude` <= 3.0
RETURN loc.`http://www.w3.org/2000/01/rdf-schema#label` AS label,
       loc.`http://example.org/latitude` AS lat,
       loc.`http://example.org/longitude` AS lon
ORDER BY label
```

### Query 3: Cities by Country

```sparql
PREFIX ex: <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?label ?country WHERE {
  ?loc a ex:Location ;
       rdfs:label ?label ;
       ex:country ?country .
  FILTER(?country = "France")
}
```

## Performance Metrics

| Query Type | Standard Evaluation | With Pushdown | Improvement |
|-----------|---------------------|---------------|-------------|
| All locations (5 cities) | 5 queries | 1 query | 5x fewer calls |
| Bounding box (100 cities) | 100 queries + filtering | 1 query | 100x fewer calls |
| Distance calculations | Client-side computation | Database-side | Native optimization |

## Additional Resources

- [GEOSPATIAL_PUSHDOWN.md](../../GEOSPATIAL_PUSHDOWN.md) - Complete documentation
- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md) - All optimization techniques
- [GeoSPARQLToCypherTranslatorTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/GeoSPARQLToCypherTranslatorTest.java) - Unit tests
- [FalkorDBGeospatialPushdownTest.java](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/query/FalkorDBGeospatialPushdownTest.java) - Integration tests

## See Also

- [FalkorDB Geospatial Documentation](https://docs.falkordb.com/cypher/functions.html#point-functions)
- [GeoSPARQL Standard](https://www.ogc.org/standard/geosparql/)
- [Apache Jena GeoSPARQL](https://jena.apache.org/documentation/geosparql/)
