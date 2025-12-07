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
 *   <li>Variable objects query both relationships and properties using UNION</li>
 *   <li>Variable predicates query all relationships using type(r)</li>
 * </ul>
 *
 * <h2>Example with variable object:</h2>
 * <pre>{@code
 * // SPARQL BGP:
 * // ?person foaf:knows ?friend .
 *
 * // Compiled Cypher:
 * // MATCH (person:Resource)-[:`http://xmlns.com/foaf/0.1/knows`]->(friend:Resource)
 * // RETURN person.uri AS person, friend.uri AS friend
 * }</pre>
 *
 * <h2>Example with variable predicate:</h2>
 * <pre>{@code
 * // SPARQL BGP:
 * // ?s ?p ?o .
 *
 * // Compiled Cypher (using UNION for relationships and properties):
 * // MATCH (s:Resource)-[r]->(o:Resource)
 * // RETURN s.uri AS s, type(r) AS p, o.uri AS o
 * // UNION ALL
 * // MATCH (s:Resource)
 * // UNWIND keys(s) AS propKey
 * // WITH s, propKey WHERE propKey <> 'uri'
 * // RETURN s.uri AS s, propKey AS p, s[propKey] AS o
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
        
        // Check if we have variable predicates - handle with special logic
        boolean hasVariablePredicate = triples.stream()
            .anyMatch(t -> t.getPredicate().isVariable());
        
        if (hasVariablePredicate) {
            return translateWithVariablePredicates(triples);
        }
        
        // Check for single-triple patterns with variable objects
        // that aren't used as subjects (potential properties or relationships)
        if (triples.size() == 1) {
            Triple triple = triples.get(0);
            Node object = triple.getObject();
            
            // Check if object is variable and not used as subject
            if (object.isVariable() && 
                !triple.getPredicate().isVariable() &&
                !triple.getPredicate().getURI().equals(RDF_TYPE_URI)) {
                
                // This is a variable object that could be either a relationship or property
                return translateWithVariableObject(triple);
            }
        }

        // Classify triples and collect variables
        List<Triple> relationshipTriples = new ArrayList<>();
        List<Triple> literalTriples = new ArrayList<>();
        List<Triple> typeTriples = new ArrayList<>();
        List<Triple> variableObjectRelTriples = new ArrayList<>();
        List<Triple> variableObjectTriples = new ArrayList<>();
        Set<String> allVariables = new HashSet<>();
        Set<String> nodeVariables = new HashSet<>();
        Map<String, Object> parameters = new HashMap<>();
        int paramCounter = 0;

        // First pass: identify which variable objects are used as subjects
        // in other triples (indicating they're resources, not literals)
        Set<String> resourceVariables = new HashSet<>();
        for (Triple triple : triples) {
            if (triple.getSubject().isVariable()) {
                resourceVariables.add(triple.getSubject().getName());
            }
        }

        for (Triple triple : triples) {
            collectVariables(triple, allVariables, nodeVariables);
            
            Node predicate = triple.getPredicate();
            Node object = triple.getObject();

            if (predicate.isURI() && predicate.getURI().equals(RDF_TYPE_URI)) {
                typeTriples.add(triple);
            } else if (object.isLiteral()) {
                literalTriples.add(triple);
            } else if (object.isURI()) {
                // Concrete URI object - definitely a relationship
                relationshipTriples.add(triple);
            } else if (object.isVariable()) {
                // Variable object - check if it's used as a subject elsewhere
                // (indicating it's a resource, not a literal)
                if (resourceVariables.contains(object.getName())) {
                    // Used as subject in another triple - treat as relationship
                    variableObjectRelTriples.add(triple);
                } else {
                    // Variable object that might be either a relationship or property
                    // These will be handled with UNION queries
                    variableObjectTriples.add(triple);
                }
            } else {
                relationshipTriples.add(triple);
            }
        }

        // Variable object patterns can only be handled for single-triple BGPs
        // (already handled above in the single-triple check)
        // Multi-triple patterns with variable objects need standard evaluation
        if (!variableObjectTriples.isEmpty()) {
            throw new CannotCompileException(
                "Multi-triple patterns with variable objects not yet supported for pushdown");
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
            appendSubjectNode(cypher, triple.getSubject(), subjectVar, 
                declaredNodes, parameters, paramCounter);
            if (triple.getSubject().isURI()) {
                paramCounter++;
            }

            // Relationship
            cypher.append("-[:`").append(relType).append("`]->");

            // Object
            appendObjectNode(cypher, triple.getObject(), objectVar,
                declaredNodes, parameters, paramCounter);
            if (triple.getObject().isURI()) {
                paramCounter++;
            }
        }

        // Build MATCH clauses for variable object relationship patterns
        // (where the variable is used as subject in another triple)
        for (Triple triple : variableObjectRelTriples) {
            String subjectVar = getNodeVariable(triple.getSubject());
            String objectVar = getNodeVariable(triple.getObject());
            String relType = sanitizeCypherIdentifier(
                triple.getPredicate().getURI());

            if (!cypher.isEmpty()) {
                cypher.append("\n");
            }
            cypher.append("MATCH ");

            // Subject
            appendSubjectNode(cypher, triple.getSubject(), subjectVar,
                declaredNodes, parameters, paramCounter);
            if (triple.getSubject().isURI()) {
                paramCounter++;
            }

            // Relationship
            cypher.append("-[:`").append(relType).append("`]->");

            // Variable object - treat as resource node
            if (declaredNodes.contains(objectVar)) {
                cypher.append("(").append(objectVar).append(")");
            } else {
                cypher.append("(").append(objectVar).append(":Resource)");
                declaredNodes.add(objectVar);
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
     * Translate a BGP with variable predicates to Cypher.
     * Uses UNION to query both relationships and properties.
     */
    private static CompilationResult translateWithVariablePredicates(
            final List<Triple> triples) throws CannotCompileException {
        
        // For now, only support single triple with variable predicate
        if (triples.size() != 1) {
            throw new CannotCompileException(
                "Multiple triples with variable predicates not yet supported");
        }

        Triple triple = triples.get(0);
        Map<String, Object> parameters = new HashMap<>();
        Map<String, String> variableMapping = new HashMap<>();
        int paramCounter = 0;

        String subjectVar = getNodeVariable(triple.getSubject());
        String predicateVar = triple.getPredicate().getName();
        String objectVar = getNodeVariable(triple.getObject());

        StringBuilder cypher = new StringBuilder();

        // Part 1: Query relationships
        cypher.append("MATCH ");
        
        // Subject
        if (triple.getSubject().isVariable()) {
            cypher.append("(").append(subjectVar).append(":Resource)");
        } else {
            String paramName = "p" + paramCounter++;
            parameters.put(paramName, triple.getSubject().getURI());
            cypher.append("(").append(subjectVar)
                  .append(":Resource {uri: $").append(paramName).append("})");
        }

        // Relationship with variable type
        cypher.append("-[_r]->");

        // Object
        if (triple.getObject().isVariable()) {
            cypher.append("(").append(objectVar).append(":Resource)");
        } else if (triple.getObject().isURI()) {
            String paramName = "p" + paramCounter++;
            parameters.put(paramName, triple.getObject().getURI());
            cypher.append("(").append(objectVar)
                  .append(":Resource {uri: $").append(paramName).append("})");
        } else {
            throw new CannotCompileException(
                "Literal objects with variable predicates not supported");
        }

        cypher.append("\nRETURN ");
        cypher.append(subjectVar).append(".uri AS ").append(
            triple.getSubject().isVariable() ? triple.getSubject().getName() : "_s");
        cypher.append(", type(_r) AS ").append(predicateVar);
        cypher.append(", ").append(objectVar).append(".uri AS ").append(
            triple.getObject().isVariable() ? triple.getObject().getName() : "_o");

        // Part 2: Query properties (using UNION ALL)
        cypher.append("\nUNION ALL\n");
        cypher.append("MATCH ");
        
        // Subject for properties
        if (triple.getSubject().isVariable()) {
            cypher.append("(").append(subjectVar).append(":Resource)");
        } else {
            // Reuse the parameter from above
            cypher.append("(").append(subjectVar)
                  .append(":Resource {uri: $p0})");
        }

        cypher.append("\nUNWIND keys(").append(subjectVar).append(") AS _propKey");
        cypher.append("\nWITH ").append(subjectVar)
              .append(", _propKey WHERE _propKey <> 'uri'");
        
        // Filter by object value if concrete
        if (triple.getObject().isLiteral()) {
            String paramName = "p" + paramCounter++;
            parameters.put(paramName, triple.getObject().getLiteralLexicalForm());
            cypher.append(" AND ").append(subjectVar)
                  .append("[_propKey] = $").append(paramName);
        }

        cypher.append("\nRETURN ");
        cypher.append(subjectVar).append(".uri AS ").append(
            triple.getSubject().isVariable() ? triple.getSubject().getName() : "_s");
        cypher.append(", _propKey AS ").append(predicateVar);
        cypher.append(", ").append(subjectVar).append("[_propKey] AS ").append(
            triple.getObject().isVariable() ? triple.getObject().getName() : "_o");

        // Part 3: Query rdf:type from labels (using UNION ALL)
        cypher.append("\nUNION ALL\n");
        cypher.append("MATCH ");
        
        // Subject for types
        if (triple.getSubject().isVariable()) {
            cypher.append("(").append(subjectVar).append(":Resource)");
        } else {
            // Reuse the parameter from above
            cypher.append("(").append(subjectVar)
                  .append(":Resource {uri: $p0})");
        }
        
        cypher.append("\nUNWIND labels(").append(subjectVar).append(") AS _label");
        cypher.append("\nWITH ").append(subjectVar)
              .append(", _label WHERE _label <> 'Resource'");
        
        // Filter by object value (type) if concrete
        if (triple.getObject().isURI()) {
            String paramName = "p" + paramCounter++;
            parameters.put(paramName, triple.getObject().getURI());
            cypher.append(" AND _label = $").append(paramName);
        }
        
        cypher.append("\nRETURN ");
        cypher.append(subjectVar).append(".uri AS ").append(
            triple.getSubject().isVariable() ? triple.getSubject().getName() : "_s");
        cypher.append(", '").append(RDF_TYPE_URI).append("' AS ").append(predicateVar);
        cypher.append(", _label AS ").append(
            triple.getObject().isVariable() ? triple.getObject().getName() : "_o");

        String query = cypher.toString();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Compiled BGP with variable predicate to Cypher:\n{}", 
                query);
        }

        return new CompilationResult(query, parameters, variableMapping);
    }

    /**
     * Translate a single triple pattern with a variable object to Cypher.
     * The variable object could be either a URI (relationship target) or a literal (property value).
     * Uses UNION to query both relationships and properties.
     *
     * @param triple the triple pattern with variable object
     * @return the compilation result
     * @throws CannotCompileException if the pattern cannot be compiled
     */
    private static CompilationResult translateWithVariableObject(
            final Triple triple) throws CannotCompileException {
        
        Map<String, Object> parameters = new HashMap<>();
        Map<String, String> variableMapping = new HashMap<>();
        int paramCounter = 0;

        String subjectVar = getNodeVariable(triple.getSubject());
        String predicateUri = triple.getPredicate().getURI();
        String objectVar = triple.getObject().getName();

        StringBuilder cypher = new StringBuilder();

        // Part 1: Query relationships (object is a Resource node)
        cypher.append("MATCH ");
        
        // Subject
        if (triple.getSubject().isVariable()) {
            cypher.append("(").append(subjectVar).append(":Resource)");
        } else {
            String paramName = "p" + paramCounter++;
            parameters.put(paramName, triple.getSubject().getURI());
            cypher.append("(").append(subjectVar)
                  .append(":Resource {uri: $").append(paramName).append("})");
        }

        // Relationship with concrete predicate
        String relType = sanitizeCypherIdentifier(predicateUri);
        cypher.append("-[:`").append(relType).append("`]->");

        // Variable object as Resource node
        cypher.append("(").append(objectVar).append(":Resource)");

        cypher.append("\nRETURN ");
        if (triple.getSubject().isVariable()) {
            cypher.append(subjectVar).append(".uri AS ")
                  .append(triple.getSubject().getName());
        } else {
            cypher.append("'").append(triple.getSubject().getURI())
                  .append("' AS _s");
        }
        cypher.append(", ").append(objectVar).append(".uri AS ").append(objectVar);

        // Part 2: Query properties (object is a literal value stored as property)
        cypher.append("\nUNION ALL\n");
        cypher.append("MATCH ");
        
        // Subject for properties
        if (triple.getSubject().isVariable()) {
            cypher.append("(").append(subjectVar).append(":Resource)");
        } else {
            // Reuse the parameter from above
            cypher.append("(").append(subjectVar)
                  .append(":Resource {uri: $p0})");
        }

        // Check if property exists
        cypher.append("\nWHERE ").append(subjectVar)
              .append(".`").append(relType).append("` IS NOT NULL");
        
        cypher.append("\nRETURN ");
        if (triple.getSubject().isVariable()) {
            cypher.append(subjectVar).append(".uri AS ")
                  .append(triple.getSubject().getName());
        } else {
            cypher.append("'").append(triple.getSubject().getURI())
                  .append("' AS _s");
        }
        cypher.append(", ").append(subjectVar).append(".`").append(relType)
              .append("` AS ").append(objectVar);

        String query = cypher.toString();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Compiled BGP with variable object to Cypher:\n{}", 
                query);
        }

        return new CompilationResult(query, parameters, variableMapping);
    }

    /**
     * Append subject node pattern to Cypher query.
     */
    private static void appendSubjectNode(
            final StringBuilder cypher,
            final Node subject,
            final String subjectVar,
            final Set<String> declaredNodes,
            final Map<String, Object> parameters,
            final int paramCounter) {
        if (subject.isVariable()) {
            if (declaredNodes.contains(subjectVar)) {
                cypher.append("(").append(subjectVar).append(")");
            } else {
                cypher.append("(").append(subjectVar).append(":Resource)");
                declaredNodes.add(subjectVar);
            }
        } else {
            String paramName = "p" + paramCounter;
            parameters.put(paramName, subject.getURI());
            if (declaredNodes.contains(subjectVar)) {
                cypher.append("(").append(subjectVar).append(")");
            } else {
                cypher.append("(").append(subjectVar)
                      .append(":Resource {uri: $").append(paramName)
                      .append("})");
                declaredNodes.add(subjectVar);
            }
        }
    }

    /**
     * Append object node pattern to Cypher query.
     */
    private static void appendObjectNode(
            final StringBuilder cypher,
            final Node object,
            final String objectVar,
            final Set<String> declaredNodes,
            final Map<String, Object> parameters,
            final int paramCounter) {
        if (object.isVariable()) {
            if (declaredNodes.contains(objectVar)) {
                cypher.append("(").append(objectVar).append(")");
            } else {
                cypher.append("(").append(objectVar).append(":Resource)");
                declaredNodes.add(objectVar);
            }
        } else if (object.isURI()) {
            String paramName = "p" + paramCounter;
            parameters.put(paramName, object.getURI());
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
            // Object variables are added to nodeVariables as they may bind to URIs
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
