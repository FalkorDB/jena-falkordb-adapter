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
 * Uses Testcontainers to automatically start a FalkorDB container for testing.
 * No manual Docker setup required - tests work both locally and in CI.
 */
@Testcontainers
public abstract class FalkorDBTestBase {
    
    /** Default FalkorDB port. */
    protected static final int FALKORDB_PORT = 6379;

    /**
     * Testcontainers container for FalkorDB.
     * 
     * The @SuppressWarnings("resource") is needed because the static container
     * field is managed by Testcontainers lifecycle (via @Container annotation)
     * and should not be manually closed.
     */
    @SuppressWarnings("resource")
    @Container
    protected static final GenericContainer<?> falkordb = new GenericContainer<>(
            DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(FALKORDB_PORT);

    /** Host where FalkorDB container is running. */
    protected static String falkorHost;
    
    /** Port where FalkorDB container is mapped. */
    protected static int falkorPort;

    /**
     * Set up the container host and port before all tests.
     */
    @BeforeAll
    public static void setUpContainer() {
        falkorHost = falkordb.getHost();
        falkorPort = falkordb.getMappedPort(FALKORDB_PORT);
    }
}
