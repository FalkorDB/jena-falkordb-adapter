package com.falkordb;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the FalkorDB-backed Fuseki SPARQL server.
 *
 * <p>This class starts an Apache Jena Fuseki server that uses FalkorDB
 * as the underlying graph database for RDF storage.</p>
 */
public final class FalkorFuseki {
    /** Logger instance for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        FalkorFuseki.class);

    /** Default FalkorDB host. */
    private static final String DEFAULT_HOST = "localhost";
    /** Default FalkorDB port. */
    private static final int DEFAULT_PORT = 6379;
    /** Default graph key name. */
    private static final String DEFAULT_GRAPH_KEY = "my_knowledge_graph";
    /** Default Fuseki server port. */
    private static final int DEFAULT_FUSEKI_PORT = 3330;
    /** Default dataset path. */
    private static final String DEFAULT_DATASET_PATH = "/falkor";
    /** Default static file base path. */
    private static final String DEFAULT_STATIC_BASE =
        "src/main/resources/webapp";

    /** Prevent instantiation of this utility class. */
    private FalkorFuseki() {
        throw new AssertionError("No instances");
    }

    /**
     * Main entry point for starting the FalkorDB-backed Fuseki server.
     *
     * @param args command line arguments (ignored)
     */
    public static void main(final String[] args) {
        // 1. Connection Details for FalkorDB (Redis)
        String host = getEnvOrDefault("FALKORDB_HOST", DEFAULT_HOST);
        int port = getEnvOrDefaultInt("FALKORDB_PORT", DEFAULT_PORT);
        String graphKey = getEnvOrDefault("FALKORDB_GRAPH", DEFAULT_GRAPH_KEY);
        int fusekiPort = getEnvOrDefaultInt(
            "FUSEKI_PORT", DEFAULT_FUSEKI_PORT);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Connecting to FalkorDB at {}:{}...", host, port);
        }

        // 2. Create the FalkorDB-backed Jena Model
        Model falkorGraph = FalkorDBModelFactory.builder()
                .host(host)
                .port(port)
                .graphName(graphKey)
                .build();

        // 3. Wrap the Model in a Dataset (Fuseki serves Datasets, not Models)
        Dataset ds = DatasetFactory.create(falkorGraph);

        // 4. Build and Start the Fuseki Server
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Starting Fuseki Server on port {}...", fusekiPort);
        }

        FusekiServer server = FusekiServer.create()
                .port(fusekiPort)
                .add(DEFAULT_DATASET_PATH, ds)
                .staticFileBase(DEFAULT_STATIC_BASE)
                .build();

        server.start();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Server running. SPARQL endpoint: "
                + "http://localhost:{}{}", fusekiPort, DEFAULT_DATASET_PATH);
        }
    }

    /**
     * Get environment variable value or return the default.
     *
     * @param name environment variable name
     * @param defaultValue default value if not set
     * @return the environment variable value or default
     */
    private static String getEnvOrDefault(final String name,
            final String defaultValue) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    /**
     * Get environment variable value as integer or return the default.
     *
     * @param name environment variable name
     * @param defaultValue default value if not set or invalid
     * @return the environment variable value as integer or default
     */
    private static int getEnvOrDefaultInt(final String name,
            final int defaultValue) {
        String value = System.getenv(name);
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Invalid integer value for {}: {}, "
                        + "using default: {}", name, value, defaultValue);
                }
            }
        }
        return defaultValue;
    }
}
