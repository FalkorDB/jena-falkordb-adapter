/**
 * Jena Assembler integration for FalkorDB.
 *
 * <p>This package provides the components needed to configure FalkorDB-backed
 * models and datasets using Jena's assembler mechanism. This enables Fuseki
 * servers to automatically create FalkorDB connections from configuration
 * files.</p>
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link com.falkordb.jena.assembler.FalkorDBVocab} - RDF vocabulary
 *       definitions</li>
 *   <li>{@link com.falkordb.jena.assembler.FalkorDBAssembler} - Model
 *       assembler</li>
 *   <li>{@link com.falkordb.jena.assembler.DatasetAssemblerFalkorDB} - Dataset
 *       assembler</li>
 *   <li>{@link com.falkordb.jena.assembler.FalkorDBInit} - Subsystem
 *       initialization</li>
 * </ul>
 *
 * <p>Example Fuseki configuration:</p>
 * <pre>
 * {@literal @}prefix falkor: &lt;http://falkordb.com/jena/assembler#&gt; .
 * {@literal @}prefix fuseki: &lt;http://jena.apache.org/fuseki#&gt; .
 *
 * :service a fuseki:Service ;
 *     fuseki:name "falkor" ;
 *     fuseki:endpoint [ fuseki:operation fuseki:query ] ;
 *     fuseki:dataset :dataset .
 *
 * :dataset a falkor:FalkorDBModel ;
 *     falkor:host "localhost" ;
 *     falkor:port 6379 ;
 *     falkor:graphName "my_graph" .
 * </pre>
 *
 * @see org.apache.jena.assembler.Assembler
 * @see org.apache.jena.sys.JenaSubsystemLifecycle
 */
package com.falkordb.jena.assembler;
