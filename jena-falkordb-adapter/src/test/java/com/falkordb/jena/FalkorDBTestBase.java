package com.falkordb.jena;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base test class for FalkorDB Testcontainers setup.
 * Shared by all FalkorDB-related tests to avoid duplication.
 * 
 * <p>This class supports two modes of operation:
 * <ul>
 *   <li><b>CI mode</b>: When FALKORDB_HOST and FALKORDB_PORT environment 
 *       variables are set, use the external FalkorDB service (no container 
 *       started)</li>
 *   <li><b>Local mode</b>: When environment variables are not set, 
 *       automatically start a FalkorDB container using Testcontainers</li>
 * </ul>
 * 
 * <p>This ensures tests work both locally (with automatic container) and 
 * in CI (with pre-configured FalkorDB service).
 */
public abstract class FalkorDBTestBase {
    
    /** Default FalkorDB port. */
    protected static final int FALKORDB_PORT = 6379;
    
    /** Environment variable name for FalkorDB host. */
    private static final String ENV_HOST = "FALKORDB_HOST";
    
    /** Environment variable name for FalkorDB port. */
    private static final String ENV_PORT = "FALKORDB_PORT";

    /**
     * Testcontainers container for FalkorDB.
     * Only created and started when environment variables are not set 
     * (local development).
     */
    private static GenericContainer<?> falkordbContainer;

    /** Host where FalkorDB is running. */
    protected static String falkorHost;
    
    /** Port where FalkorDB is accessible. */
    protected static int falkorPort;
    
    /**
     * Checks if we're running in CI mode with external FalkorDB service.
     */
    private static boolean isUsingExternalService() {
        String envHost = System.getenv(ENV_HOST);
        String envPort = System.getenv(ENV_PORT);
        return envHost != null && !envHost.isEmpty() 
                && envPort != null && !envPort.isEmpty();
    }

    /**
     * Set up FalkorDB connection before all tests.
     * Uses environment variables if set (CI), otherwise starts Testcontainer.
     */
    @BeforeAll
    public static void setUpFalkorDB() {
        if (isUsingExternalService()) {
            // CI mode: use environment variables
            falkorHost = System.getenv(ENV_HOST);
            falkorPort = Integer.parseInt(System.getenv(ENV_PORT));
        } else {
            // Local mode: create and start Testcontainer
            falkordbContainer = new GenericContainer<>(
                    DockerImageName.parse("falkordb/falkordb:latest"))
                    .withExposedPorts(FALKORDB_PORT);
            falkordbContainer.start();
            falkorHost = falkordbContainer.getHost();
            falkorPort = falkordbContainer.getMappedPort(FALKORDB_PORT);
        }
    }
    
    /**
     * Clean up FalkorDB container after all tests (if we started one).
     */
    @AfterAll
    public static void tearDownFalkorDB() {
        if (falkordbContainer != null) {
            falkordbContainer.stop();
            falkordbContainer = null;
        }
    }
}
