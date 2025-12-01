package com.falkordb.jena;

import com.falkordb.Driver;
import com.falkordb.FalkorDB;
import com.falkordb.Graph;
import com.falkordb.Record;
import com.falkordb.ResultSet;
import java.lang.reflect.Method;
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
    /** Underlying FalkorDB graph instance. */
    private final Graph graph;
    /** Name of the FalkorDB graph in use. */
    private final String graphName;

    /** Cached reflection objects for OpenTelemetry - initialized lazily. */
    private static volatile Object tracer;
    private static volatile Method spanBuilderMethod;
    private static volatile Method startSpanMethod;
    private static volatile Method setAttributeMethod;
    private static volatile Method endSpanMethod;
    private static volatile Method makeCurrentMethod;
    private static volatile Method scopeCloseMethod;
    private static volatile boolean otelInitialized = false;
    private static volatile boolean otelAvailable = false;

    /**
     * Initialize OpenTelemetry reflection objects.
     * Uses the context classloader to access the agent's classes.
     */
    private static synchronized void initOtelReflection() {
        if (otelInitialized) {
            return;
        }
        otelInitialized = true;
        try {
            // Use context classloader to access agent's classes
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = FalkorDBGraph.class.getClassLoader();
            }
            
            // Get GlobalOpenTelemetry.get()
            Class<?> globalOtelClass = Class.forName("io.opentelemetry.api.GlobalOpenTelemetry", true, cl);
            Method getMethod = globalOtelClass.getMethod("get");
            Object otel = getMethod.invoke(null);
            
            // Get tracer
            Method getTracerMethod = otel.getClass().getMethod("getTracer", String.class);
            tracer = getTracerMethod.invoke(otel, "com.falkordb.jena");
            
            // Get spanBuilder method from tracer
            spanBuilderMethod = tracer.getClass().getMethod("spanBuilder", String.class);
            
            // Get SpanBuilder.startSpan method
            Class<?> spanBuilderClass = Class.forName("io.opentelemetry.api.trace.SpanBuilder", true, cl);
            startSpanMethod = spanBuilderClass.getMethod("startSpan");
            
            // Get Span methods
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span", true, cl);
            setAttributeMethod = spanClass.getMethod("setAttribute", String.class, String.class);
            endSpanMethod = spanClass.getMethod("end");
            makeCurrentMethod = spanClass.getMethod("makeCurrent");
            
            // Get Scope.close method
            Class<?> scopeClass = Class.forName("io.opentelemetry.context.Scope", true, cl);
            scopeCloseMethod = scopeClass.getMethod("close");
            
            otelAvailable = true;
            LOGGER.info("OpenTelemetry tracing initialized successfully via classloader: {}", cl);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("OpenTelemetry API not available: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize OpenTelemetry reflection: {}", e.getMessage(), e);
        }
    }

    /**
     * Executes an operation wrapped in a span with a custom attribute.
     * Creates its own span using GlobalOpenTelemetry.get().getTracer().
     */
    private static <T> T withSpan(String spanName, String attrKey, String attrValue, 
                                   java.util.function.Supplier<T> operation) {
        if (!otelInitialized) {
            initOtelReflection();
        }
        if (!otelAvailable) {
            return operation.get();
        }
        
        Object span = null;
        Object scope = null;
        try {
            // Create and start span: tracer.spanBuilder(spanName).startSpan()
            Object spanBuilder = spanBuilderMethod.invoke(tracer, spanName);
            span = startSpanMethod.invoke(spanBuilder);
            
            // Make span current: span.makeCurrent()
            scope = makeCurrentMethod.invoke(span);
            
            // Set attribute: span.setAttribute(key, value)
            setAttributeMethod.invoke(span, attrKey, attrValue);
            
            LOGGER.debug("Created span {} with {}={}", spanName, attrKey, attrValue);
            
            // Execute operation
            return operation.get();
        } catch (Exception e) {
            LOGGER.warn("Tracing error in {}: {}", spanName, e.getMessage());
            return operation.get();
        } finally {
            try {
                // Close scope: scope.close()
                if (scope != null) {
                    scopeCloseMethod.invoke(scope);
                }
                // End span: span.end()
                if (span != null) {
                    endSpanMethod.invoke(span);
                }
            } catch (Exception e) {
                LOGGER.debug("Error closing span: {}", e.getMessage());
            }
        }
    }

    /**
     * Executes a void operation wrapped in a span with a custom attribute.
     */
    private static void withSpanVoid(String spanName, String attrKey, String attrValue, Runnable operation) {
        withSpan(spanName, attrKey, attrValue, () -> {
            operation.run();
            return null;
        });
    }

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
        this.graph = this.driver.graph(name);
        this.graphName = name;
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
        withSpanVoid("FalkorDBGraph.performAdd", "triple", tripleToString(triple), () -> {
            performAddInternal(triple);
        });
    }

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
        withSpanVoid("FalkorDBGraph.performDelete", "triple", tripleToString(triple), () -> {
            performDeleteInternal(triple);
        });
    }

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
        return withSpan("FalkorDBGraph.graphBaseFind", "pattern", tripleToString(pattern), () -> {
            return graphBaseFindInternal(pattern);
        });
    }

    private ExtendedIterator<Triple> graphBaseFindInternal(final Triple pattern) {
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

        return WrappedIterator.create(triples.iterator());
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

    /**
     * Converts a Triple to a human-readable string for tracing.
     * This provides a cleaner representation than Triple.toString().
     *
     * @param triple the Triple to convert
     * @return formatted string representation
     */
    private String tripleToString(final Triple triple) {
        var subject = triple.getSubject().isConcrete()
            ? nodeToString(triple.getSubject()) : "?s";
        var predicate = triple.getPredicate().isConcrete()
            ? nodeToString(triple.getPredicate()) : "?p";
        String object;
        if (triple.getObject().isConcrete()) {
            if (triple.getObject().isLiteral()) {
                object = "\"" + nodeToString(triple.getObject()) + "\"";
            } else {
                object = nodeToString(triple.getObject());
            }
        } else {
            object = "?o";
        }
        return "(" + subject + " " + predicate + " " + object + ")";
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
