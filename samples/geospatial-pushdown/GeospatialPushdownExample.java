package com.falkordb.samples;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 * Demonstrates geospatial query pushdown to FalkorDB.
 * 
 * This example shows how GeoSPARQL queries are automatically translated
 * to FalkorDB's native point() and distance() functions for efficient
 * database-side spatial computation.
 * 
 * Run with: mvn exec:java -Dexec.mainClass="com.falkordb.samples.GeospatialPushdownExample"
 */
public class GeospatialPushdownExample {

    private static final String GRAPH_NAME = "geospatial_demo";
    
    public static void main(String[] args) {
        System.out.println("=== Geospatial Query Pushdown Example ===\n");
        
        Model model = FalkorDBModelFactory.createModel(GRAPH_NAME);
        
        try {
            // Example 1: Store Locations with Coordinates
            storeLocationsWithCoordinates(model);
            
            // Example 2: Query All Locations
            queryAllLocations(model);
            
            // Example 3: Query Locations in Bounding Box
            queryLocationsInBoundingBox(model);
            
            // Example 4: Query by Country
            queryLocationsByCountry(model);
            
            // Example 5: Store and Parse POLYGON geometry
            demonstratePolygonGeometry(model);
            
            // Example 6: Store and Parse LINESTRING geometry
            demonstrateLinestringGeometry(model);
            
            // Example 7: Store and Parse MULTIPOINT geometry
            demonstrateMultipointGeometry(model);
            
            System.out.println("\n=== Summary ===");
            System.out.println("✓ Stored 5 European cities with coordinates");
            System.out.println("✓ Demonstrated spatial filtering with FILTER expressions");
            System.out.println("✓ All queries use database-side computation");
            System.out.println("✓ Geospatial pushdown enabled automatically");
            System.out.println("✓ Demonstrated all 4 geometry types (POINT, POLYGON, LINESTRING, MULTIPOINT)");
            System.out.println("✓ Bounding box calculation for complex geometries");
            
        } finally {
            model.close();
        }
    }
    
    /**
     * Example 1: Store locations with latitude/longitude coordinates.
     * 
     * This demonstrates storing spatial data as properties that can be
     * queried using GeoSPARQL-compatible SPARQL queries.
     */
    private static void storeLocationsWithCoordinates(Model model) {
        System.out.println("Example 1: Storing Locations with Coordinates\n");
        
        // Define properties
        Property latProp = model.createProperty("http://example.org/latitude");
        Property lonProp = model.createProperty("http://example.org/longitude");
        Property nameProp = model.createProperty("http://example.org/name");
        Property countryProp = model.createProperty("http://example.org/country");
        Property populationProp = model.createProperty("http://example.org/population");
        Resource locationType = model.createResource("http://example.org/Location");
        
        // Store major European cities
        createLocation(model, "london", "London", 51.5074, -0.1278, 
            "United Kingdom", 8982000, locationType, 
            nameProp, latProp, lonProp, countryProp, populationProp);
        
        createLocation(model, "paris", "Paris", 48.8566, 2.3522, 
            "France", 2161000, locationType,
            nameProp, latProp, lonProp, countryProp, populationProp);
        
        createLocation(model, "berlin", "Berlin", 52.5200, 13.4050, 
            "Germany", 3645000, locationType,
            nameProp, latProp, lonProp, countryProp, populationProp);
        
        createLocation(model, "madrid", "Madrid", 40.4168, -3.7038, 
            "Spain", 3223000, locationType,
            nameProp, latProp, lonProp, countryProp, populationProp);
        
        createLocation(model, "rome", "Rome", 41.9028, 12.4964, 
            "Italy", 2873000, locationType,
            nameProp, latProp, lonProp, countryProp, populationProp);
        
        System.out.println("✓ Stored 5 European cities with coordinates\n");
    }
    
    private static void createLocation(Model model, String id, String name, 
                                      double lat, double lon, String country, 
                                      int population, Resource type,
                                      Property nameProp, Property latProp, 
                                      Property lonProp, Property countryProp,
                                      Property populationProp) {
        Resource location = model.createResource("http://example.org/" + id);
        location.addProperty(RDF.type, type);
        location.addProperty(RDFS.label, name);
        location.addProperty(nameProp, name);
        location.addProperty(latProp, model.createTypedLiteral(lat));
        location.addProperty(lonProp, model.createTypedLiteral(lon));
        location.addProperty(countryProp, country);
        location.addProperty(populationProp, model.createTypedLiteral(population));
    }
    
