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
docker run -p 6379:6379 -d falkordb/falkordb:latest
```

### Build and Run

```bash
# From project root
mvn clean install

# Run geospatial example
mvn exec:java -Dexec.mainClass="com.falkordb.samples.GeospatialPushdownExample"
```

## Sample Data

Sample data includes major cities with their coordinates:

- London: 51.5074°N, 0.1278°W
- Paris: 48.8566°N, 2.3522°E
- Berlin: 52.5200°N, 13.4050°E
- Madrid: 40.4168°N, 3.7038°W
- Rome: 41.9028°N, 12.4964°E

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
