package com.falkordb.jena.query;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.expr.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UNWIND keys() optimization with FILTER constraints on variable predicates.
 * 
 * <p>These tests verify that when a FILTER constrains the possible predicates
 * in a variable predicate query, the generated Cypher uses an explicit list
 * of predicates instead of UNWIND keys() to fetch all properties.</p>
 */
public class UnwindKeysOptimizationTest {

    @Test
    @DisplayName("Variable predicate with FILTER IN uses explicit predicate list")
    public void testVariablePredicateWithFilterIn() throws Exception {
        // ?person ?p ?value .
        // FILTER(?p IN (foaf:name, foaf:age))
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        // Create FILTER: ?p IN (foaf:name, foaf:age)
        Expr varExpr = new ExprVar("p");
        ExprList values = new ExprList();
        values.add(NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/name")));
        values.add(NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/age")));
        Expr filterExpr = new E_OneOf(varExpr, values);

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        String cypher = result.cypherQuery();
        Map<String, Object> parameters = result.parameters();
        
        // Should use UNWIND with parameter list, not UNWIND keys()
        assertFalse(cypher.contains("UNWIND keys("),
            "Should not use UNWIND keys() when predicates are constrained. Query: " + cypher);
        assertTrue(cypher.contains("UNWIND $"),
            "Should use UNWIND with parameter list. Query: " + cypher);
        
        // Should have a parameter for the predicate list
        assertTrue(parameters.values().stream().anyMatch(v -> v instanceof List),
            "Should have a list parameter for constrained predicates. Parameters: " + parameters);
        
        // The list should contain the two URIs
        @SuppressWarnings("unchecked")
        List<String> predicateList = (List<String>) parameters.values().stream()
            .filter(v -> v instanceof List)
            .findFirst()
            .orElse(null);
        assertNotNull(predicateList, "Should have predicate list parameter");
        assertEquals(2, predicateList.size(), "Should have 2 predicates");
        assertTrue(predicateList.contains("http://xmlns.com/foaf/0.1/name"),
            "Should contain foaf:name");
        assertTrue(predicateList.contains("http://xmlns.com/foaf/0.1/age"),
            "Should contain foaf:age");
    }

    @Test
    @DisplayName("Variable predicate with FILTER equality uses explicit predicate list")
    public void testVariablePredicateWithFilterEquality() throws Exception {
        // ?person ?p ?value .
        // FILTER(?p = foaf:name)
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        // Create FILTER: ?p = foaf:name
        Expr varExpr = new ExprVar("p");
        Expr uriExpr = NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"));
        Expr filterExpr = new E_Equals(varExpr, uriExpr);

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        String cypher = result.cypherQuery();
        Map<String, Object> parameters = result.parameters();
        
        // Should use UNWIND with parameter list, not UNWIND keys()
        assertFalse(cypher.contains("UNWIND keys("),
            "Should not use UNWIND keys() when predicates are constrained. Query: " + cypher);
        assertTrue(cypher.contains("UNWIND $"),
            "Should use UNWIND with parameter list. Query: " + cypher);
        
        // Should have a parameter for the predicate list
        assertTrue(parameters.values().stream().anyMatch(v -> v instanceof List),
            "Should have a list parameter for constrained predicates. Parameters: " + parameters);
        
        // The list should contain the one URI
        @SuppressWarnings("unchecked")
        List<String> predicateList = (List<String>) parameters.values().stream()
            .filter(v -> v instanceof List)
            .findFirst()
            .orElse(null);
        assertNotNull(predicateList, "Should have predicate list parameter");
        assertEquals(1, predicateList.size(), "Should have 1 predicate");
        assertTrue(predicateList.contains("http://xmlns.com/foaf/0.1/name"),
            "Should contain foaf:name");
    }

    @Test
    @DisplayName("Variable predicate with FILTER OR uses explicit predicate list")
    public void testVariablePredicateWithFilterOr() throws Exception {
        // ?person ?p ?value .
        // FILTER(?p = foaf:name || ?p = foaf:age)
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        // Create FILTER: ?p = foaf:name || ?p = foaf:age
        Expr varExpr1 = new ExprVar("p");
        Expr uriExpr1 = NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"));
        Expr eq1 = new E_Equals(varExpr1, uriExpr1);
        
        Expr varExpr2 = new ExprVar("p");
        Expr uriExpr2 = NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"));
        Expr eq2 = new E_Equals(varExpr2, uriExpr2);
        
        Expr filterExpr = new E_LogicalOr(eq1, eq2);

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        String cypher = result.cypherQuery();
        Map<String, Object> parameters = result.parameters();
        
        // Should use UNWIND with parameter list, not UNWIND keys()
        assertFalse(cypher.contains("UNWIND keys("),
            "Should not use UNWIND keys() when predicates are constrained. Query: " + cypher);
        assertTrue(cypher.contains("UNWIND $"),
            "Should use UNWIND with parameter list. Query: " + cypher);
        
        // Should have a parameter for the predicate list
        assertTrue(parameters.values().stream().anyMatch(v -> v instanceof List),
            "Should have a list parameter for constrained predicates. Parameters: " + parameters);
        
        // The list should contain both URIs
        @SuppressWarnings("unchecked")
        List<String> predicateList = (List<String>) parameters.values().stream()
            .filter(v -> v instanceof List)
            .findFirst()
            .orElse(null);
        assertNotNull(predicateList, "Should have predicate list parameter");
        assertEquals(2, predicateList.size(), "Should have 2 predicates");
        assertTrue(predicateList.contains("http://xmlns.com/foaf/0.1/name"),
            "Should contain foaf:name");
        assertTrue(predicateList.contains("http://xmlns.com/foaf/0.1/age"),
            "Should contain foaf:age");
    }

    @Test
    @DisplayName("Variable predicate without FILTER uses UNWIND keys()")
    public void testVariablePredicateWithoutFilter() throws Exception {
        // ?person ?p ?value . (no FILTER)
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);

        String cypher = result.cypherQuery();
        
        // Should use UNWIND keys() when unconstrained
        assertTrue(cypher.contains("UNWIND keys("),
            "Should use UNWIND keys() when predicates are unconstrained. Query: " + cypher);
        assertFalse(cypher.contains("UNWIND $"),
            "Should not use UNWIND with parameter when unconstrained. Query: " + cypher);
    }

    @Test
    @DisplayName("Variable predicate with irrelevant FILTER uses UNWIND keys()")
    public void testVariablePredicateWithIrrelevantFilter() throws Exception {
        // ?person ?p ?value .
        // FILTER(?value > 18)  -- filter on value, not predicate
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        // Create FILTER: ?value > 18 (doesn't constrain ?p)
        Expr varExpr = new ExprVar("value");
        Expr constExpr = NodeValue.makeInteger(18);
        Expr filterExpr = new E_GreaterThan(varExpr, constExpr);

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        String cypher = result.cypherQuery();
        
        // Should use UNWIND keys() when filter doesn't constrain predicates
        assertTrue(cypher.contains("UNWIND keys("),
            "Should use UNWIND keys() when filter doesn't constrain predicates. Query: " + cypher);
    }

    @Test
    @DisplayName("Variable predicate with FILTER AND combination")
    public void testVariablePredicateWithFilterAnd() throws Exception {
        // ?person ?p ?value .
        // FILTER(?p = foaf:name && ?value = "Alice")
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        // Create FILTER: ?p = foaf:name && ?value = "Alice"
        Expr varExprP = new ExprVar("p");
        Expr uriExpr = NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"));
        Expr eqP = new E_Equals(varExprP, uriExpr);
        
        Expr varExprValue = new ExprVar("value");
        Expr literalExpr = NodeValue.makeString("Alice");
        Expr eqValue = new E_Equals(varExprValue, literalExpr);
        
        Expr filterExpr = new E_LogicalAnd(eqP, eqValue);

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        String cypher = result.cypherQuery();
        Map<String, Object> parameters = result.parameters();
        
        // Should use UNWIND with parameter list for the constrained predicate
        assertFalse(cypher.contains("UNWIND keys("),
            "Should not use UNWIND keys() when predicates are constrained. Query: " + cypher);
        assertTrue(cypher.contains("UNWIND $"),
            "Should use UNWIND with parameter list. Query: " + cypher);
        
        // Should have a parameter for the predicate list
        assertTrue(parameters.values().stream().anyMatch(v -> v instanceof List),
            "Should have a list parameter for constrained predicates. Parameters: " + parameters);
    }

    @Test
    @DisplayName("Optimization creates WHERE IS NOT NULL check")
    public void testOptimizationCreatesWhereCheck() throws Exception {
        // ?person ?p ?value .
        // FILTER(?p IN (foaf:name, foaf:age))
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        Expr varExpr = new ExprVar("p");
        ExprList values = new ExprList();
        values.add(NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/name")));
        values.add(NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/age")));
        Expr filterExpr = new E_OneOf(varExpr, values);

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        String cypher = result.cypherQuery();
        
        // When using explicit predicate list, should check property exists
        assertTrue(cypher.contains("IS NOT NULL"),
            "Should check that property exists when using explicit predicate list. Query: " + cypher);
    }

    @Test
    @DisplayName("Multiple properties in IN expression")
    public void testMultiplePropertiesInExpression() throws Exception {
        // ?person ?p ?value .
        // FILTER(?p IN (foaf:name, foaf:age, foaf:email, foaf:phone))
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("value")
        ));

        Expr varExpr = new ExprVar("p");
        ExprList values = new ExprList();
        values.add(NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/name")));
        values.add(NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/age")));
        values.add(NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/email")));
        values.add(NodeValue.makeNode(NodeFactory.createURI("http://xmlns.com/foaf/0.1/phone")));
        Expr filterExpr = new E_OneOf(varExpr, values);

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        String cypher = result.cypherQuery();
        Map<String, Object> parameters = result.parameters();
        
        // Should use optimization with 4 predicates
        assertFalse(cypher.contains("UNWIND keys("),
            "Should not use UNWIND keys(). Query: " + cypher);
        
        @SuppressWarnings("unchecked")
        List<String> predicateList = (List<String>) parameters.values().stream()
            .filter(v -> v instanceof List)
            .findFirst()
            .orElse(null);
        assertNotNull(predicateList);
        assertEquals(4, predicateList.size(), "Should have 4 predicates");
    }
}