    /**
     * Example 2: Query all locations.
     * 
     * Simple query to retrieve all stored locations with their coordinates.
     */
    private static void queryAllLocations(Model model) {
        System.out.println("Example 2: Query All Locations\n");
        
        String sparql = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            
            SELECT ?label ?lat ?lon ?country ?population WHERE {
              ?loc a ex:Location ;
                   rdfs:label ?label ;
                   ex:latitude ?lat ;
                   ex:longitude ?lon ;
                   ex:country ?country ;
                   ex:population ?population .
            }
            ORDER BY ?label
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            System.out.println("Results:");
            System.out.println("--------");
            while (results.hasNext()) {
                QuerySolution sol = results.next();
                System.out.printf("%-10s: (%.4f, %.6f) - %s, pop. %,d%n",
                    sol.getLiteral("label").getString(),
                    sol.getLiteral("lat").getDouble(),
                    sol.getLiteral("lon").getDouble(),
                    sol.getLiteral("country").getString(),
                    sol.getLiteral("population").getInt());
            }
        }
        System.out.println();
    }
    
    /**
     * Example 3: Query locations within a bounding box.
     * 
     * Demonstrates spatial filtering using FILTER expressions.
     * The adapter automatically pushes down these filters to FalkorDB.
     */
    private static void queryLocationsInBoundingBox(Model model) {
        System.out.println("Example 3: Query Locations in Bounding Box\n");
        
        String sparql = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            
            SELECT ?label ?lat ?lon ?country WHERE {
              ?loc a ex:Location ;
                   rdfs:label ?label ;
                   ex:latitude ?lat ;
                   ex:longitude ?lon ;
                   ex:country ?country .
              FILTER(?lat >= 48.0 && ?lat <= 53.0)
              FILTER(?lon >= -1.0 && ?lon <= 14.0)
            }
            ORDER BY ?label
            """;
        
        System.out.println("SPARQL Query (Bounding Box: lat 48-53°N, lon -1-14°E):");
        System.out.println(sparql);
        
        System.out.println("Generated Cypher (automatically):");
        System.out.println("MATCH (loc:Resource:Location)");
        System.out.println("WHERE loc.`http://example.org/latitude` >= 48.0");
        System.out.println("  AND loc.`http://example.org/latitude` <= 53.0");
        System.out.println("  AND loc.`http://example.org/longitude` >= -1.0");
        System.out.println("  AND loc.`http://example.org/longitude` <= 14.0");
        System.out.println("RETURN ...\n");
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            System.out.println("Results (Cities in Central/Western Europe):");
            System.out.println("--------");
            while (results.hasNext()) {
                QuerySolution sol = results.next();
                System.out.printf("%-10s: (%.4f, %.6f) - %s%n",
                    sol.getLiteral("label").getString(),
                    sol.getLiteral("lat").getDouble(),
                    sol.getLiteral("lon").getDouble(),
                    sol.getLiteral("country").getString());
            }
        }
        System.out.println();
    }
    
    /**
     * Example 4: Query locations by country.
     * 
     * Simple filter on country property.
     */
    private static void queryLocationsByCountry(Model model) {
        System.out.println("Example 4: Query Locations by Country\n");
        
        String sparql = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            
            SELECT ?label ?lat ?lon ?population WHERE {
              ?loc a ex:Location ;
                   rdfs:label ?label ;
                   ex:latitude ?lat ;
                   ex:longitude ?lon ;
                   ex:country "France" ;
                   ex:population ?population .
            }
            """;
        
        System.out.println("SPARQL Query (Cities in France):");
        System.out.println(sparql);
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            System.out.println("Results:");
            System.out.println("--------");
            while (results.hasNext()) {
                QuerySolution sol = results.next();
                System.out.printf("%-10s: (%.4f, %.6f), pop. %,d%n",
                    sol.getLiteral("label").getString(),
                    sol.getLiteral("lat").getDouble(),
                    sol.getLiteral("lon").getDouble(),
                    sol.getLiteral("population").getInt());
            }
        }
        System.out.println();
    }
    
    /**
     * Example 5: Demonstrate POLYGON geometry support.
     * 
     * Shows how POLYGON geometries are parsed with complete bounding box calculation.
     * The center point of the bounding box is used as a representative location.
     */
    private static void demonstratePolygonGeometry(Model model) {
        System.out.println("Example 5: POLYGON Geometry Support\n");
        
        // Create a polygon representing Hyde Park in London
        String polygonWKT = "POLYGON((-0.1791 51.5074, -0.1791 51.5123, -0.1626 51.5123, -0.1626 51.5074, -0.1791 51.5074))";
        
        Resource hydePark = model.createResource("http://example.org/hyde_park");
        Resource hydeParkGeom = model.createResource("http://example.org/geometries/hyde_park");
        
        hydePark.addProperty(RDFS.label, "Hyde Park");
        hydePark.addProperty(
            model.createProperty("http://www.opengis.net/ont/geosparql#hasGeometry"),
            hydeParkGeom);
        
        hydeParkGeom.addProperty(
            model.createProperty("http://www.opengis.net/ont/geosparql#asWKT"),
            model.createTypedLiteral(polygonWKT,
                "http://www.opengis.net/ont/geosparql#wktLiteral"));
        
        System.out.println("✓ Stored POLYGON geometry for Hyde Park");
        System.out.println("  WKT: " + polygonWKT);
        
        // Parse and extract bounding box
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        String pointExpr = com.falkordb.jena.query.GeoSPARQLToCypherTranslator
            .parseWKTToPoint(polygonWKT, "poly", params);
        
        System.out.println("\nParsing Result:");
        System.out.println("  Center Point: (" + params.get("poly_lat") + ", " + params.get("poly_lon") + ")");
        System.out.println("  Bounding Box:");
        System.out.println("    Min Latitude:  " + params.get("poly_minLat"));
        System.out.println("    Max Latitude:  " + params.get("poly_maxLat"));
        System.out.println("    Min Longitude: " + params.get("poly_minLon"));
        System.out.println("    Max Longitude: " + params.get("poly_maxLon"));
        System.out.println("  Cypher Expression: " + pointExpr);
        System.out.println();
    }
    
    /**
     * Example 6: Demonstrate LINESTRING geometry support.
     * 
     * Shows how LINESTRING geometries (routes, paths) are parsed with
     * complete bounding box calculation.
     */
    private static void demonstrateLinestringGeometry(Model model) {
        System.out.println("Example 6: LINESTRING Geometry Support\n");
        
        // Create a linestring representing the Thames Path
        String linestringWKT = "LINESTRING(-0.1278 51.5074, -0.0759 51.5048, -0.0277 51.5033, 0.0000 51.4934)";
        
        Resource thamesPath = model.createResource("http://example.org/thames_path");
        Resource thamesPathGeom = model.createResource("http://example.org/geometries/thames_path");
        
        thamesPath.addProperty(RDFS.label, "Thames Path");
        thamesPath.addProperty(
            model.createProperty("http://www.opengis.net/ont/geosparql#hasGeometry"),
            thamesPathGeom);
        
        thamesPathGeom.addProperty(
            model.createProperty("http://www.opengis.net/ont/geosparql#asWKT"),
            model.createTypedLiteral(linestringWKT,
                "http://www.opengis.net/ont/geosparql#wktLiteral"));
        
        System.out.println("✓ Stored LINESTRING geometry for Thames Path");
        System.out.println("  WKT: " + linestringWKT);
        
        // Parse and extract bounding box
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        String pointExpr = com.falkordb.jena.query.GeoSPARQLToCypherTranslator
            .parseWKTToPoint(linestringWKT, "line", params);
        
        System.out.println("\nParsing Result:");
        System.out.println("  Center Point: (" + params.get("line_lat") + ", " + params.get("line_lon") + ")");
        System.out.println("  Bounding Box:");
        System.out.println("    Min Latitude:  " + params.get("line_minLat"));
        System.out.println("    Max Latitude:  " + params.get("line_maxLat"));
        System.out.println("    Min Longitude: " + params.get("line_minLon"));
        System.out.println("    Max Longitude: " + params.get("line_maxLon"));
        System.out.println("  Cypher Expression: " + pointExpr);
        System.out.println();
    }
    
    /**
     * Example 7: Demonstrate MULTIPOINT geometry support.
     * 
     * Shows how MULTIPOINT geometries (multiple discrete points) are parsed
     * with complete bounding box calculation.
     */
    private static void demonstrateMultipointGeometry(Model model) {
        System.out.println("Example 7: MULTIPOINT Geometry Support\n");
        
        // Create a multipoint representing European capitals
        String multipointWKT = "MULTIPOINT((-0.1278 51.5074), (2.3522 48.8566), (13.4050 52.5200))";
        
        Resource capitals = model.createResource("http://example.org/european_capitals");
        Resource capitalsGeom = model.createResource("http://example.org/geometries/european_capitals");
        
        capitals.addProperty(RDFS.label, "European Capitals");
        capitals.addProperty(
            model.createProperty("http://www.opengis.net/ont/geosparql#hasGeometry"),
            capitalsGeom);
        
        capitalsGeom.addProperty(
            model.createProperty("http://www.opengis.net/ont/geosparql#asWKT"),
            model.createTypedLiteral(multipointWKT,
                "http://www.opengis.net/ont/geosparql#wktLiteral"));
        
        System.out.println("✓ Stored MULTIPOINT geometry for European Capitals");
        System.out.println("  WKT: " + multipointWKT);
        System.out.println("  Points: London, Paris, Berlin");
        
        // Parse and extract bounding box
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        String pointExpr = com.falkordb.jena.query.GeoSPARQLToCypherTranslator
            .parseWKTToPoint(multipointWKT, "multi", params);
        
        System.out.println("\nParsing Result:");
        System.out.println("  Center Point: (" + params.get("multi_lat") + ", " + params.get("multi_lon") + ")");
        System.out.println("  Bounding Box:");
        System.out.println("    Min Latitude:  " + params.get("multi_minLat"));
        System.out.println("    Max Latitude:  " + params.get("multi_maxLat"));
        System.out.println("    Min Longitude: " + params.get("multi_minLon"));
        System.out.println("    Max Longitude: " + params.get("multi_maxLon"));
        System.out.println("  Cypher Expression: " + pointExpr);
        System.out.println();
    }
}
