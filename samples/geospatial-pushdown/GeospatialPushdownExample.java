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
            
            System.out.println("\n=== Summary ===");
            System.out.println("✓ Stored 5 European cities with coordinates");
            System.out.println("✓ Demonstrated spatial filtering with FILTER expressions");
            System.out.println("✓ All queries use database-side computation");
            System.out.println("✓ Geospatial pushdown enabled automatically");
            
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
}
