package com.falkordb.jena.query;

import com.falkordb.jena.FalkorDBGraph;
import org.apache.jena.graph.Graph;
import org.apache.jena.query.Query;
import org.apache.jena.reasoner.InfGraph;
import org.apache.jena.sparql.ARQConstants;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.QueryEngineFactory;
import org.apache.jena.sparql.engine.QueryEngineRegistry;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.util.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query engine factory for FalkorDB-backed datasets.
 *
 * <p>This factory creates query engines that use {@link FalkorDBOpExecutor}
 * for optimized BGP evaluation. When registered, it intercepts queries
 * against FalkorDB-backed datasets and enables query pushdown.</p>
 *
 * <h2>Registration:</h2>
 * <pre>{@code
 * // Register the factory (typically done at startup)
 * FalkorDBQueryEngineFactory.register();
 *
 * // Now SPARQL queries against FalkorDB models will use pushdown
 * Model model = FalkorDBModelFactory.createModel("myGraph");
 * Query query = QueryFactory.create("SELECT ...");
 * try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
 *     ResultSet results = qexec.execSelect();
 *     // ...
 * }
 * }</pre>
 *
 * <h2>How It Works:</h2>
 * <ol>
 *   <li>The factory's {@code accept()} method checks if the dataset
 *       contains a FalkorDB graph</li>
 *   <li>If so, it creates a {@link FalkorDBQueryEngine} that uses
 *       {@link FalkorDBOpExecutor}</li>
 *   <li>The OpExecutor intercepts BGP operations and compiles them
 *       to Cypher</li>
 *   <li>Unsupported operations fall back to standard Jena evaluation</li>
 * </ol>
 */
public final class FalkorDBQueryEngineFactory implements QueryEngineFactory {

    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        FalkorDBQueryEngineFactory.class);

    /** Singleton instance. */
    private static final FalkorDBQueryEngineFactory INSTANCE =
        new FalkorDBQueryEngineFactory();

    /** Whether the factory has been registered. */
    private static volatile boolean registered = false;

    /** Private constructor for singleton. */
    private FalkorDBQueryEngineFactory() {
        // Singleton
    }

    /**
     * Get the singleton factory instance.
     *
     * @return the factory instance
     */
    public static FalkorDBQueryEngineFactory get() {
        return INSTANCE;
    }

    /**
     * Register this factory with the Jena query engine registry.
     *
     * <p>This should be called at application startup to enable
     * query pushdown for FalkorDB-backed datasets.</p>
     */
    public static synchronized void register() {
        if (!registered) {
            QueryEngineRegistry.addFactory(INSTANCE);
            registered = true;
            LOGGER.info("FalkorDB query engine factory registered");
        }
    }

    /**
     * Unregister this factory from the Jena query engine registry.
     *
     * <p>This can be used to disable query pushdown.</p>
     */
    public static synchronized void unregister() {
        if (registered) {
            QueryEngineRegistry.removeFactory(INSTANCE);
            registered = false;
            LOGGER.info("FalkorDB query engine factory unregistered");
        }
    }

    /**
     * Check if the factory is currently registered.
     *
     * @return true if registered
     */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * Check if this factory accepts the given dataset.
     *
     * <p>The factory accepts datasets that contain at least one
     * FalkorDB-backed graph.</p>
     *
     * @param query the query (unused)
     * @param dsg the dataset graph
     * @param cxt the context (unused)
     * @return true if the dataset contains a FalkorDB graph
     */
    @Override
    public boolean accept(final Query query,
                          final DatasetGraph dsg,
                          final Context cxt) {
        return hasFalkorDBGraph(dsg);
    }

    /**
     * Check if this factory accepts the given dataset for algebra execution.
     *
     * @param op the algebra operation (unused)
     * @param dsg the dataset graph
     * @param cxt the context (unused)
     * @return true if the dataset contains a FalkorDB graph
     */
    @Override
    public boolean accept(final Op op,
                          final DatasetGraph dsg,
                          final Context cxt) {
        return hasFalkorDBGraph(dsg);
    }

    /**
     * Create a query execution plan.
     *
     * @param query the query
     * @param dsg the dataset graph
     * @param input the initial binding
     * @param cxt the context
     * @return the execution plan
     */
    @Override
    public Plan create(final Query query,
                       final DatasetGraph dsg,
                       final Binding input,
                       final Context cxt) {
        // Set our OpExecutor factory in the context
        Context newContext = setupContext(cxt);
        FalkorDBQueryEngine engine =
            new FalkorDBQueryEngine(query, dsg, input, newContext);
        return engine.getPlan();
    }

    /**
     * Create a query execution plan for an algebra operation.
     *
     * @param op the algebra operation
     * @param dsg the dataset graph
     * @param input the initial binding
     * @param cxt the context
     * @return the execution plan
     */
    @Override
    public Plan create(final Op op,
                       final DatasetGraph dsg,
                       final Binding input,
                       final Context cxt) {
        // Set our OpExecutor factory in the context
        Context newContext = setupContext(cxt);
        FalkorDBQueryEngine engine =
            new FalkorDBQueryEngine(op, dsg, input, newContext);
        return engine.getPlan();
    }

    /**
     * Set up the context with our OpExecutor factory.
     *
     * @param cxt the original context
     * @return a context with our factory set
     */
    private Context setupContext(final Context cxt) {
        Context newContext = cxt != null ? cxt.copy() : new Context();
        QC.setFactory(newContext, new FalkorDBOpExecutor.Factory());
        return newContext;
    }

    /**
     * Check if the dataset contains a FalkorDB graph.
     *
     * @param dsg the dataset graph to check
     * @return true if a FalkorDB graph is found
     */
    private boolean hasFalkorDBGraph(final DatasetGraph dsg) {
        if (dsg == null) {
            return false;
        }

        // Check the default graph
        Graph defaultGraph = dsg.getDefaultGraph();
        if (isFalkorDBGraph(defaultGraph)) {
            return true;
        }

        // Check named graphs
        var graphNames = dsg.listGraphNodes();
        while (graphNames.hasNext()) {
            Graph namedGraph = dsg.getGraph(graphNames.next());
            if (isFalkorDBGraph(namedGraph)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a graph is backed by FalkorDB.
     *
     * <p>Note: This method returns false for InfGraphs even if they wrap
     * a FalkorDB graph, because query pushdown would bypass the inference
     * layer. Queries against inference models must use standard Jena
     * evaluation to ensure inference rules are applied correctly.</p>
     *
     * @param graph the graph to check
     * @return true if the graph is a FalkorDBGraph (not wrapped in inference)
     */
    private boolean isFalkorDBGraph(final Graph graph) {
        if (graph == null) {
            return false;
        }

        // Don't accept InfGraphs - they need standard Jena evaluation
        // to apply inference rules correctly
        if (graph instanceof InfGraph) {
            return false;
        }

        return graph instanceof FalkorDBGraph;
    }

    /**
     * Custom query engine for FalkorDB.
     *
     * <p>This engine uses {@link FalkorDBOpExecutor} for optimized
     * BGP evaluation.</p>
     */
    private static final class FalkorDBQueryEngine extends QueryEngineMain {

        /**
         * Create engine from a Query.
         */
        FalkorDBQueryEngine(final Query query,
                            final DatasetGraph dataset,
                            final Binding initial,
                            final Context context) {
            super(query, dataset, initial, context);
        }

        /**
         * Create engine from an algebra Op.
         */
        FalkorDBQueryEngine(final Op op,
                            final DatasetGraph dataset,
                            final Binding initial,
                            final Context context) {
            super(op, dataset, initial, context);
        }
    }
}
