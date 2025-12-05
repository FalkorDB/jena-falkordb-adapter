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
    @DisplayName("Test variable object that's used as subject compiles to relationship")
    public void testVariableObjectUsedAsSubjectCompiles() throws Exception {
        // ?s foaf:knows ?friend . ?friend foaf:knows ?fof .
        // Here ?friend is a variable object that's also used as subject
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("friend"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("fof")
        ));

        // Should fail because ?fof is not used as subject
        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(bgp));
    }

    @Test
    @DisplayName("Test single variable object falls back")
    public void testSingleVariableObjectFallsBack() {
        // ?s foaf:knows ?o - single triple with variable object
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("o")
        ));

        // Should throw because ?o might be a literal
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
    @DisplayName("Test concrete subject URI with variable object falls back")
    public void testConcreteSubjectWithVariableObjectFallsBack() {
        // <http://example.org/alice> foaf:knows ?o - variable object
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("o")
        ));

        // Should throw because ?o might be a literal
        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(bgp));
    }

    @Test
    @DisplayName("Test variable predicate compiles with UNION")
    public void testVariablePredicateCompilesWithUnion() throws Exception {
        // ?s ?p ?o - variable predicate should use UNION
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("o")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.cypherQuery());
        assertTrue(result.cypherQuery().contains("MATCH"));
        assertTrue(result.cypherQuery().contains("UNION ALL"));
        assertTrue(result.cypherQuery().contains("type(_r)"));
        assertTrue(result.cypherQuery().contains("UNWIND keys"));
    }

    @Test
    @DisplayName("Test variable predicate with concrete subject")
    public void testVariablePredicateWithConcreteSubject() throws Exception {
        // <http://example.org/alice> ?p ?o
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("o")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.cypherQuery());
        assertTrue(result.cypherQuery().contains("MATCH"));
        assertTrue(result.cypherQuery().contains("UNION ALL"));
        assertTrue(result.parameters().values().contains("http://example.org/alice"));
    }

    @Test
    @DisplayName("Test friends of friends pattern with all relationships compiles")
    public void testFriendsOfFriendsAllRelationshipsPattern() throws Exception {
        // ?a foaf:knows ?b . ?b foaf:knows ?c . ?c foaf:knows ?d .
        // All variable objects are used as subjects - pure relationship pattern
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("a"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("b")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("b"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("c")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("c"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("d")
        ));

        // ?d is not used as subject, so this should fall back
        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(bgp));
    }

    @Test
    @DisplayName("Test closed chain relationship pattern compiles")
    public void testClosedChainPattern() throws Exception {
        // ?a foaf:knows ?b . ?b foaf:knows ?a .
        // Closed chain - all variables used as subjects
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("a"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("b")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("b"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("a")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.cypherQuery());
        assertTrue(result.cypherQuery().contains("MATCH"));
        assertTrue(result.cypherQuery().contains("knows"));
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

    @Test
    @DisplayName("Test friends of friends with concrete start compiles")
    public void testFriendsOfFriendsWithConcreteStartPattern() throws Exception {
        // <http://example.org/alice> foaf:knows ?friend .
        // ?friend foaf:knows ?fof .
        // ?friend is used as subject, but ?fof is not - falls back
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("friend"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("fof")
        ));

        // ?fof is not used as subject, falls back
        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translate(bgp));
    }

    @Test
    @DisplayName("Test rdf:type pattern compiles successfully")
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
    }
}
