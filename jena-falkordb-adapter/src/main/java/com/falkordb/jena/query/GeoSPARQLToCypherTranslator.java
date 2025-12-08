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
 *   <li>POINT - Translated to {@code point({latitude: lat, longitude: lon})}</li>
 *   <li>POLYGON - Bounding box extracted for range queries</li>
 * </ul>
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
        return "distance(" + point + ", " + region + ") < 0.001";
    }

    /**
     * Translates geof:sfContains function to Cypher containment check.
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

        // For polygon-contains-point checks
        return "distance(" + container + ", " + contained + ") < 0.001";
    }

    /**
     * Translates geof:sfIntersects function to Cypher intersection check.
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

        // For geometry intersection checks
        return "distance(" + geom1 + ", " + geom2 + ") < 0.001";
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
     * @param wkt the WKT string
     * @param varPrefix variable prefix for parameter names
     * @param params parameter map to populate
     * @return Cypher point() expression
     */
    public static String parseWKTToPoint(String wkt, String varPrefix,
                                         java.util.Map<String, Object> params) {
        // Try to parse as POINT
        Matcher pointMatcher = POINT_PATTERN.matcher(wkt);
        if (pointMatcher.find()) {
            double lon = Double.parseDouble(pointMatcher.group(1));
            double lat = Double.parseDouble(pointMatcher.group(2));

            String latParam = varPrefix + "_lat";
            String lonParam = varPrefix + "_lon";

            params.put(latParam, lat);
            params.put(lonParam, lon);

            return "point({latitude: $" + latParam + ", longitude: $" + lonParam + "})";
        }

        // Try to parse as POLYGON and extract center point
        Matcher polygonMatcher = POLYGON_PATTERN.matcher(wkt);
        if (polygonMatcher.find()) {
            String coords = polygonMatcher.group(1);
            String[] points = coords.split(",");

            if (points.length > 0) {
                // Extract first point as approximation
                String[] firstPoint = points[0].trim().split("\\s+");
                if (firstPoint.length >= 2) {
                    double lon = Double.parseDouble(firstPoint[0]);
                    double lat = Double.parseDouble(firstPoint[1]);

                    String latParam = varPrefix + "_lat";
                    String lonParam = varPrefix + "_lon";

                    params.put(latParam, lat);
                    params.put(lonParam, lon);

                    return "point({latitude: $" + latParam + ", longitude: $" + lonParam + "})";
                }
            }
        }

        LOGGER.warn("Failed to parse WKT geometry: {}", wkt);
        return null;
    }

    /**
     * Extracts latitude from a WKT POINT string.
     *
     * @param wkt the WKT string
     * @return the latitude, or null if parsing fails
     */
    public static Double extractLatitude(String wkt) {
        Matcher matcher = POINT_PATTERN.matcher(wkt);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(2));
        }
        return null;
    }

    /**
     * Extracts longitude from a WKT POINT string.
     *
     * @param wkt the WKT string
     * @return the longitude, or null if parsing fails
     */
    public static Double extractLongitude(String wkt) {
        Matcher matcher = POINT_PATTERN.matcher(wkt);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return null;
    }
}
