/**
 * Apache Jena GeoSPARQL module bundled with all dependencies.
 *
 * <p>This module packages the Apache Jena GeoSPARQL library along with
 * all its dependencies into a single JAR file that can be easily added
 * to an Apache Jena Fuseki installation.</p>
 *
 * <p>GeoSPARQL provides support for representing and querying geospatial
 * data in RDF. It implements the OGC GeoSPARQL standard.</p>
 *
 * <h2>Usage with Fuseki</h2>
 * <p>To use GeoSPARQL with Fuseki:</p>
 * <ol>
 *   <li>Build this module: {@code mvn clean package}</li>
 *   <li>Copy the JAR with dependencies to Fuseki's lib directory</li>
 *   <li>Configure Fuseki with a GeoSPARQL dataset configuration</li>
 * </ol>
 *
 * @see <a href="https://jena.apache.org/documentation/geosparql/">Apache Jena GeoSPARQL Documentation</a>
 * @see <a href="https://www.ogc.org/standards/geosparql">OGC GeoSPARQL Standard</a>
 */
package com.falkordb.geosparql;
