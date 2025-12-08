package com.falkordb.jena.query;

import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.aggregate.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates SPARQL aggregation expressions to Cypher equivalents.
 *
 * <p>Supports translation of common aggregation functions:</p>
 * <ul>
 *   <li>COUNT - count()</li>
 *   <li>SUM - sum()</li>
 *   <li>AVG - avg()</li>
 *   <li>MIN - min()</li>
 *   <li>MAX - max()</li>
 *   <li>COUNT(DISTINCT) - count(DISTINCT)</li>
 * </ul>
 *
 * <h2>Example:</h2>
 * <pre>{@code
 * // SPARQL:
 * SELECT ?type (COUNT(?person) AS ?count) WHERE {
 *     ?person rdf:type ?type
 * } GROUP BY ?type
 *
 * // Translates to Cypher:
 * MATCH (person:Resource)
 * UNWIND labels(person) AS type
 * WHERE type <> 'Resource'
 * RETURN type, count(person) AS count
 * }</pre>
 */
public final class AggregationToCypherTranslator {

    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        AggregationToCypherTranslator.class);

    /**
     * Exception thrown when aggregation cannot be translated.
     */
    public static class CannotTranslateAggregationException extends Exception {
        /**
         * Constructs a new exception with the specified detail message.
         *
         * @param message the detail message
         */
        public CannotTranslateAggregationException(final String message) {
            super(message);
        }
    }

    /**
     * Result of aggregation translation.
     *
     * @param returnClause the Cypher RETURN clause with aggregations
     * @param groupByVars the list of group by variables
     */
    public record AggregationResult(
        String returnClause,
        List<String> groupByVars
    ) { }

    /**
     * Private constructor to prevent instantiation.
     */
    private AggregationToCypherTranslator() {
        throw new UnsupportedOperationException(
            "AggregationToCypherTranslator is a utility class");
    }

    /**
     * Translate SPARQL aggregation expressions to Cypher RETURN clause.
     *
     * <p>Converts SPARQL aggregations like COUNT(?x), SUM(?y), etc. to
     * their Cypher equivalents.</p>
     *
     * @param aggregators the SPARQL aggregation expressions
     * @param groupByVars the GROUP BY variables
     * @param variableMapping mapping from SPARQL variables to Cypher identifiers
     * @return the aggregation result with RETURN clause
     * @throws CannotTranslateAggregationException if translation fails
     */
    public static AggregationResult translate(
            final List<ExprAggregator> aggregators,
            final VarExprList groupByVars,
            final Map<String, String> variableMapping)
            throws CannotTranslateAggregationException {

        if (aggregators == null || aggregators.isEmpty()) {
            throw new CannotTranslateAggregationException(
                "No aggregations provided");
        }

        List<String> returnParts = new ArrayList<>();
        List<String> groupVars = new ArrayList<>();

        // Process GROUP BY variables first
        if (groupByVars != null && !groupByVars.isEmpty()) {
            for (Var groupVar : groupByVars.getVars()) {
                String cypherVar = variableMapping.getOrDefault(
                    groupVar.getVarName(), groupVar.getVarName());
                returnParts.add(cypherVar);
                groupVars.add(cypherVar);
                
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("GROUP BY variable: {} -> {}", 
                        groupVar.getVarName(), cypherVar);
                }
            }
        }

        // Process aggregation expressions
        for (ExprAggregator exprAgg : aggregators) {
            Var resultVar = exprAgg.getVar();
            String aggExpr = translateExprAggregator(
                exprAgg, variableMapping);
            returnParts.add(aggExpr + " AS " + resultVar.getVarName());
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Translated aggregation: {} AS {}", 
                    aggExpr, resultVar.getVarName());
            }
        }

        if (returnParts.isEmpty()) {
            throw new CannotTranslateAggregationException(
                "No return expressions generated");
        }

        String returnClause = String.join(", ", returnParts);
        
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Final RETURN clause: {}", returnClause);
            LOGGER.debug("GROUP BY vars: {}", groupVars);
        }

        return new AggregationResult(returnClause, groupVars);
    }

    /**
     * Translate a single ExprAggregator to Cypher.
     *
     * @param exprAgg the SPARQL expression aggregator
     * @param variableMapping mapping from SPARQL variables to Cypher
     * @return the Cypher aggregation expression
     * @throws CannotTranslateAggregationException if translation fails
     */
    private static String translateExprAggregator(
            final ExprAggregator exprAgg,
            final Map<String, String> variableMapping)
            throws CannotTranslateAggregationException {

        Aggregator aggregator = exprAgg.getAggregator();
        ExprVar aggVar = exprAgg.getAggVar();

        String innerVar;
        if (aggVar != null) {
            innerVar = variableMapping.getOrDefault(
                aggVar.getVarName(), aggVar.getVarName());
        } else {
            throw new CannotTranslateAggregationException(
                "Aggregation variable is null");
        }

        String cypherAgg = translateAggregator(aggregator, innerVar);
        return cypherAgg;
    }

    /**
     * Translate a SPARQL aggregator to Cypher.
     *
     * @param aggregator the SPARQL aggregator
     * @param variable the variable to aggregate
     * @return the Cypher aggregation function
     * @throws CannotTranslateAggregationException if aggregator not supported
     */
    private static String translateAggregator(
            final Aggregator aggregator,
            final String variable)
            throws CannotTranslateAggregationException {

        // COUNT
        if (aggregator instanceof AggCount) {
            return "count(" + variable + ")";
        }
        if (aggregator instanceof AggCountDistinct) {
            return "count(DISTINCT " + variable + ")";
        }
        // AggCountVar is like AggCount but for specific variables
        if (aggregator.getClass().getSimpleName().equals("AggCountVar")) {
            return "count(" + variable + ")";
        }
        if (aggregator.getClass().getSimpleName().equals("AggCountVarDistinct")) {
            return "count(DISTINCT " + variable + ")";
        }

        // SUM
        if (aggregator instanceof AggSum) {
            return "sum(" + variable + ")";
        }
        if (aggregator instanceof AggSumDistinct) {
            return "sum(DISTINCT " + variable + ")";
        }

        // AVG
        if (aggregator instanceof AggAvg) {
            return "avg(" + variable + ")";
        }
        if (aggregator instanceof AggAvgDistinct) {
            return "avg(DISTINCT " + variable + ")";
        }

        // MIN
        if (aggregator instanceof AggMin) {
            return "min(" + variable + ")";
        }

        // MAX
        if (aggregator instanceof AggMax) {
            return "max(" + variable + ")";
        }

        throw new CannotTranslateAggregationException(
            "Unsupported aggregator: " + aggregator.getClass().getSimpleName());
    }
}
