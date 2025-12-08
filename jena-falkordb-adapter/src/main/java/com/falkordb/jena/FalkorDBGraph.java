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
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.TransactionHandler;
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
 * <p>This class implements the minimal GraphBase hooks to translate Jena
 * Triple operations into FalkorDB (Cypher-like) queries using the JFalkorDB
 * API.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Transaction support via {@link FalkorDBTransactionHandler}</li>
 *   <li>Batch operations using Cypher UNWIND for efficiency</li>
 *   <li>OpenTelemetry tracing support</li>
 * </ul>
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
    /** Transaction handler for batch operations. */
    private final FalkorDBTransactionHandler transactionHandler;

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
        this.transactionHandler = new FalkorDBTransactionHandler(
            this.graph,
            this.graphName,
            this::performAddDirect,
            this::performDeleteDirect
        );
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

    /**
     * Returns the traced FalkorDB graph instance.
     * Use this method when you need to execute queries while
     * maintaining the span hierarchy for tracing.
     *
     * @return the TracedGraph wrapper around the underlying Graph
     */
    public TracedGraph getTracedGraph() {
        return graph;
    }

    /**
     * Returns the transaction handler for this graph.
     *
     * <p>The transaction handler supports efficient batch operations by
     * buffering triples during a transaction and flushing them in bulk
     * using Cypher's UNWIND.</p>
     *
     * @return the FalkorDB transaction handler
     */
    @Override
    public TransactionHandler getTransactionHandler() {
        return transactionHandler;
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
     * <p>This method delegates to the transaction handler to enable batch
     * operations. When in a transaction, triples are buffered and flushed
     * in bulk on commit. When not in a transaction, triples are added
     * immediately.</p>
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
            // Delegate to transaction handler for buffering or immediate add
            transactionHandler.bufferAdd(triple);
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
     * Direct add implementation, bypassing transaction buffering.
     * Used by the transaction handler for immediate non-transactional adds.
     *
     * @param triple the triple to add directly
     */
    void performAddDirect(final Triple triple) {
        performAddInternal(triple);
    }

    /**
     * Internal implementation of performAdd without tracing.
     * Translates RDF triple to Cypher CREATE/MERGE.
     * When the object is a literal, it is stored as a property on the
     * subject node. When the object is a resource, it creates a relationship
     * between nodes.
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
            // Store the actual typed value, not just the lexical form
            Object objectValue;
            var literal = triple.getObject().getLiteral();
            var literalValue = literal.getValue();
            
            // Use the typed value if available, otherwise use lexical form
            if (literalValue instanceof Number || literalValue instanceof Boolean) {
                objectValue = literalValue;
            } else {
                objectValue = triple.getObject().getLiteralLexicalForm();
            }

            params.put("subjectUri", subject);
            params.put("objectValue", objectValue);

            // Sanitize predicate to prevent Cypher injection
            String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
            
            // Store datatype URI for non-string literals to preserve type information
            // This is critical for geometry literals (e.g., geo:wktLiteral) and other custom types
            var datatypeURI = literal.getDatatypeURI();
            if (datatypeURI != null && !datatypeURI.equals("http://www.w3.org/2001/XMLSchema#string")) {
                params.put("datatypeValue", datatypeURI);
                cypher = """
                    MERGE (s:Resource {uri: $subjectUri}) \
                    SET s.`%s` = $objectValue, s.`%s__datatype` = $datatypeValue""".formatted(
                        sanitizedPredicate, sanitizedPredicate);
            } else {
                cypher = """
                    MERGE (s:Resource {uri: $subjectUri}) \
                    SET s.`%s` = $objectValue""".formatted(sanitizedPredicate);
            }
        } else if (predicate.equals(RDF.type.getURI())) {
            // Special handling for rdf:type - create node with type as label
            var object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);

            // Sanitize type to prevent Cypher injection
            String sanitizedType = sanitizeCypherIdentifier(object);
            cypher = """
                MERGE (s:Resource:`%s` {uri: $subjectUri})""".formatted(
                    sanitizedType);
        } else {
            // Create relationship for resource objects
            var object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);
            params.put("objectUri", object);

            // Sanitize predicate to prevent Cypher injection
            String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
            cypher = """
                MERGE (s:Resource {uri: $subjectUri}) \
                MERGE (o:Resource {uri: $objectUri}) \
                MERGE (s)-[r:`%s`]->(o)""".formatted(sanitizedPredicate);
        }

        graph.query(cypher, params);
    }

    /**
     * Delete a triple from the backing FalkorDB graph.
     *
     * <p>This method delegates to the transaction handler to enable batch
     * operations. When in a transaction, deletes are buffered and flushed
     * in bulk on commit. When not in a transaction, deletes are executed
     * immediately.</p>
     */
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
            // Delegate to transaction handler for buffering or immediate delete
            transactionHandler.bufferDelete(triple);
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
     * Direct delete implementation, bypassing transaction buffering.
     * Used by the transaction handler for immediate non-transactional deletes.
     *
     * @param triple the triple to delete directly
     */
    void performDeleteDirect(final Triple triple) {
        performDeleteInternal(triple);
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
            // Also remove the associated __datatype property if it exists
            params.put("subjectUri", subject);

            // Sanitize predicate to prevent Cypher injection
            String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
            cypher = """
                MATCH (s:Resource {uri: $subjectUri}) \
                REMOVE s.`%s`, s.`%s__datatype`""".formatted(
                    sanitizedPredicate, sanitizedPredicate);
        } else if (predicate.equals(RDF.type.getURI())) {
            // Special handling for rdf:type - remove label from node
            var object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);

            // Sanitize type to prevent Cypher injection
            String sanitizedType = sanitizeCypherIdentifier(object);
            cypher = """
                MATCH (s:Resource:`%s` {uri: $subjectUri}) \
                REMOVE s:`%s`""".formatted(sanitizedType, sanitizedType);
        } else {
            var object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);
            params.put("objectUri", object);

            // Sanitize predicate to prevent Cypher injection
            String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
            cypher = """
                MATCH (s:Resource {uri: $subjectUri})-[r:`%s`]->
                (o:Resource {uri: $objectUri}) DELETE r""".formatted(
                    sanitizedPredicate);
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
                
                // Skip datatype properties - they're metadata, not RDF triples
                if (predicateUri.endsWith("__datatype")) {
                    continue;
                }

                // Check if predicate matches pattern
                if (pattern.getPredicate().isConcrete()) {
                    var patternPredicate = nodeToString(pattern.getPredicate());
                    if (!predicateUri.equals(patternPredicate)) {
                        continue;
                    }
                }

                var rawValue = entry.getValue();
                var predicateNode = NodeFactory.createURI(predicateUri);
                
                // Check if there's a stored datatype for this property
                var datatypeKey = predicateUri + "__datatype";
                var storedDatatype = properties.get(datatypeKey);
                
                // Create properly typed literal based on stored datatype or value type from FalkorDB
                org.apache.jena.graph.Node object;
                if (storedDatatype != null && storedDatatype instanceof String) {
                    // Use the stored datatype to reconstruct the typed literal
                    String datatypeURI = (String) storedDatatype;
                    var datatype = TypeMapper.getInstance().getSafeTypeByName(datatypeURI);
                    object = NodeFactory.createLiteral(rawValue.toString(), datatype);
                } else if (rawValue instanceof Long) {
                    // FalkorDB returns integers as Long
                    object = NodeFactory.createLiteralByValue(((Long) rawValue).intValue(), 
                        org.apache.jena.datatypes.xsd.XSDDatatype.XSDint);
                } else if (rawValue instanceof Integer) {
                    object = NodeFactory.createLiteralByValue(rawValue, 
                        org.apache.jena.datatypes.xsd.XSDDatatype.XSDint);
                } else if (rawValue instanceof Double) {
                    object = NodeFactory.createLiteralByValue(rawValue, 
                        org.apache.jena.datatypes.xsd.XSDDatatype.XSDdouble);
                } else if (rawValue instanceof Float) {
                    object = NodeFactory.createLiteralByValue(rawValue, 
                        org.apache.jena.datatypes.xsd.XSDDatatype.XSDfloat);
                } else if (rawValue instanceof Boolean) {
                    object = NodeFactory.createLiteralByValue(rawValue, 
                        org.apache.jena.datatypes.xsd.XSDDatatype.XSDboolean);
                } else {
                    // String or other types - store as string literal
                    object = NodeFactory.createLiteralString(rawValue.toString());
                }

                // Check if object matches pattern
                if (pattern.getObject().isConcrete()) {
                    // Compare using sameValueAs which handles typed literal comparison
                    if (!object.sameValueAs(pattern.getObject())) {
                        continue;
                    }
                }

                triples.add(Triple.create(subject, predicateNode, object));
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

    /**
     * Sanitize a string for use as a Cypher identifier (label, relationship
     * type, or property name).
     *
     * <p>This method escapes backticks and other special characters to
     * prevent Cypher injection attacks when the value is used in backtick-
     * quoted identifiers.</p>
     *
     * @param value the value to sanitize
     * @return the sanitized value safe for use in Cypher identifiers
     */
    private String sanitizeCypherIdentifier(final String value) {
        if (value == null) {
            return "";
        }
        // Escape backticks by doubling them (` -> ``)
        // Also remove any null characters which could cause issues
        return value.replace("`", "``").replace("\0", "");
    }

    /**
     * Check if the graph contains the given triple using optimized Cypher
     * queries.
     *
     * <p>This method is more efficient than the default implementation which
     * uses find() because it can short-circuit as soon as a match is found
     * without iterating over all results.</p>
     *
     * @param triple the triple to check for (may contain ANY wildcards)
     * @return true if the graph contains a matching triple
     */
    @Override
    protected boolean graphBaseContains(final Triple triple) {
        Span span = tracer.spanBuilder("FalkorDBGraph.contains")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "contains")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .setAttribute(ATTR_TRIPLE_SUBJECT, nodeToString(triple.getSubject()))
            .setAttribute(ATTR_TRIPLE_PREDICATE,
                nodeToString(triple.getPredicate()))
            .setAttribute(ATTR_TRIPLE_OBJECT, nodeToString(triple.getObject()))
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            boolean result = graphBaseContainsInternal(triple);
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
     * Internal implementation of graphBaseContains without tracing.
     */
    private boolean graphBaseContainsInternal(final Triple triple) {
        // If all parts are concrete, we can do an exact check
        // Otherwise, fall back to the default containsByFind
        if (!triple.isConcrete()) {
            return containsByFind(triple);
        }

        var subject = nodeToString(triple.getSubject());
        var predicate = nodeToString(triple.getPredicate());
        var params = new HashMap<String, Object>();
        String cypher;

        if (triple.getObject().isLiteral()) {
            // Check for literal property
            var objectValue = triple.getObject().getLiteralLexicalForm();
            params.put("subjectUri", subject);
            params.put("objectValue", objectValue);

            String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
            cypher = """
                MATCH (s:Resource {uri: $subjectUri})
                WHERE s.`%s` = $objectValue
                RETURN 1 LIMIT 1""".formatted(sanitizedPredicate);
        } else if (predicate.equals(RDF.type.getURI())) {
            // Check for rdf:type (label)
            var objectType = nodeToString(triple.getObject());
            params.put("subjectUri", subject);

            String sanitizedType = sanitizeCypherIdentifier(objectType);
            cypher = """
                MATCH (s:Resource:`%s` {uri: $subjectUri})
                RETURN 1 LIMIT 1""".formatted(sanitizedType);
        } else {
            // Check for relationship
            var object = nodeToString(triple.getObject());
            params.put("subjectUri", subject);
            params.put("objectUri", object);

            String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
            cypher = """
                MATCH (s:Resource {uri: $subjectUri})-[:`%s`]->(o:Resource {uri: $objectUri})
                RETURN 1 LIMIT 1""".formatted(sanitizedPredicate);
        }

        var result = graph.query(cypher, params);
        return result.iterator().hasNext();
    }

    /**
     * Return the size of the graph (number of triples) using optimized
     * Cypher queries.
     *
     * <p>This counts all triples stored in the graph:</p>
     * <ul>
     *   <li>Literal properties on Resource nodes (excluding 'uri')</li>
     *   <li>rdf:type triples (labels on Resource nodes, excluding
     *       'Resource')</li>
     *   <li>Relationships between Resource nodes</li>
     * </ul>
     *
     * @return the number of triples in the graph
     */
    @Override
    protected int graphBaseSize() {
        Span span = tracer.spanBuilder("FalkorDBGraph.size")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "size")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            int size = graphBaseSizeInternal();
            span.setAttribute(ATTR_RESULT_COUNT, (long) size);
            span.setStatus(StatusCode.OK);
            return size;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Internal implementation of graphBaseSize without tracing.
     */
    private int graphBaseSizeInternal() {
        long count = 0;

        // Count literal properties (excluding 'uri' and '__datatype' metadata properties)
        var propsResult = graph.query("""
            MATCH (s:Resource)
            UNWIND keys(s) AS key
            WITH key WHERE key <> 'uri' AND NOT key ENDS WITH '__datatype'
            RETURN count(key) AS cnt""");
        for (var record : propsResult) {
            Object value = record.getValue("cnt");
            if (value instanceof Number) {
                count += ((Number) value).longValue();
            }
        }

        // Count rdf:type triples (labels excluding 'Resource')
        var labelsResult = graph.query("""
            MATCH (s:Resource)
            UNWIND labels(s) AS lbl
            WITH lbl WHERE lbl <> 'Resource'
            RETURN count(lbl) AS cnt""");
        for (var record : labelsResult) {
            Object value = record.getValue("cnt");
            if (value instanceof Number) {
                count += ((Number) value).longValue();
            }
        }

        // Count relationships
        var relsResult = graph.query("""
            MATCH ()-[r]->()
            RETURN count(r) AS cnt""");
        for (var record : relsResult) {
            Object value = record.getValue("cnt");
            if (value instanceof Number) {
                count += ((Number) value).longValue();
            }
        }

        // Saturate to Integer.MAX_VALUE to avoid overflow
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    /**
     * Check if the graph is empty using an optimized Cypher query.
     *
     * <p>This is more efficient than the default implementation which
     * uses contains(Triple.ANY) because it can quickly check for the
     * existence of any RDF data without full iteration.</p>
     *
     * <p>The graph is considered empty if there are no:</p>
     * <ul>
     *   <li>Literal properties (properties other than 'uri')</li>
     *   <li>rdf:type triples (labels other than 'Resource')</li>
     *   <li>Relationships between Resource nodes</li>
     * </ul>
     *
     * @return true if the graph contains no triples
     */
    @Override
    public boolean isEmpty() {
        Span span = tracer.spanBuilder("FalkorDBGraph.isEmpty")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "isEmpty")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            boolean result = isEmptyInternal();
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
     * Internal implementation of isEmpty without tracing.
     */
    private boolean isEmptyInternal() {
        // Check for any properties (excluding 'uri')
        var propsResult = graph.query("""
            MATCH (s:Resource)
            UNWIND keys(s) AS key
            WITH key WHERE key <> 'uri'
            RETURN 1 LIMIT 1""");
        if (propsResult.iterator().hasNext()) {
            return false;
        }

        // Check for any labels (excluding 'Resource')
        var labelsResult = graph.query("""
            MATCH (s:Resource)
            UNWIND labels(s) AS lbl
            WITH lbl WHERE lbl <> 'Resource'
            RETURN 1 LIMIT 1""");
        if (labelsResult.iterator().hasNext()) {
            return false;
        }

        // Check for any relationships
        var relsResult = graph.query("""
            MATCH ()-[r]->()
            RETURN 1 LIMIT 1""");
        if (relsResult.iterator().hasNext()) {
            return false;
        }

        return true;
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
