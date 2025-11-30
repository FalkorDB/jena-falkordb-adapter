package com.falkordb.jena;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
@Testcontainers
public abstract class FalkorDBTestBase {
    
    /** Default FalkorDB port. */
    protected static final int FALKORDB_PORT = 6379;
    
    /** Environment variable name for FalkorDB host. */
    private static final String ENV_HOST = "FALKORDB_HOST";
    
    /** Environment variable name for FalkorDB port. */
    private static final String ENV_PORT = "FALKORDB_PORT";

    /**
     * Testcontainers container for FalkorDB.
     * Only started when environment variables are not set (local development).
     * 
     * The @SuppressWarnings("resource") is needed because the static container
     * field is managed by Testcontainers lifecycle (via @Container annotation)
     * and should not be manually closed.
     */
    @SuppressWarnings("resource")
    @Container
    protected static final GenericContainer<?> falkordb = createContainer();

    /** Host where FalkorDB is running. */
    protected static String falkorHost;
    
    /** Port where FalkorDB is accessible. */
    protected static int falkorPort;
    
    /**
     * Creates the FalkorDB container only if environment variables are not set.
     * When running in CI with a FalkorDB service, returns null to skip container.
     */
    private static GenericContainer<?> createContainer() {
        // Check if we're running in CI with a FalkorDB service
        String envHost = System.getenv(ENV_HOST);
        String envPort = System.getenv(ENV_PORT);
        
        if (envHost != null && !envHost.isEmpty() 
                && envPort != null && !envPort.isEmpty()) {
            // CI mode: use external FalkorDB service, don't create container
            return null;
        }
        
        // Local mode: create Testcontainer
        return new GenericContainer<>(
                DockerImageName.parse("falkordb/falkordb:latest"))
                .withExposedPorts(FALKORDB_PORT);
    }

    /**
     * Set up the container host and port before all tests.
     * Uses environment variables if set (CI), otherwise uses Testcontainer.
     */
    @BeforeAll
    public static void setUpContainer() {
        String envHost = System.getenv(ENV_HOST);
        String envPort = System.getenv(ENV_PORT);
        
        if (envHost != null && !envHost.isEmpty() 
                && envPort != null && !envPort.isEmpty()) {
            // CI mode: use environment variables
            falkorHost = envHost;
            falkorPort = Integer.parseInt(envPort);
        } else {
            // Local mode: use Testcontainer
            falkorHost = falkordb.getHost();
            falkorPort = falkordb.getMappedPort(FALKORDB_PORT);
        }
    }
}
