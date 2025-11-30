package com.falkordb.jena;

import com.falkordb.Driver;
import com.falkordb.FalkorDB;
import com.falkordb.Graph;
import com.falkordb.Record;
import com.falkordb.ResultSet;
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
        // Translate RDF triple to Cypher CREATE/MERGE
        String subject = nodeToString(triple.getSubject());
        String predicate = nodeToString(triple.getPredicate());

        Map<String, Object> params = new HashMap<>(2);
        String cypher;

        if (triple.getObject().isLiteral()) {
            // Store literal as a property on the subject node
            // Use backticks to allow URIs as property names directly
            String objectValue =
                triple.getObject().getLiteralLexicalForm();

            params.put("subjectUri", subject);
            params.put("objectValue", objectValue);

            cypher = String.format(
                "MERGE (s:Resource {uri: $subjectUri}) "
                    + "SET s.`%s` = $objectValue",
                predicate
            );
        } else if (predicate.equals(RDF.type.getURI())) {
            // Special handling for rdf:type - create node with type as label
            String object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);

            cypher = String.format(
                "MERGE (s:Resource:`%s` {uri: $subjectUri})",
                object
            );
        } else {
            // Create relationship for resource objects
            String object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);
            params.put("objectUri", object);

            cypher = String.format(
                "MERGE (s:Resource {uri: $subjectUri}) "
                    + "MERGE (o:Resource {uri: $objectUri}) "
                    + "MERGE (s)-[r:`%s`]->(o)",
                predicate
            );
        }

        graph.query(cypher, params);
    }

    /** Delete a triple from the backing FalkorDB graph. */
    @Override
    public void performDelete(final Triple triple) {
        String subject = nodeToString(triple.getSubject());
        String predicate = nodeToString(triple.getPredicate());

        Map<String, Object> params = new HashMap<>(2);
        String cypher;

        if (triple.getObject().isLiteral()) {
            // Remove property from the subject node
            // Use backticks to allow URIs as property names directly
            params.put("subjectUri", subject);

            cypher = String.format(
                "MATCH (s:Resource {uri: $subjectUri}) "
                    + "REMOVE s.`%s`",
                predicate
            );
        } else if (predicate.equals(RDF.type.getURI())) {
            // Special handling for rdf:type - remove label from node
            String object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);
            cypher = String.format(
                "MATCH (s:Resource:`%s` {uri: $subjectUri}) "
                    + "REMOVE s:`%s`",
                object, object
            );
        } else {
            String object = nodeToString(triple.getObject());

            params.put("subjectUri", subject);
            params.put("objectUri", object);

            cypher = String.format(
                "MATCH (s:Resource {uri: $subjectUri})-[r:`%s`]->"
                    + "(o:Resource {uri: $objectUri}) DELETE r",
                predicate
            );
        }

        graph.query(cypher, params);
    }

    /** Find triples matching the given pattern. */
    @Override
    protected ExtendedIterator<Triple> graphBaseFind(final Triple pattern) {
        List<Triple> triples = new ArrayList<>();

        // Check if this is an rdf:type query
        boolean isTypeQuery = !pattern.getPredicate().isConcrete()
            || pattern.getPredicate().getURI().equals(RDF.type.getURI());

        if (isTypeQuery) {
            // Query for rdf:type triples (nodes with labels)
            List<Triple> typeTriples = findTypeTriples(pattern);
            triples.addAll(typeTriples);
        }

        // Query for relationship-based triples (non-literal objects,
        // non-rdf:type)
        if (!pattern.getObject().isConcrete()
            || !pattern.getObject().isLiteral()) {
            if (!pattern.getPredicate().isConcrete()
                || !pattern.getPredicate().getURI().equals(
                    RDF.type.getURI())) {
                Map<String, Object> params = new HashMap<>(2);
                String cypherRels = buildCypherMatchRelationships(pattern,
                    params);
                ResultSet result = graph.query(cypherRels, params);

                for (Record record : result) {
                    Triple triple = recordToTriple(record);
                    triples.add(triple);
                }
            }
        }

        // Query for property-based triples (literal objects)
        if (!pattern.getObject().isConcrete()
            || pattern.getObject().isLiteral()) {
            List<Triple> propertyTriples = findPropertyTriples(pattern);
            triples.addAll(propertyTriples);
        }

        return WrappedIterator.create(triples.iterator());
    }

    private String buildCypherMatchRelationships(
            final Triple pattern,
            final Map<String, Object> params) {
        StringBuilder cypher = new StringBuilder("MATCH ");

        if (pattern.getSubject().isConcrete()) {
            params.put("subjectUri", nodeToString(pattern.getSubject()));
            cypher.append("(s:Resource {uri: $subjectUri})");
        } else {
            cypher.append("(s:Resource)");
        }

        if (pattern.getPredicate().isConcrete()) {
            cypher.append(String.format("-[r:`%s`]->",
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
        List<Triple> triples = new ArrayList<>();

        // Build query to get nodes with their properties as map
        Map<String, Object> params = new HashMap<>(1);
        StringBuilder cypher = new StringBuilder("MATCH ");

        if (pattern.getSubject().isConcrete()) {
            params.put("subjectUri", nodeToString(pattern.getSubject()));
            cypher.append("(s:Resource {uri: $subjectUri})");
        } else {
            cypher.append("(s:Resource)");
        }

        cypher.append(" RETURN s, properties(s) as props");

        ResultSet result = graph.query(cypher.toString(), params);

        for (Record record : result) {
            com.falkordb.graph_entities.Node node = record.getValue("s");
            String subjectUri = node.getProperty("uri").getValue().toString();
            Node subject = NodeFactory.createURI(subjectUri);

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>)
                record.getValue("props");

            // Iterate over all properties
            for (Map.Entry<String, Object> entry
                : properties.entrySet()) {
                String predicateUri = entry.getKey();

                // Skip the 'uri' property as it's not an RDF triple
                if ("uri".equals(predicateUri)) {
                    continue;
                }

                // Check if predicate matches pattern
                if (pattern.getPredicate().isConcrete()) {
                    String patternPredicate = nodeToString(
                        pattern.getPredicate());
                    if (!predicateUri.equals(patternPredicate)) {
                        continue;
                    }
                }

                String literalValue = entry.getValue().toString();

                // Check if object matches pattern
                if (pattern.getObject().isConcrete()) {
                    String patternObject = pattern.getObject()
                        .getLiteralLexicalForm();
                    if (!literalValue.equals(patternObject)) {
                        continue;
                    }
                }

                Node predicate = NodeFactory.createURI(predicateUri);
                Node object = NodeFactory.createLiteralString(literalValue);

                triples.add(Triple.create(subject, predicate, object));
            }
        }

        return triples;
    }

    private List<Triple> findTypeTriples(final Triple pattern) {
        List<Triple> triples = new ArrayList<>();

        // Build query to get nodes with their labels
        Map<String, Object> params = new HashMap<>(1);
        StringBuilder cypher = new StringBuilder("MATCH ");

        if (pattern.getSubject().isConcrete()) {
            params.put("subjectUri", nodeToString(pattern.getSubject()));
            cypher.append("(s {uri: $subjectUri})");
        } else {
            cypher.append("(s)");
        }

        cypher.append(" RETURN s, labels(s) as nodeLabels");

        ResultSet result = graph.query(cypher.toString(), params);

        for (Record record : result) {
            com.falkordb.graph_entities.Node node = record.getValue("s");
            String subjectUri = node.getProperty("uri").getValue().toString();
            Node subject = NodeFactory.createURI(subjectUri);

            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) record.getValue(
                "nodeLabels");

            // Create an rdf:type triple for each label (except "Resource")
            for (String label : labels) {
                if ("Resource".equals(label)) {
                    continue; // Skip the base Resource label
                }

                // Check if object matches pattern
                if (pattern.getObject().isConcrete()) {
                    String patternObject = nodeToString(
                        pattern.getObject());
                    if (!label.equals(patternObject)) {
                        continue;
                    }
                }

                Node predicate = NodeFactory.createURI(RDF.type.getURI());
                Node object = NodeFactory.createURI(label);

                triples.add(Triple.create(subject, predicate, object));
            }
        }

        return triples;
    }

    private Triple recordToTriple(final Record record) {
        com.falkordb.graph_entities.Node subjectNode = record.getValue("s");
        com.falkordb.graph_entities.Edge edge = record.getValue("r");
        com.falkordb.graph_entities.Node objectNode = record.getValue("o");

        String subjectUri = subjectNode.getProperty("uri").getValue()
            .toString();
        String predicateUri = edge.getRelationshipType();

        Node subject = NodeFactory.createURI(subjectUri);
        Node predicate = NodeFactory.createURI(predicateUri);

        Node object;
        // Check for literal by looking for 'value' property
        if (objectNode.getProperty("value") != null) {
            String literalValue = objectNode.getProperty("value")
                .getValue().toString();
            object = NodeFactory.createLiteralString(literalValue);
        } else {
            String objectUri = objectNode.getProperty("uri").getValue()
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
