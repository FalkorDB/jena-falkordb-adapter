package com.falkordb.jena.assembler;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;

/**
 * Utility methods for FalkorDB assemblers.
 *
 * <p>This class provides common utility methods used by the FalkorDB
 * assembler classes to extract configuration values from RDF resources.</p>
 */
final class AssemblerUtils {

    /** Private constructor to prevent instantiation. */
    private AssemblerUtils() {
        throw new AssertionError("No instances");
    }

    /**
     * Get a string property value from a configuration resource.
     *
     * @param root the configuration resource
     * @param property the property to retrieve
     * @param defaultValue the default value if property is not present
     * @return the property value or default
     */
    static String getStringProperty(final Resource root,
            final Property property, final String defaultValue) {
        Statement stmt = root.getProperty(property);
        if (stmt == null) {
            return defaultValue;
        }
        return stmt.getString();
    }

    /**
     * Get an integer property value from a configuration resource.
     *
     * @param root the configuration resource
     * @param property the property to retrieve
     * @param defaultValue the default value if property is not present
     * @return the property value or default
     */
    static int getIntProperty(final Resource root, final Property property,
            final int defaultValue) {
        Statement stmt = root.getProperty(property);
        if (stmt == null) {
            return defaultValue;
        }
        return stmt.getInt();
    }
}
