package com.falkordb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FalkorFuseki main class.
 */
public class FalkorFusekiTest {

    @Test
    @DisplayName("Test FalkorFuseki class exists and has main method")
    public void testMainMethodExists() throws Exception {
        // Verify main method exists via reflection
        var mainMethod = FalkorFuseki.class.getMethod("main", String[].class);
        assertNotNull(mainMethod, "main method should exist");
    }

    @Test
    @DisplayName("Test FalkorFuseki help option does not throw")
    public void testHelpOption() {
        // Running with --help should print usage and return without error
        assertDoesNotThrow(() -> {
            FalkorFuseki.main(new String[]{"--help"});
        });
    }
}
