package com.falkordb.jena.query;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles SPARQL Basic Graph Patterns (BGPs) to Cypher queries.
 *
 * <p>This compiler translates a list of SPARQL triple patterns into a single
 * Cypher MATCH query that can be executed natively on FalkorDB. This enables
 * query pushdown, avoiding the N+1 query problem inherent in triple-by-triple
 * evaluation.</p>
 *
 * <h2>Translation Strategy:</h2>
 * <ul>
 *   <li>SPARQL subjects become Cypher nodes with `:Resource` label</li>
 *   <li>SPARQL predicates become Cypher relationship types or property names</li>
 *   <li>SPARQL URI objects become Cypher nodes</li>
 *   <li>SPARQL literal objects become property matches</li>
 *   <li>SPARQL variables become Cypher identifiers</li>
 *   <li>rdf:type predicates become label matches</li>
 * </ul>
 *
 * <h2>Example:</h2>
 * <pre>{@code
 * // SPARQL BGP:
 * // ?person foaf:name ?name .
 * // ?person foaf:knows ?friend .
 *
 * // Compiled Cypher:
 * // MATCH (person:Resource)
 * // WHERE person.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
 * // MATCH (person)-[:`http://xmlns.com/foaf/0.1/knows`]->(friend:Resource)
 * // RETURN person.uri AS person, person.`...name` AS name, friend.uri AS friend
 * }</pre>
 */
