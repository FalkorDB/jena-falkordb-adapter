package samples;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Example demonstrating combined lazy inference and GeoSPARQL queries.
 * 
 * This example shows how to:
 * 1. Start a Fuseki server with the combined configuration
 * 2. Load geographic and social network data
 * 3. Query using both inference and spatial predicates
 * 
 * Prerequisites:
 * - FalkorDB running on localhost:6379
 * - Configuration file: config-falkordb-lazy-inference-with-geosparql.ttl
 * - Data file: samples/geosparql-with-inference/data-example.ttl
 */
public class GeoSPARQLInferenceExample {
    
    private static final int PORT = 3030;
    private static final String SERVICE_NAME = "falkor";
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== GeoSPARQL with Lazy Inference Example ===\n");
        
        // Path to configuration file
        String configPath = "jena-fuseki-falkordb/src/main/resources/config-falkordb-lazy-inference-with-geosparql.ttl";
        
        if (!Files.exists(Path.of(configPath))) {
            System.err.println("Configuration file not found: " + configPath);
            System.err.println("Please run from the project root directory");
            return;
        }
        
        System.out.println("Starting Fuseki server with combined inference + GeoSPARQL configuration...");
        
        // Start Fuseki server with configuration file
        FusekiServer server = FusekiServer.create()
            .port(PORT)
            .parseConfigFile(configPath)
            .build();
        
        server.start();
        System.out.println("Server started on port " + PORT);
        System.out.println("Service available at: http://localhost:" + PORT + "/" + SERVICE_NAME + "\n");
        
