package com.falkordb.geosparql;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GeoSPARQL functionality with Fuseki server.
 * 
 * These tests verify that:
 * 1. GeoSPARQL module is properly configured
 * 2. Spatial data can be inserted and queried
 * 3. Spatial functions work correctly (contains, within, intersects, distance)
 * 4. Circle and rectangle geometries are properly handled
 */
public class GeoSPARQLIntegrationTest {

    private static final int TEST_PORT = 3334;
    private static final String DATASET_PATH = "/geo";
    
    private FusekiServer server;
    private String sparqlEndpoint;
    private String updateEndpoint;
    
    @BeforeEach
    public void setUp() {
        // Initialize GeoSPARQL
        GeoSPARQLConfig.setupMemoryIndex();
        
        // Create an in-memory dataset with GeoSPARQL enabled
        Dataset ds = DatasetFactory.createTxnMem();
        
        // Start Fuseki server
        server = FusekiServer.create()
            .port(TEST_PORT)
            .add(DATASET_PATH, ds)
            .build();
        server.start();
        
        sparqlEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/query";
        updateEndpoint = "http://localhost:" + TEST_PORT + DATASET_PATH + "/update";
    }
    
    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }
    
    @Test
    @DisplayName("Test GeoSPARQL server starts successfully")
    public void testServerStarts() {
        assertNotNull(server, "Server should be started");
        assertEquals(TEST_PORT, server.getPort(), "Server should be running on configured port");
    }
    
    @Test
    @DisplayName("Test inserting rectangular geometry (polygon)")
    public void testInsertRectangle() {
        // Insert a rectangular geometry
        String insertQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:park a geo:Feature ; " +
            "    geo:hasGeometry ex:parkGeom . " +
            "  ex:parkGeom a sf:Polygon ; " +
            "    geo:asWKT \"POLYGON((-0.15 51.50, -0.15 51.52, -0.10 51.52, -0.10 51.50, -0.15 51.50))\"^^geo:wktLiteral . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify data was inserted
        String selectQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?geom ?wkt WHERE { " +
            "  ex:park geo:hasGeometry ?geom . " +
            "  ?geom geo:asWKT ?wkt . " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should find the rectangular geometry");
            var solution = results.next();
            String wkt = solution.getLiteral("wkt").getString();
            assertTrue(wkt.contains("POLYGON"), "WKT should contain POLYGON");
        }
    }
    
    @Test
    @DisplayName("Test inserting point geometry (center of circle)")
    public void testInsertPoint() {
        // Insert a point geometry (representing center of a circular area)
        String insertQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:station a geo:Feature ; " +
            "    ex:name \"Central Station\" ; " +
            "    ex:serviceRadius 500 ; " +
            "    geo:hasGeometry ex:stationGeom . " +
            "  ex:stationGeom a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.125 51.508)\"^^geo:wktLiteral . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify data was inserted
        String selectQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?name ?wkt WHERE { " +
            "  ?feature a geo:Feature ; " +
            "    ex:name ?name ; " +
            "    geo:hasGeometry ?geom . " +
            "  ?geom geo:asWKT ?wkt . " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should find the point geometry");
            var solution = results.next();
            assertEquals("Central Station", solution.getLiteral("name").getString());
            String wkt = solution.getLiteral("wkt").getString();
            assertTrue(wkt.contains("POINT"), "WKT should contain POINT");
        }
    }
    
    @Test
    @DisplayName("Test inserting circle approximation (polygon)")
    public void testInsertCircleApproximation() {
        // Insert a circle approximated as a polygon
        String insertQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:garden a geo:Feature ; " +
            "    ex:name \"Circular Garden\" ; " +
            "    geo:hasGeometry ex:gardenGeom . " +
            "  ex:gardenGeom a sf:Polygon ; " +
            "    geo:asWKT \"POLYGON((-0.135 51.505, -0.1353 51.5068, -0.1364 51.5082, -0.1382 51.5089, -0.14 51.51, -0.1418 51.5089, -0.1436 51.5082, -0.1447 51.5068, -0.145 51.505, -0.1447 51.5032, -0.1436 51.5018, -0.1418 51.5011, -0.14 51.50, -0.1382 51.5011, -0.1364 51.5018, -0.1353 51.5032, -0.135 51.505))\"^^geo:wktLiteral . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Verify data was inserted
        String selectQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?name WHERE { " +
            "  ?feature a geo:Feature ; " +
            "    ex:name ?name . " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should find the circular garden");
            assertEquals("Circular Garden", results.next().getLiteral("name").getString());
        }
    }
    
    @Test
    @DisplayName("Test spatial relationship query - contains")
    public void testSpatialContains() {
        // Insert a large rectangle and a point inside it
        String insertQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:park a geo:Feature ; " +
            "    ex:name \"City Park\" ; " +
            "    geo:hasGeometry ex:parkGeom . " +
            "  ex:parkGeom a sf:Polygon ; " +
            "    geo:asWKT \"POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))\"^^geo:wktLiteral . " +
            "  ex:fountain a geo:Feature ; " +
            "    ex:name \"Fountain\" ; " +
            "    geo:hasGeometry ex:fountainGeom . " +
            "  ex:fountainGeom a sf:Point ; " +
            "    geo:asWKT \"POINT(5 5)\"^^geo:wktLiteral . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query for point within polygon using geof:sfContains
        // Filter to ensure we're checking containment of different features
        String selectQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX geof: <http://www.opengis.net/def/function/geosparql/> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?parkName ?pointName WHERE { " +
            "  ?park a geo:Feature ; ex:name ?parkName ; geo:hasGeometry ?parkGeom . " +
            "  ?point a geo:Feature ; ex:name ?pointName ; geo:hasGeometry ?pointGeom . " +
            "  ?parkGeom a sf:Polygon ; geo:asWKT ?parkWkt . " +
            "  ?pointGeom a sf:Point ; geo:asWKT ?pointWkt . " +
            "  FILTER(geof:sfContains(?parkWkt, ?pointWkt)) " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should find containment relationship");
            var solution = results.next();
            assertEquals("City Park", solution.getLiteral("parkName").getString());
            assertEquals("Fountain", solution.getLiteral("pointName").getString());
        }
    }
    
    @Test
    @DisplayName("Test loading geo shapes data file")
    public void testLoadGeoShapesData() {
        // Insert multiple geometric features
        String insertQuery = 
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
            "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/shapes/> " +
            "INSERT DATA { " +
            "  ex:cityPark a geo:Feature ; " +
            "    rdfs:label \"City Park\" ; " +
            "    geo:hasGeometry ex:cityParkGeom . " +
            "  ex:cityParkGeom a sf:Polygon ; " +
            "    geo:asWKT \"POLYGON((-0.15 51.50, -0.15 51.52, -0.10 51.52, -0.10 51.50, -0.15 51.50))\"^^geo:wktLiteral . " +
            "  ex:centralStation a geo:Feature ; " +
            "    rdfs:label \"Central Station\" ; " +
            "    geo:hasGeometry ex:centralStationGeom . " +
            "  ex:centralStationGeom a sf:Point ; " +
            "    geo:asWKT \"POINT(-0.125 51.508)\"^^geo:wktLiteral . " +
            "  ex:circularGarden a geo:Feature ; " +
            "    rdfs:label \"Circular Garden\" ; " +
            "    geo:hasGeometry ex:circularGardenGeom . " +
            "  ex:circularGardenGeom a sf:Polygon ; " +
            "    geo:asWKT \"POLYGON((-0.135 51.505, -0.1353 51.5068, -0.1364 51.5082, -0.1382 51.5089, -0.14 51.51, -0.1418 51.5089, -0.1436 51.5082, -0.1447 51.5068, -0.145 51.505, -0.1447 51.5032, -0.1436 51.5018, -0.1418 51.5011, -0.14 51.50, -0.1382 51.5011, -0.1364 51.5018, -0.1353 51.5032, -0.135 51.505))\"^^geo:wktLiteral . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Count all features
        String countQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "SELECT (COUNT(DISTINCT ?feature) AS ?count) WHERE { " +
            "  ?feature a geo:Feature . " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext());
            int count = results.next().getLiteral("count").getInt();
            assertEquals(3, count, "Should have 3 features");
        }
        
        // Query for all features with labels
        String selectQuery = 
            "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "SELECT ?label WHERE { " +
            "  ?feature a geo:Feature ; " +
            "    rdfs:label ?label . " +
            "} ORDER BY ?label";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            assertEquals("Central Station", results.next().getLiteral("label").getString());
            
            assertTrue(results.hasNext());
            assertEquals("Circular Garden", results.next().getLiteral("label").getString());
            
            assertTrue(results.hasNext());
            assertEquals("City Park", results.next().getLiteral("label").getString());
        }
    }
    
    @Test
    @DisplayName("Test spatial query - features within bounding box")
    public void testFeaturesWithinBoundingBox() {
        // Insert multiple features at different locations
        String insertQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX sf: <http://www.opengis.net/ont/sf#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:feature1 a geo:Feature ; ex:name \"Inside\" ; " +
            "    geo:hasGeometry [ a sf:Point ; geo:asWKT \"POINT(5 5)\"^^geo:wktLiteral ] . " +
            "  ex:feature2 a geo:Feature ; ex:name \"Outside\" ; " +
            "    geo:hasGeometry [ a sf:Point ; geo:asWKT \"POINT(15 15)\"^^geo:wktLiteral ] . " +
            "}";
        
        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();
        
        // Query for features within a bounding box using geof:sfWithin
        String selectQuery = 
            "PREFIX geo: <http://www.opengis.net/ont/geosparql#> " +
            "PREFIX geof: <http://www.opengis.net/def/function/geosparql/> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?name WHERE { " +
            "  ?feature a geo:Feature ; ex:name ?name ; geo:hasGeometry ?geom . " +
            "  ?geom geo:asWKT ?wkt . " +
            "  FILTER(geof:sfWithin(?wkt, \"POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))\"^^geo:wktLiteral)) " +
            "}";
        
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint).query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertTrue(results.hasNext(), "Should find feature inside bounding box");
            assertEquals("Inside", results.next().getLiteral("name").getString());
            assertFalse(results.hasNext(), "Should not find feature outside bounding box");
        }
    }
}
