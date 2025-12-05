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
    @DisplayName("Test compiling simple relationship pattern")
    public void testSimpleRelationshipPattern() throws Exception {
        // ?s foaf:knows ?o
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("o")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.cypherQuery());
        assertTrue(result.cypherQuery().contains("MATCH"));
        assertTrue(result.cypherQuery().contains(":Resource"));
        assertTrue(result.cypherQuery().contains("knows"));
        assertTrue(result.cypherQuery().contains("RETURN"));
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
    @DisplayName("Test compiling rdf:type pattern")
    public void testRdfTypePattern() throws Exception {
        // ?s rdf:type foaf:Person
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.cypherQuery());
        assertTrue(result.cypherQuery().contains("MATCH"));
        assertTrue(result.cypherQuery().contains("Person"));
        assertTrue(result.cypherQuery().contains("RETURN"));
    }

    @Test
    @DisplayName("Test compiling concrete subject URI")
    public void testConcreteSubjectURI() throws Exception {
        // <http://example.org/alice> foaf:knows ?o
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("o")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.cypherQuery());
        assertTrue(result.parameters().containsKey("p0"));
        assertEquals("http://example.org/alice", result.parameters().get("p0"));
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
    @DisplayName("Test compiling multiple patterns")
    public void testMultiplePatterns() throws Exception {
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

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.cypherQuery());
        // Should contain both patterns
        assertTrue(result.cypherQuery().contains("knows"));
        assertTrue(result.cypherQuery().contains("name"));
        assertTrue(result.cypherQuery().contains("RETURN"));
    }

    @Test
    @DisplayName("Test compilation result contains proper structure")
    public void testCompilationResultStructure() throws Exception {
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://example.org/prop"),
            NodeFactory.createVariable("o")
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
