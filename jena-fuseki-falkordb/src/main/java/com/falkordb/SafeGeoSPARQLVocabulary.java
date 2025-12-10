package com.falkordb;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * Vocabulary for Safe GeoSPARQL Dataset Assembler.
 * 
 * <p>This vocabulary extends the standard GeoSPARQL vocabulary with a safe
 * dataset type that handles mixed data gracefully.</p>
 */
public class SafeGeoSPARQLVocabulary {
    
    /**
     * Private constructor to prevent instantiation of this utility class.
     * 
     * <p>This class only provides static constants and methods.</p>
     */
    private SafeGeoSPARQLVocabulary() {
        // Utility class - no instantiation
    }
    
    /** Namespace for FalkorDB assembler vocabulary. */
    public static final String NS = "http://falkordb.com/jena/assembler#";
    
    /** Safe GeoSPARQL Dataset type. */
    public static final Resource SafeGeosparqlDataset = 
        ResourceFactory.createResource(NS + "SafeGeosparqlDataset");
    
    /**
     * Get a resource in the FalkorDB assembler namespace.
     *
     * @param local the local name
     * @return the resource
     */
    public static Resource resource(String local) {
        return ResourceFactory.createResource(NS + local);
    }
    
    /**
     * Get a property in the FalkorDB assembler namespace.
     *
     * @param local the local name
     * @return the property
     */
    public static Property property(String local) {
        return ResourceFactory.createProperty(NS + local);
    }
}
