package com.falkordb.jena;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that exercise the demo Main class code paths using Testcontainers.
 * 
 * Extends FalkorDBTestBase which provides Testcontainers setup for FalkorDB.
 * No manual Docker setup required - tests work both locally and in CI.
 */
public class MainTest extends FalkorDBTestBase {

    @Test
    @DisplayName("Test Main.runDemo() executes all demo code paths")
    public void testMainRunDemo() {
        // Execute the Main.runDemo() method with dynamic host/port from container
        // This exercises all the code paths in Main.java for full code coverage
        assertDoesNotThrow(() -> Main.runDemo(falkorHost, falkorPort));
    }
}
