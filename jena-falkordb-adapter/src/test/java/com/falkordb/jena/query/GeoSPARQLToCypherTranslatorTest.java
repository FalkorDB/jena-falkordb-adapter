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
    @DisplayName("Parse WKT POLYGON and extract first point")
    public void testParsePolygonWKT() {
        String wkt = "POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "poly", params);
        
        assertNotNull(result, "Result should not be null for polygon");
        assertTrue(result.contains("point({"), "Should contain point({ expression");
        assertEquals(0.0, params.get("poly_lat"), "Should extract first point latitude");
        assertEquals(0.0, params.get("poly_lon"), "Should extract first point longitude");
    }

    @Test
    @DisplayName("Parse WKT POLYGON with complex coordinates")
    public void testParseComplexPolygon() {
        String wkt = "POLYGON((-0.15 51.50, -0.15 51.52, -0.10 51.52, -0.10 51.50, -0.15 51.50))";
        Map<String, Object> params = new HashMap<>();
        
        String result = GeoSPARQLToCypherTranslator.parseWKTToPoint(wkt, "rect", params);
        
        assertNotNull(result);
        assertEquals(51.50, params.get("rect_lat"));
        assertEquals(-0.15, params.get("rect_lon"));
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
}
