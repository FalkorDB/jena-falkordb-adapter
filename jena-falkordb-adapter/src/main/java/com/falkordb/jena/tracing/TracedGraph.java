package com.falkordb.jena.tracing;

import com.falkordb.Graph;
import com.falkordb.ResultSet;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.Map;

/**
 * A wrapper around FalkorDB Graph that adds OpenTelemetry tracing
 * to all Redis/driver calls.
 *
 * <p>This provides Level 3 tracing: every call to the underlying
 * Redis graph driver is traced with the query and parameters.</p>
 */
public final class TracedGraph {
    /** The wrapped graph instance. */
    private final Graph delegate;

    /** The graph name for tracing attributes. */
    private final String graphName;

    /** Tracer for creating spans. */
    private final Tracer tracer;

    /** Attribute key for Cypher query. */
    private static final AttributeKey<String> ATTR_DB_STATEMENT =
        AttributeKey.stringKey("db.statement");

    /** Attribute key for database system. */
    private static final AttributeKey<String> ATTR_DB_SYSTEM =
        AttributeKey.stringKey("db.system");

    /** Attribute key for database name. */
    private static final AttributeKey<String> ATTR_DB_NAME =
        AttributeKey.stringKey("db.name");

    /** Attribute key for query parameters. */
    private static final AttributeKey<String> ATTR_DB_PARAMS =
        AttributeKey.stringKey("db.falkordb.params");

    /** Attribute key for result count. */
    private static final AttributeKey<Long> ATTR_DB_RESULT_COUNT =
        AttributeKey.longKey("db.falkordb.result_count");

    /**
     * Create a traced graph wrapper.
     *
     * @param delegate the underlying Graph to wrap
     * @param graphName the graph name for attributes
     */
    public TracedGraph(final Graph delegate, final String graphName) {
        this.delegate = delegate;
        this.graphName = graphName;
        this.tracer = TracingUtil.getTracer(TracingUtil.SCOPE_REDIS_DRIVER);
    }

    /**
     * Execute a Cypher query with tracing.
     *
     * @param cypher the Cypher query
     * @return the result set
     */
    public ResultSet query(final String cypher) {
        if (!TracingUtil.isTracingEnabled()) {
            return delegate.query(cypher);
        }

        Span span = tracer.spanBuilder("FalkorDB.query")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(ATTR_DB_SYSTEM, "falkordb")
            .setAttribute(ATTR_DB_NAME, graphName)
            .setAttribute(ATTR_DB_STATEMENT, cypher)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            ResultSet result = delegate.query(cypher);
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Execute a parameterized Cypher query with tracing.
     *
     * @param cypher the Cypher query
     * @param params the query parameters
     * @return the result set
     */
    public ResultSet query(final String cypher,
                           final Map<String, Object> params) {
        if (!TracingUtil.isTracingEnabled()) {
            return delegate.query(cypher, params);
        }

        Span span = tracer.spanBuilder("FalkorDB.query")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(ATTR_DB_SYSTEM, "falkordb")
            .setAttribute(ATTR_DB_NAME, graphName)
            .setAttribute(ATTR_DB_STATEMENT, cypher)
            .setAttribute(ATTR_DB_PARAMS, paramsToString(params))
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            ResultSet result = delegate.query(cypher, params);
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Get the underlying graph.
     *
     * @return the underlying Graph
     */
    public Graph getDelegate() {
        return delegate;
    }

    /**
     * Convert parameters map to a string for tracing.
     *
     * @param params the parameters map
     * @return string representation
     */
    private String paramsToString(final Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append("=");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append(value);
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
