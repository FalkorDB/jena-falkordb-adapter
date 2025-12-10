package com.falkordb;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.Mode;
import org.apache.jena.assembler.assemblers.AssemblerBase;
import org.apache.jena.geosparql.assembler.GeoAssembler;
import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safe GeoSPARQL Dataset Assembler that prevents spatial index building errors
 * when restarting with existing non-geometry data.
 *
 * <p>This assembler wraps the standard GeoSPARQL assembler but gracefully
 * handles the case where the underlying dataset contains mixed data (geometry
 * and non-geometry literals). It prevents the "Unrecognised Geometry Datatype"
 * error that occurs when restarting Fuseki with existing data in FalkorDB.</p>
 *
 * <p>The issue occurs because:</p>
 * <ul>
 *   <li>Apache Jena's GeoSPARQL assembler tries to build a spatial index on startup</li>
 *   <li>It iterates through all triples to find geometries</li>
 *   <li>When it encounters non-geometry literals (e.g., xsd:string), it throws an exception</li>
 *   <li>This prevents the server from starting even though the data is valid</li>
 * </ul>
 *
 * <p>This assembler catches the DatatypeFormatException and allows the server
 * to start successfully. GeoSPARQL query rewriting will still work, but the
 * spatial index won't be built until the issue is resolved in Apache Jena.</p>
 *
 * <p>Vocabulary properties (all from geosparql: namespace):</p>
 * <ul>
 *   <li>geosparql:dataset - the underlying dataset to wrap</li>
 *   <li>geosparql:inference - enable GeoSPARQL inference (default: false)</li>
 *   <li>geosparql:queryRewrite - enable query rewriting (default: true)</li>
 *   <li>geosparql:indexEnabled - enable spatial indexing (default: false)</li>
 *   <li>geosparql:applyDefaultGeometry - apply default geometry (default: false)</li>
 * </ul>
 *
 * <p>Example usage in assembler configuration:</p>
 * <pre>
 * :geospatial_dataset rdf:type falkor:SafeGeosparqlDataset ;
 *     geosparql:dataset :dataset_rdf ;
 *     geosparql:inference false ;
 *     geosparql:queryRewrite true ;
 *     geosparql:indexEnabled false .
 * </pre>
 */
public class SafeGeoSPARQLDatasetAssembler extends AssemblerBase {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(
        SafeGeoSPARQLDatasetAssembler.class);
    
    /** The standard GeoSPARQL assembler to delegate to. */
    private final GeoAssembler geoAssembler = new GeoAssembler();
    
    /**
     * Open a GeoSPARQL dataset with safe error handling.
     *
     * @param assembler the assembler to use
     * @param root the root resource
     * @param mode the assembly mode
     * @return the assembled dataset
     */
    @Override
    public Dataset open(Assembler assembler, Resource root, Mode mode) {
        LOGGER.info("Initializing Safe GeoSPARQL Dataset");
        
        try {
            // Try to use the standard GeoSPARQL assembler
            Dataset dataset = geoAssembler.open(assembler, root, mode);
            LOGGER.info("GeoSPARQL dataset created successfully");
            return dataset;
            
        } catch (org.apache.jena.datatypes.DatatypeFormatException e) {
            // This is the specific error that occurs with mixed data types
            LOGGER.warn("DatatypeFormatException during GeoSPARQL dataset creation: {}", 
                       e.getMessage());
            LOGGER.warn("This error occurs when the dataset contains non-geometry literals");
            LOGGER.warn("Falling back to base dataset without spatial index");
            
            // Get the underlying dataset without GeoSPARQL wrapper
            return fallbackToBaseDataset(assembler, root);
            
        } catch (Exception e) {
            // Any other exception (including SpatialIndexException wrapped in other exceptions)
            LOGGER.error("Error during GeoSPARQL dataset creation: {}", 
                        e.getMessage());
            
            // Check if this is a spatial index exception in the cause chain
            Throwable cause = e;
            while (cause != null) {
                if (cause.getClass().getName().contains("SpatialIndexException") ||
                    cause.getClass().getName().contains("DatatypeFormatException")) {
                    LOGGER.warn("Spatial/Datatype error detected in cause chain");
                    LOGGER.warn("Falling back to base dataset without spatial index");
                    return fallbackToBaseDataset(assembler, root);
                }
                cause = cause.getCause();
            }
            
            LOGGER.error("Unexpected error - falling back to base dataset");
            return fallbackToBaseDataset(assembler, root);
        }
    }
    
    /**
     * Fallback to the base dataset when GeoSPARQL setup fails.
     * This retrieves the underlying dataset without the GeoSPARQL wrapper.
     *
     * @param assembler the assembler to use
     * @param root the root resource
     * @return the base dataset
     */
    private Dataset fallbackToBaseDataset(Assembler assembler, Resource root) {
        try {
            // Get the geosparql:dataset property
            String GEOSPARQL_NS = "http://jena.apache.org/geosparql#";
            var datasetProperty = root.getModel().createProperty(GEOSPARQL_NS, "dataset");
            
            if (root.hasProperty(datasetProperty)) {
                Resource datasetResource = root.getProperty(datasetProperty).getResource();
                Dataset baseDataset = (Dataset) assembler.open(datasetResource);
                
                LOGGER.info("Successfully retrieved base dataset");
                LOGGER.info("GeoSPARQL query rewriting features will be available");
                LOGGER.info("Spatial index will not be available - spatial queries may be slower");
                
                // Initialize GeoSPARQL for query rewriting without index
                try {
                    GeoSPARQLConfig.setupMemoryIndex();
                    LOGGER.info("GeoSPARQL query rewriting initialized");
                } catch (Exception e) {
                    LOGGER.debug("Could not initialize GeoSPARQL query rewriting: {}", 
                                e.getMessage());
                }
                
                return baseDataset;
            }
            
            throw new IllegalStateException("No geosparql:dataset property found");
            
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve base dataset: {}", e.getMessage(), e);
            throw new org.apache.jena.assembler.exceptions.AssemblerException(root,
                "Failed to create Safe GeoSPARQL dataset", e);
        }
    }
}
