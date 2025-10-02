package com.falkordb.jena;

import com.falkordb.Driver;
import com.falkordb.FalkorDB;
import com.falkordb.Graph;
import com.falkordb.Record;
import com.falkordb.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphBase;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.WrappedIterator;

/**
 * Jena Graph implementation backed by a FalkorDB graph.
 *
 * This class implements the minimal GraphBase hooks to translate Jena Triple
 * operations into FalkorDB (Cypher-like) queries using the JFalkorDB API.
 */
public final class FalkorDBGraph extends GraphBase {
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
        this.driver = FalkorDB.driver();
        this.graph = driver.graph(name);
        this.graphName = name;
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
        this.driver = FalkorDB.driver(host, port);
        this.graph = driver.graph(name);
        this.graphName = name;
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
     * Clear all nodes and relationships from the graph.
     */
    @Override
    public void clear() {
        // Delete all nodes and relationships
        graph.query("MATCH (n) DETACH DELETE n");
    }

    /**
     * Add a triple to the backing FalkorDB graph.
     *
     * This method translates a Jena Triple to a FalkorDB/Cypher create query.
     */
    @Override
    public void performAdd(final Triple triple) {
        // Translate RDF triple to Cypher CREATE/MERGE
        String subject = nodeToString(triple.getSubject());
        String predicate = nodeToString(triple.getPredicate());

        String cypher;
        if (triple.getObject().isLiteral()) {
            String objectValue = triple.getObject().getLiteralLexicalForm();
            cypher = String.format(
                "MERGE (s:Resource {uri: '%s'}) "
                + "MERGE (o:Literal {value: '%s'}) "
                + "MERGE (s)-[r:%s]->(o)",
                escapeCypher(subject), escapeCypher(objectValue),
                sanitizeRelationType(predicate)
            );
        } else {
            String object = nodeToString(triple.getObject());
            cypher = String.format(
                "MERGE (s:Resource {uri: '%s'}) "
                + "MERGE (o:Resource {uri: '%s'}) "
                + "MERGE (s)-[r:%s]->(o)",
                escapeCypher(subject), escapeCypher(object),
                sanitizeRelationType(predicate)
            );
        }

        graph.query(cypher);
    }

    /** Delete a triple from the backing FalkorDB graph. */
    @Override
    public void performDelete(final Triple triple) {
        String subject = nodeToString(triple.getSubject());
        String predicate = nodeToString(triple.getPredicate());

        String cypher;
        if (triple.getObject().isLiteral()) {
            String objectValue = triple.getObject().getLiteralLexicalForm();
            cypher = String.format(
                "MATCH (s:Resource {uri: '%s'})-[r:%s]->"
                + "(o:Literal {value: '%s'}) DELETE r",
                escapeCypher(subject), sanitizeRelationType(predicate),
                escapeCypher(objectValue)
            );
        } else {
            String object = nodeToString(triple.getObject());
            cypher = String.format(
                "MATCH (s:Resource {uri: '%s'})-[r:%s]->"
                + "(o:Resource {uri: '%s'}) DELETE r",
                escapeCypher(subject), sanitizeRelationType(predicate),
                escapeCypher(object)
            );
        }

        graph.query(cypher);
    }

    /** Find triples matching the given pattern. */
    @Override
    protected ExtendedIterator<Triple> graphBaseFind(final Triple pattern) {
        String cypher = buildCypherMatch(pattern);

        ResultSet result = graph.query(cypher);
        List<Triple> triples = new ArrayList<>();

        for (Record record : result) {
            Triple triple = recordToTriple(record);
            triples.add(triple);
        }

        return WrappedIterator.create(triples.iterator());
    }

    private String buildCypherMatch(final Triple pattern) {
        StringBuilder cypher = new StringBuilder("MATCH ");

        if (pattern.getSubject().isConcrete()) {
            cypher.append(String.format("(s:Resource {uri: '%s'})",
                escapeCypher(nodeToString(pattern.getSubject()))));
        } else {
            cypher.append("(s:Resource)");
        }

        if (pattern.getPredicate().isConcrete()) {
            cypher.append(String.format("-[r:%s]->",
                sanitizeRelationType(nodeToString(pattern.getPredicate()))));
        } else {
            cypher.append("-[r]->");
        }

        if (pattern.getObject().isConcrete()) {
            if (pattern.getObject().isLiteral()) {
                cypher.append(String.format(
                    "(o:Literal {value: '%s'})",
                    escapeCypher(pattern.getObject().getLiteralLexicalForm())
                ));
            } else {
                cypher.append(String.format(
                    "(o:Resource {uri: '%s'})",
                    escapeCypher(nodeToString(pattern.getObject()))
                ));
            }
        } else {
            cypher.append("(o)");
        }

        cypher.append(" RETURN s, r, o");
        return cypher.toString();
    }

    private Triple recordToTriple(final Record record) {
        com.falkordb.graph_entities.Node subjectNode =
            record.getValue("s");
        com.falkordb.graph_entities.Edge edge = record.getValue("r");
        com.falkordb.graph_entities.Node objectNode =
            record.getValue("o");

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
            object = NodeFactory.createLiteral(literalValue);
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

    private String sanitizeRelationType(final String uri) {
        // Convert URI to valid FalkorDB relationship type
        // Use base64 encoding to ensure uniqueness and avoid collisions
        return "rel_" + java.util.Base64.getEncoder()
            .encodeToString(
                uri.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String escapeCypher(final String value) {
        return value.replace("'", "\\'");
    }

    @Override
    public void close() {
        try {
            driver.close();
        } catch (Exception e) {
            System.err.println(
                "Error closing FalkorDB driver: " + e.getMessage()
            );
        }
        super.close();
    }
}
