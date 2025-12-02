package com.falkordb.jena;

import com.falkordb.Driver;
import com.falkordb.FalkorDB;
import com.falkordb.Graph;
import com.falkordb.Record;
import com.falkordb.ResultSet;
import com.falkordb.jena.tracing.TracedGraph;
import com.falkordb.jena.tracing.TracingUtil;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphBase;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.WrappedIterator;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jena Graph implementation backed by a FalkorDB graph.
 *
 * This class implements the minimal GraphBase hooks to translate Jena
 * Triple operations into FalkorDB (Cypher-like) queries using the JFalkorDB
 * API.
 */
public final class FalkorDBGraph extends GraphBase {
    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        FalkorDBGraph.class);
    /** FalkorDB driver (JFalkorDB). */
    private final Driver driver;
    /** Underlying FalkorDB graph instance (with tracing). */
    private final TracedGraph graph;
    /** Name of the FalkorDB graph in use. */
    private final String graphName;
    /** Tracer for FalkorDBGraph operations. */
    private final Tracer tracer;

    /** Attribute key for operation type. */
    private static final AttributeKey<String> ATTR_OPERATION =
        AttributeKey.stringKey("falkordb.operation");

    /** Attribute key for graph name. */
    private static final AttributeKey<String> ATTR_GRAPH_NAME =
        AttributeKey.stringKey("falkordb.graph_name");

    /** Attribute key for triple subject. */
    private static final AttributeKey<String> ATTR_TRIPLE_SUBJECT =
        AttributeKey.stringKey("rdf.triple.subject");

    /** Attribute key for triple predicate. */
    private static final AttributeKey<String> ATTR_TRIPLE_PREDICATE =
        AttributeKey.stringKey("rdf.triple.predicate");

    /** Attribute key for triple object. */
    private static final AttributeKey<String> ATTR_TRIPLE_OBJECT =
        AttributeKey.stringKey("rdf.triple.object");

    /** Attribute key for pattern. */
    private static final AttributeKey<String> ATTR_PATTERN =
        AttributeKey.stringKey("rdf.pattern");

    /** Attribute key for result count. */
    private static final AttributeKey<Long> ATTR_RESULT_COUNT =
        AttributeKey.longKey("rdf.result_count");

    /**
     * Create a FalkorDB-backed graph for the given graph name. Uses the
     * default driver configuration (typically localhost:6379).
     *
     * @param name name of the FalkorDB graph to use
     */
    public FalkorDBGraph(final String name) {
        this(FalkorDB.driver(), name);
    }

    /**
     * Create a FalkorDB-backed graph using an explicit host and port.
     *
     * @param host FalkorDB host
     * @param port FalkorDB port
     * @param name name of the FalkorDB graph to use
     */
    public FalkorDBGraph(final String host,
                        final int port,
                        final String name) {
        this(FalkorDB.driver(host, port), name);
    }

    /**
     * Create a FalkorDB-backed graph with a custom driver instance.
     * This allows users to bring their own configured driver.
     *
     * @param suppliedDriver FalkorDB driver instance to use
     * @param name name of the FalkorDB graph to use
     */
    public FalkorDBGraph(final Driver suppliedDriver, final String name) {
        this.driver = suppliedDriver;
        this.graph = new TracedGraph(this.driver.graph(name), name);
        this.graphName = name;
        this.tracer = TracingUtil.getTracer(TracingUtil.SCOPE_FALKORDB_GRAPH);
        ensureIndexes();
    }

    /**
     * Ensures required indexes exist on the graph for optimal performance.
     * Creates an index on the uri property of Resource nodes.
     */
    private void ensureIndexes() {
        try {
            // Create index on Resource.uri for fast lookups
            graph.query("CREATE INDEX FOR (r:Resource) ON (r.uri)");
        } catch (Exception e) {
            // Index might already exist, which is fine
            if (!e.getMessage().contains("already indexed")) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Could not create index: {}",
                        e.getMessage());
                }
            }
        }
    }

    /**
     * Returns the FalkorDB graph name used by this graph instance.
     *
     * @return the FalkorDB graph name.
     */
    public String getGraphName() {
        return graphName;
    }

    /**
     * Returns the underlying FalkorDB graph instance.
     * Use this method if you need direct access to the graph
     * without tracing.
     *
     * @return the underlying FalkorDB Graph instance
     */
    public Graph getUnderlyingGraph() {
        return graph.getDelegate();
    }

    /** Clear all nodes and relationships from the graph. */
    @Override
    public void clear() {
        // Delete all nodes and relationships
        graph.query("MATCH (n) DETACH DELETE n");
    }

    /**
     * Add a triple to the backing FalkorDB graph.
     *
     * This method translates a Jena Triple to a FalkorDB/Cypher create
     * query. When the object is a literal, it is stored as a property on the
     * subject node. When the object is a resource, it creates a relationship
     * between nodes.
     */
    @Override
    public void performAdd(final Triple triple) {
        Span span = tracer.spanBuilder("FalkorDBGraph.performAdd")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "add")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .setAttribute(ATTR_TRIPLE_SUBJECT, nodeToString(triple.getSubject()))
            .setAttribute(ATTR_TRIPLE_PREDICATE, nodeToString(triple.getPredicate()))
            .setAttribute(ATTR_TRIPLE_OBJECT, nodeToString(triple.getObject()))
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            performAddInternal(triple);
            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    //@todo barak batch add triples for better performance
    /**
     * Internal implementation of performAdd without tracing.
     */
    private void performAddInternal(final Triple triple) {
        // Translate RDF triple to Cypher CREATE/MERGE
        var subject = nodeToString(triple.getSubject());
        var predicate = nodeToString(triple.getPredicate());

        var params = new HashMap<String, Object>(2);
        String cypher;

        if (triple.getObject().isLiteral()) {
            // Store literal as a property on the subject node
            // Use backticks to allow URIs as property names directly
            var objectValue = triple.getObject().getLiteralLexicalForm();

            params.put("subjectUri", subject);
            params.put("objectValue", objectValue);

            cypher = """
                MERGE (s:Resource {uri: $subjectUri}) \
                SET s.`%s` = $objectValue""".formatted(predicate);
        } else if (predicate.equals(RDF.type.getURI())) {
            // Special handling for rdf:type - create node with type as label
            var object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);

            cypher = """
                MERGE (s:Resource:`%s` {uri: $subjectUri})""".formatted(object);
        } else {
            // Create relationship for resource objects
            var object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);
            params.put("objectUri", object);

            cypher = """
                MERGE (s:Resource {uri: $subjectUri}) \
                MERGE (o:Resource {uri: $objectUri}) \
                MERGE (s)-[r:`%s`]->(o)""".formatted(predicate);
        }

        graph.query(cypher, params);
    }

    /** Delete a triple from the backing FalkorDB graph. */
    @Override
    public void performDelete(final Triple triple) {
        Span span = tracer.spanBuilder("FalkorDBGraph.performDelete")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "delete")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .setAttribute(ATTR_TRIPLE_SUBJECT, nodeToString(triple.getSubject()))
            .setAttribute(ATTR_TRIPLE_PREDICATE, nodeToString(triple.getPredicate()))
            .setAttribute(ATTR_TRIPLE_OBJECT, nodeToString(triple.getObject()))
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            performDeleteInternal(triple);
            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Internal implementation of performDelete without tracing.
     */
    private void performDeleteInternal(final Triple triple) {
        var subject = nodeToString(triple.getSubject());
        var predicate = nodeToString(triple.getPredicate());

        var params = new HashMap<String, Object>(2);
        String cypher;

        if (triple.getObject().isLiteral()) {
            // Remove property from the subject node
            // Use backticks to allow URIs as property names directly
            params.put("subjectUri", subject);

            cypher = """
                MATCH (s:Resource {uri: $subjectUri}) \
                REMOVE s.`%s`""".formatted(predicate);
        } else if (predicate.equals(RDF.type.getURI())) {
            // Special handling for rdf:type - remove label from node
            var object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);
            cypher = """
                MATCH (s:Resource:`%s` {uri: $subjectUri}) \
                REMOVE s:`%s`""".formatted(object, object);
        } else {
            var object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);
            params.put("objectUri", object);

            cypher = """
                MATCH (s:Resource {uri: $subjectUri})-[r:`%s`]->
                (o:Resource {uri: $objectUri}) DELETE r""".formatted(predicate);
        }

        graph.query(cypher, params);
    }

    /** Find triples matching the given pattern. */
    @Override
    protected ExtendedIterator<Triple> graphBaseFind(final Triple pattern) {
        String patternStr = patternToString(pattern);
        Span span = tracer.spanBuilder("FalkorDBGraph.find")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "find")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .setAttribute(ATTR_PATTERN, patternStr)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            List<Triple> triples = graphBaseFindInternal(pattern);
            span.setAttribute(ATTR_RESULT_COUNT, (long) triples.size());
            span.setStatus(StatusCode.OK);
            return WrappedIterator.create(triples.iterator());
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    //@todo barak, who is calling this find, can we get more context?
    /**
     * Internal implementation of graphBaseFind without tracing.
     */
    private List<Triple> graphBaseFindInternal(final Triple pattern) {
        var triples = new ArrayList<Triple>();

        // Check if this is an rdf:type query
        var isTypeQuery = !pattern.getPredicate().isConcrete()
            || pattern.getPredicate().getURI().equals(RDF.type.getURI());

        if (isTypeQuery) {
            // Query for rdf:type triples (nodes with labels)
            var typeTriples = findTypeTriples(pattern);
            triples.addAll(typeTriples);
        }

        // Query for relationship-based triples (non-literal objects,
        // non-rdf:type)
        if (!pattern.getObject().isConcrete()
            || !pattern.getObject().isLiteral()) {
            if (!pattern.getPredicate().isConcrete()
                || !pattern.getPredicate().getURI().equals(
                    RDF.type.getURI())) {
                var params = new HashMap<String, Object>(2);
                var cypherRels = buildCypherMatchRelationships(pattern, params);
                var result = graph.query(cypherRels, params);

                for (var record : result) {
                    var triple = recordToTriple(record);
                    triples.add(triple);
                }
            }
        }

        // Query for property-based triples (literal objects)
        if (!pattern.getObject().isConcrete()
            || pattern.getObject().isLiteral()) {
            var propertyTriples = findPropertyTriples(pattern);
            triples.addAll(propertyTriples);
        }

        return triples;
    }

    /**
     * Convert a triple pattern to a string for tracing.
     */
    private String patternToString(final Triple pattern) {
        return String.format("(%s, %s, %s)",
            pattern.getSubject().isConcrete()
                ? nodeToString(pattern.getSubject()) : "?",
            pattern.getPredicate().isConcrete()
                ? nodeToString(pattern.getPredicate()) : "?",
            pattern.getObject().isConcrete()
                ? nodeToString(pattern.getObject()) : "?");
    }

    private String buildCypherMatchRelationships(
            final Triple pattern,
            final Map<String, Object> params) {
        var cypher = new StringBuilder("MATCH ");

        if (pattern.getSubject().isConcrete()) {
            params.put("subjectUri", nodeToString(pattern.getSubject()));
            cypher.append("(s:Resource {uri: $subjectUri})");
        } else {
            cypher.append("(s:Resource)");
        }

        if (pattern.getPredicate().isConcrete()) {
            cypher.append("-[r:`%s`]->".formatted(
                nodeToString(pattern.getPredicate())));
        } else {
            cypher.append("-[r]->");
        }

        if (pattern.getObject().isConcrete()) {
            params.put("objectUri", nodeToString(pattern.getObject()));
            cypher.append("(o:Resource {uri: $objectUri})");
        } else {
            cypher.append("(o)");
        }

        cypher.append(" RETURN s, r, o");
        return cypher.toString();
    }

    private List<Triple> findPropertyTriples(final Triple pattern) {
        var triples = new ArrayList<Triple>();

        // Build query to get nodes with their properties as map
        var params = new HashMap<String, Object>(1);
        var cypher = new StringBuilder("MATCH ");

        if (pattern.getSubject().isConcrete()) {
            params.put("subjectUri", nodeToString(pattern.getSubject()));
            cypher.append("(s:Resource {uri: $subjectUri})");
        } else {
            cypher.append("(s:Resource)");
        }

        cypher.append(" RETURN s, properties(s) as props");

        var result = graph.query(cypher.toString(), params);

        for (var record : result) {
            com.falkordb.graph_entities.Node node = record.getValue("s");
            var subjectUri = node.getProperty("uri").getValue().toString();
            var subject = NodeFactory.createURI(subjectUri);

            @SuppressWarnings("unchecked")
            var properties = (Map<String, Object>) record.getValue("props");

            // Iterate over all properties
            for (var entry : properties.entrySet()) {
                var predicateUri = entry.getKey();

                // Skip the 'uri' property as it's not an RDF triple
                if ("uri".equals(predicateUri)) {
                    continue;
                }

                // Check if predicate matches pattern
                if (pattern.getPredicate().isConcrete()) {
                    var patternPredicate = nodeToString(pattern.getPredicate());
                    if (!predicateUri.equals(patternPredicate)) {
                        continue;
                    }
                }

                var literalValue = entry.getValue().toString();

                // Check if object matches pattern
                if (pattern.getObject().isConcrete()) {
                    var patternObject = pattern.getObject()
                        .getLiteralLexicalForm();
                    if (!literalValue.equals(patternObject)) {
                        continue;
                    }
                }

                var predicate = NodeFactory.createURI(predicateUri);
                var object = NodeFactory.createLiteralString(literalValue);

                triples.add(Triple.create(subject, predicate, object));
            }
        }

        return triples;
    }

    private List<Triple> findTypeTriples(final Triple pattern) {
        var triples = new ArrayList<Triple>();

        // Build query to get nodes with their labels
        var params = new HashMap<String, Object>(1);
        var cypher = new StringBuilder("MATCH ");

        if (pattern.getSubject().isConcrete()) {
            params.put("subjectUri", nodeToString(pattern.getSubject()));
            cypher.append("(s {uri: $subjectUri})");
        } else {
            cypher.append("(s)");
        }

        cypher.append(" RETURN s, labels(s) as nodeLabels");

        var result = graph.query(cypher.toString(), params);

        for (var record : result) {
            com.falkordb.graph_entities.Node node = record.getValue("s");
            var subjectUri = node.getProperty("uri").getValue().toString();
            var subject = NodeFactory.createURI(subjectUri);

            @SuppressWarnings("unchecked")
            var labels = (List<String>) record.getValue("nodeLabels");

            // Create an rdf:type triple for each label (except "Resource")
            for (var label : labels) {
                if ("Resource".equals(label)) {
                    continue; // Skip the base Resource label
                }

                // Check if object matches pattern
                if (pattern.getObject().isConcrete()) {
                    var patternObject = nodeToString(pattern.getObject());
                    if (!label.equals(patternObject)) {
                        continue;
                    }
                }

                var predicate = NodeFactory.createURI(RDF.type.getURI());
                var object = NodeFactory.createURI(label);

                triples.add(Triple.create(subject, predicate, object));
            }
        }

        return triples;
    }

    private Triple recordToTriple(final Record record) {
        com.falkordb.graph_entities.Node subjectNode = record.getValue("s");
        com.falkordb.graph_entities.Edge edge = record.getValue("r");
        com.falkordb.graph_entities.Node objectNode = record.getValue("o");

        var subjectUri = subjectNode.getProperty("uri").getValue().toString();
        var predicateUri = edge.getRelationshipType();

        var subject = NodeFactory.createURI(subjectUri);
        var predicate = NodeFactory.createURI(predicateUri);

        Node object;
        // Check for literal by looking for 'value' property
        if (objectNode.getProperty("value") != null) {
            var literalValue = objectNode.getProperty("value")
                .getValue().toString();
            object = NodeFactory.createLiteralString(literalValue);
        } else {
            var objectUri = objectNode.getProperty("uri").getValue()
                .toString();
            object = NodeFactory.createURI(objectUri);
        }

        return Triple.create(subject, predicate, object);
    }

    private String nodeToString(final Node node) {
        if (node.isURI()) {
            return node.getURI();
        } else if (node.isLiteral()) {
            return node.getLiteralLexicalForm();
        } else if (node.isBlank()) {
            return "_:" + node.getBlankNodeLabel();
        }
        return node.toString();
    }

    @Override
    public void close() {
        try {
            driver.close();
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error closing FalkorDB driver: {}",
                    e.getMessage());
            }
        }
        super.close();
    }
}
