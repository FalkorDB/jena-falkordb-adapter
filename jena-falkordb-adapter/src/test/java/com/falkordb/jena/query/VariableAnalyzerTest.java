package com.falkordb.jena.query;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VariableAnalyzer.
 */
public class VariableAnalyzerTest {

    @Test
    @DisplayName("Analyze empty BGP throws exception")
    public void testEmptyBGPThrowsException() {
        BasicPattern bgp = new BasicPattern();
        
        assertThrows(IllegalArgumentException.class,
            () -> VariableAnalyzer.analyze(bgp));
    }

    @Test
    @DisplayName("Analyze null BGP throws exception")
    public void testNullBGPThrowsException() {
        assertThrows(IllegalArgumentException.class,
            () -> VariableAnalyzer.analyze(null));
    }

    @Test
    @DisplayName("Variable used as subject is classified as NODE")
    public void testSubjectVariableIsNode() {
        // ?person foaf:name "Alice"
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createLiteral("Alice")
        ));

        VariableAnalyzer.AnalysisResult result = VariableAnalyzer.analyze(bgp);

        assertTrue(result.isNodeVariable("person"),
            "Subject variable should be classified as NODE");
        assertTrue(result.getNodeVariables().contains("person"),
            "Should appear in node variables set");
        assertFalse(result.isAmbiguousVariable("person"),
            "Subject variable should not be ambiguous");
    }

    @Test
    @DisplayName("Variable used only as object is classified as AMBIGUOUS")
    public void testObjectOnlyVariableIsAmbiguous() {
        // <alice> foaf:name ?name
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        VariableAnalyzer.AnalysisResult result = VariableAnalyzer.analyze(bgp);

        assertTrue(result.isAmbiguousVariable("name"),
            "Object-only variable should be classified as AMBIGUOUS");
        assertTrue(result.getAmbiguousVariables().contains("name"),
            "Should appear in ambiguous variables set");
        assertFalse(result.isNodeVariable("name"),
            "Object-only variable should not be classified as NODE");
    }

    @Test
    @DisplayName("Variable used as both subject and object is classified as NODE")
    public void testSubjectAndObjectVariableIsNode() {
        // ?person foaf:knows ?friend . ?friend foaf:name "Bob"
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("friend"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createLiteral("Bob")
        ));

        VariableAnalyzer.AnalysisResult result = VariableAnalyzer.analyze(bgp);

        // Both variables appear as subjects, so both are nodes
        assertTrue(result.isNodeVariable("person"),
            "?person appears as subject, should be NODE");
        assertTrue(result.isNodeVariable("friend"),
            "?friend appears as subject, should be NODE");
        
        // Neither should be ambiguous
        assertFalse(result.isAmbiguousVariable("person"),
            "?person should not be ambiguous");
        assertFalse(result.isAmbiguousVariable("friend"),
            "?friend should not be ambiguous");
    }

    @Test
    @DisplayName("Multi-triple pattern with ambiguous and node variables")
    public void testMultiTriplePatternClassification() {
        // ?person foaf:name ?name . ?person foaf:age ?age . ?person foaf:knows ?friend
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));

        VariableAnalyzer.AnalysisResult result = VariableAnalyzer.analyze(bgp);

        // ?person is definitely a node (appears as subject)
        assertTrue(result.isNodeVariable("person"),
            "?person appears as subject, should be NODE");
        
        // ?name, ?age, ?friend only appear as objects, so they're ambiguous
        assertTrue(result.isAmbiguousVariable("name"),
            "?name only appears as object, should be AMBIGUOUS");
        assertTrue(result.isAmbiguousVariable("age"),
            "?age only appears as object, should be AMBIGUOUS");
        assertTrue(result.isAmbiguousVariable("friend"),
            "?friend only appears as object, should be AMBIGUOUS");

        // Verify counts
        assertEquals(1, result.getNodeVariables().size(),
            "Should have 1 node variable");
        assertEquals(3, result.getAmbiguousVariables().size(),
            "Should have 3 ambiguous variables");
    }

    @Test
    @DisplayName("Variable predicate is classified as PREDICATE")
    public void testPredicateVariableClassification() {
        // ?person ?p ?value
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        VariableAnalyzer.AnalysisResult result = VariableAnalyzer.analyze(bgp);

        assertTrue(result.isPredicateVariable("p"),
            "?p appears as predicate, should be PREDICATE");
        assertTrue(result.isNodeVariable("person"),
            "?person appears as subject, should be NODE");
        assertTrue(result.isAmbiguousVariable("value"),
            "?value only appears as object, should be AMBIGUOUS");
    }

    @Test
    @DisplayName("Closed-chain pattern: mutual references both classified as NODE")
    public void testClosedChainPatternBothNodes() {
        // ?a foaf:knows ?b . ?b foaf:knows ?a
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

        VariableAnalyzer.AnalysisResult result = VariableAnalyzer.analyze(bgp);

        // Both appear as subjects, so both are nodes
        assertTrue(result.isNodeVariable("a"),
            "?a appears as subject, should be NODE");
        assertTrue(result.isNodeVariable("b"),
            "?b appears as subject, should be NODE");
        
        // Neither should be ambiguous
        assertFalse(result.isAmbiguousVariable("a"),
            "?a should not be ambiguous");
        assertFalse(result.isAmbiguousVariable("b"),
            "?b should not be ambiguous");

        assertEquals(2, result.getNodeVariables().size(),
            "Should have 2 node variables");
        assertEquals(0, result.getAmbiguousVariables().size(),
            "Should have 0 ambiguous variables");
    }

    @Test
    @DisplayName("Complex pattern with rdf:type")
    public void testPatternWithRdfType() {
        // ?person rdf:type foaf:Person . ?person foaf:name ?name . ?person foaf:email ?email
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI(RDF.type.getURI()),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/email"),
            NodeFactory.createVariable("email")
        ));

        VariableAnalyzer.AnalysisResult result = VariableAnalyzer.analyze(bgp);

        assertTrue(result.isNodeVariable("person"),
            "?person appears as subject, should be NODE");
        assertTrue(result.isAmbiguousVariable("name"),
            "?name only appears as object, should be AMBIGUOUS");
        assertTrue(result.isAmbiguousVariable("email"),
            "?email only appears as object, should be AMBIGUOUS");
    }

    @Test
    @DisplayName("CanPushdown returns true for valid BGP")
    public void testCanPushdownValidBGP() {
        // ?person foaf:name ?name
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        assertTrue(VariableAnalyzer.canPushdown(bgp),
            "Should allow pushdown for valid BGP");
    }

    @Test
    @DisplayName("CanPushdown returns false for empty BGP")
    public void testCanPushdownEmptyBGP() {
        BasicPattern bgp = new BasicPattern();
        
        assertFalse(VariableAnalyzer.canPushdown(bgp),
            "Should not allow pushdown for empty BGP");
    }

    @Test
    @DisplayName("CanPushdown returns false for null BGP")
    public void testCanPushdownNullBGP() {
        assertFalse(VariableAnalyzer.canPushdown(null),
            "Should not allow pushdown for null BGP");
    }

    @Test
    @DisplayName("CanPushdown returns true for multi-triple pattern with ambiguous variables")
    public void testCanPushdownMultiTripleWithAmbiguous() {
        // ?person foaf:name ?name . ?person foaf:age ?age
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        assertTrue(VariableAnalyzer.canPushdown(bgp),
            "Should allow pushdown even with ambiguous variables");
    }

    @Test
    @DisplayName("getAllVariables returns all variables from pattern")
    public void testGetAllVariables() {
        // ?person foaf:name ?name . ?person ?p ?value
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        VariableAnalyzer.AnalysisResult result = VariableAnalyzer.analyze(bgp);

        assertEquals(4, result.getAllVariables().size(),
            "Should have 4 unique variables");
        assertTrue(result.getAllVariables().contains("person"),
            "Should contain ?person");
        assertTrue(result.getAllVariables().contains("name"),
            "Should contain ?name");
        assertTrue(result.getAllVariables().contains("p"),
            "Should contain ?p");
        assertTrue(result.getAllVariables().contains("value"),
            "Should contain ?value");
    }
}
