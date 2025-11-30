package com.falkordb.jena.assembler;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * Vocabulary class defining the RDF terms used in FalkorDB assembler
 * configurations.
 *
 * <p>This class provides the namespace URI and property definitions
 * that can be used in Jena assembler configuration files to configure
 * FalkorDB-backed models for Fuseki.</p>
 *
 * <p>Example configuration in Turtle format:</p>
 * <pre>
 * {@literal @}prefix falkor: &lt;http://falkordb.com/jena/assembler#&gt; .
 *
 * :myModel a falkor:FalkorDBModel ;
 *     falkor:host "localhost" ;
 *     falkor:port 6379 ;
 *     falkor:graphName "my_graph" .
 * </pre>
 */
public final class FalkorDBVocab {

    /**
     * The namespace URI for FalkorDB assembler vocabulary.
     */
    public static final String NS = "http://falkordb.com/jena/assembler#";

    /**
     * Returns the namespace URI for FalkorDB assembler vocabulary.
     *
     * @return the namespace URI
     */
    public static String getURI() {
        return NS;
    }

    /**
     * Create a resource in the FalkorDB namespace.
     *
     * @param localName the local name of the resource
     * @return the resource
     */
    private static Resource resource(final String localName) {
        return ResourceFactory.createResource(NS + localName);
    }

    /**
     * Create a property in the FalkorDB namespace.
     *
     * @param localName the local name of the property
     * @return the property
     */
    private static Property property(final String localName) {
        return ResourceFactory.createProperty(NS, localName);
    }

    /**
     * The type for a FalkorDB-backed model.
     * Usage: {@code :myModel a falkor:FalkorDBModel .}
     */
    public static final Resource FalkorDBModel = resource("FalkorDBModel");

    /**
     * Property to specify the FalkorDB host.
     * Default value: "localhost"
     */
    public static final Property host = property("host");

    /**
     * Property to specify the FalkorDB port.
     * Default value: 6379
     */
    public static final Property port = property("port");

    /**
     * Property to specify the FalkorDB graph name.
     * Default value: "rdf_graph"
     */
    public static final Property graphName = property("graphName");

    /** Private constructor to prevent instantiation. */
    private FalkorDBVocab() {
        throw new AssertionError("No instances");
    }
}
