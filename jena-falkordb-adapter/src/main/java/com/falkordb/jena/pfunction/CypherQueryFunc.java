package com.falkordb.jena.pfunction;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.reasoner.InfGraph;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.pfunction.PropFuncArg;
import org.apache.jena.sparql.pfunction.PropFuncArgType;
import org.apache.jena.sparql.pfunction.PropertyFunctionEval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Jena "magic property" (property function) that allows executing native
 * Cypher queries directly against FalkorDB from within SPARQL.
 *
 * <p>This provides a query pushdown mechanism for power users who need
 * to bypass the slower triple-by-triple matching engine and execute
 * optimized graph traversals directly in FalkorDB.</p>
 *
 * <h2>Usage in SPARQL:</h2>
 * <pre>{@code
 * PREFIX falkor: <http://falkordb.com/jena#>
 *
 * SELECT ?name ?age WHERE {
 *   (?name ?age) falkor:cypher '''
 *     MATCH (p:Resource)
 *     WHERE p.`http://example.org/name` IS NOT NULL
 *     RETURN p.`http://example.org/name` AS name,
 *            p.`http://example.org/age` AS age
 *   '''
 * }
 * }</pre>
 *
 * <h2>Result Mapping:</h2>
 * <ul>
 *   <li>The Cypher query RETURN clause column names are mapped to SPARQL
 *       variables specified in the subject list</li>
 *   <li>String values become RDF literals</li>
 *   <li>Node objects with a 'uri' property become URI resources</li>
 *   <li>Other values are converted to typed literals</li>
 * </ul>
 *
 * @see <a href="https://jena.apache.org/documentation/query/extension.html">
 *      Jena ARQ Extensions</a>
 */
