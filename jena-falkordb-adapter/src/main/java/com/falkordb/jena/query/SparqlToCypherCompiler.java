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
            // Support variable predicates in OPTIONAL using UNION-based approach
            return translateWithOptionalVariablePredicates(
                requiredBGP, optionalBGP, filterExpr, requiredResult, 
                parameters, variableMapping, allVariables, nodeVariables, declaredNodes);
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
            // Use unique prefix for each geospatial function
            String geoPrefix = "geo_p" + (paramCounter++);
            String geoExpr = GeoSPARQLToCypherTranslator.translateGeoFunction(
                expr, geoPrefix, parameters);
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
        
        // Use VariableAnalyzer to determine variable grounding rules
        VariableAnalyzer.AnalysisResult analysis = VariableAnalyzer.analyze(bgp);
        LOGGER.debug("Variable analysis: {} NODE variables, {} AMBIGUOUS variables",
                analysis.getNodeVariables().size(), analysis.getAmbiguousVariables().size());
        
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

        // Classify triples and collect variables using the analysis
        List<Triple> relationshipTriples = new ArrayList<>();
        List<Triple> literalTriples = new ArrayList<>();
        List<Triple> typeTriples = new ArrayList<>();
        List<Triple> variableObjectRelTriples = new ArrayList<>();
        List<Triple> variableObjectTriples = new ArrayList<>();
        Set<String> allVariables = new HashSet<>();
        Set<String> nodeVariables = new HashSet<>();
        Map<String, Object> parameters = new HashMap<>();
        int paramCounter = 0;

        // Use analysis results to determine which variables are resources (nodes)
        Set<String> resourceVariables = analysis.getNodeVariables();

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
                // Variable object - check if it's a NODE (used as subject) based on analysis
                if (analysis.isNodeVariable(object.getName())) {
                    // Variable is a node - treat as relationship
                    variableObjectRelTriples.add(triple);
                } else if (analysis.isAmbiguousVariable(object.getName())) {
                    // Variable is ambiguous - could be node or literal
                    // For multi-triple patterns, we now handle these with UNION
                    variableObjectTriples.add(triple);
                }
            } else {
                relationshipTriples.add(triple);
            }
        }

        // NEW: Multi-triple patterns with ambiguous variable objects
        // Try partial optimization: optimize NODE variables, use UNION for AMBIGUOUS
        if (!variableObjectTriples.isEmpty() && triples.size() > 1) {
            LOGGER.debug("Multi-triple BGP with {} ambiguous variable objects - attempting partial optimization",
                    variableObjectTriples.size());
            
            // Check if we can apply partial optimization
            // Criteria: ambiguous variables should not depend on each other in complex ways
            boolean canOptimize = canApplyPartialOptimization(
                    variableObjectTriples, variableObjectRelTriples, relationshipTriples, literalTriples);
            
            if (canOptimize) {
                LOGGER.debug("Applying partial optimization for multi-triple pattern");
                return translateWithPartialOptimization(
                        bgp, analysis, variableObjectTriples, 
                        relationshipTriples, variableObjectRelTriples, 
                        typeTriples, literalTriples, parameters, paramCounter);
            } else {
                LOGGER.debug("Cannot apply partial optimization, falling back to standard evaluation");
                throw new CannotCompileException(
                    "Multi-triple patterns with ambiguous variable objects cannot be safely optimized yet. " +
                    "Pattern complexity requires standard evaluation for correctness.");
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
     * Extract predicate URI constraints from a FILTER expression.
     * 
     * <p>Analyzes FILTER expressions to find constraints on a variable predicate.
     * Supports:</p>
     * <ul>
     * <li>Equality: ?p = foaf:name</li>
     * <li>IN expressions: ?p IN (foaf:name, foaf:age, foaf:email)</li>
     * <li>OR combinations: ?p = foaf:name || ?p = foaf:age</li>
     * </ul>
     * 
     * @param filterExpr the FILTER expression to analyze
     * @param predicateVarName the name of the predicate variable to extract constraints for
     * @return set of predicate URIs that constrain the variable, or null if unconstrained
     */
    private static Set<String> extractPredicateConstraints(
            final Expr filterExpr,
            final String predicateVarName) {
        
        if (filterExpr == null || predicateVarName == null) {
            return null;
        }
        
        Set<String> constraints = new HashSet<>();
        
        // Handle equality: ?p = <uri>
        if (filterExpr instanceof E_Equals) {
            E_Equals eq = (E_Equals) filterExpr;
            Expr left = eq.getArg1();
            Expr right = eq.getArg2();
            
            // Check if one side is our predicate variable
            if (left instanceof ExprVar && ((ExprVar) left).getVarName().equals(predicateVarName)) {
                if (right instanceof NodeValue) {
                    Node node = ((NodeValue) right).getNode();
                    if (node != null && node.isURI()) {
                        constraints.add(node.getURI());
                        return constraints;
                    }
                }
            } else if (right instanceof ExprVar && ((ExprVar) right).getVarName().equals(predicateVarName)) {
                if (left instanceof NodeValue) {
                    Node node = ((NodeValue) left).getNode();
                    if (node != null && node.isURI()) {
                        constraints.add(node.getURI());
                        return constraints;
                    }
                }
            }
        }
        
        // Handle IN expression: ?p IN (uri1, uri2, ...)
        else if (filterExpr instanceof E_OneOf) {
            E_OneOf oneOf = (E_OneOf) filterExpr;
            Expr lhs = oneOf.getLHS();
            
            // Check if LHS is our predicate variable
            if (lhs instanceof ExprVar && ((ExprVar) lhs).getVarName().equals(predicateVarName)) {
                ExprList valuesList = oneOf.getRHS();
                for (Expr value : valuesList.getList()) {
                    if (value instanceof NodeValue) {
                        Node node = ((NodeValue) value).getNode();
                        if (node != null && node.isURI()) {
                            constraints.add(node.getURI());
                        }
                    }
                }
                if (!constraints.isEmpty()) {
                    return constraints;
                }
            }
        }
        
        // Handle OR expression: ?p = uri1 || ?p = uri2
        else if (filterExpr instanceof E_LogicalOr) {
            E_LogicalOr or = (E_LogicalOr) filterExpr;
            Set<String> leftConstraints = extractPredicateConstraints(or.getArg1(), predicateVarName);
            Set<String> rightConstraints = extractPredicateConstraints(or.getArg2(), predicateVarName);
            
            if (leftConstraints != null && rightConstraints != null) {
                constraints.addAll(leftConstraints);
                constraints.addAll(rightConstraints);
                return constraints;
            }
        }
        
        // Handle AND expression: could have ?p constraint combined with other filters
        else if (filterExpr instanceof E_LogicalAnd) {
            E_LogicalAnd and = (E_LogicalAnd) filterExpr;
            Set<String> leftConstraints = extractPredicateConstraints(and.getArg1(), predicateVarName);
            Set<String> rightConstraints = extractPredicateConstraints(and.getArg2(), predicateVarName);
            
            // For AND, only one side needs to constrain the predicate
            if (leftConstraints != null) {
                return leftConstraints;
            }
            if (rightConstraints != null) {
                return rightConstraints;
            }
        }
        
        // No constraints found or not a supported pattern
        return null;
    }
    
    /**
     * Internal translation method with filter without tracing.
     */
    private static CompilationResult translateWithFilterInternal(
            final BasicPattern bgp,
            final Expr filterExpr)
            throws CannotCompileException {
        
        // Check if we have variable predicates
        List<Triple> triples = bgp.getList();
        boolean hasVariablePredicate = triples.stream()
            .anyMatch(t -> t.getPredicate().isVariable());
        
        // If we have variable predicates, try to extract predicate constraints from FILTER
        if (hasVariablePredicate && triples.size() == 1) {
            Triple triple = triples.get(0);
            if (triple.getPredicate().isVariable()) {
                String predicateVarName = triple.getPredicate().getName();
                Set<String> constrainedPredicates = extractPredicateConstraints(filterExpr, predicateVarName);
                
                // If we found constraints, compile with the optimization
                if (constrainedPredicates != null && !constrainedPredicates.isEmpty()) {
                    CompilationResult result = translateWithVariablePredicates(triples, constrainedPredicates);
                    
                    // The FILTER is already incorporated into the Cypher query via constrained predicates
                    // So we return the result directly
                    return result;
                }
            }
        }
        
        // First, compile the BGP without filter (or with unconstrained variable predicates)
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
     * 
     * @param triples list of triples with variable predicates
     * @return the compilation result
     * @throws CannotCompileException if pattern cannot be compiled
     */
    private static CompilationResult translateWithVariablePredicates(
            final List<Triple> triples) throws CannotCompileException {
        return translateWithVariablePredicates(triples, null);
    }
    
    /**
     * Translate a BGP with variable predicates to Cypher.
     * Uses UNION to query both relationships and properties.
     * 
     * <p>If constrainedPredicates is provided, the properties part will only fetch
     * those specific predicates instead of using UNWIND keys() to fetch all properties.
     * This optimization significantly reduces data transfer when FILTER constraints
     * limit the possible predicates.</p>
     * 
     * @param triples list of triples with variable predicates
     * @param constrainedPredicates optional set of predicate URIs to constrain the query
     *                               (null means fetch all predicates)
     * @return the compilation result
     * @throws CannotCompileException if pattern cannot be compiled
     */
    private static CompilationResult translateWithVariablePredicates(
            final List<Triple> triples,
            final Set<String> constrainedPredicates) throws CannotCompileException {
        
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

        // Part 2: Query properties
        // Optimization: If we know which predicates to query, use explicit list instead of UNWIND keys()
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

        // Optimization: Use constrained predicates if provided
        if (constrainedPredicates != null && !constrainedPredicates.isEmpty()) {
            // Use explicit list of predicates instead of UNWIND keys()
            String listParamName = "p" + paramCounter++;
            parameters.put(listParamName, new ArrayList<>(constrainedPredicates));
            cypher.append("\nUNWIND $").append(listParamName).append(" AS _propKey");
            cypher.append("\nWHERE ").append(subjectVar).append("[_propKey] IS NOT NULL");
        } else {
            // No constraints - use UNWIND keys() to get all properties
            cypher.append("\nUNWIND keys(").append(subjectVar).append(") AS _propKey");
            cypher.append("\nWITH ").append(subjectVar)
                  .append(", _propKey WHERE _propKey <> 'uri'");
        }
        
        // Filter by object value if concrete
        if (triple.getObject().isLiteral()) {
            String paramName = "p" + paramCounter++;
            parameters.put(paramName, triple.getObject().getLiteralLexicalForm());
            
            // Add filter condition
            if (constrainedPredicates != null && !constrainedPredicates.isEmpty()) {
                cypher.append(" AND ").append(subjectVar)
                      .append("[_propKey] = $").append(paramName);
            } else {
                cypher.append(" AND ").append(subjectVar)
                      .append("[_propKey] = $").append(paramName);
            }
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
     * Translate OPTIONAL pattern with variable predicates to Cypher.
     * 
     * <p>This method handles SPARQL OPTIONAL patterns that contain variable predicates
     * by creating a UNION query that matches relationships, properties, and types
     * using OPTIONAL MATCH clauses.</p>
     * 
     * <h3>Example:</h3>
     * <pre>{@code
     * // SPARQL:
     * // SELECT ?person ?p ?o WHERE {
     * //   ?person rdf:type foaf:Person .
     * //   OPTIONAL { ?person ?p ?o }
     * // }
     * 
     * // Compiled Cypher (three-part UNION):
     * // MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
     * // OPTIONAL MATCH (person)-[_r]->(o:Resource)
     * // RETURN person.uri AS person, type(_r) AS p, o.uri AS o
     * // UNION ALL
     * // MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
     * // OPTIONAL MATCH (person)
     * // UNWIND keys(person) AS _propKey
     * // WITH person, _propKey WHERE _propKey <> 'uri'
     * // RETURN person.uri AS person, _propKey AS p, person[_propKey] AS o
     * // UNION ALL
     * // MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)
     * // OPTIONAL MATCH (person)
     * // UNWIND labels(person) AS _label
     * // WITH person, _label WHERE _label <> 'Resource'
     * // RETURN person.uri AS person, 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type' AS p, _label AS o
     * }</pre>
     *
     * @param requiredBGP the required basic graph pattern
     * @param optionalBGP the optional basic graph pattern with variable predicates
     * @param filterExpr optional filter expression (may be null)
     * @param requiredResult the compiled result for the required pattern
     * @param parameters the parameters map to add to
     * @param variableMapping the variable mapping to add to
     * @param allVariables all variables from both patterns
     * @param nodeVariables variables that represent nodes
     * @param declaredNodes nodes that have been declared
     * @return the compilation result containing Cypher query and metadata
     * @throws CannotCompileException if the pattern cannot be compiled
     */
    private static CompilationResult translateWithOptionalVariablePredicates(
            final BasicPattern requiredBGP,
            final BasicPattern optionalBGP,
            final Expr filterExpr,
            final CompilationResult requiredResult,
            final Map<String, Object> parameters,
            final Map<String, String> variableMapping,
            final Set<String> allVariables,
            final Set<String> nodeVariables,
            final Set<String> declaredNodes)
            throws CannotCompileException {
        
        List<Triple> optionalTriples = optionalBGP.getList();
        
        // For now, only support single triple with variable predicate in OPTIONAL
        if (optionalTriples.size() != 1) {
            throw new CannotCompileException(
                "Multiple triples with variable predicates in OPTIONAL not yet supported");
        }
        
        Triple triple = optionalTriples.get(0);
        
        // Ensure predicate is variable
        if (!triple.getPredicate().isVariable()) {
            throw new CannotCompileException(
                "Expected variable predicate in OPTIONAL pattern");
        }
        
        // Get required query parts (remove RETURN clause)
        String requiredQuery = requiredResult.cypherQuery();
        int returnIndex = requiredQuery.lastIndexOf("\nRETURN");
        if (returnIndex < 0) {
            throw new CannotCompileException(
                "Could not find RETURN clause in required pattern");
        }
        String requiredMatches = requiredQuery.substring(0, returnIndex);
        
        // Add FILTER clause if present
        if (filterExpr != null) {
            int paramCounter = parameters.size();
            String filterCypher = translateFilterExpr(filterExpr, variableMapping, nodeVariables, parameters, paramCounter);
            if (filterCypher != null && !filterCypher.isEmpty()) {
                if (requiredMatches.contains("\nWHERE ")) {
                    requiredMatches = requiredMatches + " AND " + filterCypher;
                } else {
                    requiredMatches = requiredMatches + "\nWHERE " + filterCypher;
                }
            }
        }
        
        int paramCounter = parameters.size();
        String subjectVar = getNodeVariable(triple.getSubject());
        String predicateVar = triple.getPredicate().getName();
        String objectVar = getNodeVariable(triple.getObject());
        
        // Add optional triple's variables to the sets
        allVariables.add(predicateVar);
        if (triple.getObject().isVariable()) {
            allVariables.add(triple.getObject().getName());
            nodeVariables.add(objectVar);
        }
        if (triple.getSubject().isVariable()) {
            allVariables.add(triple.getSubject().getName());
        }
        
        // Store parameter name for reuse in Parts 2 and 3
        String subjectParamName = null;
        
        StringBuilder cypher = new StringBuilder();
        
        // Part 1: Query relationships with OPTIONAL MATCH
        cypher.append(requiredMatches);
        cypher.append("\nOPTIONAL MATCH ");
        
        // Subject (should already be declared from required pattern)
        if (triple.getSubject().isVariable()) {
            cypher.append("(").append(subjectVar).append(")");
        } else {
            subjectParamName = "p" + paramCounter++;
            parameters.put(subjectParamName, triple.getSubject().getURI());
            cypher.append("(").append(subjectVar)
                  .append(":Resource {uri: $").append(subjectParamName).append("})");
        }
        
        // Relationship with variable type
        cypher.append("-[_r]->");
        
        // Object
        if (triple.getObject().isVariable()) {
            cypher.append("(").append(objectVar).append(":Resource)");
            nodeVariables.add(objectVar);
        } else if (triple.getObject().isURI()) {
            String paramName = "p" + paramCounter++;
            parameters.put(paramName, triple.getObject().getURI());
            cypher.append("(").append(objectVar)
                  .append(":Resource {uri: $").append(paramName).append("})");
            nodeVariables.add(objectVar);
        } else {
            throw new CannotCompileException(
                "Literal objects with variable predicates not supported in OPTIONAL");
        }
        
        cypher.append("\nRETURN ");
        
        // Build return clause for relationships part
        List<String> returnParts1 = new ArrayList<>();
        for (String varName : allVariables) {
            if (varName.equals(predicateVar)) {
                returnParts1.add("type(_r) AS " + predicateVar);
            } else if (variableMapping.containsKey(varName)) {
                returnParts1.add(variableMapping.get(varName) + " AS " + varName);
            } else if (nodeVariables.contains(varName)) {
                returnParts1.add(sanitizeVariableName(varName) + ".uri AS " + varName);
            }
        }
        
        // Ensure predicate variable is in return
        if (!returnParts1.stream().anyMatch(p -> p.contains(" AS " + predicateVar))) {
            returnParts1.add("type(_r) AS " + predicateVar);
        }
        
        cypher.append(String.join(", ", returnParts1));
        
        // Part 2: Query properties with OPTIONAL MATCH
        cypher.append("\nUNION ALL\n");
        cypher.append(requiredMatches);
        cypher.append("\nOPTIONAL MATCH ");
        
        // Subject
        if (triple.getSubject().isVariable()) {
            cypher.append("(").append(subjectVar).append(")");
        } else {
            // Reuse the parameter from Part 1
            cypher.append("(").append(subjectVar)
                  .append(":Resource {uri: $").append(subjectParamName).append("})");
        }
        
        cypher.append("\nUNWIND keys(").append(subjectVar).append(") AS _propKey");
        cypher.append("\nWITH ");
        
        // Include all required variables in WITH clause
        List<String> withParts = new ArrayList<>();
        for (String varName : allVariables) {
            if (!varName.equals(predicateVar) && !varName.equals(triple.getObject().getName())) {
                if (nodeVariables.contains(varName)) {
                    withParts.add(sanitizeVariableName(varName));
                }
            }
        }
        if (!withParts.isEmpty()) {
            cypher.append(String.join(", ", withParts)).append(", ");
        }
        
        cypher.append("_propKey WHERE _propKey <> 'uri'");
        
        cypher.append("\nRETURN ");
        
        // Build return clause for properties part
        List<String> returnParts2 = new ArrayList<>();
        for (String varName : allVariables) {
            if (varName.equals(predicateVar)) {
                returnParts2.add("_propKey AS " + predicateVar);
            } else if (varName.equals(triple.getObject().getName())) {
                returnParts2.add(subjectVar + "[_propKey] AS " + varName);
            } else if (variableMapping.containsKey(varName)) {
                returnParts2.add(variableMapping.get(varName) + " AS " + varName);
            } else if (nodeVariables.contains(varName)) {
                returnParts2.add(sanitizeVariableName(varName) + ".uri AS " + varName);
            }
        }
        
        cypher.append(String.join(", ", returnParts2));
        
        // Part 3: Query types (rdf:type from labels) with OPTIONAL MATCH
        cypher.append("\nUNION ALL\n");
        cypher.append(requiredMatches);
        cypher.append("\nOPTIONAL MATCH ");
        
        // Subject
        if (triple.getSubject().isVariable()) {
            cypher.append("(").append(subjectVar).append(")");
        } else {
            // Reuse the parameter from Part 1
            cypher.append("(").append(subjectVar)
                  .append(":Resource {uri: $").append(subjectParamName).append("})");
        }
        
        cypher.append("\nUNWIND labels(").append(subjectVar).append(") AS _label");
        cypher.append("\nWITH ");
        
        // Include all required variables in WITH clause
        if (!withParts.isEmpty()) {
            cypher.append(String.join(", ", withParts)).append(", ");
        }
        
        cypher.append("_label WHERE _label <> 'Resource'");
        
        cypher.append("\nRETURN ");
        
        // Build return clause for types part
        List<String> returnParts3 = new ArrayList<>();
        for (String varName : allVariables) {
            if (varName.equals(predicateVar)) {
                returnParts3.add("'" + RDF_TYPE_URI + "' AS " + predicateVar);
            } else if (varName.equals(triple.getObject().getName())) {
                returnParts3.add("_label AS " + varName);
            } else if (variableMapping.containsKey(varName)) {
                returnParts3.add(variableMapping.get(varName) + " AS " + varName);
            } else if (nodeVariables.contains(varName)) {
                returnParts3.add(sanitizeVariableName(varName) + ".uri AS " + varName);
            }
        }
        
        cypher.append(String.join(", ", returnParts3));
        
        String query = cypher.toString();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Compiled OPTIONAL with variable predicate to Cypher:\n{}", 
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
     * Translate multi-triple BGP with ambiguous variable objects to Cypher.
     * 
     * <p>This method handles multi-triple patterns where some variable objects are ambiguous
     * (could be either nodes or literals). It generates a combination of regular MATCH clauses
     * for non-ambiguous triples and UNION-based queries for ambiguous variables.</p>
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * // SPARQL:
     * // ?person foaf:name ?name . ?person foaf:age ?age . ?person foaf:knows ?friend
     * 
     * // Where ?name and ?age are ambiguous (could be literals or relationships)
     * // and ?friend is ambiguous
     * 
     * // Generated Cypher uses regular MATCH for the subject and checks both
     * // relationship and property patterns for ambiguous variables
     * }</pre>
     *
     * @param bgp the complete basic graph pattern
     * @param analysis the variable analysis result
     * @param ambiguousTriples list of triples with ambiguous variable objects
     * @return compilation result with Cypher query
     * @throws CannotCompileException if pattern cannot be compiled
     */
    private static CompilationResult translateWithAmbiguousVariables(
            final BasicPattern bgp,
            final VariableAnalyzer.AnalysisResult analysis,
            final List<Triple> ambiguousTriples) throws CannotCompileException {
        
        LOGGER.debug("Translating multi-triple BGP with {} ambiguous variables",
                ambiguousTriples.size());

        Map<String, Object> parameters = new HashMap<>();
        Map<String, String> variableMapping = new HashMap<>();
        int paramCounter = 0;

        List<Triple> allTriples = bgp.getList();
        Set<String> allVariables = new HashSet<>();
        Set<String> nodeVariables = new HashSet<>();

        // Collect all variables
        for (Triple triple : allTriples) {
            collectVariables(triple, allVariables, nodeVariables);
        }

        // Separate triples into non-ambiguous and ambiguous
        List<Triple> nonAmbiguousTriples = new ArrayList<>();
        for (Triple triple : allTriples) {
            if (!ambiguousTriples.contains(triple)) {
                nonAmbiguousTriples.add(triple);
            }
        }

        // Build the base MATCH clauses for non-ambiguous triples
        StringBuilder cypher = new StringBuilder();
        Set<String> declaredNodes = new HashSet<>();

        // Build MATCH clauses for non-ambiguous patterns
        for (Triple triple : nonAmbiguousTriples) {
            Node subject = triple.getSubject();
            Node predicate = triple.getPredicate();
            Node object = triple.getObject();
            
            String subjectVar = getNodeVariable(subject);

            if (predicate.isURI() && predicate.getURI().equals(RDF_TYPE_URI)) {
                // Type triple
                if (!cypher.isEmpty()) {
                    cypher.append("\n");
                }
                cypher.append("MATCH ");
                
                if (object.isURI()) {
                    String typeLabel = sanitizeCypherIdentifier(object.getURI());
                    if (subject.isVariable()) {
                        if (declaredNodes.contains(subjectVar)) {
                            cypher.append("(").append(subjectVar)
                                  .append(":`").append(typeLabel).append("`)");
                        } else {
                            cypher.append("(").append(subjectVar)
                                  .append(":Resource:`").append(typeLabel).append("`)");
                            declaredNodes.add(subjectVar);
                        }
                    } else {
                        String paramName = "p" + paramCounter++;
                        parameters.put(paramName, subject.getURI());
                        cypher.append("(").append(subjectVar)
                              .append(":Resource:`").append(typeLabel)
                              .append("` {uri: $").append(paramName).append("})");
                        declaredNodes.add(subjectVar);
                    }
                }
            } else if (object.isLiteral()) {
                // Literal property triple
                if (!declaredNodes.contains(subjectVar)) {
                    if (!cypher.isEmpty()) {
                        cypher.append("\n");
                    }
                    cypher.append("MATCH ");
                    if (subject.isVariable()) {
                        cypher.append("(").append(subjectVar).append(":Resource)");
                    } else {
                        String paramName = "p" + paramCounter++;
                        parameters.put(paramName, subject.getURI());
                        cypher.append("(").append(subjectVar)
                              .append(":Resource {uri: $").append(paramName).append("})");
                    }
                    declaredNodes.add(subjectVar);
                }
            } else if (object.isURI() || (object.isVariable() && analysis.isNodeVariable(object.getName()))) {
                // Relationship triple (object is URI or known node variable)
                if (!cypher.isEmpty()) {
                    cypher.append("\n");
                }
                cypher.append("MATCH ");

                appendSubjectNode(cypher, subject, subjectVar, declaredNodes, parameters, paramCounter);
                if (subject.isURI()) {
                    paramCounter++;
                }

                String relType = sanitizeCypherIdentifier(predicate.getURI());
                cypher.append("-[:`").append(relType).append("`]->");

                String objectVar = getNodeVariable(object);
                if (object.isVariable()) {
                    if (declaredNodes.contains(objectVar)) {
                        cypher.append("(").append(objectVar).append(")");
                    } else {
                        cypher.append("(").append(objectVar).append(":Resource)");
                        declaredNodes.add(objectVar);
                    }
                } else {
                    String paramName = "p" + paramCounter++;
                    parameters.put(paramName, object.getURI());
                    cypher.append("(").append(objectVar)
                          .append(":Resource {uri: $").append(paramName).append("})");
                    declaredNodes.add(objectVar);
                }
            }
        }

        // Now handle ambiguous triples - for each ambiguous triple, we'll add WHERE conditions
        // that check both relationship and property paths
        List<String> whereConditions = new ArrayList<>();
        
        for (Triple triple : ambiguousTriples) {
            Node subject = triple.getSubject();
            Node predicate = triple.getPredicate();
            Node object = triple.getObject();
            
            String subjectVar = getNodeVariable(subject);
            String objectVar = object.getName();
            String predUri = sanitizeCypherIdentifier(predicate.getURI());

            // Ensure subject node is declared
            if (!declaredNodes.contains(subjectVar)) {
                if (!cypher.isEmpty()) {
                    cypher.append("\n");
                }
                cypher.append("MATCH ");
                if (subject.isVariable()) {
                    cypher.append("(").append(subjectVar).append(":Resource)");
                } else {
                    String paramName = "p" + paramCounter++;
                    parameters.put(paramName, subject.getURI());
                    cypher.append("(").append(subjectVar)
                          .append(":Resource {uri: $").append(paramName).append("})");
                }
                declaredNodes.add(subjectVar);
            }

            // For ambiguous variables, we need to check if they match via relationship OR property
            // This is handled by adding the ambiguous object to the variable mapping
            // The actual matching is done via OPTIONAL MATCH for both paths

            // Add OPTIONAL MATCH for relationship path
            cypher.append("\nOPTIONAL MATCH (").append(subjectVar)
                  .append(")-[:`").append(predUri).append("`]->(")
                  .append(objectVar).append("_rel:Resource)");

            // Add OPTIONAL MATCH for property path (checking if property exists)
            // Note: We can't directly MATCH on property, so we'll use WHERE conditions instead
            
            // Track the variable mapping for the return clause
            // The object could come from either the relationship or the property
            variableMapping.put(objectVar, 
                "coalesce(" + objectVar + "_rel.uri, " + subjectVar + ".`" + predUri + "`)");
        }

        // Build RETURN clause
        cypher.append("\nRETURN ");
        List<String> returnParts = new ArrayList<>();

        for (String varName : allVariables) {
            if (variableMapping.containsKey(varName)) {
                // Ambiguous variable - use COALESCE to prefer relationship over property
                returnParts.add(variableMapping.get(varName) + " AS " + varName);
            } else if (nodeVariables.contains(varName) || analysis.isNodeVariable(varName)) {
                // Known node variable
                returnParts.add(varName + ".uri AS " + varName);
            }
        }

        if (returnParts.isEmpty()) {
            returnParts.add("1 AS _result");
        }

        cypher.append(String.join(", ", returnParts));

        String query = cypher.toString();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Compiled multi-triple BGP with ambiguous variables to Cypher:\n{}", query);
        }

        return new CompilationResult(query, parameters, variableMapping);
    }

    /**
     * Check if partial optimization can be applied to a pattern with ambiguous variables.
     * 
     * <p>Partial optimization is safe when:</p>
     * <ul>
     *   <li>The pattern has NODE relationships (object variables used as subjects)</li>
     *   <li>Ambiguous variables can be handled with reasonable UNION size</li>
     * </ul>
     *
     * <p>We are conservative and only optimize when we have clear NODE relationships
     * to avoid issues with FILTER expressions that might operate on ambiguous variables.</p>
     *
     * @param ambiguousTriples triples with ambiguous variable objects
     * @param nodeRelTriples triples with known node relationships
     * @param relationshipTriples triples with concrete relationships
     * @param literalTriples triples with concrete literals
     * @return true if partial optimization is safe to apply
     */
    private static boolean canApplyPartialOptimization(
            final List<Triple> ambiguousTriples,
            final List<Triple> nodeRelTriples,
            final List<Triple> relationshipTriples,
            final List<Triple> literalTriples) {
        
        // Get unique ambiguous variables
        Set<String> ambiguousObjects = new HashSet<>();
        for (Triple triple : ambiguousTriples) {
            if (triple.getObject().isVariable()) {
                ambiguousObjects.add(triple.getObject().getName());
            }
        }
        
        // Limit to reasonable number to avoid query explosion
        if (ambiguousObjects.size() > 4) {
            return false; // Too many ambiguous vars
        }
        
        // Only optimize if we have NODE relationships or concrete relationships
        // This ensures we have graph structure to optimize
        // Don't optimize based solely on literal triples as those patterns
        // often have FILTER expressions that don't work well with UNION
        boolean hasGraphStructure = !relationshipTriples.isEmpty() || !nodeRelTriples.isEmpty();
        
        return hasGraphStructure;
    }

    /**
     * Translate pattern with partial optimization for ambiguous variables.
     * 
     * <p>This method implements the partial optimization strategy for patterns where
     * ambiguous variables can appear ANYWHERE in the pattern (not just at the end):</p>
     * <ol>
     *   <li>Generate optimized Cypher for NODE relationships (definite graph traversal)</li>
     *   <li>Use UNION to handle all possible combinations of ambiguous variables</li>
     *   <li>Each ambiguous variable can be either a relationship or a property</li>
     * </ol>
     *
     * <p>Example: For 2 ambiguous variables, generates 2^2 = 4 queries in UNION:
     * <ul>
     *   <li>Both as relationships</li>
     *   <li>First as relationship, second as property</li>
     *   <li>First as property, second as relationship</li>
     *   <li>Both as properties</li>
     * </ul>
     * </p>
     *
     * @param bgp the complete basic graph pattern
     * @param analysis the variable analysis result
     * @param ambiguousTriples triples with ambiguous objects
     * @param relationshipTriples triples with concrete relationships
     * @param nodeRelTriples triples with known node relationships
     * @param typeTriples triples with rdf:type
     * @param literalTriples triples with concrete literals
     * @param parameters existing parameters map
     * @param paramCounter parameter counter
     * @return compilation result with optimized Cypher
     * @throws CannotCompileException if compilation fails
     */
    private static CompilationResult translateWithPartialOptimization(
            final BasicPattern bgp,
            final VariableAnalyzer.AnalysisResult analysis,
            final List<Triple> ambiguousTriples,
            final List<Triple> relationshipTriples,
            final List<Triple> nodeRelTriples,
            final List<Triple> typeTriples,
            final List<Triple> literalTriples,
            final Map<String, Object> parameters,
            int paramCounter) throws CannotCompileException {
        
        LOGGER.debug("Generating partial optimization: {} node triples, {} ambiguous triples",
                relationshipTriples.size() + nodeRelTriples.size(), ambiguousTriples.size());
        
        // Collect all variables
        Set<String> allVariables = new HashSet<>();
        Set<String> nodeVariables = new HashSet<>();
        for (Triple triple : bgp.getList()) {
            collectVariables(triple, allVariables, nodeVariables);
        }
        
        // Get unique ambiguous variables
        List<String> ambiguousVars = new ArrayList<>();
        for (Triple triple : ambiguousTriples) {
            if (triple.getObject().isVariable()) {
                String varName = triple.getObject().getName();
                if (!ambiguousVars.contains(varName)) {
                    ambiguousVars.add(varName);
                }
            }
        }
        
        int numAmbiguous = ambiguousVars.size();
        int numCombinations = (int) Math.pow(2, numAmbiguous);
        
        LOGGER.debug("Generating {} UNION queries for {} ambiguous variables", 
                numCombinations, numAmbiguous);
        
        // Build all combinations: for each ambiguous variable, try both as relationship and property
        StringBuilder finalQuery = new StringBuilder();
        
        for (int i = 0; i < numCombinations; i++) {
            if (i > 0) {
                finalQuery.append("\nUNION ALL\n");
            }
            
            // Determine which ambiguous variables are relationships vs properties in this combination
            Map<String, Boolean> varIsRelationship = new HashMap<>();
            for (int j = 0; j < numAmbiguous; j++) {
                boolean isRel = ((i >> j) & 1) == 1;
                varIsRelationship.put(ambiguousVars.get(j), isRel);
            }
            
            // Build query for this combination
            StringBuilder queryPart = buildQueryForCombination(
                    relationshipTriples, nodeRelTriples, typeTriples, literalTriples,
                    ambiguousTriples, analysis, allVariables, nodeVariables,
                    varIsRelationship, parameters, paramCounter);
            
            finalQuery.append(queryPart);
        }
        
        Map<String, String> variableMapping = new HashMap<>();
        String query = finalQuery.toString();
        
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Generated partial optimization query with {} combinations:\n{}", 
                    numCombinations, query);
        }
        
        return new CompilationResult(query, parameters, variableMapping);
    }

    /**
     * Build a single Cypher query for a specific combination of ambiguous variable interpretations.
     *
     * @param relationshipTriples concrete relationship triples
     * @param nodeRelTriples node relationship triples
     * @param typeTriples type triples
     * @param literalTriples literal triples
     * @param ambiguousTriples ambiguous triples
     * @param analysis variable analysis
     * @param allVariables all variables in pattern
     * @param nodeVariables node variables
     * @param varIsRelationship map of ambiguous var name -> true if relationship, false if property
     * @param parameters parameters map
     * @param paramCounter parameter counter
     * @return Cypher query for this combination
     */
    private static StringBuilder buildQueryForCombination(
            final List<Triple> relationshipTriples,
            final List<Triple> nodeRelTriples,
            final List<Triple> typeTriples,
            final List<Triple> literalTriples,
            final List<Triple> ambiguousTriples,
            final VariableAnalyzer.AnalysisResult analysis,
            final Set<String> allVariables,
            final Set<String> nodeVariables,
            final Map<String, Boolean> varIsRelationship,
            final Map<String, Object> parameters,
            int paramCounter) {
        
        StringBuilder cypher = new StringBuilder();
        Set<String> declaredNodes = new HashSet<>();
        
        // Build MATCH for type triples
        for (Triple triple : typeTriples) {
            if (!cypher.isEmpty()) cypher.append("\n");
            cypher.append("MATCH ");
            String subjectVar = getNodeVariable(triple.getSubject());
            if (triple.getObject().isURI()) {
                String typeLabel = sanitizeCypherIdentifier(triple.getObject().getURI());
                cypher.append("(").append(subjectVar)
                      .append(":Resource:`").append(typeLabel).append("`)");
                declaredNodes.add(subjectVar);
            }
        }
        
        // Build MATCH for concrete relationships
        for (Triple triple : relationshipTriples) {
            if (!cypher.isEmpty()) cypher.append("\n");
            cypher.append("MATCH ");
            String subjectVar = getNodeVariable(triple.getSubject());
            String objectVar = getNodeVariable(triple.getObject());
            String relType = sanitizeCypherIdentifier(triple.getPredicate().getURI());
            
            if (!declaredNodes.contains(subjectVar)) {
                cypher.append("(").append(subjectVar).append(":Resource)");
                declaredNodes.add(subjectVar);
            } else {
                cypher.append("(").append(subjectVar).append(")");
            }
            
            cypher.append("-[:`").append(relType).append("`]->");
            cypher.append("(").append(objectVar).append(":Resource)");
            declaredNodes.add(objectVar);
        }
        
        // Build MATCH for node relationships (objects used as subjects)
        for (Triple triple : nodeRelTriples) {
            if (!cypher.isEmpty()) cypher.append("\n");
            cypher.append("MATCH ");
            String subjectVar = getNodeVariable(triple.getSubject());
            String objectVar = getNodeVariable(triple.getObject());
            String relType = sanitizeCypherIdentifier(triple.getPredicate().getURI());
            
            if (!declaredNodes.contains(subjectVar)) {
                cypher.append("(").append(subjectVar).append(":Resource)");
                declaredNodes.add(subjectVar);
            } else {
                cypher.append("(").append(subjectVar).append(")");
            }
            
            cypher.append("-[:`").append(relType).append("`]->");
            cypher.append("(").append(objectVar).append(":Resource)");
            declaredNodes.add(objectVar);
        }
        
        // Handle ambiguous triples based on this combination
        List<String> whereConditions = new ArrayList<>();
        Map<String, String> propertyReturns = new HashMap<>();
        
        for (Triple triple : ambiguousTriples) {
            String subjectVar = getNodeVariable(triple.getSubject());
            String objectVar = triple.getObject().getName();
            String predUri = sanitizeCypherIdentifier(triple.getPredicate().getURI());
            
            // Ensure subject is declared
            if (!declaredNodes.contains(subjectVar)) {
                if (!cypher.isEmpty()) cypher.append("\n");
                cypher.append("MATCH (").append(subjectVar).append(":Resource)");
                declaredNodes.add(subjectVar);
            }
            
            Boolean isRel = varIsRelationship.get(objectVar);
            if (isRel != null && isRel) {
                // Treat as relationship
                if (!cypher.isEmpty()) cypher.append("\n");
                cypher.append("MATCH (").append(subjectVar)
                      .append(")-[:`").append(predUri).append("`]->(")
                      .append(objectVar).append(":Resource)");
                declaredNodes.add(objectVar);
            } else {
                // Treat as property
                whereConditions.add(subjectVar + ".`" + predUri + "` IS NOT NULL");
                propertyReturns.put(objectVar, subjectVar + ".`" + predUri + "`");
            }
        }
        
        // Add WHERE clause if needed
        if (!whereConditions.isEmpty()) {
            cypher.append("\nWHERE ").append(String.join(" AND ", whereConditions));
        }
        
        // Build RETURN clause
        cypher.append("\nRETURN ");
        List<String> returnParts = new ArrayList<>();
        for (String varName : allVariables) {
            if (propertyReturns.containsKey(varName)) {
                // This is an ambiguous variable being treated as property
                returnParts.add(propertyReturns.get(varName) + " AS " + varName);
            } else if (analysis.isNodeVariable(varName) || nodeVariables.contains(varName) || declaredNodes.contains(varName)) {
                // This is a node variable
                returnParts.add(varName + ".uri AS " + varName);
            }
        }
        
        if (returnParts.isEmpty()) {
            returnParts.add("1 AS _result");
        }
        
        cypher.append(String.join(", ", returnParts));
        
        return cypher;
    }

    /**
     * Build optimized Cypher path for node relationships.
     *
     * @param relationshipTriples concrete relationship triples
     * @param nodeRelTriples node relationship triples
     * @param typeTriples type triples
     * @param literalTriples literal triples
     * @param ambiguousTriples ambiguous triples
     * @param analysis variable analysis
     * @param parameters parameters map
     * @param paramCounter parameter counter
     * @param treatAmbiguousAsRelationships true to treat ambiguous vars as relationships, false as properties
     * @return Cypher query string
     */
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
