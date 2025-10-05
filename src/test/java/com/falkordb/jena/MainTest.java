package com.falkordb.jena;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/**
 * Small test to execute the demo Main and exercise its code paths so the
 * demo is included in test coverage reports.
 */
public class MainTest {
    @Test
    public void testMainRunsWithoutThrowing() {
        // Execute the demo main; the existing test infra expects a
        // FalkorDB instance on localhost:6379 (same as other tests).
        assertDoesNotThrow(() -> Main.main(new String[0]));
    }
}
