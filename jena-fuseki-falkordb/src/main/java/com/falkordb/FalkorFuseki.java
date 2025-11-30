package com.falkordb;

import com.falkordb.jena.FalkorDBModelFactory;
import java.io.File;
import java.net.URL;
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
 *
 * <p>The server can be started in two modes:</p>
 * <ul>
 *   <li><b>Config mode</b>: Using a TTL configuration file with the assembler
 *       (e.g., {@code --config config-falkordb.ttl})</li>
 *   <li><b>Direct mode</b>: Using environment variables for connection settings
 *       (default mode when no config file is specified)</li>
 * </ul>
 *
 * <p>Example config file usage:</p>
 * <pre>
 * java -jar jena-fuseki-falkordb.jar --config config-falkordb.ttl
 * </pre>
 *
 * <p>Example environment variable usage:</p>
 * <pre>
 * export FALKORDB_HOST=localhost
 * export FALKORDB_PORT=6379
 * export FALKORDB_GRAPH=my_graph
 * export FUSEKI_PORT=3330
 * java -jar jena-fuseki-falkordb.jar
 * </pre>
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
    /** Classpath resource path for webapp. */
    private static final String WEBAPP_RESOURCE_PATH = "webapp";
    /** Development webapp path relative to module root. */
    private static final String DEV_WEBAPP_PATH = "src/main/resources/webapp";
    /** Module webapp path when running from project root. */
    private static final String MODULE_WEBAPP_PATH =
        "jena-fuseki-falkordb/" + DEV_WEBAPP_PATH;
    /** Default config file name in classpath. */
    private static final String DEFAULT_CONFIG_RESOURCE = "config-falkordb.ttl";

    /** Prevent instantiation of this utility class. */
    private FalkorFuseki() {
        throw new AssertionError("No instances");
    }

    /**
     * Main entry point for starting the FalkorDB-backed Fuseki server.
     *
     * <p>Supports the following command line options:</p>
     * <ul>
     *   <li>{@code --config <file>}: Path to TTL configuration file</li>
     *   <li>{@code --help}: Show usage information</li>
     * </ul>
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        // Parse command line arguments
        String configFile = null;
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configFile = args[i + 1];
                i++;
            } else if ("--help".equals(args[i])) {
                printUsage();
                return;
            }
        }

        // Start server with config file or direct mode
        if (configFile != null) {
            startWithConfig(configFile);
        } else {
            // Check for config file via environment variable
            String envConfig = System.getenv("FUSEKI_CONFIG");
            if (envConfig != null && !envConfig.isEmpty()) {
                startWithConfig(envConfig);
            } else {
                startDirect();
            }
        }
    }

    /**
     * Print usage information to stdout.
     */
    private static void printUsage() {
        System.out.println("""
            FalkorDB Fuseki Server

            Usage:
              java -jar jena-fuseki-falkordb.jar [options]

            Options:
              --config <file>  Path to TTL configuration file
              --help           Show this help message

            Environment Variables (used when no config file):
              FALKORDB_HOST    FalkorDB host (default: localhost)
              FALKORDB_PORT    FalkorDB port (default: 6379)
              FALKORDB_GRAPH   Graph name (default: my_knowledge_graph)
              FUSEKI_PORT      Fuseki server port (default: 3330)
              FUSEKI_CONFIG    Path to config file (alternative to --config)

            Example config file (TTL):
              @prefix falkor:  <http://falkordb.com/jena/assembler#> .
              @prefix fuseki:  <http://jena.apache.org/fuseki#> .
              @prefix ja:      <http://jena.hpl.hp.com/2005/11/Assembler#> .

              :dataset_rdf rdf:type ja:RDFDataset ;
                  ja:defaultGraph :falkor_db_model .

              :falkor_db_model rdf:type falkor:FalkorDBModel ;
                  falkor:host "localhost" ;
                  falkor:port 6379 ;
                  falkor:graphName "my_graph" .""");
    }

    /**
     * Start the Fuseki server using a configuration file.
     *
     * <p>The configuration file should be in Turtle format and define
     * FalkorDB-backed datasets using the assembler vocabulary.</p>
     *
     * @param configPath path to the configuration file
     */
    private static void startWithConfig(final String configPath) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Starting Fuseki with config file: {}", configPath);
        }

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            // Try loading from classpath
            URL resourceUrl = FalkorFuseki.class.getClassLoader()
                .getResource(configPath);
            if (resourceUrl != null) {
                try {
                    configFile = new File(resourceUrl.toURI());
                } catch (java.net.URISyntaxException e) {
                    LOGGER.error("Invalid config file URI: {}", configPath);
                    System.exit(1);
                    return;
                }
            } else {
                LOGGER.error("Config file not found: {}", configPath);
                System.exit(1);
                return;
            }
        }

        int fusekiPort = getEnvOrDefaultInt("FUSEKI_PORT", DEFAULT_FUSEKI_PORT);

        FusekiServer.Builder serverBuilder = FusekiServer.create()
            .port(fusekiPort)
            .parseConfigFile(configFile.getAbsolutePath());

        // Try to set up static files
        String staticBase = getStaticFileBase();
        if (staticBase != null) {
            serverBuilder.staticFileBase(staticBase);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Serving static files from: {}", staticBase);
            }
        }

        FusekiServer server = serverBuilder.build();
        server.start();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Server running on port {}. "
                + "Check config file for service endpoints.", fusekiPort);
        }
    }

    /**
     * Start the Fuseki server using direct connection settings.
     *
     * <p>Connection settings are read from environment variables.</p>
     */
    private static void startDirect() {
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

        FusekiServer.Builder serverBuilder = FusekiServer.create()
                .port(fusekiPort)
                .add(DEFAULT_DATASET_PATH, ds);

        // Try to set up static files from classpath or filesystem
        String staticBase = getStaticFileBase();
        if (staticBase != null) {
            serverBuilder.staticFileBase(staticBase);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Serving static files from: {}", staticBase);
            }
        }

        FusekiServer server = serverBuilder.build();
        server.start();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Server running. SPARQL endpoint: "
                + "http://localhost:{}{}", fusekiPort, DEFAULT_DATASET_PATH);
        }
    }

    /**
     * Gets the static file base path.
     * First checks for STATIC_FILES_BASE environment variable,
     * then tries to get resources from the classpath,
     * then falls back to filesystem paths if running from source.
     *
     * @return the static file base path, or null if not available
     */
    private static String getStaticFileBase() {
        // First check for explicit configuration via environment variable
        String envPath = System.getenv("STATIC_FILES_BASE");
        if (envPath != null && !envPath.isEmpty()) {
            File envFile = new File(envPath);
            try {
                String canonicalPath = envFile.getCanonicalPath();
                File canonicalFile = new File(canonicalPath);
                if (canonicalFile.exists() && canonicalFile.isDirectory()) {
                    return canonicalPath;
                } else {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("STATIC_FILES_BASE '{}' does not exist or is not a directory", envPath);
                    }
                }
            } catch (java.io.IOException e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Invalid path in STATIC_FILES_BASE: {}", envPath);
                }
            }
        }

        // Try to get from classpath resource
        URL resourceUrl = FalkorFuseki.class.getClassLoader()
            .getResource(WEBAPP_RESOURCE_PATH);
        if (resourceUrl != null) {
            String protocol = resourceUrl.getProtocol();
            if ("file".equals(protocol)) {
                // Running from filesystem (e.g., IDE or mvn exec)
                return resourceUrl.getPath();
            }
            // Running from JAR - Fuseki can't serve from JAR directly
            // Return null and let Fuseki use its default behavior
        }

        // Fallback: try src/main/resources/webapp for development
        File devPath = new File(DEV_WEBAPP_PATH);
        if (devPath.exists() && devPath.isDirectory()) {
            return devPath.getAbsolutePath();
        }

        // Try jena-fuseki-falkordb subdir for running from root
        File submodulePath = new File(MODULE_WEBAPP_PATH);
        if (submodulePath.exists() && submodulePath.isDirectory()) {
            return submodulePath.getAbsolutePath();
        }

        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn("Static files not available. "
                + "Web UI may not be accessible.");
        }
        return null;
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