public final class CypherQueryFunc extends PropertyFunctionEval {

    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        CypherQueryFunc.class);

    /** The property function URI. */
    public static final String URI = "http://falkordb.com/jena#cypher";

    /** Instrumentation scope name for property function operations. */
    public static final String SCOPE_PFUNCTION =
        "com.falkordb.jena.pfunction.CypherQueryFunc";

    /** Tracer for property function operations. */
    private final Tracer tracer;

    /** Attribute key for Cypher query. */
    private static final AttributeKey<String> ATTR_CYPHER_QUERY =
        AttributeKey.stringKey("falkordb.cypher.query");

    /** Attribute key for result count. */
    private static final AttributeKey<Long> ATTR_RESULT_COUNT =
        AttributeKey.longKey("falkordb.cypher.result_count");

    /** Attribute key for variable count. */
    private static final AttributeKey<Long> ATTR_VAR_COUNT =
        AttributeKey.longKey("falkordb.cypher.var_count");

    /**
     * Creates a new CypherQueryFunc instance.
     * Subject is a LIST (list of variables), object is SINGLE (Cypher query).
     */
    public CypherQueryFunc() {
        super(PropFuncArgType.PF_ARG_LIST, PropFuncArgType.PF_ARG_SINGLE);
        this.tracer = TracingUtil.getTracer(SCOPE_PFUNCTION);
    }

    /**
     * Execute the property function.
     *
     * <p>This method is called by the Jena query engine when the magic
     * property is encountered in a SPARQL query.</p>
     *
     * @param binding the current binding (unused in this implementation)
     * @param argSubject the subject of the property (list of variables
     *        to bind results to)
     * @param predicate the predicate (this property function)
     * @param argObject the object (the Cypher query string)
     * @param execCxt the execution context
     * @return an iterator over result bindings
     */
    @Override
    public QueryIterator execEvaluated(
            final Binding binding,
            final PropFuncArg argSubject,
            final Node predicate,
            final PropFuncArg argObject,
            final ExecutionContext execCxt) {

        // Extract the Cypher query from the object argument
        String cypherQuery = extractCypherQuery(argObject);
        if (cypherQuery == null) {
            LOGGER.warn("falkor:cypher requires a literal Cypher "
                + "query string as the object");
            return QueryIterPlainWrapper.create(List.<Binding>of().iterator(),
                execCxt);
        }

        // Get the FalkorDB graph from the execution context
        Graph graph = execCxt.getActiveGraph();
        FalkorDBGraph falkorGraph = findFalkorDBGraph(graph);
        if (falkorGraph == null) {
            LOGGER.warn("falkor:cypher can only be used with FalkorDBGraph, "
                + "got: {}", graph.getClass().getName());
            return QueryIterPlainWrapper.create(List.<Binding>of().iterator(),
                execCxt);
        }

        // Extract the variable list from the subject
        List<Var> vars = extractVariables(argSubject);

        // Execute with tracing
        Span span = tracer.spanBuilder("CypherQueryFunc.execute")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_CYPHER_QUERY, cypherQuery)
            .setAttribute(ATTR_VAR_COUNT, (long) vars.size())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Log the query for debugging
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Magic property executing Cypher query: {}",
                    truncateForLog(cypherQuery));
            }

            // Execute the Cypher query on FalkorDB
            ResultSet resultSet = falkorGraph.getTracedGraph()
                .query(cypherQuery);

            // Convert results to bindings
            List<Binding> bindings = convertResultsToBindings(
                resultSet, vars, binding);

            span.setAttribute(ATTR_RESULT_COUNT, (long) bindings.size());
            span.setStatus(StatusCode.OK);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Magic property returned {} results",
                    bindings.size());
            }

            return QueryIterPlainWrapper.create(bindings.iterator(), execCxt);

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            LOGGER.error("Error executing Cypher query: {}", e.getMessage());
            throw new RuntimeException(
                "Error executing Cypher query: " + e.getMessage(), e);
        } finally {
            span.end();
        }
    }

    /**
     * Extract the Cypher query string from the object argument.
     *
     * @param object the object PropFuncArg (should contain a literal)
     * @return the Cypher query string, or null if invalid
     */
    private String extractCypherQuery(final PropFuncArg object) {
        if (object == null) {
            return null;
        }

        // Handle single node argument
        Node argNode = object.getArg();
        if (argNode != null && argNode.isLiteral()) {
            return argNode.getLiteralLexicalForm();
        }

        // Handle list argument (take first element)
        List<Node> argList = object.getArgList();
        if (argList != null && !argList.isEmpty()) {
            Node first = argList.get(0);
            if (first.isLiteral()) {
                return first.getLiteralLexicalForm();
            }
        }

        return null;
    }

    /**
     * Extract variables from the subject argument.
     *
     * @param subject the subject property function argument
     * @return a list of variables
     */
    private List<Var> extractVariables(final PropFuncArg subject) {
        List<Var> vars = new ArrayList<>();

        if (subject == null) {
            return vars;
        }

        // Handle single variable
        Node argNode = subject.getArg();
        if (argNode != null && argNode.isVariable()) {
            vars.add(Var.alloc(argNode));
            return vars;
        }

        // Handle list of variables
        List<Node> argList = subject.getArgList();
        if (argList != null) {
            for (Node node : argList) {
                if (node.isVariable()) {
                    vars.add(Var.alloc(node));
                }
            }
        }

        return vars;
    }

    /**
     * Convert FalkorDB result set to Jena bindings.
     *
     * @param resultSet the FalkorDB result set
     * @param vars the variables to bind
     * @param parentBinding the parent binding to extend
     * @return a list of bindings
     */
    private List<Binding> convertResultsToBindings(
            final ResultSet resultSet,
            final List<Var> vars,
            final Binding parentBinding) {

        List<Binding> bindings = new ArrayList<>();

        // Get column headers from result set
        Header header = resultSet.getHeader();
        List<String> headers = header.getSchemaNames();

        // Create a mapping from column name to variable
        Map<String, Var> columnToVar = new HashMap<>();

        // First, try to match by name
        for (Var var : vars) {
            String varName = var.getVarName();
            if (headers.contains(varName)) {
                columnToVar.put(varName, var);
            }
        }

        // If no name matches, map by position
        if (columnToVar.isEmpty() && !vars.isEmpty()) {
            for (int i = 0; i < Math.min(vars.size(), headers.size()); i++) {
                columnToVar.put(headers.get(i), vars.get(i));
            }
        }

        // Iterate over results
        for (Record record : resultSet) {
            BindingBuilder builder = Binding.builder(parentBinding);

            for (Map.Entry<String, Var> entry : columnToVar.entrySet()) {
                String columnName = entry.getKey();
                Var var = entry.getValue();

                try {
                    Object value = record.getValue(columnName);
                    Node node = valueToNode(value);
                    if (node != null) {
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
            // Try to get URI property
            var uriProp = graphNode.getProperty("uri");
            if (uriProp != null) {
                return NodeFactory.createURI(uriProp.getValue().toString());
            }
            // Fall back to string representation
            return NodeFactory.createLiteralString(graphNode.toString());
        }

        // Handle primitives
        if (value instanceof String strVal) {
            // Check if it looks like a valid URI
            if (strVal.startsWith("http://") || strVal.startsWith("https://")) {
                try {
                    // Validate URI before creating
                    java.net.URI.create(strVal);
                    return NodeFactory.createURI(strVal);
                } catch (IllegalArgumentException e) {
                    // Invalid URI, treat as string literal
                    return NodeFactory.createLiteralString(strVal);
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
     * Truncate a string for logging purposes.
     *
     * @param str the string to truncate
     * @return the truncated string
     */
    private String truncateForLog(final String str) {
        final int maxLen = 200;
        if (str == null) {
            return null;
        }
        String normalized = str.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    /**
     * Find the underlying FalkorDBGraph, unwrapping inference graph wrappers.
     *
     * <p>When inference/reasoning is enabled, Jena wraps the underlying graph
     * in an InfGraph implementation (e.g., FBRuleInfGraph). This method
     * recursively unwraps such wrappers to find the underlying FalkorDBGraph.</p>
     *
     * @param graph the graph to search
     * @return the underlying FalkorDBGraph, or null if not found
     */
    private FalkorDBGraph findFalkorDBGraph(final Graph graph) {
        if (graph == null) {
            return null;
        }

        // Direct match
        if (graph instanceof FalkorDBGraph) {
            return (FalkorDBGraph) graph;
        }

        // Unwrap inference graphs
        if (graph instanceof InfGraph infGraph) {
            Graph rawGraph = infGraph.getRawGraph();
            if (rawGraph != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Unwrapping InfGraph {} to access raw graph",
                        graph.getClass().getName());
                }
                return findFalkorDBGraph(rawGraph);
            }
        }

        return null;
    }
}
