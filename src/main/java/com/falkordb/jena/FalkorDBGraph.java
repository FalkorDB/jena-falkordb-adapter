package com.falkordb.jena;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphBase;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.WrappedIterator;
import com.falkordb.FalkorDB;
import com.falkordb.Driver;
import com.falkordb.Graph;
import com.falkordb.Record;
import com.falkordb.ResultSet;
import org.apache.jena.graph.NodeFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Jena Graph implementation backed by a FalkorDB graph via the JFalkorDB driver.
 * <p>
 * This class implements the minimal GraphBase hooks to translate Jena Triple
 * operations into FalkorDB (Cypher-like) queries using the JFalkorDB API.
 */
public class FalkorDBGraph extends GraphBase {
    private final Driver driver;
    /** Underlying FalkorDB driver instance */
    private final Graph graph;
    /** Name of the FalkorDB graph in use */
    private final String graphName;
    
    /**
     * Create a FalkorDB-backed graph for the given graph name using the
     * default driver configuration (typically localhost:6379).
     *
    * @param graphName name of the FalkorDB graph to use
     */
    public FalkorDBGraph(final String graphName) {
        this.driver = FalkorDB.driver();
        this.graph = driver.graph(graphName);
        this.graphName = graphName;
    }
    
    /**
     * Create a FalkorDB-backed graph using an explicit host and port.
     *
    * @param hostParam FalkorDB host
    * @param portParam FalkorDB port
    * @param graphName name of the FalkorDB graph to use
     */
    public FalkorDBGraph(final String hostParam, final int portParam, final String graphName) {
        this.driver = FalkorDB.driver();
        this.graph = driver.graph(graphName);
        this.graphName = graphName;
    }
    
    @Override
    /**
     * Add a triple to the backing FalkorDB graph.
     * <p>
     * This method translates a Jena Triple to a FalkorDB/Cypher create query.
     */
    public void performAdd(final Triple triple) {
        // Translate RDF triple to Cypher CREATE/MERGE
        String subject = nodeToString(triple.getSubject());
        String predicate = nodeToString(triple.getPredicate());
        String object = nodeToString(triple.getObject());
        
        String cypher = String.format(
            "MERGE (s:Resource {uri: '%s'}) "
            + "MERGE (o:Resource {uri: '%s'}) "
            + "MERGE (s)-[r:%s]->(o)",
            escapeCypher(subject), escapeCypher(object),
            sanitizeRelationType(predicate)
        );
        
        graph.query(cypher);
    }
    
    @Override
    /**
     * Delete a triple from the backing FalkorDB graph.
     */
    public void performDelete(final Triple triple) {
        // Translate to Cypher DELETE
        String subject = nodeToString(triple.getSubject());
        String predicate = nodeToString(triple.getPredicate());
        String object = nodeToString(triple.getObject());
        
        String cypher = String.format(
            "MATCH (s:Resource {uri: '%s'})-[r:%s]->(o:Resource {uri: '%s'}) "
            + "DELETE r",
            escapeCypher(subject), sanitizeRelationType(predicate),
            escapeCypher(object)
        );
        
        graph.query(cypher);
    }
    
    @Override
    /**
     * Find triples matching the given pattern.
     */
    protected ExtendedIterator<Triple> graphBaseFind(final Triple pattern) {
        // Translate triple pattern to Cypher MATCH query
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
        
        // Build subject part
        if (pattern.getSubject().isConcrete()) {
            cypher.append(String.format("(s:Resource {uri: '%s'})",
                escapeCypher(nodeToString(pattern.getSubject()))));
        } else {
            cypher.append("(s:Resource)");
        }
        
        // Build predicate part
        if (pattern.getPredicate().isConcrete()) {
            cypher.append(String.format("-[r:%s]->",
                sanitizeRelationType(nodeToString(pattern.getPredicate()))));
        } else {
            cypher.append("-[r]->");
        }
        
        // Build object part
        if (pattern.getObject().isConcrete()) {
            if (pattern.getObject().isLiteral()) {
                cypher.append(String.format("(o:Literal {value: '%s'})",
                    escapeCypher(pattern.getObject().getLiteralLexicalForm())));
            } else {
                cypher.append(String.format("(o:Resource {uri: '%s'})",
                    escapeCypher(nodeToString(pattern.getObject()))));
            }
        } else {
            cypher.append("(o)");
        }
        
        cypher.append(" RETURN s, r, o");
        return cypher.toString();
    }
    
    private Triple recordToTriple(final Record record) {
        // Convert FalkorDB record back to Jena triple
        com.falkordb.graph_entities.Node subjectNode = record.getValue("s");
        com.falkordb.graph_entities.Edge edge = record.getValue("r");
        com.falkordb.graph_entities.Node objectNode = record.getValue("o");
        // Extract URI from node properties
        String subjectUri = subjectNode.getProperty("uri").getValue().toString();
        String predicateUri = edge.getRelationshipType();
        String objectValue = objectNode.getProperty("uri").getValue().toString();
        
        Node subject = NodeFactory.createURI(subjectUri);
        Node predicate = NodeFactory.createURI(predicateUri);
        Node object = NodeFactory.createURI(objectValue); // TODO: handle literals properly
        
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
        // Remove special characters, keep only alphanumeric and underscore
        return uri.replaceAll("[^a-zA-Z0-9_]", "_");
    }
    
    private String escapeCypher(final String value) {
        // Escape single quotes for Cypher
        return value.replace("'", "\\'");
    }
    
    @Override
    public void close() {
        try {
            driver.close();
        } catch (Exception e) {
            // Log the error but don't throw
            System.err.println("Error closing FalkorDB driver: " + e.getMessage());
        }
        super.close();
    }
}