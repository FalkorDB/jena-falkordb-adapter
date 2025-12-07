/**
 * Query optimization components for FalkorDB-Jena adapter.
 *
 * <p>This package contains the query pushdown implementation that translates
 * SPARQL Basic Graph Patterns (BGPs) into native Cypher queries for optimal
 * performance. Instead of the default triple-by-triple evaluation, this
 * optimizer sends a single Cypher query to FalkorDB.</p>
 *
 * <p>Key components:</p>
 * <ul>
 *   <li>{@link com.falkordb.jena.query.SparqlToCypherCompiler} - Translates
 *       SPARQL BGPs to Cypher MATCH clauses</li>
 *   <li>{@link com.falkordb.jena.query.FalkorDBOpExecutor} - Custom OpExecutor
 *       that intercepts BGP operations</li>
 *   <li>{@link com.falkordb.jena.query.FalkorDBQueryEngineFactory} - Factory
 *       to register the custom query engine</li>
 * </ul>
 */
package com.falkordb.jena.query;
