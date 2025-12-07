package com.falkordb.jena.assembler;

import com.falkordb.jena.pfunction.CypherQueryFunc;
import com.falkordb.jena.query.FalkorDBQueryEngineFactory;
import org.apache.jena.assembler.Assembler;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry;
import org.apache.jena.sys.JenaSubsystemLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initialization class for the FalkorDB Jena Assembler module.
 *
 * <p>This class is loaded automatically by Jena's subsystem initialization
 * mechanism via the Service Provider Interface (SPI). It registers the
 * {@link FalkorDBAssembler} with Jena's assembler registry, enabling
 * Fuseki to create FalkorDB-backed models from configuration files.</p>
 *
 * <p>It also registers the {@link CypherQueryFunc} magic property function
 * that allows executing native Cypher queries from SPARQL.</p>
 *
 * <p>The registration is performed when Jena initializes, typically at
 * application startup.</p>
 *
 * <p><strong>Note:</strong> Query pushdown optimization is available via
 * {@link FalkorDBQueryEngineFactory} but must be explicitly enabled by
 * calling {@code FalkorDBQueryEngineFactory.register()} if desired.</p>
 *
 * @see FalkorDBAssembler
 * @see FalkorDBVocab
 * @see CypherQueryFunc
 * @see FalkorDBQueryEngineFactory
 */
public class FalkorDBInit implements JenaSubsystemLifecycle {

    /** Logger instance for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        FalkorDBInit.class);

    /**
     * Creates a new FalkorDBInit instance.
     */
    public FalkorDBInit() {
        // Default constructor for SPI discovery
    }

    /**
     * Returns the initialization level for this subsystem.
     * Level 500 ensures this runs after core Jena initialization.
     *
     * @return the initialization level
     */
    @Override
    public int level() {
        return 500;
    }

    /**
     * Initialize the FalkorDB assembler module.
     * This method registers the FalkorDB assembler with Jena's registry
     * and the falkor:cypher magic property function.
     *
     * <p>Note: Query pushdown optimization via {@link FalkorDBQueryEngineFactory}
     * is NOT automatically enabled. Applications that want query pushdown
     * must explicitly call {@code FalkorDBQueryEngineFactory.register()}.</p>
     */
    @Override
    public void start() {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Initializing FalkorDB Jena Assembler");
        }

        // Register the FalkorDBModel type with our dataset assembler
        AssemblerUtils.registerDataset(
            FalkorDBVocab.FalkorDBModel,
            new DatasetAssemblerFalkorDB()
        );

        // Also register as a model assembler for direct model assembly
        Assembler.general().implementWith(
            FalkorDBVocab.FalkorDBModel,
            FalkorDBAssembler.INSTANCE
        );

        // Register the falkor:cypher magic property function
        PropertyFunctionRegistry.get().put(
            CypherQueryFunc.URI,
            CypherQueryFunc.class
        );

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("FalkorDB Jena Assembler registered for type: {}",
                FalkorDBVocab.FalkorDBModel);
            LOGGER.info("FalkorDB magic property registered: {}",
                CypherQueryFunc.URI);
        }
    }

    /**
     * Shutdown hook for the FalkorDB assembler module.
     * Currently does nothing as no cleanup is required.
     */
    @Override
    public void stop() {
        // No cleanup required
    }
}
