package com.falkordb.jena.query;

import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionRegistry;
import org.apache.jena.sparql.sse.SSE;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GeoSPARQLToCypherTranslator.
 * 
 * Tests the translation of WKT geometries to FalkorDB Cypher point() expressions
 * and GeoSPARQL function translation.
 */
public class GeoSPARQLToCypherTranslatorTest {

    @Test
    @DisplayName("Parse WKT POINT to Cypher point() expression")
    public void testParsePointWKT() {
        String wkt = "POINT(-0.1278 51.5074)";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "geo", params);
        
        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("point({"), "Should contain point({ expression");
        assertTrue(result.contains("latitude:"), "Should contain latitude parameter");
        assertTrue(result.contains("longitude:"), "Should contain longitude parameter");
        assertEquals(51.5074, params.get("geo_lat"), "Latitude should be extracted");
        assertEquals(-0.1278, params.get("geo_lon"), "Longitude should be extracted");
    }

    @Test
    @DisplayName("Parse WKT POINT with positive coordinates")
    public void testParsePointPositiveCoordinates() {
        String wkt = "POINT(2.3522 48.8566)";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "p", params);
        
        assertNotNull(result);
        assertEquals(48.8566, params.get("p_lat"));
        assertEquals(2.3522, params.get("p_lon"));
    }

    @Test
    @DisplayName("Extract latitude from WKT POINT")
    public void testExtractLatitude() {
        String wkt = "POINT(-0.1278 51.5074)";
        
        Double lat = GeoSPARQLToCypherTranslator.extractLatitude(wkt);
        
        assertNotNull(lat, "Latitude should be extracted");
        assertEquals(51.5074, lat, 0.0001, "Latitude should match");
    }

    @Test
    @DisplayName("Extract longitude from WKT POINT")
    public void testExtractLongitude() {
        String wkt = "POINT(-0.1278 51.5074)";
        
        Double lon = GeoSPARQLToCypherTranslator.extractLongitude(wkt);
        
        assertNotNull(lon, "Longitude should be extracted");
        assertEquals(-0.1278, lon, 0.0001, "Longitude should match");
    }

    @Test
    @DisplayName("Parse WKT POLYGON and extract bounding box center")
    public void testParsePolygonWKT() {
        String wkt = "POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "poly", params);
        
        assertNotNull(result, "Result should not be null for polygon");
        assertTrue(result.contains("point({"), "Should contain point({ expression");
        // Center point of bounding box: lat (0+10)/2 = 5.0, lon (0+10)/2 = 5.0
        assertEquals(5.0, params.get("poly_lat"), "Should extract bounding box center latitude");
        assertEquals(5.0, params.get("poly_lon"), "Should extract bounding box center longitude");
        // Check bounding box parameters
        assertEquals(0.0, params.get("poly_minLat"), "Should store min latitude");
        assertEquals(10.0, params.get("poly_maxLat"), "Should store max latitude");
        assertEquals(0.0, params.get("poly_minLon"), "Should store min longitude");
        assertEquals(10.0, params.get("poly_maxLon"), "Should store max longitude");
    }

    @Test
    @DisplayName("Parse WKT POLYGON with complex coordinates and compute correct bounding box")
    public void testParseComplexPolygon() {
        String wkt = "POLYGON((-0.15 51.50, -0.15 51.52, -0.10 51.52, -0.10 51.50, -0.15 51.50))";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "rect", params);
        
        assertNotNull(result);
        // Center point: lat (51.50+51.52)/2 = 51.51, lon (-0.15+-0.10)/2 = -0.125
        assertEquals(51.51, (Double) params.get("rect_lat"), 0.001);
        assertEquals(-0.125, (Double) params.get("rect_lon"), 0.001);
        // Check bounding box parameters
        assertEquals(51.50, params.get("rect_minLat"));
        assertEquals(51.52, params.get("rect_maxLat"));
        assertEquals(-0.15, params.get("rect_minLon"));
        assertEquals(-0.10, params.get("rect_maxLon"));
    }

    @Test
    @DisplayName("Handle invalid WKT gracefully")
    public void testInvalidWKT() {
        String wkt = "INVALID WKT STRING";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "invalid", params);
        
        assertNull(result, "Should return null for invalid WKT");
        assertTrue(params.isEmpty(), "No parameters should be added for invalid WKT");
    }

    @Test
    @DisplayName("Extract latitude from invalid WKT returns null")
    public void testExtractLatitudeInvalid() {
        String wkt = "INVALID WKT";
        
        Double lat = GeoSPARQLToCypherTranslator.extractLatitude(wkt);
        
        assertNull(lat, "Should return null for invalid WKT");
    }

    @Test
    @DisplayName("Extract longitude from invalid WKT returns null")
    public void testExtractLongitudeInvalid() {
        String wkt = "INVALID WKT";
        
        Double lon = GeoSPARQLToCypherTranslator.extractLongitude(wkt);
        
        assertNull(lon, "Should return null for invalid WKT");
    }

    @Test
    @DisplayName("Parse WKT POINT with case insensitivity")
    public void testCaseInsensitivity() {
        String wkt1 = "POINT(-0.1278 51.5074)";
        String wkt2 = "point(-0.1278 51.5074)";
        String wkt3 = "Point(-0.1278 51.5074)";
        
        Map<String, Object> params1 = new HashMap<>();
        Map<String, Object> params2 = new HashMap<>();
        Map<String, Object> params3 = new HashMap<>();
        
        String result1 = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt1, "p", params1);
        String result2 = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt2, "p", params2);
        String result3 = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt3, "p", params3);
        
        assertNotNull(result1);
        assertNotNull(result2);
        assertNotNull(result3);
        assertEquals(params1.get("p_lat"), params2.get("p_lat"));
        assertEquals(params1.get("p_lat"), params3.get("p_lat"));
    }

    @Test
    @DisplayName("Parse WKT POINT with extra whitespace")
    public void testExtraWhitespace() {
        String wkt = "POINT(  -0.1278   51.5074  )";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "p", params);
        
        assertNotNull(result);
        assertEquals(51.5074, params.get("p_lat"));
        assertEquals(-0.1278, params.get("p_lon"));
    }

    @Test
    @DisplayName("isGeoSPARQLFunction returns true for geof:distance")
    public void testIsGeoSPARQLFunctionDistance() {
        // Create a simple expression that looks like a GeoSPARQL function
        // We'll use SSE to parse a function call
        Expr expr = SSE.parseExpr("(<http://www.opengis.net/def/function/geosparql/distance> ?a ?b)");
        
        boolean result = GeoSPARQLToCypherTranslator.isGeoSPARQLFunction(expr);
        
        assertTrue(result, "Should recognize geof:distance as GeoSPARQL function");
    }

    @Test
    @DisplayName("isGeoSPARQLFunction returns false for non-function expression")
    public void testIsGeoSPARQLFunctionNonFunction() {
        Expr expr = new ExprVar("x");
        
        boolean result = GeoSPARQLToCypherTranslator.isGeoSPARQLFunction(expr);
        
        assertFalse(result, "Should return false for non-function expression");
    }

    @Test
    @DisplayName("isGeoSPARQLFunction returns false for non-GeoSPARQL function")
    public void testIsGeoSPARQLFunctionNonGeoSPARQL() {
        // A regular SPARQL function, not GeoSPARQL
        Expr expr = SSE.parseExpr("(str ?x)");
        
        boolean result = GeoSPARQLToCypherTranslator.isGeoSPARQLFunction(expr);
        
        assertFalse(result, "Should return false for non-GeoSPARQL function");
    }

    @Test
    @DisplayName("translateGeoFunction handles distance function")
    public void testTranslateGeoFunctionDistance() {
        // Create a geof:distance expression with literal WKT arguments
        Expr expr = SSE.parseExpr("(<http://www.opengis.net/def/function/geosparql/distance> 'POINT(0 0)' 'POINT(1 1)')");
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.translateGeoFunction(expr, "geo", params);
        
        // Should return a distance expression
        assertNotNull(result, "Should translate distance function");
        assertTrue(result.contains("distance("), "Should contain distance function call");
    }

    @Test
    @DisplayName("translateGeoFunction handles sfWithin function")
    public void testTranslateGeoFunctionSfWithin() {
        Expr expr = SSE.parseExpr("(<http://www.opengis.net/def/function/geosparql/sfWithin> 'POINT(0 0)' 'POINT(1 1)')");
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.translateGeoFunction(expr, "geo", params);
        
        assertNotNull(result, "Should translate sfWithin function");
        assertTrue(result.contains("distance("), "Should use distance for sfWithin");
    }

    @Test
    @DisplayName("translateGeoFunction handles sfContains function")
    public void testTranslateGeoFunctionSfContains() {
        Expr expr = SSE.parseExpr("(<http://www.opengis.net/def/function/geosparql/sfContains> 'POINT(0 0)' 'POINT(1 1)')");
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.translateGeoFunction(expr, "geo", params);
        
        assertNotNull(result, "Should translate sfContains function");
        assertTrue(result.contains("distance("), "Should use distance for sfContains");
    }

    @Test
    @DisplayName("translateGeoFunction handles sfIntersects function")
    public void testTranslateGeoFunctionSfIntersects() {
        Expr expr = SSE.parseExpr("(<http://www.opengis.net/def/function/geosparql/sfIntersects> 'POINT(0 0)' 'POINT(1 1)')");
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.translateGeoFunction(expr, "geo", params);
        
        assertNotNull(result, "Should translate sfIntersects function");
        assertTrue(result.contains("distance("), "Should use distance for sfIntersects");
    }

    @Test
    @DisplayName("translateGeoFunction returns null for unsupported function")
    public void testTranslateGeoFunctionUnsupported() {
        Expr expr = SSE.parseExpr("(<http://www.opengis.net/def/function/geosparql/unsupportedFunction> ?a ?b)");
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.translateGeoFunction(expr, "geo", params);
        
        assertNull(result, "Should return null for unsupported GeoSPARQL function");
    }

    @Test
    @DisplayName("translateGeoFunction returns null for non-ExprFunction")
    public void testTranslateGeoFunctionNonExprFunction() {
        Expr expr = new ExprVar("x");
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.translateGeoFunction(expr, "geo", params);
        
        assertNull(result, "Should return null for non-ExprFunction");
    }

    @Test
    @DisplayName("translateGeoFunction returns null for non-GeoSPARQL function")
    public void testTranslateGeoFunctionNonGeoSPARQL() {
        Expr expr = SSE.parseExpr("(str ?x)");
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.translateGeoFunction(expr, "geo", params);
        
        assertNull(result, "Should return null for non-GeoSPARQL function");
    }

    @Test
    @DisplayName("translateGeoFunction handles function with insufficient arguments")
    public void testTranslateGeoFunctionInsufficientArgs() {
        // distance function with only one argument
        Expr expr = SSE.parseExpr("(<http://www.opengis.net/def/function/geosparql/distance> 'POINT(0 0)')");
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.translateGeoFunction(expr, "geo", params);
        
        assertNull(result, "Should return null for function with insufficient arguments");
    }

    @Test
    @DisplayName("translateGeoFunction populates parameters correctly")
    public void testTranslateGeoFunctionPopulatesParameters() {
        Expr expr = SSE.parseExpr("(<http://www.opengis.net/def/function/geosparql/distance> 'POINT(-0.1278 51.5074)' 'POINT(2.3522 48.8566)')");
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.translateGeoFunction(expr, "test", params);
        
        assertNotNull(result, "Should successfully translate");
        assertFalse(params.isEmpty(), "Should populate parameters");
        // Check that latitude and longitude parameters were added
        assertTrue(params.containsKey("test_lat") || params.keySet().stream().anyMatch(k -> k.endsWith("_lat")), 
            "Should contain latitude parameter");
        assertTrue(params.containsKey("test_lon") || params.keySet().stream().anyMatch(k -> k.endsWith("_lon")), 
            "Should contain longitude parameter");
    }

    // ========================================
    // Tests for LINESTRING geometry type
    // ========================================

    @Test
    @DisplayName("Parse WKT LINESTRING and compute bounding box center")
    public void testParseLinestringWKT() {
        String wkt = "LINESTRING(0 0, 5 5, 10 0)";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "line", params);
        
        assertNotNull(result, "Result should not be null for linestring");
        assertTrue(result.contains("point({"), "Should contain point({ expression");
        // Bounding box: lat [0, 5], lon [0, 10]
        // Center: lat = 2.5, lon = 5.0
        assertEquals(2.5, params.get("line_lat"), "Should extract bounding box center latitude");
        assertEquals(5.0, params.get("line_lon"), "Should extract bounding box center longitude");
        // Check bounding box parameters
        assertEquals(0.0, params.get("line_minLat"));
        assertEquals(5.0, params.get("line_maxLat"));
        assertEquals(0.0, params.get("line_minLon"));
        assertEquals(10.0, params.get("line_maxLon"));
    }

    @Test
    @DisplayName("Parse WKT LINESTRING with case insensitivity")
    public void testLinestringCaseInsensitivity() {
        String wkt1 = "LINESTRING(0 0, 10 10)";
        String wkt2 = "linestring(0 0, 10 10)";
        String wkt3 = "LineString(0 0, 10 10)";
        
        Map<String, Object> params1 = new HashMap<>();
        Map<String, Object> params2 = new HashMap<>();
        Map<String, Object> params3 = new HashMap<>();
        
        String result1 = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt1, "l", params1);
        String result2 = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt2, "l", params2);
        String result3 = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt3, "l", params3);
        
        assertNotNull(result1);
        assertNotNull(result2);
        assertNotNull(result3);
        assertEquals(params1.get("l_lat"), params2.get("l_lat"));
        assertEquals(params1.get("l_lat"), params3.get("l_lat"));
    }

    @Test
    @DisplayName("Parse complex LINESTRING with negative coordinates")
    public void testComplexLinestring() {
        String wkt = "LINESTRING(-0.1278 51.5074, 2.3522 48.8566, 13.4050 52.5200)";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "route", params);
        
        assertNotNull(result);
        // Bounding box: lat [48.8566, 52.5200], lon [-0.1278, 13.4050]
        // Center: lat = 50.6883, lon = 6.6386
        assertEquals(50.6883, (Double) params.get("route_lat"), 0.001);
        assertEquals(6.6386, (Double) params.get("route_lon"), 0.001);
        assertEquals(48.8566, params.get("route_minLat"));
        assertEquals(52.5200, params.get("route_maxLat"));
        assertEquals(-0.1278, params.get("route_minLon"));
        assertEquals(13.4050, params.get("route_maxLon"));
    }

    @Test
    @DisplayName("Extract latitude from LINESTRING")
    public void testExtractLatitudeFromLinestring() {
        String wkt = "LINESTRING(0 0, 5 5, 10 0)";
        
        Double lat = GeoSPARQLToCypherTranslator.extractLatitude(wkt);
        
        assertNotNull(lat, "Latitude should be extracted from linestring");
        assertEquals(2.5, lat, 0.001, "Should extract center latitude of bounding box");
    }

    @Test
    @DisplayName("Extract longitude from LINESTRING")
    public void testExtractLongitudeFromLinestring() {
        String wkt = "LINESTRING(0 0, 5 5, 10 0)";
        
        Double lon = GeoSPARQLToCypherTranslator.extractLongitude(wkt);
        
        assertNotNull(lon, "Longitude should be extracted from linestring");
        assertEquals(5.0, lon, 0.001, "Should extract center longitude of bounding box");
    }

    // ========================================
    // Tests for MULTIPOINT geometry type
    // ========================================

    @Test
    @DisplayName("Parse WKT MULTIPOINT and compute bounding box center")
    public void testParseMultipointWKT() {
        String wkt = "MULTIPOINT((0 0), (5 5), (10 10))";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "multi", params);
        
        assertNotNull(result, "Result should not be null for multipoint");
        assertTrue(result.contains("point({"), "Should contain point({ expression");
        // Bounding box: lat [0, 10], lon [0, 10]
        // Center: lat = 5.0, lon = 5.0
        assertEquals(5.0, params.get("multi_lat"), "Should extract bounding box center latitude");
        assertEquals(5.0, params.get("multi_lon"), "Should extract bounding box center longitude");
        // Check bounding box parameters
        assertEquals(0.0, params.get("multi_minLat"));
        assertEquals(10.0, params.get("multi_maxLat"));
        assertEquals(0.0, params.get("multi_minLon"));
        assertEquals(10.0, params.get("multi_maxLon"));
    }

    @Test
    @DisplayName("Parse WKT MULTIPOINT without parentheses around points")
    public void testParseMultipointWithoutParens() {
        String wkt = "MULTIPOINT(0 0, 5 5, 10 10)";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "mp", params);
        
        assertNotNull(result, "Result should not be null for multipoint without parens");
        assertEquals(5.0, params.get("mp_lat"));
        assertEquals(5.0, params.get("mp_lon"));
    }

    @Test
    @DisplayName("Parse WKT MULTIPOINT with case insensitivity")
    public void testMultipointCaseInsensitivity() {
        String wkt1 = "MULTIPOINT((0 0), (10 10))";
        String wkt2 = "multipoint((0 0), (10 10))";
        String wkt3 = "MultiPoint((0 0), (10 10))";
        
        Map<String, Object> params1 = new HashMap<>();
        Map<String, Object> params2 = new HashMap<>();
        Map<String, Object> params3 = new HashMap<>();
        
        String result1 = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt1, "m", params1);
        String result2 = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt2, "m", params2);
        String result3 = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt3, "m", params3);
        
        assertNotNull(result1);
        assertNotNull(result2);
        assertNotNull(result3);
        assertEquals(params1.get("m_lat"), params2.get("m_lat"));
        assertEquals(params1.get("m_lat"), params3.get("m_lat"));
    }

    @Test
    @DisplayName("Parse complex MULTIPOINT with negative coordinates")
    public void testComplexMultipoint() {
        String wkt = "MULTIPOINT((-0.1278 51.5074), (2.3522 48.8566), (13.4050 52.5200))";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "cities", params);
        
        assertNotNull(result);
        // Bounding box: lat [48.8566, 52.5200], lon [-0.1278, 13.4050]
        assertEquals(50.6883, (Double) params.get("cities_lat"), 0.001);
        assertEquals(6.6386, (Double) params.get("cities_lon"), 0.001);
        assertEquals(48.8566, params.get("cities_minLat"));
        assertEquals(52.5200, params.get("cities_maxLat"));
        assertEquals(-0.1278, params.get("cities_minLon"));
        assertEquals(13.4050, params.get("cities_maxLon"));
    }

    @Test
    @DisplayName("Extract latitude from MULTIPOINT")
    public void testExtractLatitudeFromMultipoint() {
        String wkt = "MULTIPOINT((0 0), (10 10))";
        
        Double lat = GeoSPARQLToCypherTranslator.extractLatitude(wkt);
        
        assertNotNull(lat, "Latitude should be extracted from multipoint");
        assertEquals(5.0, lat, 0.001, "Should extract center latitude of bounding box");
    }

    @Test
    @DisplayName("Extract longitude from MULTIPOINT")
    public void testExtractLongitudeFromMultipoint() {
        String wkt = "MULTIPOINT((0 0), (10 10))";
        
        Double lon = GeoSPARQLToCypherTranslator.extractLongitude(wkt);
        
        assertNotNull(lon, "Longitude should be extracted from multipoint");
        assertEquals(5.0, lon, 0.001, "Should extract center longitude of bounding box");
    }

    // ========================================
    // Tests for extractBoundingBox method
    // ========================================

    @Test
    @DisplayName("Extract bounding box from POINT")
    public void testExtractBoundingBoxFromPoint() {
        String wkt = "POINT(-0.1278 51.5074)";
        
        double[] bbox = GeoSPARQLToCypherTranslator.extractBoundingBox(wkt);
        
        assertNotNull(bbox, "Bounding box should be extracted");
        assertEquals(4, bbox.length, "Bounding box should have 4 values");
        // For a point, min and max are the same
        assertEquals(51.5074, bbox[0], 0.001); // minLat
        assertEquals(51.5074, bbox[1], 0.001); // maxLat
        assertEquals(-0.1278, bbox[2], 0.001); // minLon
        assertEquals(-0.1278, bbox[3], 0.001); // maxLon
    }

    @Test
    @DisplayName("Extract bounding box from POLYGON")
    public void testExtractBoundingBoxFromPolygon() {
        String wkt = "POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))";
        
        double[] bbox = GeoSPARQLToCypherTranslator.extractBoundingBox(wkt);
        
        assertNotNull(bbox);
        assertEquals(0.0, bbox[0], 0.001); // minLat
        assertEquals(10.0, bbox[1], 0.001); // maxLat
        assertEquals(0.0, bbox[2], 0.001); // minLon
        assertEquals(10.0, bbox[3], 0.001); // maxLon
    }

    @Test
    @DisplayName("Extract bounding box from LINESTRING")
    public void testExtractBoundingBoxFromLinestring() {
        String wkt = "LINESTRING(0 0, 5 5, 10 0)";
        
        double[] bbox = GeoSPARQLToCypherTranslator.extractBoundingBox(wkt);
        
        assertNotNull(bbox);
        assertEquals(0.0, bbox[0], 0.001); // minLat
        assertEquals(5.0, bbox[1], 0.001); // maxLat
        assertEquals(0.0, bbox[2], 0.001); // minLon
        assertEquals(10.0, bbox[3], 0.001); // maxLon
    }

    @Test
    @DisplayName("Extract bounding box from MULTIPOINT")
    public void testExtractBoundingBoxFromMultipoint() {
        String wkt = "MULTIPOINT((0 0), (10 10))";
        
        double[] bbox = GeoSPARQLToCypherTranslator.extractBoundingBox(wkt);
        
        assertNotNull(bbox);
        assertEquals(0.0, bbox[0], 0.001); // minLat
        assertEquals(10.0, bbox[1], 0.001); // maxLat
        assertEquals(0.0, bbox[2], 0.001); // minLon
        assertEquals(10.0, bbox[3], 0.001); // maxLon
    }

    @Test
    @DisplayName("Extract bounding box from invalid WKT returns null")
    public void testExtractBoundingBoxFromInvalidWKT() {
        String wkt = "INVALID WKT";
        
        double[] bbox = GeoSPARQLToCypherTranslator.extractBoundingBox(wkt);
        
        assertNull(bbox, "Should return null for invalid WKT");
    }

    // ========================================
    // Tests for updated extract methods with all geometry types
    // ========================================

    @Test
    @DisplayName("Extract latitude works with all geometry types")
    public void testExtractLatitudeAllTypes() {
        assertEquals(51.5074, GeoSPARQLToCypherTranslator.extractLatitude("POINT(-0.1278 51.5074)"), 0.001);
        assertEquals(5.0, GeoSPARQLToCypherTranslator.extractLatitude("POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))"), 0.001);
        assertEquals(2.5, GeoSPARQLToCypherTranslator.extractLatitude("LINESTRING(0 0, 5 5, 10 0)"), 0.001);
        assertEquals(5.0, GeoSPARQLToCypherTranslator.extractLatitude("MULTIPOINT((0 0), (10 10))"), 0.001);
    }

    @Test
    @DisplayName("Extract longitude works with all geometry types")
    public void testExtractLongitudeAllTypes() {
        assertEquals(-0.1278, GeoSPARQLToCypherTranslator.extractLongitude("POINT(-0.1278 51.5074)"), 0.001);
        assertEquals(5.0, GeoSPARQLToCypherTranslator.extractLongitude("POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))"), 0.001);
        assertEquals(5.0, GeoSPARQLToCypherTranslator.extractLongitude("LINESTRING(0 0, 5 5, 10 0)"), 0.001);
        assertEquals(5.0, GeoSPARQLToCypherTranslator.extractLongitude("MULTIPOINT((0 0), (10 10))"), 0.001);
    }
}
