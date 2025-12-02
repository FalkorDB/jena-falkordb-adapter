/**
 * OpenTelemetry tracing integration for Jena FalkorDB.
 *
 * <p>This package provides tracing utilities using the OpenTelemetry SDK
 * to capture traces at three levels:</p>
 * <ol>
 *   <li>Top-level Fuseki calls with request parameters</li>
 *   <li>FalkorDBGraph operations with triple parameters</li>
 *   <li>Underlying Redis/graph driver calls with query parameters</li>
 * </ol>
 *
 * <p>Traces are exported via OTLP protocol to a collector such as Jaeger.</p>
 *
 * @see com.falkordb.jena.tracing.TracingUtil
 */
package com.falkordb.jena.tracing;
