package com.falkordb;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.sys.JenaSubsystemLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initialization class for the Safe GeoSPARQL Dataset Assembler.
 *
 * <p>This class is loaded automatically by Jena's subsystem initialization
 * mechanism via the Service Provider Interface (SPI). It registers the
 * {@link SafeGeoSPARQLDatasetAssembler} with Jena's assembler registry,
 * enabling Fuseki to create safe GeoSPARQL datasets that handle mixed
 * data types gracefully.</p>
 *
 * <p>The registration is performed when Jena initializes, typically at
 * application startup.</p>
 *
 * @see SafeGeoSPARQLDatasetAssembler
 * @see SafeGeoSPARQLVocabulary
 */
public class SafeGeoSPARQLInit implements JenaSubsystemLifecycle {

    /** Logger instance for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        SafeGeoSPARQLInit.class);

    /**
     * Creates a new SafeGeoSPARQLInit instance.
     */
    public SafeGeoSPARQLInit() {
        // Default constructor for SPI discovery
    }

    /**
     * Returns the initialization level for this subsystem.
     * Level 600 ensures this runs after FalkorDB initialization (500)
     * and after GeoSPARQL initialization.
     *
     * @return the initialization level
     */
    @Override
    public int level() {
        return 600;
    }

    /**
     * Initialize the Safe GeoSPARQL Dataset Assembler module.
     * This method registers the safe assembler with Jena's registry.
     */
    @Override
    public void start() {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Initializing Safe GeoSPARQL Dataset Assembler");
        }

        // Register the SafeGeosparqlDataset type with our assembler
        AssemblerUtils.registerDataset(
            SafeGeoSPARQLVocabulary.SafeGeosparqlDataset,
            new SafeGeoSPARQLDatasetAssembler()
        );

        // Also register as a general assembler
        Assembler.general().implementWith(
            SafeGeoSPARQLVocabulary.SafeGeosparqlDataset,
            new SafeGeoSPARQLDatasetAssembler()
        );

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Safe GeoSPARQL Dataset Assembler registered for type: {}",
                SafeGeoSPARQLVocabulary.SafeGeosparqlDataset);
        }
    }

    /**
     * Shutdown hook for the Safe GeoSPARQL assembler module.
     * Currently does nothing as no cleanup is required.
     */
    @Override
    public void stop() {
        // No cleanup required
    }
}
