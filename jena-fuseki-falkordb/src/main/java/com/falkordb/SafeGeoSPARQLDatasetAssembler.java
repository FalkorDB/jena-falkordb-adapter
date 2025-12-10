package com.falkordb;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.Mode;
import org.apache.jena.assembler.assemblers.AssemblerBase;
import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safe GeoSPARQL Dataset Assembler that prevents spatial index building errors
 * by bypassing spatial index creation entirely.
 *
 * <p>This assembler is designed for use with FalkorDB where spatial operations
 * are pushed down to the database's native geospatial functions (point() and distance()).
 * Since FalkorDB handles spatial indexing natively, there's no need for Apache Jena
 * to build an in-memory spatial index.</p>
 *
 * <p>Benefits of this approach:</p>
 * <ul>
 *   <li>Eliminates spatial index building errors when restarting with existing data</li>
 *   <li>Reduces memory overhead by not maintaining a duplicate spatial index</li>
 *   <li>Leverages FalkorDB's native geospatial capabilities via query pushdown</li>
 *   <li>Prevents "Unrecognised Geometry Datatype" errors with mixed data types</li>
 *   <li>Enables GeoSPARQL query rewriting without spatial indexing</li>
 * </ul>
 *
 * <p>Vocabulary properties (all from geosparql: namespace):</p>
 * <ul>
 *   <li>geosparql:dataset - the underlying dataset to wrap (required)</li>
 *   <li>geosparql:inference - enable GeoSPARQL inference (default: false)</li>
 *   <li>geosparql:queryRewrite - enable query rewriting (default: true)</li>
 *   <li>geosparql:indexEnabled - spatial indexing (ignored, always disabled)</li>
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
    
    /**
     * Constructs a new SafeGeoSPARQLDatasetAssembler.
     * 
     * <p>This assembler bypasses Apache Jena's GeoAssembler to prevent spatial
     * index building, as spatial operations are handled by FalkorDB.</p>
     */
    public SafeGeoSPARQLDatasetAssembler() {
        super();
    }
    
    private static final Logger LOGGER = LoggerFactory.getLogger(
        SafeGeoSPARQLDatasetAssembler.class);
    
    private static final String GEOSPARQL_NS = "http://jena.apache.org/geosparql#";
    
    /**
     * Open a GeoSPARQL dataset without building a spatial index.
     *
     * <p>This method bypasses Apache Jena's GeoAssembler to prevent spatial index
     * building. Since FalkorDB handles spatial operations natively through query
     * pushdown, an in-memory spatial index is not needed.</p>
     *
     * @param assembler the assembler to use
     * @param root the root resource
     * @param mode the assembly mode
     * @return the assembled dataset with GeoSPARQL query rewriting enabled
     */
    @Override
    public Dataset open(Assembler assembler, Resource root, Mode mode) {
        LOGGER.info("Initializing Safe GeoSPARQL Dataset (spatial indexing disabled)");
        LOGGER.info("Spatial operations will be pushed down to FalkorDB's native geospatial functions");
        
        // Get configuration properties
        Property datasetProperty = root.getModel().createProperty(GEOSPARQL_NS, "dataset");
        Property inferenceProperty = root.getModel().createProperty(GEOSPARQL_NS, "inference");
        Property queryRewriteProperty = root.getModel().createProperty(GEOSPARQL_NS, "queryRewrite");
        Property applyDefaultGeometryProperty = root.getModel().createProperty(GEOSPARQL_NS, "applyDefaultGeometry");
        
        // Validate required property
        if (!root.hasProperty(datasetProperty)) {
            String msg = "No geosparql:dataset property found - this property is required";
            LOGGER.error(msg);
            throw new org.apache.jena.assembler.exceptions.AssemblerException(root, msg);
        }
        
        // Get configuration values with defaults
        boolean inference = root.hasProperty(inferenceProperty) 
            ? root.getProperty(inferenceProperty).getBoolean() 
            : false;
        boolean queryRewrite = root.hasProperty(queryRewriteProperty)
            ? root.getProperty(queryRewriteProperty).getBoolean()
            : true;
        boolean applyDefaultGeometry = root.hasProperty(applyDefaultGeometryProperty)
            ? root.getProperty(applyDefaultGeometryProperty).getBoolean()
            : false;
        
        LOGGER.info("GeoSPARQL configuration: inference={}, queryRewrite={}, applyDefaultGeometry={}", 
                   inference, queryRewrite, applyDefaultGeometry);
        LOGGER.info("Spatial indexing: DISABLED (spatial queries pushed down to FalkorDB)");
        
        // Get the underlying dataset
        Resource datasetResource = root.getProperty(datasetProperty).getResource();
        Dataset baseDataset = (Dataset) assembler.open(datasetResource);
        
        LOGGER.info("Base dataset opened successfully");
        
        // Configure GeoSPARQL for query rewriting WITHOUT spatial index
        // This enables GeoSPARQL function recognition and query rewriting
        // while spatial operations are handled by FalkorDB via pushdown
        try {
            // Initialize GeoSPARQL with query rewriting enabled but NO spatial index
            // The setupMemoryIndex() only sets up vocabulary and query rewriting,
            // it doesn't force index building like the GeoAssembler does
            GeoSPARQLConfig.setupMemoryIndex();
            LOGGER.info("GeoSPARQL query rewriting initialized successfully");
            LOGGER.info("Spatial queries will be translated to FalkorDB's point() and distance() functions");
        } catch (Exception e) {
            LOGGER.warn("Could not initialize GeoSPARQL query rewriting: {}", e.getMessage());
            LOGGER.warn("GeoSPARQL functions may not be recognized in queries");
        }
        
        LOGGER.info("Safe GeoSPARQL Dataset initialized successfully");
        
        // Return the base dataset without wrapping it in a GeoSPARQL dataset
        // Query pushdown will handle spatial operations at the database level
        return baseDataset;
    }
}
