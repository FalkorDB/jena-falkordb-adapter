package com.falkordb.jena.query;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SparqlToCypherCompiler.
 */
public class SparqlToCypherCompilerTest {

    @Test
    @DisplayName("Test compiling empty BGP throws exception")
    public void testEmptyBGPThrowsException() {
        BasicPattern bgp = new BasicPattern();
        
        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(bgp));
    }

    @Test
    @DisplayName("Test compiling null BGP throws exception")
    public void testNullBGPThrowsException() {
        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(null));
    }

    @Test
    @DisplayName("Test variable object throws exception (falls back to standard evaluation)")
    public void testVariableObjectThrowsException() {
        // ?s foaf:name ?name - variable object not supported for pushdown
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        // Should throw CannotCompileException because we can't determine
        // if the variable object will be a literal or resource
        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(bgp));
    }

    @Test
    @DisplayName("Test compiling literal property pattern")
    public void testLiteralPropertyPattern() throws Exception {
        // ?s foaf:name "Alice" - concrete literal
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createLiteralString("Alice")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.cypherQuery());
        assertTrue(result.cypherQuery().contains("MATCH"));
        assertTrue(result.cypherQuery().contains("RETURN"));
        // Concrete literal value should be parameterized
        assertTrue(result.parameters().values().contains("Alice"));
    }

    @Test
    @DisplayName("Test compiling relationship with concrete URIs")
    public void testRelationshipWithConcreteURIs() throws Exception {
        // <http://example.org/alice> foaf:knows <http://example.org/bob>
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createURI("http://example.org/bob")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.cypherQuery());
        assertTrue(result.cypherQuery().contains("MATCH"));
        assertTrue(result.cypherQuery().contains(":Resource"));
        assertTrue(result.cypherQuery().contains("knows"));
    }

    @Test
    @DisplayName("Test concrete subject URI with variable object throws exception")
    public void testConcreteSubjectWithVariableObjectThrowsException() {
        // <http://example.org/alice> foaf:knows ?o - variable object not supported
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("o")
        ));

        // Variable objects are not supported for pushdown
        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(bgp));
    }

    @Test
    @DisplayName("Test variable predicate throws exception")
    public void testVariablePredicateThrowsException() {
        // ?s ?p ?o - variable predicates not supported
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("o")
        ));

        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(bgp));
    }

    @Test
    @DisplayName("Test multiple patterns with variable objects throws exception")
    public void testMultiplePatternsWithVariableObjects() {
        // ?person foaf:knows ?friend .
        // ?friend foaf:name ?name .
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("friend"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        // Variable objects are not supported for pushdown
        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(bgp));
    }

    @Test
    @DisplayName("Test compilation result contains proper structure")
    public void testCompilationResultStructure() throws Exception {
        // Use concrete URIs for subject and object
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/s"),
            NodeFactory.createURI("http://example.org/prop"),
            NodeFactory.createURI("http://example.org/o")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result.cypherQuery());
        assertNotNull(result.parameters());
        assertNotNull(result.variableMapping());
    }

    @Test
    @DisplayName("Test concrete literal value is parameterized")
    public void testConcreteLiteralValue() throws Exception {
        // ?s foaf:name "Alice"
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createLiteralString("Alice")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        // Concrete literal should be parameterized
        assertTrue(result.parameters().values().contains("Alice"));
    }
}
