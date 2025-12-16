package com.falkordb;

import com.falkordb.jena.FalkorDBModelFactory;
import com.falkordb.jena.tracing.TracingUtil;
import com.falkordb.tracing.FusekiTracingFilter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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

        // Enable Fuseki UI by serving webapp resources from classpath
        setupWebappUI(serverBuilder);

        // Configure tracing
        configureTracing(serverBuilder);

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

        // Enable Fuseki UI by serving webapp resources from classpath
        setupWebappUI(serverBuilder);

        // Configure tracing
        configureTracing(serverBuilder);

        FusekiServer server = serverBuilder.build();
        server.start();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Server running. SPARQL endpoint: "
                + "http://localhost:{}{}", fusekiPort, DEFAULT_DATASET_PATH);
        }
    }

    /**
     * Setup the Fuseki web UI by extracting webapp resources from JAR.
     * The webapp resources are packaged in the JAR under the "webapp/" directory
     * from the jena-fuseki-ui dependency. This method extracts them to a
     * temporary directory and configures the server to serve them.
     *
     * @param serverBuilder the FusekiServer.Builder to configure
     */
    private static void setupWebappUI(
            final FusekiServer.Builder serverBuilder) {
        try {
            // Get the webapp resource URL
            URL webappUrl = FalkorFuseki.class.getClassLoader()
                .getResource("webapp/");
            
            if (webappUrl == null) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Webapp resources not found in classpath. "
                        + "Web UI will not be available.");
                }
                return;
            }

            String protocol = webappUrl.getProtocol();
            
            if ("file".equals(protocol)) {
                // Running from filesystem (development mode)
                String webappPath = webappUrl.getPath();
                serverBuilder.staticFileBase(webappPath);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Serving Fuseki UI from: {}", webappPath);
                }
            } else if ("jar".equals(protocol)) {
                // Running from JAR - extract webapp to temp directory
                Path tempDir = Files.createTempDirectory("fuseki-webapp-");
                tempDir.toFile().deleteOnExit();
                
                extractWebappFromJar(tempDir);
                
                // Point to the webapp subdirectory within the temp directory
                String webappPath = tempDir.resolve("webapp").toString();
                serverBuilder.staticFileBase(webappPath);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Serving Fuseki UI from extracted resources: {}",
                        webappPath);
                }
            }
        } catch (IOException e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Failed to setup webapp UI: {}", e.getMessage());
            }
        }
    }

    /**
     * Extract webapp resources from the JAR to a temporary directory.
     *
     * @param targetDir the directory to extract resources to
     * @throws IOException if extraction fails
     */
    private static void extractWebappFromJar(final Path targetDir)
            throws IOException {
        // Get the JAR file containing this class
        String jarPath = FalkorFuseki.class.getProtectionDomain()
            .getCodeSource().getLocation().getPath();
        
        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                
                // Extract only webapp/ resources
                if (name.startsWith("webapp/") && !entry.isDirectory()) {
                    Path targetFile = targetDir.resolve(name);
                    Files.createDirectories(targetFile.getParent());
                    
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        Files.copy(is, targetFile,
                            StandardCopyOption.REPLACE_EXISTING);
                        targetFile.toFile().deleteOnExit();
                    }
                }
            }
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

    /**
     * Configure OpenTelemetry tracing for the Fuseki server.
     *
     * <p>Initializes OpenTelemetry and adds the tracing filter to
     * the server builder if tracing is enabled.</p>
     *
     * @param serverBuilder the FusekiServer.Builder to configure
     */
    private static void configureTracing(
            final FusekiServer.Builder serverBuilder) {
        // Initialize OpenTelemetry
        TracingUtil.getOpenTelemetry();

        // Add tracing filter if tracing is enabled
        if (TracingUtil.isTracingEnabled()) {
            serverBuilder.addFilter("/*", new FusekiTracingFilter());
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("OpenTelemetry tracing enabled");
            }
        }
    }
}