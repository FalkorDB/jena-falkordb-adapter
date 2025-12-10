package com.falkordb.jena.query;

import com.falkordb.jena.tracing.TracingUtil;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprFunction;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates GeoSPARQL function calls to FalkorDB Cypher geospatial expressions.
 *
 * <p>This translator converts GeoSPARQL spatial functions (like {@code geof:distance},
 * {@code geof:sfWithin}, {@code geof:sfContains}) to their equivalent FalkorDB Cypher
 * expressions using the {@code point()} function and {@code distance()} function.</p>
 *
 * <h2>Supported GeoSPARQL Functions:</h2>
 * <ul>
 *   <li>{@code geof:distance} - Translates to FalkorDB {@code distance()} function</li>
 *   <li>{@code geof:sfWithin} - Translates to distance-based range checks</li>
 *   <li>{@code geof:sfContains} - Translates to spatial containment logic</li>
 *   <li>{@code geof:sfIntersects} - Translates to spatial intersection logic</li>
 * </ul>
 *
 * <h2>Supported Geometry Types:</h2>
 * <ul>
 *   <li>POINT - Exact coordinates translated to {@code point({latitude: lat, longitude: lon})}</li>
 *   <li>POLYGON - Bounding box center point used as approximation</li>
 *   <li>LINESTRING - Bounding box center point used as approximation</li>
 *   <li>MULTIPOINT - Bounding box center point used as approximation</li>
 * </ul>
 * 
 * <p><b>Note:</b> For complex geometries (POLYGON, LINESTRING, MULTIPOINT), the adapter calculates
 * the complete bounding box (min/max latitude/longitude) and uses the center point as a representative
 * location. The bounding box parameters are stored and can be used for range queries.</p>
 *
 * <h2>Example Translations:</h2>
 * <pre>{@code
 * // GeoSPARQL distance function:
 * // geof:distance(?pointA, ?pointB)
 * // Translated to Cypher:
 * // distance(pointA, pointB)
 *
 * // GeoSPARQL within function with distance:
 * // geof:sfWithin(?point, 10000)  # within 10km
 * // Translated to Cypher:
 * // distance(point, centerPoint) < 10000
 * }</pre>
 *
 * @see <a href="https://docs.falkordb.com/cypher/functions.html#point-functions">FalkorDB Point Functions</a>
 * @see <a href="https://www.ogc.org/standard/geosparql/">GeoSPARQL Standard</a>
 */
