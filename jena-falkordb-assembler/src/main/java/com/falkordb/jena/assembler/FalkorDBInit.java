package com.falkordb.jena.assembler;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
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
 * <p>The registration is performed when Jena initializes, typically at
 * application startup.</p>
 *
 * @see FalkorDBAssembler
 * @see FalkorDBVocab
 */
public class FalkorDBInit implements JenaSubsystemLifecycle {

    /** Logger instance for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        FalkorDBInit.class);

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
     * This method registers the FalkorDB assembler with Jena's registry.
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

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("FalkorDB Jena Assembler registered for type: {}",
                FalkorDBVocab.FalkorDBModel);
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
