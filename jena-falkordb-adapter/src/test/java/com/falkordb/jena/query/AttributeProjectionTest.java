package com.falkordb.jena.query;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that Cypher queries implement attribute projection optimization.
 * 
 * <p>Attribute projection ensures that only required attributes are returned
 * in Cypher RETURN clauses, minimizing data transfer and improving performance.</p>
 */
public class AttributeProjectionTest {

    @Test
    @DisplayName("Node queries return only URI, not all properties")
    public void testNodeOnlyReturnsURI() throws Exception {
        // ?person a foaf:Person .
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);

        String cypher = result.cypherQuery();
        
        // Should return only person.uri, not entire person node
        assertTrue(cypher.contains("person.uri AS person"),
            "Should project only URI attribute, not all node properties. Query: " + cypher);
        
        // Should NOT return entire node
        assertFalse(cypher.matches(".*RETURN\\s+person\\s*$"),
            "Should not return entire node object. Query: " + cypher);
        assertFalse(cypher.matches(".*RETURN\\s+person\\s*,"),
            "Should not return entire node object. Query: " + cypher);
    }

    @Test
    @DisplayName("Property queries return only specific property values")
    public void testPropertyOnlyReturnsRequestedValue() throws Exception {
        // ?person foaf:name ?name .
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);

        String cypher = result.cypherQuery();
        
        // Should return only person.uri and person.name
        assertTrue(cypher.contains("person.uri AS person"),
            "Should project person URI. Query: " + cypher);
        assertTrue(cypher.contains("person.`http://xmlns.com/foaf/0.1/name` AS name") ||
                   cypher.contains("person[propKey] AS name"),
            "Should project only the name property. Query: " + cypher);
        
        // Should NOT return entire person node
        assertFalse(cypher.matches(".*RETURN\\s+person\\s*,\\s*person\\..*"),
            "Should not return both entire node and properties. Query: " + cypher);
    }

    @Test
    @DisplayName("Multi-property queries return only requested properties")
    public void testMultiPropertyOnlyReturnsRequested() throws Exception {
        // ?person foaf:name ?name .
        // ?person foaf:age ?age .
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

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);

        String cypher = result.cypherQuery();
        
        // Should return only URI, name, and age
        assertTrue(cypher.contains("person.uri AS person"),
            "Should project person URI. Query: " + cypher);
        assertTrue(cypher.contains("http://xmlns.com/foaf/0.1/name"),
            "Should reference name property. Query: " + cypher);
        assertTrue(cypher.contains("http://xmlns.com/foaf/0.1/age"),
            "Should reference age property. Query: " + cypher);
        
        // Should NOT return entire person node
        assertFalse(cypher.matches(".*RETURN\\s+person[^.].*"),
            "Should not return entire person node. Query: " + cypher);
    }

    @Test
    @DisplayName("Relationship queries return only endpoint URIs")
    public void testRelationshipOnlyReturnsEndpointURIs() throws Exception {
        // ?person foaf:knows ?friend .
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);

        String cypher = result.cypherQuery();
        
        // Should return only person.uri and friend.uri  
        assertTrue(cypher.contains("person.uri AS person") ||
                   cypher.contains("person") && cypher.contains(".uri"),
            "Should project person URI. Query: " + cypher);
        assertTrue(cypher.contains("friend.uri AS friend") ||
                   cypher.contains("friend") && cypher.contains(".uri"),
            "Should project friend URI. Query: " + cypher);
        
        // Should NOT return entire nodes
        assertFalse(cypher.matches(".*RETURN\\s+person\\s*,\\s*friend\\s*$"),
            "Should not return entire nodes. Query: " + cypher);
    }

    @Test
    @DisplayName("Concrete literal pattern returns only specific property")
    public void testConcreteLiteralOnlyReturnsProperty() throws Exception {
        // ?person foaf:name "Alice" .
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createLiteral("Alice")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);

        String cypher = result.cypherQuery();
        
        // Should return only person.uri (no need to return the literal since it's constant)
        assertTrue(cypher.contains("person.uri AS person") ||
                   (cypher.contains("person") && cypher.contains(".uri")),
            "Should project person URI. Query: " + cypher);
        assertTrue(cypher.contains("http://xmlns.com/foaf/0.1/name"),
            "Should filter on name property. Query: " + cypher);
        
        // Should NOT return entire person node
        assertFalse(cypher.matches(".*RETURN\\s+person[^.].*AS person"),
            "Should not return entire node. Query: " + cypher);
    }

    @Test
    @DisplayName("Variable predicate query returns only URI and individual properties")
    public void testVariablePredicateDoesNotReturnEntireNode() throws Exception {
        // <alice> ?p ?o .
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/person/alice"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("o")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);

        String cypher = result.cypherQuery();
        
        // Should return only s.uri and individual property values
        assertTrue(cypher.contains(".uri AS"),
            "Should project URI. Query: " + cypher);
        
        // Variable predicates use UNWIND keys, but should still project individual values
        if (cypher.contains("UNWIND keys")) {
            assertTrue(cypher.contains("[_propKey]") || cypher.contains("[propKey]"),
                "Should project individual property values using key access. Query: " + cypher);
        }
        
        // Should NOT return entire node
        assertFalse(cypher.matches(".*RETURN\\s+[a-z]+\\s*,\\s*_propKey"),
            "Should not return entire node. Query: " + cypher);
    }

    @Test
    @DisplayName("Relationship and property pattern returns only specific attributes")
    public void testMixedPatternOnlyReturnsRequired() throws Exception {
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

        String cypher = result.cypherQuery();
        
        // Should return only person.uri, friend.uri, and friend.name
        assertTrue(cypher.contains("person"),
            "Should include person in results. Query: " + cypher);
        assertTrue(cypher.contains("friend"),
            "Should include friend in results. Query: " + cypher);
        assertTrue(cypher.contains("name"),
            "Should include name in results. Query: " + cypher);
        
        // Should use attribute projection (. notation) for URIs
        assertTrue(cypher.contains(".uri"),
            "Should project URI attributes. Query: " + cypher);
        
        // Should NOT return entire nodes
        assertFalse(cypher.matches(".*RETURN\\s+person\\s*,\\s*friend[^.]"),
            "Should not return entire nodes. Query: " + cypher);
    }

    @Test
    @DisplayName("OPTIONAL pattern returns only required attributes")
    public void testOptionalPatternOnlyReturnsRequired() throws Exception {
        // Create pattern for testing OPTIONAL
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/email"),
            NodeFactory.createVariable("email")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translateWithOptional(required, optional);

        String cypher = result.cypherQuery();
        
        // Should return only person.uri and email.uri (or email property value)
        assertTrue(cypher.contains("person.uri AS person") ||
                   (cypher.contains("person") && cypher.contains(".uri")),
            "Should project person URI. Query: " + cypher);
        assertTrue(cypher.contains("email"),
            "Should include email in results. Query: " + cypher);
        
        // Should NOT return entire nodes
        assertFalse(cypher.matches(".*RETURN\\s+person\\s*,\\s*email\\s*$"),
            "Should not return entire nodes. Query: " + cypher);
    }

    @Test
    @DisplayName("UNION pattern returns only required attributes in both branches")
    public void testUnionPatternOnlyReturnsRequired() throws Exception {
        // Left branch: ?person a foaf:Student .
        BasicPattern left = new BasicPattern();
        left.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Student")
        ));

        // Right branch: ?person a foaf:Teacher .
        BasicPattern right = new BasicPattern();
        right.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Teacher")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translateUnion(left, right);

        String cypher = result.cypherQuery();
        
        // Both UNION branches should return only person.uri
        assertTrue(cypher.contains("person.uri AS person"),
            "Should project person URI in UNION branches. Query: " + cypher);
        
        // Count occurrences of projection in UNION query
        int projectionCount = cypher.split("person\\.uri AS person").length - 1;
        assertEquals(2, projectionCount,
            "Both UNION branches should project person.uri. Query: " + cypher);
        
        // Should NOT return entire nodes
        assertFalse(cypher.matches(".*RETURN\\s+person\\s*$"),
            "Should not return entire node. Query: " + cypher);
    }

    @Test
    @DisplayName("Verify parameters are used for filters, not for projections")
    public void testParametersNotUsedForProjections() throws Exception {
        // ?person foaf:name "Alice" .
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createLiteral("Alice")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);

        String cypher = result.cypherQuery();
        Map<String, Object> parameters = result.parameters();
        
        // Parameters should be for the literal value "Alice", not for projections
        assertTrue(parameters.containsValue("Alice"),
            "Should have parameter for literal value. Parameters: " + parameters);
        
        // RETURN clause should not use parameters for projection
        String returnClause = cypher.substring(cypher.lastIndexOf("RETURN"));
        assertFalse(returnClause.contains("$"),
            "RETURN clause should not use parameters for projections. Return clause: " + returnClause);
        
        // RETURN clause should project person.uri directly
        assertTrue(returnClause.contains("person.uri AS person") ||
                   returnClause.contains("person") && returnClause.contains(".uri"),
            "Should project person.uri. Return clause: " + returnClause);
    }

    @Test
    @DisplayName("Complex query with multiple patterns returns only required attributes")
    public void testComplexQueryOnlyReturnsRequired() throws Exception {
        // ?person a foaf:Person .
        // ?person foaf:name ?name .
        // ?person foaf:knows ?friend .
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));

        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);

        String cypher = result.cypherQuery();
        
        // Should return only person.uri, person.name, and friend.uri
        assertTrue(cypher.contains("person"),
            "Should include person. Query: " + cypher);
        assertTrue(cypher.contains("name"),
            "Should include name. Query: " + cypher);
        assertTrue(cypher.contains("friend"),
            "Should include friend. Query: " + cypher);
        
        // Should use attribute projection
        assertTrue(cypher.contains(".uri"),
            "Should project URI attributes. Query: " + cypher);
        
        // RETURN clause should not return entire nodes
        String returnClause = cypher.substring(cypher.lastIndexOf("RETURN"));
        assertFalse(returnClause.matches(".*RETURN\\s+person\\s*,"),
            "Should not return entire person node. Return clause: " + returnClause);
        assertFalse(returnClause.matches(".*,\\s*friend\\s*$"),
            "Should not return entire friend node. Return clause: " + returnClause);
    }
}
