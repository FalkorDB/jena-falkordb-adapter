package com.falkordb.geosparql;

/**
 * GeoSPARQL module marker class.
 *
 * <p>This module packages the Apache Jena GeoSPARQL library along with
 * all its dependencies. The actual GeoSPARQL functionality is provided
 * by the org.apache.jena.geosparql packages.</p>
 *
 * <p>This class serves as a marker and provides version information for
 * the bundled module.</p>
 *
 * @see <a href="https://jena.apache.org/documentation/geosparql/">Apache Jena GeoSPARQL Documentation</a>
 */
public final class GeoSPARQLModule {

    /** The Apache Jena version used in this module. */
    public static final String JENA_VERSION = "5.6.0";
    
    /** Module version. */
    public static final String MODULE_VERSION = "0.2.0-SNAPSHOT";

    /**
     * Private constructor to prevent instantiation.
     */
    private GeoSPARQLModule() {
        throw new AssertionError("No instances");
    }

    /**
     * Returns the Apache Jena version used in this module.
     *
     * @return the Jena version string
     */
    public static String getJenaVersion() {
        return JENA_VERSION;
    }

    /**
     * Returns the module version.
     *
     * @return the module version string
     */
    public static String getModuleVersion() {
        return MODULE_VERSION;
    }
}
