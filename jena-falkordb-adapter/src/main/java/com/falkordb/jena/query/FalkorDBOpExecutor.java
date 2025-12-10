package com.falkordb.jena.query;

import com.falkordb.Header;
import com.falkordb.Record;
import com.falkordb.ResultSet;
import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.tracing.TracingUtil;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.reasoner.InfGraph;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpGroup;
import org.apache.jena.sparql.algebra.op.OpLeftJoin;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.engine.main.OpExecutor;
import org.apache.jena.sparql.engine.main.OpExecutorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Custom OpExecutor that pushes Basic Graph Pattern (BGP) evaluation
 * down to FalkorDB using native Cypher queries.
 *
 * <p>This executor intercepts {@link OpBGP} operations and translates them
 * to Cypher MATCH queries for efficient execution. Instead of the default
 * triple-by-triple evaluation which can result in N+1 database queries,
 * this executor sends a single optimized Cypher query.</p>
 *
 * <h2>Example:</h2>
 * <pre>{@code
 * // SPARQL query:
 * // SELECT ?name WHERE {
 * //   ?person foaf:knows ?friend .
 * //   ?friend foaf:name ?name .
 * // }
 *
 * // Instead of:
 * // Query 1: find all (?person, foaf:knows, ?friend)
 * // For each result, Query 2: find (?friend, foaf:name, ?name)
 *
 * // Executes as:
 * // MATCH (person:Resource)-[:`foaf:knows`]->(friend:Resource)
 * // WHERE friend.`foaf:name` IS NOT NULL
 * // RETURN friend.`foaf:name` AS name
 * }</pre>
 *
 * <p>If the BGP cannot be compiled to Cypher (e.g., due to unsupported
 * features like variable predicates), the executor falls back to the
 * standard Jena evaluation.</p>
 */
