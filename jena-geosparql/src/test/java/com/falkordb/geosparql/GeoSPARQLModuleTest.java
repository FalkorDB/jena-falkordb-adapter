package com.falkordb.geosparql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GeoSPARQLModule class.
 */
public class GeoSPARQLModuleTest {

    @Test
    @DisplayName("Test getJenaVersion returns correct version")
    public void testGetJenaVersion() {
        String version = GeoSPARQLModule.getJenaVersion();
        assertNotNull(version, "Jena version should not be null");
        assertEquals("5.6.0", version, "Jena version should be 5.6.0");
    }

    @Test
    @DisplayName("Test getModuleVersion returns correct version")
    public void testGetModuleVersion() {
        String version = GeoSPARQLModule.getModuleVersion();
        assertNotNull(version, "Module version should not be null");
        assertEquals("0.2.0-SNAPSHOT", version, "Module version should be 0.2.0-SNAPSHOT");
    }

    @Test
    @DisplayName("Test JENA_VERSION constant")
    public void testJenaVersionConstant() {
        assertEquals("5.6.0", GeoSPARQLModule.JENA_VERSION, 
            "JENA_VERSION constant should be 5.6.0");
    }

    @Test
    @DisplayName("Test MODULE_VERSION constant")
    public void testModuleVersionConstant() {
        assertEquals("0.2.0-SNAPSHOT", GeoSPARQLModule.MODULE_VERSION, 
            "MODULE_VERSION constant should be 0.2.0-SNAPSHOT");
    }

    @Test
    @DisplayName("Test private constructor throws AssertionError")
    public void testPrivateConstructorThrowsAssertionError() throws Exception {
        Constructor<GeoSPARQLModule> constructor = GeoSPARQLModule.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        
        InvocationTargetException exception = assertThrows(
            InvocationTargetException.class,
            () -> constructor.newInstance(),
            "Constructor should throw exception when invoked via reflection"
        );
        
        assertTrue(exception.getCause() instanceof AssertionError,
            "Cause should be AssertionError");
        assertEquals("No instances", exception.getCause().getMessage(),
            "AssertionError message should be 'No instances'");
    }
}
