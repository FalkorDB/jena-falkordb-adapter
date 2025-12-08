package com.falkordb.jena.query;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.core.Var;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for geospatial query pushdown to FalkorDB.
 * 
 * Tests the translation and execution of GeoSPARQL queries using
 * FalkorDB's native point() and distance() functions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FalkorDBGeospatialPushdownTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;
    private static final String GRAPH_NAME = "geospatial_test";
    
    private Model model;
    private FalkorDBGraph graph;

    @BeforeAll
    public void setUp() {
        model = FalkorDBModelFactory.createModel(HOST, PORT, GRAPH_NAME);
        graph = (FalkorDBGraph) model.getGraph();
    }

    @BeforeEach
    public void clearGraph() {
        graph.clear();
    }

    @AfterAll
    public void tearDown() {
        if (graph != null) {
            graph.clear();
        }
        if (model != null) {
            model.close();
        }
    }

    @Test
    @DisplayName("Store and query locations with point geometries")
    public void testStoreLocationsWithPoints() {
        // Create locations with point geometries using FalkorDB's native point function
        // We'll store coordinates as properties and test querying them
        
        Resource london = model.createResource("http://example.org/london");
        london.addProperty(model.createProperty("http://example.org/name"), "London");
        london.addProperty(model.createProperty("http://example.org/latitude"), 
            model.createTypedLiteral(51.5074));
        london.addProperty(model.createProperty("http://example.org/longitude"), 
            model.createTypedLiteral(-0.1278));
        
        Resource paris = model.createResource("http://example.org/paris");
        paris.addProperty(model.createProperty("http://example.org/name"), "Paris");
        paris.addProperty(model.createProperty("http://example.org/latitude"), 
            model.createTypedLiteral(48.8566));
        paris.addProperty(model.createProperty("http://example.org/longitude"), 
            model.createTypedLiteral(2.3522));
        
        // Query to verify data was stored
        String sparql = """
            PREFIX ex: <http://example.org/>
            SELECT ?city ?name ?lat ?lon WHERE {
                ?city ex:name ?name .
                ?city ex:latitude ?lat .
                ?city ex:longitude ?lon .
            }
            ORDER BY ?name
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find locations");
            
            QuerySolution sol1 = results.next();
            assertEquals("London", sol1.getLiteral("name").getString());
            assertEquals(51.5074, sol1.getLiteral("lat").getDouble(), 0.001);
            
            assertTrue(results.hasNext(), "Should find second location");
            QuerySolution sol2 = results.next();
            assertEquals("Paris", sol2.getLiteral("name").getString());
            assertEquals(48.8566, sol2.getLiteral("lat").getDouble(), 0.001);
        }
    }

    @Test
    @DisplayName("Test WKT parsing utilities")
    public void testWKTParsingUtilities() {
        // Test the WKT parsing functions directly
        String londonWKT = "POINT(-0.1278 51.5074)";
        
        Double lat = GeoSPARQLToCypherTranslator.extractLatitude(londonWKT);
        Double lon = GeoSPARQLToCypherTranslator.extractLongitude(londonWKT);
        
        assertNotNull(lat, "Latitude should be extracted");
        assertNotNull(lon, "Longitude should be extracted");
        assertEquals(51.5074, lat, 0.0001, "Latitude should match London");
        assertEquals(-0.1278, lon, 0.0001, "Longitude should match London");
    }

    @Test
    @DisplayName("Query locations within bounding box")
    public void testLocationsWithinBoundingBox() {
        // Add multiple locations
        Resource loc1 = model.createResource("http://example.org/loc1");
        loc1.addProperty(model.createProperty("http://example.org/name"), "Inside");
        loc1.addProperty(model.createProperty("http://example.org/latitude"), 
            model.createTypedLiteral(51.0));
        loc1.addProperty(model.createProperty("http://example.org/longitude"), 
            model.createTypedLiteral(0.0));
        
        Resource loc2 = model.createResource("http://example.org/loc2");
        loc2.addProperty(model.createProperty("http://example.org/name"), "Outside");
        loc2.addProperty(model.createProperty("http://example.org/latitude"), 
            model.createTypedLiteral(60.0));
        loc2.addProperty(model.createProperty("http://example.org/longitude"), 
            model.createTypedLiteral(10.0));
        
        // Query for locations within latitude range
        String sparql = """
            PREFIX ex: <http://example.org/>
            SELECT ?name ?lat WHERE {
                ?loc ex:name ?name .
                ?loc ex:latitude ?lat .
                FILTER(?lat >= 50 && ?lat <= 52)
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find location inside range");
            QuerySolution sol = results.next();
            assertEquals("Inside", sol.getLiteral("name").getString());
            
            assertFalse(results.hasNext(), "Should not find location outside range");
        }
    }

    @Test
    @DisplayName("Store GeoSPARQL compatible data with WKT")
    public void testStoreGeoSPARQLData() {
        // Store location data in a GeoSPARQL-compatible format
        // Using standard geo vocabulary
        
        Resource london = model.createResource("http://example.org/locations/london");
        Resource londonGeom = model.createResource("http://example.org/geometries/london");
        
        london.addProperty(
            model.createProperty("http://www.w3.org/2000/01/rdf-schema#label"), 
            "London");
        london.addProperty(
            model.createProperty("http://www.opengis.net/ont/geosparql#hasGeometry"), 
            londonGeom);
        
        londonGeom.addProperty(
            model.createProperty("http://www.opengis.net/ont/geosparql#asWKT"),
            model.createTypedLiteral("POINT(-0.1278 51.5074)", 
                "http://www.opengis.net/ont/geosparql#wktLiteral"));
        
        // Query for the location
        String sparql = """
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX geo: <http://www.opengis.net/ont/geosparql#>
            SELECT ?label ?wkt WHERE {
                ?location rdfs:label ?label .
                ?location geo:hasGeometry ?geom .
                ?geom geo:asWKT ?wkt .
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext(), "Should find location with geometry");
            QuerySolution sol = results.next();
            assertEquals("London", sol.getLiteral("label").getString());
            assertTrue(sol.getLiteral("wkt").getString().contains("POINT"));
        }
    }

    @Test
    @DisplayName("Test coordinate extraction from multiple formats")
    public void testCoordinateExtraction() {
        // Test different WKT format variations
        String[] testCases = {
            "POINT(-0.1278 51.5074)",
            "POINT(  -0.1278   51.5074  )",
            "point(-0.1278 51.5074)",
            "Point(-0.1278 51.5074)"
        };
        
        for (String wkt : testCases) {
            Double lat = GeoSPARQLToCypherTranslator.extractLatitude(wkt);
            Double lon = GeoSPARQLToCypherTranslator.extractLongitude(wkt);
            
            assertNotNull(lat, "Latitude should be extracted from: " + wkt);
            assertNotNull(lon, "Longitude should be extracted from: " + wkt);
            assertEquals(51.5074, lat, 0.0001);
            assertEquals(-0.1278, lon, 0.0001);
        }
    }

    @Test
    @DisplayName("Test polygon WKT parsing")
    public void testPolygonParsing() {
        String polygonWKT = "POLYGON((-0.15 51.50, -0.15 51.52, -0.10 51.52, -0.10 51.50, -0.15 51.50))";
        
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(polygonWKT, "poly", params);
        
        assertNotNull(result, "Should parse polygon");
        assertTrue(result.contains("point({"), "Should contain point expression");
        
        // Should extract first coordinate as approximation
        Object lat = params.get("poly_lat");
        Object lon = params.get("poly_lon");
        
        assertNotNull(lat, "Should extract latitude from polygon");
        assertNotNull(lon, "Should extract longitude from polygon");
    }

    @Test
    @DisplayName("Store location with point geometry in native format")
    public void testNativePointStorage() {
        // This test demonstrates how we would store points if FalkorDB
        // supported direct Cypher point() in property values
        // For now, we store coordinates as separate properties
        
        Resource location = model.createResource("http://example.org/station");
        location.addProperty(model.createProperty("http://example.org/name"), "Central Station");
        location.addProperty(model.createProperty("http://example.org/lat"), 
            model.createTypedLiteral(51.508));
        location.addProperty(model.createProperty("http://example.org/lon"), 
            model.createTypedLiteral(-0.125));
        
        String sparql = """
            PREFIX ex: <http://example.org/>
            SELECT ?name ?lat ?lon WHERE {
                ?loc ex:name ?name .
                ?loc ex:lat ?lat .
                ?loc ex:lon ?lon .
            }
            """;
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            assertTrue(results.hasNext());
            QuerySolution sol = results.next();
            assertEquals("Central Station", sol.getLiteral("name").getString());
            assertEquals(51.508, sol.getLiteral("lat").getDouble(), 0.001);
            assertEquals(-0.125, sol.getLiteral("lon").getDouble(), 0.001);
        }
    }
}