public final class GeoSPARQLToCypherTranslator {

    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        GeoSPARQLToCypherTranslator.class);

    /** Instrumentation scope name. */
    public static final String SCOPE_GEOSPATIAL =
        "com.falkordb.jena.query.GeoSPARQLToCypherTranslator";

    /** Tracer for geospatial operations. */
    private static final Tracer TRACER = TracingUtil.getTracer(SCOPE_GEOSPATIAL);

    /** GeoSPARQL function namespace. */
    private static final String GEOF_NS = "http://www.opengis.net/def/function/geosparql/";

    /** GeoSPARQL geometry namespace. */
    private static final String GEO_NS = "http://www.opengis.net/ont/geosparql#";

    /** Attribute key for geospatial function type. */
    private static final AttributeKey<String> ATTR_GEO_FUNCTION =
        AttributeKey.stringKey("falkordb.geospatial.function");

    /** Attribute key for geometry type. */
    private static final AttributeKey<String> ATTR_GEOMETRY_TYPE =
        AttributeKey.stringKey("falkordb.geospatial.geometry_type");

    /** Pattern for extracting POINT coordinates from WKT. */
    private static final Pattern POINT_PATTERN =
        Pattern.compile("POINT\\s*\\(\\s*([+-]?\\d+\\.?\\d*)\\s+([+-]?\\d+\\.?\\d*)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /** Pattern for extracting POLYGON bounding box from WKT. */
    private static final Pattern POLYGON_PATTERN =
        Pattern.compile("POLYGON\\s*\\(\\s*\\((.+?)\\)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /** Pattern for extracting LINESTRING coordinates from WKT. */
    private static final Pattern LINESTRING_PATTERN =
        Pattern.compile("LINESTRING\\s*\\((.+?)\\)",
            Pattern.CASE_INSENSITIVE);

    /** Pattern for extracting MULTIPOINT coordinates from WKT. */
    private static final Pattern MULTIPOINT_PATTERN =
        Pattern.compile("MULTIPOINT\\s*\\((.+?)\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Default distance threshold for spatial operations (in degrees).
     * 
     * <p>This is a simplified approximation used for spatial operations like
     * sfWithin, sfContains, and sfIntersects. A more accurate implementation
     * would use proper geometric algorithms.</p>
     * 
     * <p>Note: 0.001 degrees ≈ 111 meters at the equator</p>
     */
    private static final double SPATIAL_THRESHOLD_DEGREES = 0.001;

    /**
     * Private constructor to prevent instantiation.
     */
    private GeoSPARQLToCypherTranslator() {
        throw new AssertionError("No instances");
    }

    /**
     * Checks if the given expression is a GeoSPARQL function.
     *
     * @param expr the expression to check
     * @return true if the expression is a GeoSPARQL function
     */
    public static boolean isGeoSPARQLFunction(Expr expr) {
        if (!(expr instanceof ExprFunction)) {
            return false;
        }

        ExprFunction func = (ExprFunction) expr;
        String funcIRI = func.getFunctionIRI();

        return funcIRI != null && funcIRI.startsWith(GEOF_NS);
    }

    /**
     * Translates a GeoSPARQL function expression to Cypher.
     *
     * @param expr the GeoSPARQL function expression
     * @param varPrefix prefix for variable names
     * @param params parameter map to populate
     * @return the Cypher expression, or null if translation fails
     */
    public static String translateGeoFunction(Expr expr, String varPrefix,
                                             java.util.Map<String, Object> params) {
        if (!(expr instanceof ExprFunction)) {
            return null;
        }

        ExprFunction func = (ExprFunction) expr;
        String funcIRI = func.getFunctionIRI();

        if (funcIRI == null || !funcIRI.startsWith(GEOF_NS)) {
            return null;
        }

        String funcName = funcIRI.substring(GEOF_NS.length());

        Span span = TRACER.spanBuilder("GeoSPARQLToCypherTranslator.translateGeoFunction")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_GEO_FUNCTION, funcName)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            String cypher = switch (funcName) {
                case "distance" -> translateDistance(func, varPrefix, params);
                case "sfWithin" -> translateWithin(func, varPrefix, params);
                case "sfContains" -> translateContains(func, varPrefix, params);
                case "sfIntersects" -> translateIntersects(func, varPrefix, params);
                default -> {
                    LOGGER.debug("Unsupported GeoSPARQL function: {}", funcName);
                    yield null;
                }
            };

            if (cypher != null) {
                span.setStatus(StatusCode.OK);
                span.setAttribute("falkordb.cypher.expression", cypher);
            } else {
                span.setStatus(StatusCode.ERROR, "Translation failed");
            }

            return cypher;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            LOGGER.error("Error translating GeoSPARQL function: {}", funcName, e);
            return null;
        } finally {
            span.end();
        }
    }

    /**
     * Translates geof:distance function to Cypher distance() function.
     *
     * @param func the distance function
     * @param varPrefix variable prefix
     * @param params parameter map
     * @return Cypher expression
     */
    private static String translateDistance(ExprFunction func, String varPrefix,
                                           java.util.Map<String, Object> params) {
        if (func.getArgs().size() < 2) {
            return null;
        }

        String arg1 = translateGeometryArg(func.getArgs().get(0), varPrefix, params);
        String arg2 = translateGeometryArg(func.getArgs().get(1), varPrefix, params);

        if (arg1 == null || arg2 == null) {
            return null;
        }

        return "distance(" + arg1 + ", " + arg2 + ")";
    }

    /**
     * Translates geof:sfWithin function to Cypher distance check.
     * 
     * <p><b>Limitation:</b> This is a simplified implementation using distance-based
     * approximation. A full implementation would require proper point-in-polygon
     * algorithms.</p>
     *
     * @param func the within function
     * @param varPrefix variable prefix
     * @param params parameter map
     * @return Cypher expression
     */
    private static String translateWithin(ExprFunction func, String varPrefix,
                                         java.util.Map<String, Object> params) {
        if (func.getArgs().size() < 2) {
            return null;
        }

        String point = translateGeometryArg(func.getArgs().get(0), varPrefix, params);
        String region = translateGeometryArg(func.getArgs().get(1), varPrefix, params);

        if (point == null || region == null) {
            return null;
        }

        // For point-in-polygon checks, we use bounding box approximation
        // This is a simplified implementation
        return "distance(" + point + ", " + region + ") < " + SPATIAL_THRESHOLD_DEGREES;
    }

    /**
     * Translates geof:sfContains function to Cypher containment check.
     * 
     * <p><b>Limitation:</b> This is a simplified implementation using distance-based
     * approximation. A full implementation would require proper geometric containment
     * algorithms.</p>
     *
     * @param func the contains function
     * @param varPrefix variable prefix
     * @param params parameter map
     * @return Cypher expression
     */
    private static String translateContains(ExprFunction func, String varPrefix,
                                           java.util.Map<String, Object> params) {
        if (func.getArgs().size() < 2) {
            return null;
        }

        String container = translateGeometryArg(func.getArgs().get(0), varPrefix, params);
        String contained = translateGeometryArg(func.getArgs().get(1), varPrefix, params);

        if (container == null || contained == null) {
            return null;
        }

        // For polygon-contains-point checks - simplified approximation
        return "distance(" + container + ", " + contained + ") < " + SPATIAL_THRESHOLD_DEGREES;
    }

    /**
     * Translates geof:sfIntersects function to Cypher intersection check.
     * 
     * <p><b>Limitation:</b> This is a simplified implementation using distance-based
     * approximation. A full implementation would require proper geometric intersection
     * algorithms.</p>
     *
     * @param func the intersects function
     * @param varPrefix variable prefix
     * @param params parameter map
     * @return Cypher expression
     */
    private static String translateIntersects(ExprFunction func, String varPrefix,
                                             java.util.Map<String, Object> params) {
        if (func.getArgs().size() < 2) {
            return null;
        }

        String geom1 = translateGeometryArg(func.getArgs().get(0), varPrefix, params);
        String geom2 = translateGeometryArg(func.getArgs().get(1), varPrefix, params);

        if (geom1 == null || geom2 == null) {
            return null;
        }

        // For geometry intersection checks - simplified approximation
        return "distance(" + geom1 + ", " + geom2 + ") < " + SPATIAL_THRESHOLD_DEGREES;
    }

    /**
     * Translates a geometry argument (variable or literal) to Cypher.
     *
     * @param arg the geometry argument
     * @param varPrefix variable prefix
     * @param params parameter map
     * @return Cypher expression for the geometry
     */
    private static String translateGeometryArg(Expr arg, String varPrefix,
                                              java.util.Map<String, Object> params) {
        if (arg instanceof ExprVar) {
            // Variable reference - assume it's a node property containing a point
            String varName = ((ExprVar) arg).getVarName();
            return varName;
        } else if (arg.isConstant()) {
            // Literal WKT geometry
            NodeValue nv = arg.getConstant();
            if (nv.isString()) {
                String wkt = nv.getString();
                return parseWKTToPoint(wkt, varPrefix, params);
            }
        }

        return null;
    }

    /**
     * Parses a WKT string and returns a Cypher point() expression.
     * 
     * <p>For complex geometries (POLYGON, LINESTRING, MULTIPOINT), this method
     * calculates the center point of the bounding box as an approximation.</p>
     *
     * @param wkt the WKT string
     * @param varPrefix variable prefix for parameter names
     * @param params parameter map to populate
     * @return Cypher point() expression
     */
    public static String parseWKTToPoint(String wkt, String varPrefix,
                                         java.util.Map<String, Object> params) {
        Span span = TRACER.spanBuilder("GeoSPARQLToCypherTranslator.parseWKTToPoint")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Try to parse as POINT
            Matcher pointMatcher = POINT_PATTERN.matcher(wkt);
            if (pointMatcher.find()) {
                double lon = Double.parseDouble(pointMatcher.group(1));
                double lat = Double.parseDouble(pointMatcher.group(2));

                span.setAttribute(ATTR_GEOMETRY_TYPE, "POINT");
                return createPointExpression(lat, lon, varPrefix, params, span);
            }

            // Try to parse as POLYGON and extract bounding box center
            Matcher polygonMatcher = POLYGON_PATTERN.matcher(wkt);
            if (polygonMatcher.find()) {
                String coords = polygonMatcher.group(1);
                BoundingBox bbox = calculateBoundingBox(coords);
                
                if (bbox != null) {
                    span.setAttribute(ATTR_GEOMETRY_TYPE, "POLYGON");
                    double centerLat = (bbox.minLat + bbox.maxLat) / 2.0;
                    double centerLon = (bbox.minLon + bbox.maxLon) / 2.0;
                    
                    // Store bounding box parameters for potential range queries
                    params.put(varPrefix + "_minLat", bbox.minLat);
                    params.put(varPrefix + "_maxLat", bbox.maxLat);
                    params.put(varPrefix + "_minLon", bbox.minLon);
                    params.put(varPrefix + "_maxLon", bbox.maxLon);
                    
                    return createPointExpression(centerLat, centerLon, varPrefix, params, span);
                }
            }

            // Try to parse as LINESTRING and extract bounding box center
            Matcher linestringMatcher = LINESTRING_PATTERN.matcher(wkt);
            if (linestringMatcher.find()) {
                String coords = linestringMatcher.group(1);
                BoundingBox bbox = calculateBoundingBox(coords);
                
                if (bbox != null) {
                    span.setAttribute(ATTR_GEOMETRY_TYPE, "LINESTRING");
                    double centerLat = (bbox.minLat + bbox.maxLat) / 2.0;
                    double centerLon = (bbox.minLon + bbox.maxLon) / 2.0;
                    
                    // Store bounding box parameters
                    params.put(varPrefix + "_minLat", bbox.minLat);
                    params.put(varPrefix + "_maxLat", bbox.maxLat);
                    params.put(varPrefix + "_minLon", bbox.minLon);
                    params.put(varPrefix + "_maxLon", bbox.maxLon);
                    
                    return createPointExpression(centerLat, centerLon, varPrefix, params, span);
                }
            }

            // Try to parse as MULTIPOINT and extract bounding box center
            Matcher multipointMatcher = MULTIPOINT_PATTERN.matcher(wkt);
            if (multipointMatcher.find()) {
                String coords = multipointMatcher.group(1);
                // MULTIPOINT format can be: (lon1 lat1), (lon2 lat2) or lon1 lat1, lon2 lat2
                // Remove parentheses for uniform parsing
                coords = coords.replaceAll("[()]", "");
                BoundingBox bbox = calculateBoundingBox(coords);
                
                if (bbox != null) {
                    span.setAttribute(ATTR_GEOMETRY_TYPE, "MULTIPOINT");
                    double centerLat = (bbox.minLat + bbox.maxLat) / 2.0;
                    double centerLon = (bbox.minLon + bbox.maxLon) / 2.0;
                    
                    // Store bounding box parameters
                    params.put(varPrefix + "_minLat", bbox.minLat);
                    params.put(varPrefix + "_maxLat", bbox.maxLat);
                    params.put(varPrefix + "_minLon", bbox.minLon);
                    params.put(varPrefix + "_maxLon", bbox.maxLon);
                    
                    return createPointExpression(centerLat, centerLon, varPrefix, params, span);
                }
            }

            LOGGER.warn("Failed to parse WKT geometry: {}", wkt);
            span.setStatus(StatusCode.ERROR, "Unsupported WKT format");
            return null;
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            LOGGER.error("Error parsing WKT geometry: {}", wkt, e);
            return null;
        } finally {
            span.end();
        }
    }

    /**
     * Creates a Cypher point() expression with parameters.
     *
     * @param lat latitude
     * @param lon longitude
     * @param varPrefix variable prefix for parameter names
     * @param params parameter map to populate
     * @param span tracing span to add attributes to
     * @return Cypher point() expression
     */
    private static String createPointExpression(double lat, double lon, String varPrefix,
                                               java.util.Map<String, Object> params, Span span) {
        String latParam = varPrefix + "_lat";
        String lonParam = varPrefix + "_lon";

        params.put(latParam, lat);
        params.put(lonParam, lon);

        span.setAttribute("falkordb.geospatial.latitude", lat);
        span.setAttribute("falkordb.geospatial.longitude", lon);
        span.setStatus(StatusCode.OK);

        return "point({latitude: $" + latParam + ", longitude: $" + lonParam + "})";
    }

    /**
     * Calculates the bounding box from a coordinate string.
     * 
     * <p>The coordinate string should contain space-separated lon/lat pairs,
     * with pairs separated by commas.</p>
     *
     * @param coords coordinate string (e.g., "lon1 lat1, lon2 lat2, lon3 lat3")
     * @return bounding box with min/max lat/lon, or null if parsing fails
     */
    private static BoundingBox calculateBoundingBox(String coords) {
        String[] points = coords.split(",");
        if (points.length == 0) {
            return null;
        }

        double minLat = Double.MAX_VALUE;
        double maxLat = Double.MIN_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = Double.MIN_VALUE;
        
        int successfulParses = 0;

        for (String point : points) {
            String[] parts = point.trim().split("\\s+");
            if (parts.length >= 2) {
                try {
                    double lon = Double.parseDouble(parts[0]);
                    double lat = Double.parseDouble(parts[1]);

                    minLat = Math.min(minLat, lat);
                    maxLat = Math.max(maxLat, lat);
                    minLon = Math.min(minLon, lon);
                    maxLon = Math.max(maxLon, lon);
                    successfulParses++;
                } catch (NumberFormatException e) {
                    LOGGER.warn("Failed to parse coordinate pair: {}", point);
                    // If any coordinate fails to parse, we may have incomplete data
                    // But we continue to try parsing other coordinates
                }
            }
        }

        // Validate we found at least one valid point with both lat and lon
        if (successfulParses == 0 || 
            minLat == Double.MAX_VALUE || maxLat == Double.MIN_VALUE ||
            minLon == Double.MAX_VALUE || maxLon == Double.MIN_VALUE) {
            LOGGER.warn("Failed to calculate bounding box: no valid coordinates found");
            return null;
        }

        return new BoundingBox(minLat, maxLat, minLon, maxLon);
    }

    /**
     * Represents a geographic bounding box.
     */
    private static class BoundingBox {
        final double minLat;
        final double maxLat;
        final double minLon;
        final double maxLon;

        BoundingBox(double minLat, double maxLat, double minLon, double maxLon) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
        }
    }

    /**
     * Extracts latitude from a WKT geometry string.
     * 
     * <p>For POINT geometries, returns the exact latitude.
     * For complex geometries (POLYGON, LINESTRING, MULTIPOINT), returns
     * the center latitude of the bounding box.</p>
     *
     * @param wkt the WKT string
     * @return the latitude (or center latitude for complex geometries), or null if parsing fails
     */
    public static Double extractLatitude(String wkt) {
        // Try POINT first
        Matcher matcher = POINT_PATTERN.matcher(wkt);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(2));
        }

        // Try POLYGON
        Matcher polygonMatcher = POLYGON_PATTERN.matcher(wkt);
        if (polygonMatcher.find()) {
            BoundingBox bbox = calculateBoundingBox(polygonMatcher.group(1));
            if (bbox != null) {
                return (bbox.minLat + bbox.maxLat) / 2.0;
            }
        }

        // Try LINESTRING
        Matcher linestringMatcher = LINESTRING_PATTERN.matcher(wkt);
        if (linestringMatcher.find()) {
            BoundingBox bbox = calculateBoundingBox(linestringMatcher.group(1));
            if (bbox != null) {
                return (bbox.minLat + bbox.maxLat) / 2.0;
            }
        }

        // Try MULTIPOINT
        Matcher multipointMatcher = MULTIPOINT_PATTERN.matcher(wkt);
        if (multipointMatcher.find()) {
            String coords = multipointMatcher.group(1).replaceAll("[()]", "");
            BoundingBox bbox = calculateBoundingBox(coords);
            if (bbox != null) {
                return (bbox.minLat + bbox.maxLat) / 2.0;
            }
        }

        return null;
    }

    /**
     * Extracts longitude from a WKT geometry string.
     * 
     * <p>For POINT geometries, returns the exact longitude.
     * For complex geometries (POLYGON, LINESTRING, MULTIPOINT), returns
     * the center longitude of the bounding box.</p>
     *
     * @param wkt the WKT string
     * @return the longitude (or center longitude for complex geometries), or null if parsing fails
     */
    public static Double extractLongitude(String wkt) {
        // Try POINT first
        Matcher matcher = POINT_PATTERN.matcher(wkt);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }

        // Try POLYGON
        Matcher polygonMatcher = POLYGON_PATTERN.matcher(wkt);
        if (polygonMatcher.find()) {
            BoundingBox bbox = calculateBoundingBox(polygonMatcher.group(1));
            if (bbox != null) {
                return (bbox.minLon + bbox.maxLon) / 2.0;
            }
        }

        // Try LINESTRING
        Matcher linestringMatcher = LINESTRING_PATTERN.matcher(wkt);
        if (linestringMatcher.find()) {
            BoundingBox bbox = calculateBoundingBox(linestringMatcher.group(1));
            if (bbox != null) {
                return (bbox.minLon + bbox.maxLon) / 2.0;
            }
        }

        // Try MULTIPOINT
        Matcher multipointMatcher = MULTIPOINT_PATTERN.matcher(wkt);
        if (multipointMatcher.find()) {
            String coords = multipointMatcher.group(1).replaceAll("[()]", "");
            BoundingBox bbox = calculateBoundingBox(coords);
            if (bbox != null) {
                return (bbox.minLon + bbox.maxLon) / 2.0;
            }
        }

        return null;
    }

    /**
     * Extracts the bounding box from a WKT geometry string.
     * 
     * <p>Returns a double array with [minLat, maxLat, minLon, maxLon].</p>
     *
     * @param wkt the WKT string
     * @return bounding box as [minLat, maxLat, minLon, maxLon], or null if parsing fails
     */
    public static double[] extractBoundingBox(String wkt) {
        BoundingBox bbox = null;

        // Try POINT
        Matcher pointMatcher = POINT_PATTERN.matcher(wkt);
        if (pointMatcher.find()) {
            double lon = Double.parseDouble(pointMatcher.group(1));
            double lat = Double.parseDouble(pointMatcher.group(2));
            return new double[]{lat, lat, lon, lon};
        }

        // Try POLYGON
        Matcher polygonMatcher = POLYGON_PATTERN.matcher(wkt);
        if (polygonMatcher.find()) {
            bbox = calculateBoundingBox(polygonMatcher.group(1));
        }

        // Try LINESTRING
        if (bbox == null) {
            Matcher linestringMatcher = LINESTRING_PATTERN.matcher(wkt);
            if (linestringMatcher.find()) {
                bbox = calculateBoundingBox(linestringMatcher.group(1));
            }
        }

        // Try MULTIPOINT
        if (bbox == null) {
            Matcher multipointMatcher = MULTIPOINT_PATTERN.matcher(wkt);
            if (multipointMatcher.find()) {
                String coords = multipointMatcher.group(1).replaceAll("[()]", "");
                bbox = calculateBoundingBox(coords);
            }
        }

        if (bbox != null) {
            return new double[]{bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon};
        }

        return null;
    }
}