public final class SparqlToCypherCompiler {

    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        SparqlToCypherCompiler.class);

    /** RDF type URI for special handling. */
    private static final String RDF_TYPE_URI = RDF.type.getURI();

    /**
     * Result of compiling a BGP to Cypher.
     *
     * @param cypherQuery the compiled Cypher query
     * @param parameters the query parameters (for parameterized queries)
     * @param variableMapping mapping from SPARQL variable names to Cypher expressions
     */
    public record CompilationResult(
            String cypherQuery,
            Map<String, Object> parameters,
            Map<String, String> variableMapping) {
    }

    /**
     * Exception thrown when a BGP cannot be compiled to Cypher.
     */
    public static class CannotCompileException extends Exception {
        /**
         * Constructs a new CannotCompileException.
         *
         * @param message the detail message
         */
        public CannotCompileException(final String message) {
            super(message);
        }
    }

    /** Private constructor to prevent instantiation. */
    private SparqlToCypherCompiler() {
        // Utility class
    }

    /**
     * Translate a SPARQL Basic Graph Pattern to a Cypher query.
     *
     * @param bgp the Basic Graph Pattern to translate
     * @return the compilation result containing Cypher query and metadata
     * @throws CannotCompileException if the BGP cannot be compiled
     */
    public static CompilationResult translate(final BasicPattern bgp)
            throws CannotCompileException {
        if (bgp == null || bgp.isEmpty()) {
            throw new CannotCompileException("Empty BGP cannot be compiled");
        }

        List<Triple> triples = bgp.getList();
        
        // Classify triples and collect variables
        List<Triple> relationshipTriples = new ArrayList<>();
        List<Triple> literalTriples = new ArrayList<>();
        List<Triple> typeTriples = new ArrayList<>();
        Set<String> allVariables = new HashSet<>();
        Set<String> nodeVariables = new HashSet<>();
        Map<String, Object> parameters = new HashMap<>();
        int paramCounter = 0;

        for (Triple triple : triples) {
            collectVariables(triple, allVariables, nodeVariables);
            
            Node predicate = triple.getPredicate();
            Node object = triple.getObject();

            if (predicate.isVariable()) {
                // Variable predicates are complex - fall back
                throw new CannotCompileException(
                    "Variable predicates are not supported for pushdown");
            }

            if (predicate.isURI() && predicate.getURI().equals(RDF_TYPE_URI)) {
                typeTriples.add(triple);
            } else if (object.isLiteral()) {
                literalTriples.add(triple);
            } else if (object.isURI()) {
                // Concrete URI object - definitely a relationship
                relationshipTriples.add(triple);
            } else if (object.isVariable()) {
                // Variable object - could be literal or resource
                // For now, fall back to standard evaluation for these
                throw new CannotCompileException(
                    "Variable objects (may be literal or resource) are not yet supported for pushdown");
            } else {
                relationshipTriples.add(triple);
            }
        }

        // Build the Cypher query
        StringBuilder cypher = new StringBuilder();
        Map<String, String> variableMapping = new HashMap<>();

        // Build MATCH clauses for relationship patterns
        Set<String> declaredNodes = new HashSet<>();
        
        for (Triple triple : relationshipTriples) {
            String subjectVar = getNodeVariable(triple.getSubject());
            String objectVar = getNodeVariable(triple.getObject());
            String relType = sanitizeCypherIdentifier(
                triple.getPredicate().getURI());

            if (!cypher.isEmpty()) {
                cypher.append("\n");
            }
            cypher.append("MATCH ");

            // Subject
            if (triple.getSubject().isVariable()) {
                if (declaredNodes.contains(subjectVar)) {
                    cypher.append("(").append(subjectVar).append(")");
                } else {
                    cypher.append("(").append(subjectVar).append(":Resource)");
                    declaredNodes.add(subjectVar);
                }
            } else {
                String paramName = "p" + paramCounter++;
                parameters.put(paramName, triple.getSubject().getURI());
                if (declaredNodes.contains(subjectVar)) {
                    cypher.append("(").append(subjectVar).append(")");
                } else {
                    cypher.append("(").append(subjectVar)
                          .append(":Resource {uri: $").append(paramName)
                          .append("})");
                    declaredNodes.add(subjectVar);
                }
            }

            // Relationship
            cypher.append("-[:`").append(relType).append("`]->");

            // Object
            if (triple.getObject().isVariable()) {
                if (declaredNodes.contains(objectVar)) {
                    cypher.append("(").append(objectVar).append(")");
                } else {
                    cypher.append("(").append(objectVar).append(":Resource)");
                    declaredNodes.add(objectVar);
                }
            } else if (triple.getObject().isURI()) {
                String paramName = "p" + paramCounter++;
                parameters.put(paramName, triple.getObject().getURI());
                if (declaredNodes.contains(objectVar)) {
                    cypher.append("(").append(objectVar).append(")");
                } else {
                    cypher.append("(").append(objectVar)
                          .append(":Resource {uri: $").append(paramName)
                          .append("})");
                    declaredNodes.add(objectVar);
                }
            }
        }

        // Build MATCH clauses for type patterns
        for (Triple triple : typeTriples) {
            String subjectVar = getNodeVariable(triple.getSubject());

            if (!cypher.isEmpty()) {
                cypher.append("\n");
            }

            if (triple.getObject().isVariable()) {
                // Variable type - need to return labels
                throw new CannotCompileException(
                    "Variable types in rdf:type are not fully supported");
            } else if (triple.getObject().isURI()) {
                String typeLabel = sanitizeCypherIdentifier(
                    triple.getObject().getURI());
                
                cypher.append("MATCH ");
                if (triple.getSubject().isVariable()) {
                    if (declaredNodes.contains(subjectVar)) {
                        cypher.append("(").append(subjectVar)
                              .append(":`").append(typeLabel).append("`)");
                    } else {
                        cypher.append("(").append(subjectVar)
                              .append(":Resource:`").append(typeLabel)
                              .append("`)");
                        declaredNodes.add(subjectVar);
                    }
                } else {
                    String paramName = "p" + paramCounter++;
                    parameters.put(paramName, triple.getSubject().getURI());
                    cypher.append("(").append(subjectVar)
                          .append(":Resource:`").append(typeLabel)
                          .append("` {uri: $").append(paramName).append("})");
                    declaredNodes.add(subjectVar);
                }
            }
        }

        // Handle literal property patterns
        for (Triple triple : literalTriples) {
            String subjectVar = getNodeVariable(triple.getSubject());
            String predUri = sanitizeCypherIdentifier(
                triple.getPredicate().getURI());

            if (!declaredNodes.contains(subjectVar)) {
                if (!cypher.isEmpty()) {
                    cypher.append("\n");
                }
                cypher.append("MATCH ");
                if (triple.getSubject().isVariable()) {
                    cypher.append("(").append(subjectVar).append(":Resource)");
                } else {
                    String paramName = "p" + paramCounter++;
                    parameters.put(paramName, triple.getSubject().getURI());
                    cypher.append("(").append(subjectVar)
                          .append(":Resource {uri: $").append(paramName)
                          .append("})");
                }
                declaredNodes.add(subjectVar);
            }

            // Add WHERE clause for literal match
            if (!cypher.isEmpty()) {
                cypher.append("\n");
            }
            
            if (triple.getObject().isVariable()) {
                // Variable literal - just check property exists
                cypher.append("WHERE ").append(subjectVar)
                      .append(".`").append(predUri).append("` IS NOT NULL");
                // Map variable to property expression
                String objVar = triple.getObject().getName();
                variableMapping.put(objVar, 
                    subjectVar + ".`" + predUri + "`");
            } else {
                // Concrete literal - match value
                String paramName = "p" + paramCounter++;
                parameters.put(paramName, 
                    triple.getObject().getLiteralLexicalForm());
                cypher.append("WHERE ").append(subjectVar)
                      .append(".`").append(predUri).append("` = $")
                      .append(paramName);
            }
        }

        // If no MATCH clauses were generated but we have variables, 
        // we need to handle standalone node queries
        if (cypher.isEmpty() && !nodeVariables.isEmpty()) {
            for (String nodeVar : nodeVariables) {
                if (!declaredNodes.contains(nodeVar)) {
                    if (!cypher.isEmpty()) {
                        cypher.append("\n");
                    }
                    cypher.append("MATCH (").append(nodeVar)
                          .append(":Resource)");
                    declaredNodes.add(nodeVar);
                }
            }
        }

        // Build RETURN clause
        cypher.append("\nRETURN ");
        List<String> returnParts = new ArrayList<>();

        for (String varName : allVariables) {
            if (variableMapping.containsKey(varName)) {
                // Literal variable - return the property expression
                returnParts.add(variableMapping.get(varName) 
                    + " AS " + varName);
            } else if (nodeVariables.contains(varName)) {
                // Node variable - return the URI
                returnParts.add(varName + ".uri AS " + varName);
            }
        }

        if (returnParts.isEmpty()) {
            // No variables to return - return 1 as a marker
            returnParts.add("1 AS _result");
        }

        cypher.append(String.join(", ", returnParts));

        String query = cypher.toString();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Compiled BGP to Cypher:\n{}", query);
        }

        return new CompilationResult(query, parameters, variableMapping);
    }

    /**
     * Collect variables from a triple pattern.
     */
    private static void collectVariables(
            final Triple triple,
            final Set<String> allVariables,
            final Set<String> nodeVariables) {
        if (triple.getSubject().isVariable()) {
            String name = triple.getSubject().getName();
            allVariables.add(name);
            nodeVariables.add(name);
        }
        if (triple.getPredicate().isVariable()) {
            allVariables.add(triple.getPredicate().getName());
        }
        if (triple.getObject().isVariable()) {
            String name = triple.getObject().getName();
            allVariables.add(name);
            // Object variables might be nodes or literals
            // We'll classify them based on the predicate
            if (!triple.getPredicate().isURI() 
                || !triple.getPredicate().getURI().equals(RDF_TYPE_URI)) {
                // For non-type predicates, object might be a node
                // We'll check if it's used in a literal context
            }
            nodeVariables.add(name);
        }
    }

    /**
     * Get a Cypher-safe variable name for a node.
     */
    private static String getNodeVariable(final Node node) {
        if (node.isVariable()) {
            return sanitizeVariableName(node.getName());
        } else if (node.isURI()) {
            // Generate a unique name for concrete URIs
            return "_n" + Math.abs(node.getURI().hashCode());
        } else if (node.isBlank()) {
            return "_b" + node.getBlankNodeLabel().replace("-", "_");
        }
        return "_node";
    }

    /**
     * Sanitize a SPARQL variable name for use in Cypher.
     */
    private static String sanitizeVariableName(final String name) {
        // Cypher identifiers can contain letters, digits, and underscores
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Sanitize a string for use as a Cypher identifier.
     */
    private static String sanitizeCypherIdentifier(final String value) {
        if (value == null) {
            return "";
        }
        // Escape backticks by doubling them
        return value.replace("`", "``").replace("\0", "");
    }
}
