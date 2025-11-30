package com.falkordb.jena.assembler;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.Mode;
import org.apache.jena.assembler.assemblers.AssemblerBase;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jena Assembler implementation for creating FalkorDB-backed models.
 *
 * <p>This assembler reads configuration from RDF and creates a Jena Model
 * backed by FalkorDB. It is used by Fuseki to automatically configure
 * FalkorDB models from assembler configuration files.</p>
 *
 * <p>Configuration properties:</p>
 * <ul>
 *   <li>{@code falkor:host} - FalkorDB host (default: "localhost")</li>
 *   <li>{@code falkor:port} - FalkorDB port (default: 6379)</li>
 *   <li>{@code falkor:graphName} - FalkorDB graph name
 *       (default: "rdf_graph")</li>
 * </ul>
 */
public class FalkorDBAssembler extends AssemblerBase {

    /** Logger instance for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        FalkorDBAssembler.class);

    /** Default FalkorDB host. */
    private static final String DEFAULT_HOST = "localhost";

    /** Default FalkorDB port. */
    private static final int DEFAULT_PORT = 6379;

    /** Default graph name. */
    private static final String DEFAULT_GRAPH_NAME = "rdf_graph";

    /** Singleton instance of this assembler. */
    public static final Assembler INSTANCE = new FalkorDBAssembler();

    /**
     * Open (create) a FalkorDB-backed model from the given configuration
     * resource.
     *
     * @param a the assembler
     * @param root the configuration resource
     * @param mode the assembler mode
     * @return a FalkorDB-backed Jena Model
     */
    @Override
    public Model open(final Assembler a, final Resource root, final Mode mode) {
        // Extract configuration properties
        String host = AssemblerUtils.getStringProperty(root,
            FalkorDBVocab.host, DEFAULT_HOST);
        int port = AssemblerUtils.getIntProperty(root,
            FalkorDBVocab.port, DEFAULT_PORT);
        String graphName = AssemblerUtils.getStringProperty(root,
            FalkorDBVocab.graphName, DEFAULT_GRAPH_NAME);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Creating FalkorDB model: host={}, port={}, graph={}",
                host, port, graphName);
        }

        // Create and return the FalkorDB-backed model
        return FalkorDBModelFactory.builder()
            .host(host)
            .port(port)
            .graphName(graphName)
            .build();
    }
}
