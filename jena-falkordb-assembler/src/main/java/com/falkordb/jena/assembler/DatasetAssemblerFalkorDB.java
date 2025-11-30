package com.falkordb.jena.assembler;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.Mode;
import org.apache.jena.assembler.assemblers.AssemblerBase;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.core.DatasetGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dataset assembler for creating FalkorDB-backed datasets.
 *
 * <p>This assembler creates a Dataset backed by FalkorDB. It is registered
 * with Fuseki's assembler registry to enable configuration of FalkorDB
 * datasets from assembler configuration files.</p>
 *
 * <p>Example configuration in Turtle format:</p>
 * <pre>
 * {@literal @}prefix falkor: &lt;http://falkordb.com/jena/assembler#&gt; .
 * {@literal @}prefix fuseki: &lt;http://jena.apache.org/fuseki#&gt; .
 * {@literal @}prefix ja: &lt;http://jena.hpl.hp.com/2005/11/Assembler#&gt; .
 *
 * :service a fuseki:Service ;
 *     fuseki:name "falkor" ;
 *     fuseki:endpoint [ fuseki:operation fuseki:query ] ;
 *     fuseki:endpoint [ fuseki:operation fuseki:update ] ;
 *     fuseki:dataset :dataset .
 *
 * :dataset a falkor:FalkorDBModel ;
 *     falkor:host "localhost" ;
 *     falkor:port 6379 ;
 *     falkor:graphName "my_knowledge_graph" .
 * </pre>
 */
public class DatasetAssemblerFalkorDB extends AssemblerBase {

    /** Logger instance for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        DatasetAssemblerFalkorDB.class);

    /**
     * Creates a new DatasetAssemblerFalkorDB instance.
     */
    public DatasetAssemblerFalkorDB() {
        // Default constructor
    }

    /** Default FalkorDB host. */
    private static final String DEFAULT_HOST = "localhost";

    /** Default FalkorDB port. */
    private static final int DEFAULT_PORT = 6379;

    /** Default graph name. */
    private static final String DEFAULT_GRAPH_NAME = "rdf_graph";

    /**
     * Create a DatasetGraph from the given configuration resource.
     *
     * @param a the assembler
     * @param root the configuration resource
     * @return a FalkorDB-backed DatasetGraph
     */
    public DatasetGraph createDataset(final Assembler a, final Resource root) {
        // Extract configuration properties
        String host = AssemblerUtils.getStringProperty(root,
            FalkorDBVocab.host, DEFAULT_HOST);
        int port = AssemblerUtils.getIntProperty(root,
            FalkorDBVocab.port, DEFAULT_PORT);
        String graphName = AssemblerUtils.getStringProperty(root,
            FalkorDBVocab.graphName, DEFAULT_GRAPH_NAME);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                "Creating FalkorDB dataset: host={}, port={}, graph={}",
                host, port, graphName);
        }

        // Create the FalkorDB-backed model
        Model model = FalkorDBModelFactory.builder()
            .host(host)
            .port(port)
            .graphName(graphName)
            .build();

        // Wrap in a Dataset and return its DatasetGraph
        Dataset dataset = DatasetFactory.create(model);
        return dataset.asDatasetGraph();
    }

    /**
     * Open (create) a Dataset from the given configuration resource.
     *
     * @param a the assembler
     * @param root the configuration resource
     * @param mode the assembler mode
     * @return a FalkorDB-backed Dataset
     */
    @Override
    public Dataset open(final Assembler a, final Resource root,
            final Mode mode) {
        DatasetGraph dsg = createDataset(a, root);
        return DatasetFactory.wrap(dsg);
    }
}
