package com.falkordb.jena.query;

import com.falkordb.jena.tracing.TracingUtil;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.*;
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

    /** Instrumentation scope name. */
    public static final String SCOPE_COMPILER =
        "com.falkordb.jena.query.SparqlToCypherCompiler";

    /** Tracer for compiler operations. */
    private static final Tracer TRACER = TracingUtil.getTracer(SCOPE_COMPILER);

    /** RDF type URI for special handling. */
    private static final String RDF_TYPE_URI = RDF.type.getURI();

    /** Attribute key for optimization type. */
    private static final AttributeKey<String> ATTR_OPTIMIZATION_TYPE =
        AttributeKey.stringKey("falkordb.optimization.type");

    /** Attribute key for input BGP (SPARQL pattern). */
    private static final AttributeKey<String> ATTR_INPUT_BGP =
        AttributeKey.stringKey("falkordb.optimization.input_bgp");

    /** Attribute key for output Cypher query. */
    private static final AttributeKey<String> ATTR_OUTPUT_CYPHER =
        AttributeKey.stringKey("falkordb.cypher.query");

    /** Attribute key for triple count. */
    private static final AttributeKey<Long> ATTR_TRIPLE_COUNT =
        AttributeKey.longKey("sparql.bgp.triple_count");

    /** Attribute key for parameter count. */
    private static final AttributeKey<Long> ATTR_PARAM_COUNT =
        AttributeKey.longKey("falkordb.optimization.param_count");

    /** Attribute key for variable count. */
    private static final AttributeKey<Long> ATTR_VAR_COUNT =
        AttributeKey.longKey("sparql.bgp.variable_count");

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
     * Translate a SPARQL Basic Graph Pattern with OPTIONAL clause to a Cypher query.
     * 
     * <p>This method handles SPARQL OPTIONAL patterns by translating them to Cypher
     * OPTIONAL MATCH clauses. The required BGP is compiled first, followed by the
     * optional BGP with OPTIONAL MATCH.</p>
     * 
     * <h3>Example:</h3>
     * <pre>{@code
     * // SPARQL:
     * // SELECT ?person ?email WHERE {
     * //   ?person rdf:type foaf:Person .
     * //   OPTIONAL { ?person foaf:email ?email }
     * // }
     * 
     * // Compiled Cypher:
     * // MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
     * // OPTIONAL MATCH (person)-[:`http://xmlns.com/foaf/0.1/email`]->(email:Resource)
     * // RETURN person.uri AS person, email.uri AS email
     * }</pre>
     *
     * @param requiredBGP the required Basic Graph Pattern
     * @param optionalBGP the optional Basic Graph Pattern
     * @return the compilation result containing Cypher query and metadata
     * @throws CannotCompileException if either BGP cannot be compiled
     */
    /**
     * Translate SPARQL BGPs with OPTIONAL pattern to Cypher (no filter).
     * 
     * @param requiredBGP the required basic graph pattern
     * @param optionalBGP the optional basic graph pattern
     * @return the compilation result with Cypher query and parameters
     * @throws CannotCompileException if the pattern cannot be compiled
     */
    public static CompilationResult translateWithOptional(
            final BasicPattern requiredBGP,
            final BasicPattern optionalBGP)
            throws CannotCompileException {
        return translateWithOptional(requiredBGP, optionalBGP, null);
    }

    /**
     * Translate SPARQL BGPs with OPTIONAL pattern and optional FILTER to Cypher.
     * 
     * @param requiredBGP the required basic graph pattern
     * @param optionalBGP the optional basic graph pattern
     * @param filterExpr the filter expression (may be null)
     * @return the compilation result with Cypher query and parameters
     * @throws CannotCompileException if the pattern cannot be compiled
     */
    public static CompilationResult translateWithOptional(
            final BasicPattern requiredBGP,
            final BasicPattern optionalBGP,
            final Expr filterExpr)
            throws CannotCompileException {
        
        if (requiredBGP == null || requiredBGP.isEmpty()) {
            throw new CannotCompileException(
                "Required BGP cannot be empty for OPTIONAL pattern");
        }
        
        if (optionalBGP == null || optionalBGP.isEmpty()) {
            throw new CannotCompileException(
                "Optional BGP cannot be empty");
        }
        
        String inputPattern = formatBGP(requiredBGP) + " OPTIONAL { " + formatBGP(optionalBGP) + " }";
        if (filterExpr != null) {
            inputPattern += " FILTER(" + filterExpr.toString() + ")";
        }
        
        Span span = TRACER.spanBuilder("SparqlToCypherCompiler.translateWithOptional")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPTIMIZATION_TYPE, "OPTIONAL_PUSHDOWN")
            .setAttribute(ATTR_TRIPLE_COUNT, (long) (requiredBGP.size() + optionalBGP.size()))
            .setAttribute(ATTR_INPUT_BGP, inputPattern)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            CompilationResult result = translateWithOptionalInternal(requiredBGP, optionalBGP, filterExpr);
            
            span.setAttribute(ATTR_OUTPUT_CYPHER, result.cypherQuery());
            span.setAttribute(ATTR_PARAM_COUNT, (long) result.parameters().size());
            span.setAttribute(ATTR_VAR_COUNT, (long) result.variableMapping().size());
            span.setStatus(StatusCode.OK);
            
            return result;
        } catch (CannotCompileException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
    
    /**
     * Internal translation method for OPTIONAL without tracing.
     */
    private static CompilationResult translateWithOptionalInternal(
            final BasicPattern requiredBGP,
            final BasicPattern optionalBGP,
            final Expr filterExpr)
            throws CannotCompileException {
        
        // Compile the required pattern first
        // Use special handling that treats variable objects as literal properties
        CompilationResult requiredResult = translateForOptional(requiredBGP);
        
        // Get required query parts (remove RETURN clause)
        String requiredQuery = requiredResult.cypherQuery();
        int returnIndex = requiredQuery.lastIndexOf("\nRETURN");
        if (returnIndex < 0) {
            throw new CannotCompileException(
                "Could not find RETURN clause in required pattern");
        }
        String requiredMatches = requiredQuery.substring(0, returnIndex);
        
        // Collect variables and parameters from required pattern
        Map<String, Object> parameters = new HashMap<>(requiredResult.parameters());
        Map<String, String> variableMapping = new HashMap<>(requiredResult.variableMapping());
        Set<String> allVariables = new HashSet<>();
        Set<String> nodeVariables = new HashSet<>();
        Set<String> declaredNodes = new HashSet<>();
        
        // Parse required pattern to identify declared nodes
        for (Triple triple : requiredBGP.getList()) {
            collectVariables(triple, allVariables, nodeVariables);
            if (triple.getSubject().isVariable()) {
                declaredNodes.add(getNodeVariable(triple.getSubject()));
            }
            if (triple.getObject().isVariable()) {
                declaredNodes.add(getNodeVariable(triple.getObject()));
            }
        }
        
        // Build the optional pattern - translate triples to OPTIONAL MATCH
        StringBuilder optionalCypher = new StringBuilder();
        int paramCounter = parameters.size();
        
        List<Triple> optionalTriples = optionalBGP.getList();
        
        // Check for variable predicates in optional pattern
        boolean hasVariablePredicate = optionalTriples.stream()
            .anyMatch(t -> t.getPredicate().isVariable());
        
        if (hasVariablePredicate) {
            throw new CannotCompileException(
                "Variable predicates in OPTIONAL patterns not yet supported");
        }
        
        // Identify which variables in the optional pattern are resources (used as subjects)
        Set<String> optionalResourceVariables = new HashSet<>();
        for (Triple t : optionalTriples) {
            if (t.getSubject().isVariable()) {
                optionalResourceVariables.add(t.getSubject().getName());
            }
        }
        // Also check required triples to see if optional variables are used as subjects there
        for (Triple t : requiredBGP.getList()) {
            if (t.getSubject().isVariable()) {
                optionalResourceVariables.add(t.getSubject().getName());
            }
        }
        
        // Process optional triples
        for (Triple triple : optionalTriples) {
            collectVariables(triple, allVariables, nodeVariables);
            
            Node predicate = triple.getPredicate();
            Node object = triple.getObject();
            
            optionalCypher.append("\nOPTIONAL MATCH ");
            
            String subjectVar = getNodeVariable(triple.getSubject());
            String objectVar = getNodeVariable(triple.getObject());
            
            if (predicate.isURI() && predicate.getURI().equals(RDF_TYPE_URI)) {
                // Handle rdf:type in optional pattern
                if (object.isVariable()) {
                    throw new CannotCompileException(
                        "Variable types in OPTIONAL rdf:type not yet supported");
                }
                
                String typeLabel = sanitizeCypherIdentifier(object.getURI());
                
                // Subject should already be declared
                if (triple.getSubject().isVariable()) {
                    optionalCypher.append("(").append(subjectVar)
                          .append(":`").append(typeLabel).append("`)");
                } else {
                    String paramName = "p" + paramCounter++;
                    parameters.put(paramName, triple.getSubject().getURI());
                    optionalCypher.append("(").append(subjectVar)
                          .append(":`").append(typeLabel)
                          .append("` {uri: $").append(paramName).append("})");
                }
            } else if (object.isLiteral() || 
                       (object.isVariable() && !optionalResourceVariables.contains(object.getName()))) {
                // Handle literal property in optional pattern
                // This includes concrete literals and variables that aren't used as subjects
                String predUri = sanitizeCypherIdentifier(predicate.getURI());
                
                // Match node if not already declared
                if (!declaredNodes.contains(subjectVar)) {
                    if (triple.getSubject().isVariable()) {
                        optionalCypher.append("(").append(subjectVar).append(":Resource)");
                    } else {
                        String paramName = "p" + paramCounter++;
                        parameters.put(paramName, triple.getSubject().getURI());
                        optionalCypher.append("(").append(subjectVar)
                              .append(":Resource {uri: $").append(paramName).append("})");
                    }
                    declaredNodes.add(subjectVar);
                }
                
                // Add WHERE clause for literal match
                optionalCypher.append("\nWHERE ");
                
                if (object.isVariable()) {
                    // Variable literal - just check property exists
                    optionalCypher.append(subjectVar)
                          .append(".`").append(predUri).append("` IS NOT NULL");
                    // Map variable to property expression
                    variableMapping.put(object.getName(), 
                        subjectVar + ".`" + predUri + "`");
                } else if (object.isLiteral()) {
                    // Concrete literal - match value
                    String paramName = "p" + paramCounter++;
                    parameters.put(paramName, object.getLiteralLexicalForm());
                    optionalCypher.append(subjectVar)
                          .append(".`").append(predUri).append("` = $")
                          .append(paramName);
                }
            } else {
                // Handle relationship in optional pattern
                String relType = sanitizeCypherIdentifier(predicate.getURI());
                
                // Subject (should already be declared from required pattern)
                if (triple.getSubject().isVariable()) {
                    if (declaredNodes.contains(subjectVar)) {
                        optionalCypher.append("(").append(subjectVar).append(")");
                    } else {
                        optionalCypher.append("(").append(subjectVar).append(":Resource)");
                        declaredNodes.add(subjectVar);
                    }
                } else {
                    String paramName = "p" + paramCounter++;
                    parameters.put(paramName, triple.getSubject().getURI());
                    if (declaredNodes.contains(subjectVar)) {
                        optionalCypher.append("(").append(subjectVar).append(")");
                    } else {
                        optionalCypher.append("(").append(subjectVar)
                              .append(":Resource {uri: $").append(paramName).append("})");
                        declaredNodes.add(subjectVar);
                    }
                }
                
                // Relationship
                optionalCypher.append("-[:`").append(relType).append("`]->");
                
                // Object
                if (object.isVariable()) {
                    optionalCypher.append("(").append(objectVar).append(":Resource)");
                    declaredNodes.add(objectVar);
                } else if (object.isURI()) {
                    String paramName = "p" + paramCounter++;
                    parameters.put(paramName, object.getURI());
                    optionalCypher.append("(").append(objectVar)
                          .append(":Resource {uri: $").append(paramName).append("})");
                    declaredNodes.add(objectVar);
                }
            }
        }
        
        // Build RETURN clause with all variables (required + optional)
        StringBuilder returnClause = new StringBuilder("\nRETURN ");
        List<String> returnParts = new ArrayList<>();
        
        for (String varName : allVariables) {
            if (variableMapping.containsKey(varName)) {
                // Literal variable - return the property expression
                returnParts.add(variableMapping.get(varName) + " AS " + varName);
            } else if (nodeVariables.contains(varName)) {
                // Node variable - return the URI
                returnParts.add(sanitizeVariableName(varName) + ".uri AS " + varName);
            }
        }
        
        if (returnParts.isEmpty()) {
            returnParts.add("1 AS _result");
        }
        
        returnClause.append(String.join(", ", returnParts));
        
        // Add FILTER condition if present
        String finalRequiredMatches = requiredMatches;
        if (filterExpr != null) {
            String filterCypher = translateFilterExpr(filterExpr, variableMapping, nodeVariables, parameters, paramCounter);
            if (filterCypher != null && !filterCypher.isEmpty()) {
                // Check if there's already a WHERE clause in requiredMatches
                if (requiredMatches.contains("\nWHERE ")) {
                    // Append to existing WHERE with AND
                    finalRequiredMatches = requiredMatches + " AND " + filterCypher;
                } else {
                    // Add new WHERE clause
                    finalRequiredMatches = requiredMatches + "\nWHERE " + filterCypher;
                }
            }
        }
        
        // Combine required + filter + optional + return
        String finalQuery = finalRequiredMatches + optionalCypher.toString() + returnClause.toString();
        
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Compiled BGP with OPTIONAL to Cypher:\n{}", finalQuery);
        }
        
        return new CompilationResult(finalQuery, parameters, variableMapping);
    }

    /**
     * Translate a SPARQL FILTER expression to Cypher WHERE clause.
     * 
     * @param expr the SPARQL filter expression
     * @param variableMapping mapping of variables to Cypher expressions
     * @param nodeVariables set of node variable names
     * @param parameters parameter map to add new parameters to
     * @param paramCounter starting parameter counter
     * @return Cypher WHERE clause condition (without "WHERE" keyword)
     * @throws CannotCompileException if the expression cannot be translated
     */
    private static String translateFilterExpr(
            final Expr expr,
            final Map<String, String> variableMapping,
            final Set<String> nodeVariables,
            final Map<String, Object> parameters,
            int paramCounter) throws CannotCompileException {
        
        if (expr == null) {
            return null;
        }
        
        // Handle comparison operators
        if (expr instanceof E_LessThan) {
            E_LessThan lt = (E_LessThan) expr;
            String left = translateFilterOperand(lt.getArg1(), variableMapping, nodeVariables);
            String right = translateFilterOperand(lt.getArg2(), variableMapping, nodeVariables);
            return left + " < " + right;
        } else if (expr instanceof E_LessThanOrEqual) {
            E_LessThanOrEqual lte = (E_LessThanOrEqual) expr;
            String left = translateFilterOperand(lte.getArg1(), variableMapping, nodeVariables);
            String right = translateFilterOperand(lte.getArg2(), variableMapping, nodeVariables);
            return left + " <= " + right;
        } else if (expr instanceof E_GreaterThan) {
            E_GreaterThan gt = (E_GreaterThan) expr;
            String left = translateFilterOperand(gt.getArg1(), variableMapping, nodeVariables);
            String right = translateFilterOperand(gt.getArg2(), variableMapping, nodeVariables);
            return left + " > " + right;
        } else if (expr instanceof E_GreaterThanOrEqual) {
            E_GreaterThanOrEqual gte = (E_GreaterThanOrEqual) expr;
            String left = translateFilterOperand(gte.getArg1(), variableMapping, nodeVariables);
            String right = translateFilterOperand(gte.getArg2(), variableMapping, nodeVariables);
            return left + " >= " + right;
        } else if (expr instanceof E_Equals) {
            E_Equals eq = (E_Equals) expr;
            String left = translateFilterOperand(eq.getArg1(), variableMapping, nodeVariables);
            String right = translateFilterOperand(eq.getArg2(), variableMapping, nodeVariables);
            return left + " = " + right;
        } else if (expr instanceof E_NotEquals) {
            E_NotEquals ne = (E_NotEquals) expr;
            String left = translateFilterOperand(ne.getArg1(), variableMapping, nodeVariables);
            String right = translateFilterOperand(ne.getArg2(), variableMapping, nodeVariables);
            return left + " <> " + right;
        } else if (expr instanceof E_LogicalAnd) {
            E_LogicalAnd and = (E_LogicalAnd) expr;
            String left = translateFilterExpr(and.getArg1(), variableMapping, nodeVariables, parameters, paramCounter);
            String right = translateFilterExpr(and.getArg2(), variableMapping, nodeVariables, parameters, paramCounter);
            return "(" + left + " AND " + right + ")";
        } else if (expr instanceof E_LogicalOr) {
            E_LogicalOr or = (E_LogicalOr) expr;
            String left = translateFilterExpr(or.getArg1(), variableMapping, nodeVariables, parameters, paramCounter);
            String right = translateFilterExpr(or.getArg2(), variableMapping, nodeVariables, parameters, paramCounter);
            return "(" + left + " OR " + right + ")";
        } else if (expr instanceof E_LogicalNot) {
            E_LogicalNot not = (E_LogicalNot) expr;
            String arg = translateFilterExpr(not.getArg(), variableMapping, nodeVariables, parameters, paramCounter);
            return "NOT (" + arg + ")";
        }
        
        // Check if this is a GeoSPARQL function
        if (GeoSPARQLToCypherTranslator.isGeoSPARQLFunction(expr)) {
            String geoExpr = GeoSPARQLToCypherTranslator.translateGeoFunction(
                expr, "geo_p" + paramCounter, parameters);
            if (geoExpr != null) {
                return geoExpr;
            }
        }
        
        throw new CannotCompileException(
            "Unsupported FILTER expression type: " + expr.getClass().getSimpleName());
    }
    
    /**
     * Translate a FILTER operand (variable, constant, or property) to Cypher expression.
     */
    private static String translateFilterOperand(
            final Expr expr,
            final Map<String, String> variableMapping,
            final Set<String> nodeVariables) throws CannotCompileException {
        
        if (expr instanceof ExprVar) {
            ExprVar var = (ExprVar) expr;
            String varName = var.getVarName();
            
            // Check if it's mapped to a property expression
            if (variableMapping.containsKey(varName)) {
                return variableMapping.get(varName);
            }
            
            // Otherwise it's a node variable
            if (nodeVariables.contains(varName)) {
                return sanitizeVariableName(varName) + ".uri";
            }
            
            throw new CannotCompileException(
                "Unknown variable in FILTER: " + varName);
        } else if (expr instanceof NodeValue) {
            NodeValue nodeVal = (NodeValue) expr;
            
            // NodeValue for constants may not have a node - use direct accessors
            if (nodeVal.isInteger()) {
                return String.valueOf(nodeVal.getInteger().longValue());
            } else if (nodeVal.isDecimal()) {
                return nodeVal.getDecimal().toString();
            } else if (nodeVal.isDouble()) {
                return String.valueOf(nodeVal.getDouble());
            } else if (nodeVal.isFloat()) {
                return String.valueOf(nodeVal.getFloat());
            } else if (nodeVal.isBoolean()) {
                return String.valueOf(nodeVal.getBoolean());
            } else if (nodeVal.isString()) {
                return "'" + nodeVal.getString().replace("'", "\\'") + "'";
            }
            
            // For other node values, try to get the node
            Node node = nodeVal.getNode();
            if (node != null) {
                if (node.isLiteral()) {
                    Object value = node.getLiteralValue();
                    if (value instanceof Number) {
                        return value.toString();
                    } else if (value instanceof String) {
                        return "'" + value.toString().replace("'", "\\'") + "'";
                    } else if (value instanceof Boolean) {
                        return value.toString();
                    }
                    return "'" + node.getLiteralLexicalForm().replace("'", "\\'") + "'";
                } else if (node.isURI()) {
                    return "'" + node.getURI().replace("'", "\\'") + "'";
                }
            }
        }
        
        throw new CannotCompileException(
            "Unsupported FILTER operand type: " + expr.getClass().getSimpleName());
    }

    /**
     * Translate a SPARQL UNION pattern to a Cypher query.
     *
     * <p>This method handles SPARQL UNION patterns by translating them to Cypher
     * UNION queries. Each branch is compiled separately and combined with UNION.</p>
     *
     * <p><b>Example:</b></p>
     * <pre>{@code
     * // SPARQL:
     * // SELECT ?person WHERE {
     * //   { ?person rdf:type foaf:Student }
     * //   UNION
     * //   { ?person rdf:type foaf:Teacher }
     * // }
     * 
     * // Compiled Cypher:
     * // MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Student`)
     * // RETURN person.uri AS person
     * // UNION
     * // MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Teacher`)
     * // RETURN person.uri AS person
     * }</pre>
     *
     * @param leftBGP the left Basic Graph Pattern
     * @param rightBGP the right Basic Graph Pattern
     * @return the compilation result containing Cypher query and metadata
     * @throws CannotCompileException if either BGP cannot be compiled
     */
    public static CompilationResult translateUnion(
            final BasicPattern leftBGP,
            final BasicPattern rightBGP)
            throws CannotCompileException {
        
        if (leftBGP == null || leftBGP.isEmpty()) {
            throw new CannotCompileException(
                "Left BGP cannot be empty for UNION pattern");
        }
        
        if (rightBGP == null || rightBGP.isEmpty()) {
            throw new CannotCompileException(
                "Right BGP cannot be empty for UNION pattern");
        }
        
        String inputPattern = "{ " + formatBGP(leftBGP) + " } UNION { " + formatBGP(rightBGP) + " }";
        
        Span span = TRACER.spanBuilder("SparqlToCypherCompiler.translateUnion")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPTIMIZATION_TYPE, "UNION_PUSHDOWN")
            .setAttribute(ATTR_TRIPLE_COUNT, (long) (leftBGP.size() + rightBGP.size()))
            .setAttribute(ATTR_INPUT_BGP, inputPattern)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Compile left branch
            CompilationResult leftResult = translate(leftBGP);
            
            // Compile right branch
            CompilationResult rightResult = translate(rightBGP);
            
            // Merge parameters from both branches with unique names
            Map<String, Object> parameters = new HashMap<>(leftResult.parameters());
            Map<String, Object> rightParams = rightResult.parameters();
            
            // Check if parameter names conflict and rename if necessary
            String rightQuery = rightResult.cypherQuery();
            for (Map.Entry<String, Object> entry : rightParams.entrySet()) {
                String paramName = entry.getKey();
                if (parameters.containsKey(paramName)) {
                    // Conflict - need to rename parameter in right query
                    String newParamName = paramName + "_r";
                    int counter = 1;
                    while (parameters.containsKey(newParamName) || rightParams.containsKey(newParamName)) {
                        newParamName = paramName + "_r" + counter++;
                    }
                    // Replace parameter name in right query
                    rightQuery = rightQuery.replace("$" + paramName, "$" + newParamName);
                    parameters.put(newParamName, entry.getValue());
                } else {
                    parameters.put(paramName, entry.getValue());
                }
            }
            
            // Combine variable mappings - they should be consistent for UNION
            Map<String, String> variableMapping = new HashMap<>(leftResult.variableMapping());
            variableMapping.putAll(rightResult.variableMapping());
            
            // Combine queries with UNION
            StringBuilder cypherQuery = new StringBuilder();
            cypherQuery.append(leftResult.cypherQuery());
            cypherQuery.append("\nUNION\n");
            cypherQuery.append(rightQuery);
            
            String finalQuery = cypherQuery.toString();
            
            span.setAttribute(ATTR_OUTPUT_CYPHER, finalQuery);
            span.setAttribute(ATTR_PARAM_COUNT, (long) parameters.size());
            span.setAttribute(ATTR_VAR_COUNT, (long) variableMapping.size());
            span.setStatus(StatusCode.OK);
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Compiled UNION pattern to Cypher:\n{}", finalQuery);
            }
            
            return new CompilationResult(finalQuery, parameters, variableMapping);
            
        } catch (CannotCompileException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw new CannotCompileException("Error compiling UNION: " + e.getMessage());
        } finally {
            span.end();
        }
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
        
        Span span = TRACER.spanBuilder("SparqlToCypherCompiler.translate")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPTIMIZATION_TYPE, "BGP_PUSHDOWN")
            .setAttribute(ATTR_TRIPLE_COUNT, (long) triples.size())
            .setAttribute(ATTR_INPUT_BGP, formatBGP(bgp))
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            CompilationResult result = translateInternal(bgp);
            
            span.setAttribute(ATTR_OUTPUT_CYPHER, result.cypherQuery());
            span.setAttribute(ATTR_PARAM_COUNT, (long) result.parameters().size());
            span.setAttribute(ATTR_VAR_COUNT, (long) result.variableMapping().size());
            span.setStatus(StatusCode.OK);
            
            return result;
        } catch (CannotCompileException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Internal translation method without tracing.
     */
    private static CompilationResult translateInternal(final BasicPattern bgp)
            throws CannotCompileException {
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
                    // Variable object not used as subject
                    // For now, add to a separate list - we'll handle these as variable objects
                    variableObjectTriples.add(triple);
                }
            } else {
                relationshipTriples.add(triple);
            }
        }

        // Check if we have variable objects that aren't used as subjects
        // These are ambiguous (could be relationships or literal properties)
        // For multi-triple BGPs, fall back to standard evaluation
        if (!variableObjectTriples.isEmpty() && triples.size() > 1) {
            throw new CannotCompileException(
                "Variable objects not used as subjects in multi-triple BGPs " +
                "cannot be safely compiled. Use single-triple patterns or " +
                "OPTIONAL patterns for disambiguation.");
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
        
        // Handle variable object triples (for single-triple patterns only)
        // Multi-triple patterns with variable objects should have been caught earlier at line 609
        if (!variableObjectTriples.isEmpty() && triples.size() == 1) {
            // Single-triple pattern - delegate to specialized handler
            return translateWithVariableObject(variableObjectTriples.get(0));
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
     * Translate a SPARQL Basic Graph Pattern with FILTER expression to Cypher.
     * 
     * <p>This method extends the standard BGP translation by adding a WHERE clause
     * for the FILTER expression. The FILTER is translated to Cypher WHERE conditions
     * and applied after the MATCH clauses.</p>
     * 
     * <p><b>Supported FILTER Operators:</b></p>
     * <ul>
     *   <li>Comparison: {@code <}, {@code <=}, {@code >}, {@code >=}, {@code =}, {@code <>}</li>
     *   <li>Logical: {@code AND}, {@code OR}, {@code NOT}</li>
     *   <li>Operands: Variables (from literal properties or nodes), numeric literals, 
     *       string literals, boolean literals</li>
     * </ul>
     * 
     * <p><b>Example:</b></p>
     * <pre>{@code
     * // SPARQL:
     * // SELECT ?person ?age WHERE {
     * //   ?person foaf:age ?age .
     * //   FILTER(?age >= 18 AND ?age < 65)
     * // }
     * 
     * // Compiled Cypher:
     * // MATCH (person:Resource)
     * // WHERE person.`http://xmlns.com/foaf/0.1/age` IS NOT NULL
     * // WHERE (person.`http://xmlns.com/foaf/0.1/age` >= 18 AND 
     * //        person.`http://xmlns.com/foaf/0.1/age` < 65)
     * // RETURN person.uri AS person, 
     * //        person.`http://xmlns.com/foaf/0.1/age` AS age
     * }</pre>
     * 
     * @param bgp the Basic Graph Pattern
     * @param filterExpr the FILTER expression to apply
     * @return the compilation result with Cypher query and parameters
     * @throws CannotCompileException if the BGP or FILTER cannot be compiled
     */
    public static CompilationResult translateWithFilter(
            final BasicPattern bgp,
            final Expr filterExpr)
            throws CannotCompileException {
        
        if (filterExpr == null) {
            // No filter - just translate the BGP normally
            return translate(bgp);
        }
        
        Span span = TRACER.spanBuilder("SparqlToCypherCompiler.translateWithFilter")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPTIMIZATION_TYPE, "FILTER_PUSHDOWN")
            .setAttribute(ATTR_TRIPLE_COUNT, (long) bgp.size())
            .setAttribute(ATTR_INPUT_BGP, formatBGP(bgp) + " FILTER(" + filterExpr.toString() + ")")
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            CompilationResult result = translateWithFilterInternal(bgp, filterExpr);
            
            span.setAttribute(ATTR_OUTPUT_CYPHER, result.cypherQuery());
            span.setAttribute(ATTR_PARAM_COUNT, (long) result.parameters().size());
            span.setAttribute(ATTR_VAR_COUNT, (long) result.variableMapping().size());
            span.setStatus(StatusCode.OK);
            
            return result;
        } catch (CannotCompileException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
    
    /**
     * Internal translation method with filter without tracing.
     */
    private static CompilationResult translateWithFilterInternal(
            final BasicPattern bgp,
            final Expr filterExpr)
            throws CannotCompileException {
        
        // First, compile the BGP without filter
        CompilationResult bgpResult = translateInternal(bgp);
        
        // Extract the components from the BGP result
        String bgpQuery = bgpResult.cypherQuery();
        Map<String, Object> parameters = new HashMap<>(bgpResult.parameters());
        Map<String, String> variableMapping = bgpResult.variableMapping();
        
        // Identify node variables from the BGP
        Set<String> nodeVariables = new HashSet<>();
        for (Triple triple : bgp.getList()) {
            if (triple.getSubject().isVariable()) {
                nodeVariables.add(triple.getSubject().getName());
            }
            if (triple.getObject().isVariable() && !triple.getObject().isLiteral()) {
                // Only add object variables that aren't in variableMapping
                // (those in variableMapping are literal properties)
                String objVar = triple.getObject().getName();
                if (!variableMapping.containsKey(objVar)) {
                    nodeVariables.add(objVar);
                }
            }
        }
        
        // Translate the filter expression to Cypher WHERE clause
        int paramCounter = parameters.size();
        String filterCypher = translateFilterExpr(
            filterExpr, variableMapping, nodeVariables, parameters, paramCounter);
        
        // Check if this is a UNION query (contains "UNION ALL")
        if (bgpQuery.contains("UNION ALL")) {
            // For UNION queries, we need to add the filter to each branch
            String[] unionParts = bgpQuery.split("UNION ALL");
            StringBuilder finalQuery = new StringBuilder();
            
            for (int i = 0; i < unionParts.length; i++) {
                String part = unionParts[i].trim();
                
                // Find the RETURN clause in this part
                int returnIndex = part.lastIndexOf("\nRETURN");
                if (returnIndex < 0) {
                    returnIndex = part.lastIndexOf("RETURN");
                }
                
                if (returnIndex >= 0) {
                    String matchPart = part.substring(0, returnIndex);
                    String returnPart = part.substring(returnIndex);
                    
                    if (i > 0) {
                        finalQuery.append("\nUNION ALL\n");
                    }
                    
                    // Add WHERE clause for filter
                    String finalMatchPart;
                    if (matchPart.contains("\nWHERE ")) {
                        // Append to existing WHERE clause with AND
                        finalMatchPart = matchPart + "\n  AND " + filterCypher;
                    } else {
                        // Add new WHERE clause
                        finalMatchPart = matchPart + "\nWHERE " + filterCypher;
                    }
                    
                    finalQuery.append(finalMatchPart);
                    finalQuery.append(returnPart);
                } else {
                    // No RETURN found - shouldn't happen, but handle gracefully
                    if (i > 0) {
                        finalQuery.append("\nUNION ALL\n");
                    }
                    finalQuery.append(part);
                }
            }
            
            String finalQueryStr = finalQuery.toString();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Compiled BGP with FILTER (UNION) to Cypher:\n{}", finalQueryStr);
            }
            
            return new CompilationResult(finalQueryStr, parameters, variableMapping);
        } else {
            // Non-UNION query - simple case
            int returnIndex = bgpQuery.lastIndexOf("\nRETURN");
            if (returnIndex < 0) {
                throw new CannotCompileException(
                    "Could not find RETURN clause in compiled BGP");
            }
            
            // Split the query into MATCH/WHERE part and RETURN part
            String matchPart = bgpQuery.substring(0, returnIndex);
            String returnPart = bgpQuery.substring(returnIndex);
            
            // Combine: MATCH part + additional WHERE for filter + RETURN part
            String finalMatchPart;
            
            // Check if there's already a WHERE clause
            if (matchPart.contains("\nWHERE ")) {
                // Append to existing WHERE clause with AND
                finalMatchPart = matchPart + "\n  AND " + filterCypher;
            } else {
                // Add new WHERE clause
                finalMatchPart = matchPart + "\nWHERE " + filterCypher;
            }
            
            StringBuilder finalQuery = new StringBuilder(finalMatchPart);
            finalQuery.append(returnPart);
            
            String finalQueryStr = finalQuery.toString();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Compiled BGP with FILTER to Cypher:\n{}", finalQueryStr);
            }
            
            return new CompilationResult(finalQueryStr, parameters, variableMapping);
        }
    }

    /**
     * Translate a BGP for use in OPTIONAL patterns.
     * 
     * <p>This method treats variable objects that aren't used as subjects
     * as literal properties, which is appropriate in the context of OPTIONAL
     * patterns where we want to match literal properties in the required part.</p>
     * 
     * @param bgp the basic graph pattern to translate
     * @return the compilation result
     * @throws CannotCompileException if the pattern cannot be compiled
     */
    private static CompilationResult translateForOptional(final BasicPattern bgp)
            throws CannotCompileException {
        if (bgp == null || bgp.isEmpty()) {
            throw new CannotCompileException("Empty BGP cannot be compiled");
        }

        List<Triple> triples = bgp.getList();
        
        // Check if we have variable predicates - not supported in optional context
        boolean hasVariablePredicate = triples.stream()
            .anyMatch(t -> t.getPredicate().isVariable());
        
        if (hasVariablePredicate) {
            throw new CannotCompileException(
                "Variable predicates in OPTIONAL patterns not supported");
        }
        
        // Classify triples and collect variables
        List<Triple> relationshipTriples = new ArrayList<>();
        List<Triple> literalTriples = new ArrayList<>();
        List<Triple> typeTriples = new ArrayList<>();
        List<Triple> variableObjectRelTriples = new ArrayList<>();
        Set<String> allVariables = new HashSet<>();
        Set<String> nodeVariables = new HashSet<>();
        Map<String, Object> parameters = new HashMap<>();
        int paramCounter = 0;

        // First pass: identify which variable objects are used as subjects
        Set<String> resourceVariables = new HashSet<>();
        for (Triple triple : triples) {
            if (triple.getSubject().isVariable()) {
                resourceVariables.add(triple.getSubject().getName());
            }
        }

        // Classify triples
        for (Triple triple : triples) {
            collectVariables(triple, allVariables, nodeVariables);
            
            Node predicate = triple.getPredicate();
            Node object = triple.getObject();

            if (predicate.isURI() && predicate.getURI().equals(RDF_TYPE_URI)) {
                typeTriples.add(triple);
            } else if (object.isLiteral()) {
                literalTriples.add(triple);
            } else if (object.isURI()) {
                relationshipTriples.add(triple);
            } else if (object.isVariable()) {
                // In OPTIONAL context, treat variable objects as literal properties
                // unless they're used as subjects elsewhere
                if (resourceVariables.contains(object.getName())) {
                    variableObjectRelTriples.add(triple);
                } else {
                    // Treat as literal property in OPTIONAL context
                    literalTriples.add(triple);
                }
            } else {
                relationshipTriples.add(triple);
            }
        }

        // Build the Cypher query
        StringBuilder cypher = new StringBuilder();
        Map<String, String> variableMapping = new HashMap<>();
        Set<String> declaredNodes = new HashSet<>();
        
        // Build MATCH clauses for relationship patterns
        for (Triple triple : relationshipTriples) {
            String subjectVar = getNodeVariable(triple.getSubject());
            String objectVar = getNodeVariable(triple.getObject());
            String relType = sanitizeCypherIdentifier(
                triple.getPredicate().getURI());

            if (!cypher.isEmpty()) {
                cypher.append("\n");
            }
            cypher.append("MATCH ");

            appendSubjectNode(cypher, triple.getSubject(), subjectVar, 
                declaredNodes, parameters, paramCounter);
            if (triple.getSubject().isURI()) {
                paramCounter++;
            }

            cypher.append("-[:`").append(relType).append("`]->");

            appendObjectNode(cypher, triple.getObject(), objectVar,
                declaredNodes, parameters, paramCounter);
            if (triple.getObject().isURI()) {
                paramCounter++;
            }
        }

        // Build MATCH clauses for variable object relationship patterns
        for (Triple triple : variableObjectRelTriples) {
            String subjectVar = getNodeVariable(triple.getSubject());
            String objectVar = getNodeVariable(triple.getObject());
            String relType = sanitizeCypherIdentifier(
                triple.getPredicate().getURI());

            if (!cypher.isEmpty()) {
                cypher.append("\n");
            }
            cypher.append("MATCH ");

            appendSubjectNode(cypher, triple.getSubject(), subjectVar,
                declaredNodes, parameters, paramCounter);
            if (triple.getSubject().isURI()) {
                paramCounter++;
            }

            cypher.append("-[:`").append(relType).append("`]->");

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
                throw new CannotCompileException(
                    "Variable types in rdf:type are not supported");
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
        List<String> whereConditions = new ArrayList<>();
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
            
            if (triple.getObject().isVariable()) {
                // Variable literal - check property exists
                String condition = subjectVar + ".`" + predUri + "` IS NOT NULL";
                whereConditions.add(condition);
                String objVar = triple.getObject().getName();
                variableMapping.put(objVar, 
                    subjectVar + ".`" + predUri + "`");
            } else {
                // Concrete literal - match value
                String paramName = "p" + paramCounter++;
                parameters.put(paramName, 
                    triple.getObject().getLiteralLexicalForm());
                String condition = subjectVar + ".`" + predUri + "` = $" + paramName;
                whereConditions.add(condition);
            }
        }
        
        // Add combined WHERE clause if there are conditions
        if (!whereConditions.isEmpty()) {
            cypher.append("\nWHERE ");
            cypher.append(String.join(" AND ", whereConditions));
        }

        // Build RETURN clause
        cypher.append("\nRETURN ");
        List<String> returnParts = new ArrayList<>();

        for (String varName : allVariables) {
            if (variableMapping.containsKey(varName)) {
                returnParts.add(variableMapping.get(varName) 
                    + " AS " + varName);
            } else if (nodeVariables.contains(varName)) {
                returnParts.add(varName + ".uri AS " + varName);
            }
        }

        if (returnParts.isEmpty()) {
            returnParts.add("1 AS _result");
        }

        cypher.append(String.join(", ", returnParts));

        return new CompilationResult(cypher.toString(), parameters, variableMapping);
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
            // Reuse the parameter name from Part 1 (stored in parameters map)
            String firstParamName = parameters.keySet().iterator().next();
            cypher.append("(").append(subjectVar)
                  .append(":Resource {uri: $").append(firstParamName).append("})");
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

    /**
     * Format a BasicPattern for tracing (truncated for readability).
     */
    private static String formatBGP(final BasicPattern bgp) {
        if (bgp == null || bgp.isEmpty()) {
            return "(empty)";
        }
        
        StringBuilder sb = new StringBuilder();
        List<Triple> triples = bgp.getList();
        int count = 0;
        for (Triple triple : triples) {
            if (count > 0) {
                sb.append(" . ");
            }
            sb.append(formatTriple(triple));
            count++;
            // Limit to 3 triples for readability
            if (count >= 3 && triples.size() > 3) {
                sb.append(" ... (").append(triples.size() - 3).append(" more)");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Format a single triple for tracing.
     */
    private static String formatTriple(final Triple triple) {
        return formatNode(triple.getSubject()) + " " +
               formatNode(triple.getPredicate()) + " " +
               formatNode(triple.getObject());
    }

    /**
     * Format a node for tracing (shortened URIs).
     */
    private static String formatNode(final Node node) {
        if (node.isVariable()) {
            return "?" + node.getName();
        } else if (node.isURI()) {
            String uri = node.getURI();
            // Shorten common prefixes
            if (uri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")) {
                return "rdf:" + uri.substring("http://www.w3.org/1999/02/22-rdf-syntax-ns#".length());
            } else if (uri.startsWith("http://xmlns.com/foaf/0.1/")) {
                return "foaf:" + uri.substring("http://xmlns.com/foaf/0.1/".length());
            } else if (uri.length() > 50) {
                return "<..." + uri.substring(uri.length() - 30) + ">";
            }
            return "<" + uri + ">";
        } else if (node.isLiteral()) {
            String lex = node.getLiteralLexicalForm();
            if (lex.length() > 20) {
                return "\"" + lex.substring(0, 20) + "...\"";
            }
            return "\"" + lex + "\"";
        }
        return node.toString();
    }
}