        try {
            // Define endpoints
            String updateEndpoint = "http://localhost:" + PORT + "/" + SERVICE_NAME + "/update";
            String queryEndpoint = "http://localhost:" + PORT + "/" + SERVICE_NAME + "/query";
            
            // Load example data
            System.out.println("Loading example data...");
            loadExampleData(updateEndpoint);
            System.out.println("Data loaded successfully\n");
            
            // Run example queries
            runExampleQueries(queryEndpoint);
            
            System.out.println("\n=== Example Complete ===");
            System.out.println("\nServer is still running. Press Ctrl+C to stop.");
            System.out.println("You can access the Fuseki UI at: http://localhost:" + PORT);
            
            // Keep server running
            Thread.currentThread().join();
            
        } finally {
            server.stop();
            System.out.println("\nServer stopped");
        }
    }
    
    /**
     * Load example data demonstrating social network with geographic locations.
     */
    private static void loadExampleData(String updateEndpoint) {
        String insertData = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo:    <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf:     <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex:     <http://example.org/> " +
            "INSERT DATA { " +
            // Social network
            "  ex:alice a social:Person ; " +
            "    ex:name 'Alice Johnson' ; " +
            "    ex:occupation 'Software Engineer' ; " +
            "    social:knows ex:bob ; " +
            "    geo:hasGeometry ex:aliceLocation . " +
            "  ex:bob a social:Person ; " +
            "    ex:name 'Bob Smith' ; " +
            "    ex:occupation 'Data Scientist' ; " +
            "    social:knows ex:carol, ex:dave ; " +
            "    geo:hasGeometry ex:bobLocation . " +
            "  ex:carol a social:Person ; " +
            "    ex:name 'Carol Williams' ; " +
            "    ex:occupation 'Designer' ; " +
            "    geo:hasGeometry ex:carolLocation . " +
            "  ex:dave a social:Person ; " +
            "    ex:name 'Dave Brown' ; " +
            "    ex:occupation 'Product Manager' ; " +
            "    social:knows ex:eve ; " +
            "    geo:hasGeometry ex:daveLocation . " +
            "  ex:eve a social:Person ; " +
            "    ex:name 'Eve Davis' ; " +
            "    ex:occupation 'Marketing Manager' ; " +
            "    geo:hasGeometry ex:eveLocation . " +
            // Geographic locations (London coordinates)
            "  ex:aliceLocation a sf:Point ; " +
            "    geo:asWKT 'POINT(-0.1278 51.5074)'^^geo:wktLiteral . " +
            "  ex:bobLocation a sf:Point ; " +
            "    geo:asWKT 'POINT(-0.1426 51.5390)'^^geo:wktLiteral . " +
            "  ex:carolLocation a sf:Point ; " +
            "    geo:asWKT 'POINT(-0.0759 51.5255)'^^geo:wktLiteral . " +
            "  ex:daveLocation a sf:Point ; " +
            "    geo:asWKT 'POINT(-0.1234 51.5309)'^^geo:wktLiteral . " +
            "  ex:eveLocation a sf:Point ; " +
            "    geo:asWKT 'POINT(-0.1030 51.5416)'^^geo:wktLiteral . " +
            // Geographic feature - Central London area
            "  ex:centralLondon a geo:Feature ; " +
            "    ex:name 'Central London' ; " +
            "    geo:hasGeometry ex:centralLondonArea . " +
            "  ex:centralLondonArea a sf:Polygon ; " +
            "    geo:asWKT 'POLYGON((-0.20 51.48, -0.20 51.52, -0.05 51.52, -0.05 51.48, -0.20 51.48))'^^geo:wktLiteral . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertData).execute();
    }
    
    /**
     * Run example queries demonstrating combined inference and spatial capabilities.
     */
    private static void runExampleQueries(String queryEndpoint) {
        System.out.println("=====================================");
        System.out.println("Running Example Queries");
        System.out.println("=====================================\n");
        
        // Example 1: Find transitive friends with locations
        example1_TransitiveFriendsWithLocations(queryEndpoint);
        
        // Example 2: Check transitive connection
        example2_CheckTransitiveConnection(queryEndpoint);
        
        // Example 3: All people with locations
        example3_AllPeopleWithLocations(queryEndpoint);
        
        // Example 4: Extended network with occupations
        example4_ExtendedNetworkWithOccupations(queryEndpoint);
    }
    
    /**
     * Example 1: Find all people Alice knows transitively with their locations.
     * Demonstrates: Inference (knows_transitively) + GeoSPARQL (locations)
     */
    private static void example1_TransitiveFriendsWithLocations(String queryEndpoint) {
        System.out.println("--- Example 1: Transitive Friends with Locations ---");
        System.out.println("Query: Find all people Alice knows transitively with their locations\n");
        
        String query = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo:    <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex:     <http://example.org/> " +
            "SELECT ?friendName ?location " +
            "WHERE { " +
            "  ex:alice social:knows_transitively ?friend . " +  // Lazy inference
            "  ?friend ex:name ?friendName ; " +
            "          geo:hasGeometry ?geom . " +
            "  ?geom geo:asWKT ?location . " +                    // GeoSPARQL
            "} ORDER BY ?friendName";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(queryEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.out(System.out, results);
        }
        System.out.println();
    }
    
    /**
     * Example 2: Check if two people are transitively connected.
     * Demonstrates: ASK query with inference
     */
    private static void example2_CheckTransitiveConnection(String queryEndpoint) {
        System.out.println("--- Example 2: Check Transitive Connection ---");
        System.out.println("Query: Is Eve in Alice's extended network?\n");
        
        String query = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX ex:     <http://example.org/> " +
            "ASK { " +
            "  ex:alice social:knows_transitively ex:eve . " +  // Uses inference
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(queryEndpoint).query(query).build()) {
            boolean result = qexec.execAsk();
            System.out.println("Result: " + result);
            System.out.println("(Expected: true - Eve is reachable via Bob -> Dave -> Eve)\n");
        }
    }
    
    /**
     * Example 3: List all people with their geographic locations.
     * Demonstrates: Pure GeoSPARQL query (baseline)
     */
    private static void example3_AllPeopleWithLocations(String queryEndpoint) {
        System.out.println("--- Example 3: All People with Locations ---");
        System.out.println("Query: List all people with their geographic locations\n");
        
        String query = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo:    <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex:     <http://example.org/> " +
            "SELECT ?personName ?location " +
            "WHERE { " +
            "  ?person a social:Person ; " +
            "          ex:name ?personName ; " +
            "          geo:hasGeometry ?geom . " +
            "  ?geom geo:asWKT ?location . " +
            "} ORDER BY ?personName";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(queryEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.out(System.out, results);
        }
        System.out.println();
    }
    
    /**
     * Example 4: Find people in extended network with their occupations.
     * Demonstrates: Complex query combining inference, attributes, and spatial data
     */
    private static void example4_ExtendedNetworkWithOccupations(String queryEndpoint) {
        System.out.println("--- Example 4: Extended Network with Occupations ---");
        System.out.println("Query: Find people Bob knows transitively with occupations and locations\n");
        
        String query = 
            "PREFIX social: <http://example.org/social#> " +
            "PREFIX geo:    <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex:     <http://example.org/> " +
            "SELECT ?friendName ?occupation ?location " +
            "WHERE { " +
            "  ex:bob social:knows_transitively ?friend . " +     // Inference
            "  ?friend ex:name ?friendName ; " +
            "          ex:occupation ?occupation ; " +
            "          geo:hasGeometry ?geom . " +
            "  ?geom geo:asWKT ?location . " +                     // Spatial
            "} ORDER BY ?friendName";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(queryEndpoint).query(query).build()) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.out(System.out, results);
        }
        System.out.println();
    }
}