public final class FalkorDBOpExecutor extends OpExecutor {

    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        FalkorDBOpExecutor.class);

    /** Instrumentation scope name. */
    public static final String SCOPE_OP_EXECUTOR =
        "com.falkordb.jena.query.FalkorDBOpExecutor";

    /** Tracer for OpExecutor operations. */
    private final Tracer tracer;

    /** The FalkorDB graph instance, or null if not available. */
    private final FalkorDBGraph falkorGraph;

    /** Attribute key for Cypher query. */
    private static final AttributeKey<String> ATTR_CYPHER_QUERY =
        AttributeKey.stringKey("falkordb.cypher.query");

    /** Attribute key for result count. */
    private static final AttributeKey<Long> ATTR_RESULT_COUNT =
        AttributeKey.longKey("falkordb.result_count");

    /** Attribute key for fallback indicator. */
    private static final AttributeKey<Boolean> ATTR_FALLBACK =
        AttributeKey.booleanKey("falkordb.fallback");

    /** Attribute key for triple count. */
    private static final AttributeKey<Long> ATTR_TRIPLE_COUNT =
        AttributeKey.longKey("sparql.bgp.triple_count");

    /** Attribute key for optimization type. */
    private static final AttributeKey<String> ATTR_OPTIMIZATION_TYPE =
        AttributeKey.stringKey("falkordb.optimization.type");

    /**
     * Factory for creating FalkorDBOpExecutor instances.
     */
    public static class Factory implements OpExecutorFactory {
        /**
         * Constructs a new Factory.
         */
        public Factory() {
            // Default constructor
        }
        
        @Override
        public OpExecutor create(final ExecutionContext execCxt) {
            return new FalkorDBOpExecutor(execCxt);
        }
    }

    /**
     * Create a new FalkorDBOpExecutor.
     *
     * @param execCxt the execution context
     */
    public FalkorDBOpExecutor(final ExecutionContext execCxt) {
        super(execCxt);
        this.tracer = TracingUtil.getTracer(SCOPE_OP_EXECUTOR);
        this.falkorGraph = findFalkorDBGraph(execCxt.getActiveGraph());
    }

    /**
     * Execute a Basic Graph Pattern.
     *
     * <p>This method attempts to compile the BGP to Cypher and execute it
     * natively on FalkorDB. If compilation fails, it falls back to the
     * standard Jena evaluation.</p>
     *
     * @param opBGP the BGP operation
     * @param input the input query iterator
     * @return the result query iterator
     */
    @Override
    protected QueryIterator execute(final OpBGP opBGP,
                                    final QueryIterator input) {
        // If we don't have a FalkorDB graph, fall back to standard execution
        if (falkorGraph == null) {
            return super.execute(opBGP, input);
        }

        int tripleCount = opBGP.getPattern().size();
        
        Span span = tracer.spanBuilder("FalkorDBOpExecutor.execute")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPTIMIZATION_TYPE, "BGP_EXECUTION")
            .setAttribute(ATTR_TRIPLE_COUNT, (long) tripleCount)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Try to compile the BGP to Cypher
            SparqlToCypherCompiler.CompilationResult compilation =
                SparqlToCypherCompiler.translate(opBGP.getPattern());

            String cypherQuery = compilation.cypherQuery();
            Map<String, Object> parameters = compilation.parameters();

            span.setAttribute(ATTR_CYPHER_QUERY, cypherQuery);
            span.setAttribute(ATTR_FALLBACK, false);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("BGP pushdown: executing Cypher query:\n{}",
                    cypherQuery);
            }

            // Execute on FalkorDB
            ResultSet resultSet = falkorGraph.getTracedGraph()
                .query(cypherQuery, parameters);

            // Convert results to bindings
            List<Binding> bindings = convertResultsToBindings(
                resultSet, input);

            span.setAttribute(ATTR_RESULT_COUNT, (long) bindings.size());
            span.setStatus(StatusCode.OK);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("BGP pushdown: returned {} results",
                    bindings.size());
            }

            return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);

        } catch (SparqlToCypherCompiler.CannotCompileException e) {
            // Fall back to standard Jena execution
            span.setAttribute(ATTR_FALLBACK, true);
            span.addEvent("Falling back to standard execution: " 
                + e.getMessage());

            // Warn when optimization limitation causes fallback to Jena
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("BGP query pushdown optimization not applicable, "
                    + "using Jena fallback implementation: {}",
                    e.getMessage());
            }

            span.setStatus(StatusCode.OK);
            return super.execute(opBGP, input);

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);

            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Error during BGP pushdown, falling back: {}",
                    e.getMessage());
            }

            // Fall back to standard execution on any error
            return super.execute(opBGP, input);

        } finally {
            span.end();
        }
    }

    /**
     * Execute a FILTER operation.
     *
     * <p>This method attempts to compile the underlying BGP with the FILTER
     * expression and execute them natively on FalkorDB using Cypher WHERE clauses.
     * If compilation fails, it falls back to the standard Jena evaluation.</p>
     *
     * @param opFilter the filter operation
     * @param input the input query iterator
     * @return the result query iterator
     */
    @Override
    protected QueryIterator execute(final OpFilter opFilter,
                                    final QueryIterator input) {
        // If we don't have a FalkorDB graph, fall back to standard execution
        if (falkorGraph == null) {
            return super.execute(opFilter, input);
        }

        Span span = tracer.spanBuilder("FalkorDBOpExecutor.executeFilter")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPTIMIZATION_TYPE, "FILTER_EXECUTION")
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Extract the sub-operation and filter expressions
            Op subOp = opFilter.getSubOp();
            List<Expr> filters = opFilter.getExprs().getList();
            
            // Only handle single FILTER expression for now
            if (filters.isEmpty()) {
                // No filter expressions - fall back to standard execution
                span.setAttribute(ATTR_FALLBACK, true);
                span.addEvent("No filter expressions, falling back");
                span.setStatus(StatusCode.OK);
                
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("FILTER pushdown optimization not applicable: "
                        + "no filter expressions found, using Jena fallback implementation");
                }
                return super.execute(opFilter, input);
            }
            
            if (filters.size() > 1) {
                // Multiple filter expressions - combine with AND
                // For now, fall back to standard execution
                span.setAttribute(ATTR_FALLBACK, true);
                span.addEvent("Multiple filter expressions not yet optimized");
                span.setStatus(StatusCode.OK);
                
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("FILTER pushdown optimization not applicable "
                        + "(multiple filter expressions not yet supported), "
                        + "using Jena fallback implementation");
                }
                return super.execute(opFilter, input);
            }
            
            // Check if sub-operation is a BGP
            if (!(subOp instanceof OpBGP)) {
                span.setAttribute(ATTR_FALLBACK, true);
                span.addEvent("Sub-operation is not BGP, falling back");
                span.setStatus(StatusCode.OK);
                
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("FILTER pushdown optimization not applicable "
                        + "(sub-operation is not a Basic Graph Pattern), "
                        + "using Jena fallback implementation");
                }
                return super.execute(opFilter, input);
            }
            
            BasicPattern bgp = ((OpBGP) subOp).getPattern();
            Expr filterExpr = filters.get(0);
            
            int tripleCount = bgp.size();
            span.setAttribute(ATTR_TRIPLE_COUNT, (long) tripleCount);
            
            // Try to compile the BGP with FILTER to Cypher
            SparqlToCypherCompiler.CompilationResult compilation =
                SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

            String cypherQuery = compilation.cypherQuery();
            Map<String, Object> parameters = compilation.parameters();

            span.setAttribute(ATTR_CYPHER_QUERY, cypherQuery);
            span.setAttribute(ATTR_FALLBACK, false);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("FILTER pushdown: executing Cypher query:\n{}",
                    cypherQuery);
            }

            // Execute on FalkorDB
            ResultSet resultSet = falkorGraph.getTracedGraph()
                .query(cypherQuery, parameters);

            // Convert results to bindings
            List<Binding> bindings = convertResultsToBindings(
                resultSet, input);

            span.setAttribute(ATTR_RESULT_COUNT, (long) bindings.size());
            span.setStatus(StatusCode.OK);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("FILTER pushdown: returned {} results",
                    bindings.size());
            }

            return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);

        } catch (SparqlToCypherCompiler.CannotCompileException e) {
            // Fall back to standard Jena execution
            span.setAttribute(ATTR_FALLBACK, true);
            span.addEvent("Falling back to standard execution: " 
                + e.getMessage());

            // Warn when optimization limitation causes fallback to Jena
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("FILTER pushdown optimization not applicable, "
                    + "using Jena fallback implementation: {}",
                    e.getMessage());
            }

            span.setStatus(StatusCode.OK);
            return super.execute(opFilter, input);

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);

            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Error during FILTER pushdown, falling back: {}",
                    e.getMessage());
            }

            // Fall back to standard execution on any error
            return super.execute(opFilter, input);

        } finally {
            span.end();
        }
    }

    /**
     * Execute an OPTIONAL (left join) pattern.
     *
     * <p>This method attempts to compile the left and right BGPs with OPTIONAL
     * and execute them natively on FalkorDB using OPTIONAL MATCH. If compilation
     * fails, it falls back to the standard Jena evaluation.</p>
     *
     * @param opLeftJoin the left join operation (OPTIONAL)
     * @param input the input query iterator
     * @return the result query iterator
     */
    @Override
    protected QueryIterator execute(final OpLeftJoin opLeftJoin,
                                    final QueryIterator input) {
        // If we don't have a FalkorDB graph, fall back to standard execution
        if (falkorGraph == null) {
            return super.execute(opLeftJoin, input);
        }

        Span span = tracer.spanBuilder("FalkorDBOpExecutor.executeOptional")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPTIMIZATION_TYPE, "OPTIONAL_EXECUTION")
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Extract left (required) and right (optional) patterns
            Op left = opLeftJoin.getLeft();
            Op right = opLeftJoin.getRight();
            
            // Handle OpFilter wrapping OpBGP on the left side
            Expr filterExpr = null;
            if (left instanceof OpFilter) {
                OpFilter opFilter = (OpFilter) left;
                filterExpr = opFilter.getExprs().getList().isEmpty() 
                    ? null 
                    : opFilter.getExprs().getList().get(0);
                left = opFilter.getSubOp();
            }
            
            // Check if both sides are BGPs (after unwrapping filter)
            if (!(left instanceof OpBGP) || !(right instanceof OpBGP)) {
                span.setAttribute(ATTR_FALLBACK, true);
                String side = !(left instanceof OpBGP) ? "left" : "right";
                span.addEvent("OPTIONAL " + side + " side is not BGP, falling back");
                span.setStatus(StatusCode.OK);
                
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("OPTIONAL pattern pushdown optimization not applicable: "
                        + side + " side is not a Basic Graph Pattern, "
                        + "using Jena fallback implementation");
                }
                return super.execute(opLeftJoin, input);
            }
            
            BasicPattern leftBGP = ((OpBGP) left).getPattern();
            BasicPattern rightBGP = ((OpBGP) right).getPattern();
            
            int totalTriples = leftBGP.size() + rightBGP.size();
            span.setAttribute(ATTR_TRIPLE_COUNT, (long) totalTriples);
            
            // Try to compile with OPTIONAL support (with FILTER if present)
            SparqlToCypherCompiler.CompilationResult compilation =
                SparqlToCypherCompiler.translateWithOptional(leftBGP, rightBGP, filterExpr);

            String cypherQuery = compilation.cypherQuery();
            Map<String, Object> parameters = compilation.parameters();

            span.setAttribute(ATTR_CYPHER_QUERY, cypherQuery);
            span.setAttribute(ATTR_FALLBACK, false);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("OPTIONAL pushdown: executing Cypher query:\n{}",
                    cypherQuery);
            }

            // Execute on FalkorDB
            ResultSet resultSet = falkorGraph.getTracedGraph()
                .query(cypherQuery, parameters);

            // Convert results to bindings
            List<Binding> bindings = convertResultsToBindings(
                resultSet, input);

            span.setAttribute(ATTR_RESULT_COUNT, (long) bindings.size());
            span.setStatus(StatusCode.OK);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("OPTIONAL pushdown: returned {} results",
                    bindings.size());
            }

            return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);

        } catch (SparqlToCypherCompiler.CannotCompileException e) {
            // Fall back to standard Jena execution
            span.setAttribute(ATTR_FALLBACK, true);
            span.addEvent("Falling back to standard execution: " 
                + e.getMessage());

            // Warn when optimization limitation causes fallback to Jena
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("OPTIONAL pattern pushdown optimization not applicable, "
                    + "using Jena fallback implementation: {}",
                    e.getMessage());
            }

            span.setStatus(StatusCode.OK);
            return super.execute(opLeftJoin, input);

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);

            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Error during OPTIONAL pushdown, falling back: {}",
                    e.getMessage());
            }

            // Fall back to standard execution on any error
            return super.execute(opLeftJoin, input);

        } finally {
            span.end();
        }
    }

    /**
     * Execute a UNION (alternative patterns) operation.
     *
     * <p>This method attempts to compile both branches of the UNION and combine them
     * using Cypher UNION. If compilation fails, it falls back to the standard Jena
     * evaluation.</p>
     *
     * @param opUnion the union operation
     * @param input the input query iterator
     * @return the result query iterator
     */
    @Override
    protected QueryIterator execute(final OpUnion opUnion,
                                    final QueryIterator input) {
        // If we don't have a FalkorDB graph, fall back to standard execution
        if (falkorGraph == null) {
            return super.execute(opUnion, input);
        }

        Span span = tracer.spanBuilder("FalkorDBOpExecutor.executeUnion")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPTIMIZATION_TYPE, "UNION_EXECUTION")
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Extract left and right patterns
            Op left = opUnion.getLeft();
            Op right = opUnion.getRight();
            
            // Check if both sides are BGPs
            if (!(left instanceof OpBGP) || !(right instanceof OpBGP)) {
                span.setAttribute(ATTR_FALLBACK, true);
                String side = !(left instanceof OpBGP) ? "left" : "right";
                span.addEvent("UNION " + side + " side is not BGP, falling back");
                span.setStatus(StatusCode.OK);
                
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("UNION pattern pushdown optimization not applicable: "
                        + side + " side is not a Basic Graph Pattern, "
                        + "using Jena fallback implementation");
                }
                return super.execute(opUnion, input);
            }
            
            BasicPattern leftBGP = ((OpBGP) left).getPattern();
            BasicPattern rightBGP = ((OpBGP) right).getPattern();
            
            int totalTriples = leftBGP.size() + rightBGP.size();
            span.setAttribute(ATTR_TRIPLE_COUNT, (long) totalTriples);
            
            // Try to compile with UNION support
            SparqlToCypherCompiler.CompilationResult compilation =
                SparqlToCypherCompiler.translateUnion(leftBGP, rightBGP);

            String cypherQuery = compilation.cypherQuery();
            Map<String, Object> parameters = compilation.parameters();

            span.setAttribute(ATTR_CYPHER_QUERY, cypherQuery);
            span.setAttribute(ATTR_FALLBACK, false);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("UNION pushdown: executing Cypher query:\n{}",
                    cypherQuery);
            }

            // Execute on FalkorDB
            ResultSet resultSet = falkorGraph.getTracedGraph()
                .query(cypherQuery, parameters);

            // Convert results to bindings
            List<Binding> bindings = convertResultsToBindings(
                resultSet, input);

            span.setAttribute(ATTR_RESULT_COUNT, (long) bindings.size());
            span.setStatus(StatusCode.OK);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("UNION pushdown: returned {} results",
                    bindings.size());
            }

            return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);

        } catch (SparqlToCypherCompiler.CannotCompileException e) {
            // Fall back to standard Jena execution
            span.setAttribute(ATTR_FALLBACK, true);
            span.addEvent("Falling back to standard execution: " 
                + e.getMessage());

            // Warn when optimization limitation causes fallback to Jena
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("UNION pattern pushdown optimization not applicable, "
                    + "using Jena fallback implementation: {}",
                    e.getMessage());
            }

            span.setStatus(StatusCode.OK);
            return super.execute(opUnion, input);

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);

            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Error during UNION pushdown, falling back: {}",
                    e.getMessage());
            }

            // Fall back to standard execution on any error
            return super.execute(opUnion, input);

        } finally {
            span.end();
        }
    }

    /**
     * Execute a GROUP operation with aggregations.
     *
     * <p>This method attempts to compile the underlying BGP with GROUP BY
     * and aggregation expressions, executing them natively on FalkorDB using
     * Cypher aggregation functions. If compilation fails, it falls back to
     * standard Jena evaluation.</p>
     *
     * @param opGroup the group operation
     * @param input the input query iterator
     * @return the result query iterator
     */
    @Override
    protected QueryIterator execute(final OpGroup opGroup,
                                    final QueryIterator input) {
        // If we don't have a FalkorDB graph, fall back to standard execution
        if (falkorGraph == null) {
            return super.execute(opGroup, input);
        }

        Span span = tracer.spanBuilder("FalkorDBOpExecutor.executeGroup")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPTIMIZATION_TYPE, "GROUP_EXECUTION")
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Check if the subOp is a BGP (we only support aggregation over BGPs)
            Op subOp = opGroup.getSubOp();
            if (!(subOp instanceof OpBGP)) {
                span.setAttribute(ATTR_FALLBACK, true);
                span.addEvent("SubOp is not BGP, falling back");
                span.setStatus(StatusCode.OK);
                
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("GROUP BY aggregation pushdown optimization not applicable "
                        + "(sub-operation is not a Basic Graph Pattern), "
                        + "using Jena fallback implementation");
                }
                return super.execute(opGroup, input);
            }

            BasicPattern bgp = ((OpBGP) subOp).getPattern();
            VarExprList groupVars = opGroup.getGroupVars();
            List<org.apache.jena.sparql.expr.ExprAggregator> aggregations = 
                opGroup.getAggregators();

            span.setAttribute(ATTR_TRIPLE_COUNT, (long) bgp.size());
            span.setAttribute("falkordb.group_vars", 
                groupVars != null ? groupVars.size() : 0);
            span.setAttribute("falkordb.aggregations", 
                aggregations != null ? aggregations.size() : 0);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("GROUP pushdown attempt: {} triples, {} group vars, {} aggregations",
                    bgp.size(), 
                    groupVars != null ? groupVars.size() : 0,
                    aggregations != null ? aggregations.size() : 0);
            }

            // Compile the BGP
            SparqlToCypherCompiler.CompilationResult bgpCompilation =
                SparqlToCypherCompiler.translate(bgp);

            // Translate aggregations
            AggregationToCypherTranslator.AggregationResult aggResult =
                AggregationToCypherTranslator.translate(
                    aggregations,
                    groupVars,
                    bgpCompilation.variableMapping()
                );

            // Build the complete Cypher query with aggregation
            String cypherQuery = buildAggregationQuery(
                bgpCompilation.cypherQuery(),
                aggResult.returnClause()
            );

            Map<String, Object> parameters = bgpCompilation.parameters();

            span.setAttribute(ATTR_CYPHER_QUERY, cypherQuery);
            span.setAttribute(ATTR_FALLBACK, false);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("GROUP pushdown: executing Cypher query:\n{}",
                    cypherQuery);
            }

            // Execute on FalkorDB
            ResultSet resultSet = falkorGraph.getTracedGraph()
                .query(cypherQuery, parameters);

            // Convert results to bindings
            List<Binding> bindings = convertResultsToBindings(
                resultSet, input);

            span.setAttribute(ATTR_RESULT_COUNT, (long) bindings.size());
            span.setStatus(StatusCode.OK);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("GROUP pushdown: returned {} results",
                    bindings.size());
            }

            return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);

        } catch (SparqlToCypherCompiler.CannotCompileException
                | AggregationToCypherTranslator.CannotTranslateAggregationException e) {
            // Fall back to standard Jena execution
            span.setAttribute(ATTR_FALLBACK, true);
            span.addEvent("Falling back to standard execution: "
                + e.getMessage());

            // Warn when optimization limitation causes fallback to Jena
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("GROUP BY aggregation pushdown optimization not applicable, "
                    + "using Jena fallback implementation: {}",
                    e.getMessage());
            }

            span.setStatus(StatusCode.OK);
            return super.execute(opGroup, input);

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);

            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Error during GROUP pushdown, falling back: {}",
                    e.getMessage());
            }

            // Fall back to standard execution on any error
            return super.execute(opGroup, input);

        } finally {
            span.end();
        }
    }

    /**
     * Build a complete Cypher query with aggregation.
     *
     * <p>Takes a base BGP query and replaces or adds a RETURN clause
     * with aggregations.</p>
     *
     * @param baseQuery the base Cypher query from BGP compilation
     * @param returnClause the aggregation RETURN clause
     * @return the complete Cypher query with aggregation
     */
    private String buildAggregationQuery(
            final String baseQuery,
            final String returnClause) {
        
        // Remove the existing RETURN clause from base query
        // Base query typically ends with "RETURN var1 AS x, var2 AS y, ..."
        int returnIndex = baseQuery.lastIndexOf("RETURN");
        if (returnIndex == -1) {
            // No RETURN clause, just append it
            return baseQuery + "\nRETURN " + returnClause;
        }

        // Replace the RETURN clause
        String matchPart = baseQuery.substring(0, returnIndex).trim();
        return matchPart + "\nRETURN " + returnClause;
    }

    /**
     * Convert FalkorDB result set to Jena bindings.
     *
     * @param resultSet the FalkorDB result set
     * @param input the input query iterator for parent bindings
     * @return a list of bindings
     */
    private List<Binding> convertResultsToBindings(
            final ResultSet resultSet,
            final QueryIterator input) {

        List<Binding> bindings = new ArrayList<>();

        // Get parent bindings if available
        List<Binding> parentBindings = new ArrayList<>();
        while (input.hasNext()) {
            parentBindings.add(input.next());
        }
        if (parentBindings.isEmpty()) {
            parentBindings.add(null); // Use null as placeholder for no parent
        }

        // Get column headers from result set
        Header header = resultSet.getHeader();
        List<String> columnNames = header.getSchemaNames();

        // Iterate over results
        for (Record record : resultSet) {
            for (Binding parentBinding : parentBindings) {
                BindingBuilder builder = parentBinding != null
                    ? Binding.builder(parentBinding)
                    : Binding.builder();

                for (String columnName : columnNames) {
                    if (columnName.startsWith("_")) {
                        // Skip internal columns
                        continue;
                    }

                    try {
                        Object value = record.getValue(columnName);
                        Node node = valueToNode(value);
                        if (node != null) {
                            Var var = Var.alloc(columnName);
                            builder.add(var, node);
                        }
                    } catch (Exception e) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Error getting column {}: {}",
                                columnName, e.getMessage());
                        }
                    }
                }

                bindings.add(builder.build());
            }
        }

        return bindings;
    }

    /**
     * Convert a FalkorDB value to a Jena Node.
     *
     * @param value the FalkorDB value
     * @return a Jena Node, or null if conversion fails
     */
    private Node valueToNode(final Object value) {
        if (value == null) {
            return null;
        }

        // Handle FalkorDB Node (graph entity)
        if (value instanceof com.falkordb.graph_entities.Node graphNode) {
            var uriProp = graphNode.getProperty("uri");
            if (uriProp != null) {
                return NodeFactory.createURI(uriProp.getValue().toString());
            }
            return NodeFactory.createLiteralString(graphNode.toString());
        }

        // Handle primitives
        if (value instanceof String strVal) {
            // Only treat as URI if it starts with a known URI scheme
            // and passes validation
            if (strVal.startsWith("http://") || strVal.startsWith("https://")
                    || strVal.startsWith("urn:") || strVal.startsWith("file://")
                    || strVal.startsWith("mailto:") || strVal.startsWith("ftp://")
                    || strVal.startsWith("ftps://")) {
                try {
                    // Validate the URI is well-formed
                    java.net.URI uri = java.net.URI.create(strVal);
                    // Additional validation: ensure it has proper structure
                    if (uri.getScheme() != null && !uri.getScheme().isEmpty()) {
                        return NodeFactory.createURI(strVal);
                    }
                } catch (IllegalArgumentException e) {
                    // Invalid URI, treat as string literal
                }
            }
            return NodeFactory.createLiteralString(strVal);
        }

        if (value instanceof Long longVal) {
            return NodeFactory.createLiteralByValue(longVal,
                org.apache.jena.datatypes.xsd.XSDDatatype.XSDinteger);
        }

        if (value instanceof Double doubleVal) {
            return NodeFactory.createLiteralByValue(doubleVal,
                org.apache.jena.datatypes.xsd.XSDDatatype.XSDdouble);
        }

        if (value instanceof Boolean boolVal) {
            return NodeFactory.createLiteralByValue(boolVal,
                org.apache.jena.datatypes.xsd.XSDDatatype.XSDboolean);
        }

        if (value instanceof Integer intVal) {
            return NodeFactory.createLiteralByValue(intVal,
                org.apache.jena.datatypes.xsd.XSDDatatype.XSDinteger);
        }

        // Default: convert to string literal
        return NodeFactory.createLiteralString(value.toString());
    }

    /**
     * Find the underlying FalkorDBGraph, unwrapping wrappers.
     *
     * @param graph the graph to search
     * @return the FalkorDBGraph, or null if not found
     */
    private FalkorDBGraph findFalkorDBGraph(final Graph graph) {
        if (graph == null) {
            return null;
        }

        if (graph instanceof FalkorDBGraph) {
            return (FalkorDBGraph) graph;
        }

        if (graph instanceof InfGraph infGraph) {
            Graph rawGraph = infGraph.getRawGraph();
            if (rawGraph != null) {
                return findFalkorDBGraph(rawGraph);
            }
        }

        return null;
    }
}
