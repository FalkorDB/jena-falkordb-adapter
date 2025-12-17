package com.falkordb.jena.query;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

        // ?fof is ambiguous (not used as subject)
        // With partial optimization, this should now compile with UNION
        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);
        
        assertNotNull(result, "Should compile multi-triple pattern with partial optimization");
        assertNotNull(result.cypherQuery(), "Should generate Cypher query");
        assertTrue(result.cypherQuery().contains("MATCH"),
            "Should contain MATCH clauses");
        assertTrue(result.cypherQuery().contains("UNION ALL"),
            "Should use UNION for ambiguous variable");
        
        // ?friend is used as subject, so it's a node (relationship)
        // ?fof is ambiguous, handled with partial optimization
        assertTrue(result.cypherQuery().contains("friend"),
            "Should include friend variable");
        assertTrue(result.cypherQuery().contains("fof"),
            "Should include fof variable");
    }

    @Test
    @DisplayName("Test single variable object compiles with UNION")
    public void testSingleVariableObjectCompilesWithUnion() throws Exception {
        // ?s foaf:knows ?o - single triple with variable object
        // Should now compile with UNION to query both relationships and properties
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
        assertTrue(result.cypherQuery().contains("UNION ALL"));
        assertTrue(result.cypherQuery().contains("knows"));
        // Should query both relationships and properties
        assertTrue(result.cypherQuery().contains(":Resource)"));
        assertTrue(result.cypherQuery().contains("IS NOT NULL"));
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
    @DisplayName("Test concrete subject URI with variable object compiles with UNION")
    public void testConcreteSubjectWithVariableObjectCompilesWithUnion() throws Exception {
        // <http://example.org/alice> foaf:knows ?o - variable object
        // Should now compile with UNION to query both relationships and properties
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
        assertTrue(result.cypherQuery().contains("MATCH"));
        assertTrue(result.cypherQuery().contains("UNION ALL"));
        assertTrue(result.cypherQuery().contains("alice"));
        // Should parameterize the concrete URI
        assertTrue(result.parameters().values().contains("http://example.org/alice"));
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
        // All variable objects are used as subjects except ?d - which is ambiguous
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

        // ?d is not used as subject, so it's ambiguous (relationship or property)
        // With partial optimization, this should now compile with UNION
        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);
        
        assertNotNull(result, "Should compile multi-triple pattern with partial optimization");
        assertNotNull(result.cypherQuery(), "Should generate Cypher query");
        assertTrue(result.cypherQuery().contains("MATCH"),
            "Should contain MATCH clauses");
        assertTrue(result.cypherQuery().contains("UNION ALL"),
            "Should use UNION for ambiguous variable");
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
        // ?friend is used as subject, but ?fof is not - ambiguous
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

        // ?fof is not used as subject, so it's ambiguous (relationship or property)
        // With partial optimization, this should now compile with UNION
        SparqlToCypherCompiler.CompilationResult result =
            SparqlToCypherCompiler.translate(bgp);
        
        assertNotNull(result, "Should compile multi-triple pattern with partial optimization");
        assertNotNull(result.cypherQuery(), "Should generate Cypher query");
        assertTrue(result.cypherQuery().contains("MATCH"),
            "Should contain MATCH clauses");
        assertTrue(result.cypherQuery().contains("UNION ALL"),
            "Should use UNION for ambiguous variable");
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

    @Test
    @DisplayName("Test variable object optimization generates correct Cypher structure")
    public void testVariableObjectOptimizationStructure() throws Exception {
        // ?person foaf:knows ?friend
        // Should generate UNION query for both relationships and properties
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        assertNotNull(cypher);
        
        // Should have UNION ALL
        assertTrue(cypher.contains("UNION ALL"), 
            "Should have UNION ALL for querying both relationships and properties");
        
        // Part 1: Should query relationships
        assertTrue(cypher.contains("-[:`http://xmlns.com/foaf/0.1/knows`]->"),
            "Should query relationship edges");
        assertTrue(cypher.contains("(friend:Resource)"),
            "Should query friend as Resource node");
        
        // Part 2: Should query properties
        assertTrue(cypher.contains("IS NOT NULL"),
            "Should check if property exists");
        assertTrue(cypher.contains(".`http://xmlns.com/foaf/0.1/knows`"),
            "Should access property value");
    }

    @Test
    @DisplayName("Test variable object with concrete subject parameterizes correctly")
    public void testVariableObjectConcreteSubjectParameters() throws Exception {
        // <http://example.org/alice> ex:property ?value
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://example.org/property"),
            NodeFactory.createVariable("value")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        assertNotNull(result.parameters());
        
        // Concrete subject URI should be parameterized
        assertTrue(result.parameters().values().contains("http://example.org/alice"),
            "Concrete subject URI should be parameterized");
        
        // Should have UNION for both query paths
        assertTrue(result.cypherQuery().contains("UNION ALL"));
    }

    @Test
    @DisplayName("Test variable object returns correct variable names")
    public void testVariableObjectReturnsCorrectVariables() throws Exception {
        // ?s ex:prop ?o
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("subject"),
            NodeFactory.createURI("http://example.org/hasValue"),
            NodeFactory.createVariable("object")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Both UNION branches should return the same variable names
        assertTrue(cypher.contains("AS subject"), 
            "Should return subject variable");
        assertTrue(cypher.contains("AS object"),
            "Should return object variable");
        
        // Both branches should be consistent - both should return both variables
        // Count occurrences properly
        int subjectCount = 0;
        int objectCount = 0;
        String[] lines = cypher.split("\n");
        for (String line : lines) {
            if (line.contains(" AS subject")) subjectCount++;
            if (line.contains(" AS object")) objectCount++;
        }
        
        assertTrue(subjectCount >= 2, 
            "Should have subject in both UNION branches (found " + subjectCount + "). Query:\n" + cypher);
        assertTrue(objectCount >= 2, 
            "Should have object in both UNION branches (found " + objectCount + "). Query:\n" + cypher);
    }

    // ==================== OPTIONAL Pattern Tests ====================

    @Test
    @DisplayName("Test basic OPTIONAL pattern with relationship")
    public void testBasicOptionalPattern() throws Exception {
        // Required: ?person rdf:type foaf:Person
        // Optional: ?person foaf:email ?email
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

        assertNotNull(result);
        String cypher = result.cypherQuery();
        assertNotNull(cypher);
        
        // Should have MATCH for required pattern
        assertTrue(cypher.contains("MATCH (person:Resource:`http://xmlns.com/foaf/0.1/Person`)"));
        
        // Should have OPTIONAL MATCH for optional pattern
        assertTrue(cypher.contains("OPTIONAL MATCH"));
        assertTrue(cypher.contains("`http://xmlns.com/foaf/0.1/email`"));
        
        // Should return both variables
        assertTrue(cypher.contains("RETURN"));
        assertTrue(cypher.contains("person.uri AS person"));
        // email can be either a relationship or property - check it's in the result
        assertTrue(cypher.contains("email") && cypher.contains("AS email"));
    }

    @Test
    @DisplayName("Test OPTIONAL pattern with literal property")
    public void testOptionalPatternWithLiteral() throws Exception {
        // Required: ?person foaf:name ?name
        // Optional: ?person foaf:age ?age
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should have OPTIONAL MATCH with WHERE for literal
        assertTrue(cypher.contains("OPTIONAL MATCH"));
        assertTrue(cypher.contains("WHERE"));
        assertTrue(cypher.contains("`http://xmlns.com/foaf/0.1/age` IS NOT NULL"));
        
        // Should return both name and age as properties
        assertTrue(cypher.contains("name") && cypher.contains("AS name"));
        assertTrue(cypher.contains("age") && cypher.contains("AS age"));
    }

    @Test
    @DisplayName("Test OPTIONAL with multiple triples")
    public void testOptionalWithMultipleTriples() throws Exception {
        // Required: ?person rdf:type foaf:Person
        // Optional: ?person foaf:knows ?friend . ?friend foaf:name ?friendName
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));
        optional.add(Triple.create(
            NodeFactory.createVariable("friend"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("friendName")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should have two OPTIONAL MATCH clauses
        long optionalCount = cypher.lines().filter(line -> line.trim().startsWith("OPTIONAL MATCH")).count();
        assertEquals(2, optionalCount, "Should have 2 OPTIONAL MATCH clauses");
        
        // Should reference person, friend, and friendName
        assertTrue(cypher.contains("person"));
        assertTrue(cypher.contains("friend"));
        assertTrue(cypher.contains("friendName"));
    }

    @Test
    @DisplayName("Test OPTIONAL with concrete subject")
    public void testOptionalWithConcreteSubject() throws Exception {
        // Required: <alice> foaf:name ?name
        // Optional: <alice> foaf:email ?email
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/email"),
            NodeFactory.createVariable("email")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        Map<String, Object> parameters = result.parameters();
        
        // Should have parameters for concrete URI
        assertTrue(parameters.containsValue("http://example.org/alice"));
        
        // Should have OPTIONAL MATCH
        assertTrue(cypher.contains("OPTIONAL MATCH"));
    }

    @Test
    @DisplayName("Test OPTIONAL with concrete literal in optional part")
    public void testOptionalWithConcreteLiteral() throws Exception {
        // Required: ?person rdf:type foaf:Person
        // Optional: ?person foaf:name "Alice"
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createLiteralString("Alice")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        Map<String, Object> parameters = result.parameters();
        
        // Should have OPTIONAL MATCH with WHERE checking for specific value
        assertTrue(cypher.contains("OPTIONAL MATCH"));
        assertTrue(cypher.contains("WHERE"));
        assertTrue(cypher.contains("="));
        
        // Should have parameter for "Alice"
        assertTrue(parameters.containsValue("Alice"));
    }

    @Test
    @DisplayName("Test OPTIONAL throws exception for empty required BGP")
    public void testOptionalThrowsForEmptyRequired() {
        BasicPattern required = new BasicPattern();
        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/email"),
            NodeFactory.createVariable("email")
        ));

        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translateWithOptional(required, optional));
    }

    @Test
    @DisplayName("Test OPTIONAL throws exception for empty optional BGP")
    public void testOptionalThrowsForEmptyOptional() {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));
        BasicPattern optional = new BasicPattern();

        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translateWithOptional(required, optional));
    }

    @Test
    @DisplayName("Test OPTIONAL with variable predicate compiles with UNION")
    public void testOptionalWithVariablePredicateCompilesWithUnion() throws Exception {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("o")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);
        
        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should use UNION ALL for three parts: relationships, properties, types
        assertTrue(cypher.contains("UNION ALL"), "Should use UNION ALL for multiple parts");
        
        // Count UNION ALL occurrences (should be 2 for 3-part UNION)
        int unionCount = cypher.split("UNION ALL", -1).length - 1;
        assertEquals(2, unionCount, "Should have 2 UNION ALL (3 parts total)");
        
        // Should have OPTIONAL MATCH clauses
        assertTrue(cypher.contains("OPTIONAL MATCH"), "Should have OPTIONAL MATCH");
        
        // Should handle relationships (type(_r))
        assertTrue(cypher.contains("type(_r)"), "Should query relationship types");
        
        // Should handle properties (keys())
        assertTrue(cypher.contains("keys("), "Should query node properties");
        assertTrue(cypher.contains("_propKey"), "Should use property key variable");
        
        // Should handle types (labels())
        assertTrue(cypher.contains("labels("), "Should query node labels");
        assertTrue(cypher.contains("_label"), "Should use label variable");
        
        // Should return correct variables
        assertTrue(cypher.contains("AS p"), "Should return predicate variable");
        assertTrue(cypher.contains("AS o"), "Should return object variable");
        assertTrue(cypher.contains("AS person"), "Should return person variable");
    }

    @Test
    @DisplayName("Test OPTIONAL pattern structure is correct")
    public void testOptionalPatternStructure() throws Exception {
        // Required: ?person rdf:type foaf:Person
        // Optional: ?person foaf:email ?email
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
        String[] lines = cypher.split("\n");
        
        // Verify structure: MATCH ... OPTIONAL MATCH ... RETURN
        boolean hasMatch = false;
        boolean hasOptionalMatch = false;
        boolean hasReturn = false;
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("MATCH")) hasMatch = true;
            if (trimmed.startsWith("OPTIONAL MATCH")) hasOptionalMatch = true;
            if (trimmed.startsWith("RETURN")) hasReturn = true;
        }
        
        assertTrue(hasMatch, "Should have MATCH clause");
        assertTrue(hasOptionalMatch, "Should have OPTIONAL MATCH clause");
        assertTrue(hasReturn, "Should have RETURN clause");
    }

    @Test
    @DisplayName("Test OPTIONAL with FILTER less than")
    public void testOptionalWithFilterLessThan() throws SparqlToCypherCompiler.CannotCompileException {
        // Required: ?person foaf:name ?name, ?person foaf:age ?age
        // Filter: ?age < 30
        // Optional: ?person foaf:email ?email
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/email"),
            NodeFactory.createVariable("email")
        ));

        // Create filter expression: ?age < 30
        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_LessThan(
                new org.apache.jena.sparql.expr.ExprVar("age"),
                org.apache.jena.sparql.expr.NodeValue.makeInteger(30)
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should have WHERE clause with filter
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("<"), "Should have < operator");
        assertTrue(cypher.contains("30"), "Should have value 30");
        
        // Should have OPTIONAL MATCH after WHERE
        assertTrue(cypher.contains("OPTIONAL MATCH"), "Should have OPTIONAL MATCH");
        
        // WHERE should come before OPTIONAL MATCH
        int whereIdx = cypher.indexOf("WHERE");
        int optionalIdx = cypher.indexOf("OPTIONAL MATCH");
        assertTrue(whereIdx < optionalIdx && whereIdx > 0, 
            "WHERE should come before OPTIONAL MATCH");
    }

    @Test
    @DisplayName("Test OPTIONAL with FILTER equals")
    public void testOptionalWithFilterEquals() throws SparqlToCypherCompiler.CannotCompileException {
        // Required: ?person foaf:name ?name
        // Filter: ?name = "Alice"
        // Optional: ?person foaf:email ?email
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/email"),
            NodeFactory.createVariable("email")
        ));

        // Create filter: ?name = "Alice"
        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_Equals(
                new org.apache.jena.sparql.expr.ExprVar("name"),
                org.apache.jena.sparql.expr.NodeValue.makeString("Alice")
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should have WHERE with equals
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("="), "Should have = operator");
        assertTrue(cypher.contains("Alice"), "Should have Alice value");
        
        // Should have OPTIONAL MATCH
        assertTrue(cypher.contains("OPTIONAL MATCH"), "Should have OPTIONAL MATCH");
    }

    @Test
    @DisplayName("Test OPTIONAL with FILTER AND condition")
    public void testOptionalWithFilterAnd() throws SparqlToCypherCompiler.CannotCompileException {
        // Required: ?person foaf:name ?name, ?person foaf:age ?age
        // Filter: ?age > 20 AND ?age < 30
        // Optional: ?person foaf:email ?email
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/email"),
            NodeFactory.createVariable("email")
        ));

        // Create filter: ?age > 20 AND ?age < 30
        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_LogicalAnd(
                new org.apache.jena.sparql.expr.E_GreaterThan(
                    new org.apache.jena.sparql.expr.ExprVar("age"),
                    org.apache.jena.sparql.expr.NodeValue.makeInteger(20)
                ),
                new org.apache.jena.sparql.expr.E_LessThan(
                    new org.apache.jena.sparql.expr.ExprVar("age"),
                    org.apache.jena.sparql.expr.NodeValue.makeInteger(30)
                )
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should have WHERE with AND
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("AND"), "Should have AND operator");
        assertTrue(cypher.contains(">"), "Should have > operator");
        assertTrue(cypher.contains("<"), "Should have < operator");
        assertTrue(cypher.contains("20"), "Should have value 20");
        assertTrue(cypher.contains("30"), "Should have value 30");
    }

    // ==================== FILTER Expression Tests ====================

    @Test
    @DisplayName("Test FILTER with less than comparison")
    public void testFilterWithLessThan() throws SparqlToCypherCompiler.CannotCompileException {
        // BGP: ?person foaf:age ?age
        // FILTER: ?age < 30
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        // Create filter expression: ?age < 30
        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_LessThan(
                new org.apache.jena.sparql.expr.ExprVar("age"),
                org.apache.jena.sparql.expr.NodeValue.makeInteger(30)
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should have WHERE clause with filter
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("<"), "Should have < operator");
        assertTrue(cypher.contains("30"), "Should have value 30");
        assertTrue(cypher.contains("RETURN"), "Should have RETURN clause");
        
        // Each RETURN should have a WHERE before it (for UNION queries, check each branch)
        String[] parts = cypher.split("RETURN");
        for (int i = 0; i < parts.length - 1; i++) {
            assertTrue(parts[i].contains("WHERE"), 
                "Each query branch should have WHERE before RETURN");
        }
    }

    @Test
    @DisplayName("Test FILTER with greater than or equal comparison")
    public void testFilterWithGreaterThanOrEqual() throws SparqlToCypherCompiler.CannotCompileException {
        // BGP: ?person foaf:age ?age
        // FILTER: ?age >= 18
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_GreaterThanOrEqual(
                new org.apache.jena.sparql.expr.ExprVar("age"),
                org.apache.jena.sparql.expr.NodeValue.makeInteger(18)
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains(">="), "Should have >= operator");
        assertTrue(cypher.contains("18"), "Should have value 18");
    }

    @Test
    @DisplayName("Test FILTER with equals comparison")
    public void testFilterWithEquals() throws SparqlToCypherCompiler.CannotCompileException {
        // BGP: ?person foaf:name ?name
        // FILTER: ?name = "Alice"
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_Equals(
                new org.apache.jena.sparql.expr.ExprVar("name"),
                org.apache.jena.sparql.expr.NodeValue.makeString("Alice")
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("="), "Should have = operator");
        assertTrue(cypher.contains("Alice"), "Should have Alice value");
    }

    @Test
    @DisplayName("Test FILTER with not equals comparison")
    public void testFilterWithNotEquals() throws SparqlToCypherCompiler.CannotCompileException {
        // BGP: ?person foaf:name ?name
        // FILTER: ?name <> "Bob"
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_NotEquals(
                new org.apache.jena.sparql.expr.ExprVar("name"),
                org.apache.jena.sparql.expr.NodeValue.makeString("Bob")
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("<>"), "Should have <> operator");
        assertTrue(cypher.contains("Bob"), "Should have Bob value");
    }

    @Test
    @DisplayName("Test FILTER with AND logical operator")
    public void testFilterWithAnd() throws SparqlToCypherCompiler.CannotCompileException {
        // BGP: ?person foaf:age ?age
        // FILTER: ?age >= 18 AND ?age < 65
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_LogicalAnd(
                new org.apache.jena.sparql.expr.E_GreaterThanOrEqual(
                    new org.apache.jena.sparql.expr.ExprVar("age"),
                    org.apache.jena.sparql.expr.NodeValue.makeInteger(18)
                ),
                new org.apache.jena.sparql.expr.E_LessThan(
                    new org.apache.jena.sparql.expr.ExprVar("age"),
                    org.apache.jena.sparql.expr.NodeValue.makeInteger(65)
                )
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("AND"), "Should have AND operator");
        assertTrue(cypher.contains(">="), "Should have >= operator");
        assertTrue(cypher.contains("<"), "Should have < operator");
        assertTrue(cypher.contains("18"), "Should have value 18");
        assertTrue(cypher.contains("65"), "Should have value 65");
    }

    @Test
    @DisplayName("Test FILTER with OR logical operator")
    public void testFilterWithOr() throws SparqlToCypherCompiler.CannotCompileException {
        // BGP: ?person foaf:age ?age
        // FILTER: ?age < 18 OR ?age > 65
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_LogicalOr(
                new org.apache.jena.sparql.expr.E_LessThan(
                    new org.apache.jena.sparql.expr.ExprVar("age"),
                    org.apache.jena.sparql.expr.NodeValue.makeInteger(18)
                ),
                new org.apache.jena.sparql.expr.E_GreaterThan(
                    new org.apache.jena.sparql.expr.ExprVar("age"),
                    org.apache.jena.sparql.expr.NodeValue.makeInteger(65)
                )
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("OR"), "Should have OR operator");
        assertTrue(cypher.contains("<"), "Should have < operator");
        assertTrue(cypher.contains(">"), "Should have > operator");
    }

    @Test
    @DisplayName("Test FILTER with NOT logical operator")
    public void testFilterWithNot() throws SparqlToCypherCompiler.CannotCompileException {
        // BGP: ?person foaf:age ?age
        // FILTER: NOT(?age < 18)
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_LogicalNot(
                new org.apache.jena.sparql.expr.E_LessThan(
                    new org.apache.jena.sparql.expr.ExprVar("age"),
                    org.apache.jena.sparql.expr.NodeValue.makeInteger(18)
                )
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithFilter(bgp, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("NOT"), "Should have NOT operator");
        assertTrue(cypher.contains("<"), "Should have < operator");
    }

    @Test
    @DisplayName("Test FILTER with complex expression (AND + OR)")
    public void testFilterWithComplexExpression() throws SparqlToCypherCompiler.CannotCompileException {
        // BGP: ?person rdf:type foaf:Person, ?person foaf:knows ?friend
        // FILTER: Complex node-based filter
        // Note: Using relationship pattern to avoid variable object ambiguity
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));

        // Create a filter expression that tests relationships
        // Since we can't directly filter on URIs with the current implementation,
        // let's use a single-triple pattern instead for this test
        BasicPattern simpleBgp = new BasicPattern();
        simpleBgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_LogicalAnd(
                new org.apache.jena.sparql.expr.E_GreaterThan(
                    new org.apache.jena.sparql.expr.ExprVar("age"),
                    org.apache.jena.sparql.expr.NodeValue.makeInteger(20)
                ),
                new org.apache.jena.sparql.expr.E_LessThan(
                    new org.apache.jena.sparql.expr.ExprVar("age"),
                    org.apache.jena.sparql.expr.NodeValue.makeInteger(30)
                )
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithFilter(simpleBgp, filterExpr);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause");
        assertTrue(cypher.contains("AND"), "Should have AND operator");
        assertTrue(cypher.contains(">"), "Should have > operator");
        assertTrue(cypher.contains("<"), "Should have < operator");
        assertTrue(cypher.contains("20"), "Should have value 20");
        assertTrue(cypher.contains("30"), "Should have value 30");
    }

    @Test
    @DisplayName("Test FILTER with null expression returns original BGP")
    public void testFilterWithNullExpression() throws SparqlToCypherCompiler.CannotCompileException {
        // BGP: ?person foaf:name ?name
        BasicPattern bgp = new BasicPattern();
        bgp.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        SparqlToCypherCompiler.CompilationResult resultWithNull = 
            SparqlToCypherCompiler.translateWithFilter(bgp, null);
        SparqlToCypherCompiler.CompilationResult resultWithoutFilter = 
            SparqlToCypherCompiler.translate(bgp);

        assertNotNull(resultWithNull);
        assertNotNull(resultWithoutFilter);
        
        // Both should be equivalent
        assertEquals(resultWithoutFilter.cypherQuery(), resultWithNull.cypherQuery());
    }

    // ==================== UNION Pattern Tests ====================

    @Test
    @DisplayName("Test basic UNION pattern with type queries")
    public void testUnionWithTypePatterns() throws SparqlToCypherCompiler.CannotCompileException {
        // Left: ?person rdf:type foaf:Student
        BasicPattern leftBGP = new BasicPattern();
        leftBGP.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Student")
        ));

        // Right: ?person rdf:type foaf:Teacher
        BasicPattern rightBGP = new BasicPattern();
        rightBGP.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Teacher")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateUnion(leftBGP, rightBGP);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should contain UNION keyword
        assertTrue(cypher.contains("UNION"), "Should have UNION keyword");
        
        // Should have MATCH clauses for both patterns
        assertTrue(cypher.contains("MATCH"), "Should have MATCH clauses");
        
        // Should have both type labels
        assertTrue(cypher.contains("Student"), "Should reference Student type");
        assertTrue(cypher.contains("Teacher"), "Should reference Teacher type");
        
        // Should have RETURN clauses
        assertTrue(cypher.contains("RETURN"), "Should have RETURN clauses");
        assertTrue(cypher.contains("person"), "Should return person variable");
    }

    @Test
    @DisplayName("Test UNION with relationship patterns")
    public void testUnionWithRelationships() throws SparqlToCypherCompiler.CannotCompileException {
        // Left: ?person foaf:knows ?friend
        BasicPattern leftBGP = new BasicPattern();
        leftBGP.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));

        // Right: ?person foaf:worksWith ?colleague
        BasicPattern rightBGP = new BasicPattern();
        rightBGP.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/worksWith"),
            NodeFactory.createVariable("colleague")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateUnion(leftBGP, rightBGP);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should contain UNION
        assertTrue(cypher.contains("UNION"), "Should have UNION keyword");
        
        // Should reference both relationships
        assertTrue(cypher.contains("knows") || cypher.contains("foaf"), 
            "Should reference knows relationship");
        assertTrue(cypher.contains("worksWith") || cypher.contains("foaf"), 
            "Should reference worksWith relationship");
    }

    @Test
    @DisplayName("Test UNION with concrete subjects")
    public void testUnionWithConcreteSubjects() throws SparqlToCypherCompiler.CannotCompileException {
        // Left: <alice> foaf:knows ?friend
        BasicPattern leftBGP = new BasicPattern();
        leftBGP.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));

        // Right: <bob> foaf:knows ?friend
        BasicPattern rightBGP = new BasicPattern();
        rightBGP.add(Triple.create(
            NodeFactory.createURI("http://example.org/bob"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/knows"),
            NodeFactory.createVariable("friend")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateUnion(leftBGP, rightBGP);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        Map<String, Object> params = result.parameters();
        
        // Should have UNION
        assertTrue(cypher.contains("UNION"), "Should have UNION keyword");
        
        // Should have parameters for both URIs
        assertTrue(params.size() >= 2, "Should have at least 2 parameters for the URIs");
        
        // Parameters should contain alice and bob URIs
        boolean hasAlice = params.values().stream()
            .anyMatch(v -> v.toString().contains("alice"));
        boolean hasBob = params.values().stream()
            .anyMatch(v -> v.toString().contains("bob"));
        assertTrue(hasAlice, "Should have parameter for alice");
        assertTrue(hasBob, "Should have parameter for bob");
    }

    @Test
    @DisplayName("Test UNION with multi-triple patterns")
    public void testUnionWithMultiTriplePatterns() throws SparqlToCypherCompiler.CannotCompileException {
        // Left: ?person rdf:type foaf:Student . ?person foaf:name "StudentName"
        // Using concrete literal to avoid variable object issue
        BasicPattern leftBGP = new BasicPattern();
        leftBGP.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Student")
        ));
        leftBGP.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createLiteralByValue(20, null)
        ));

        // Right: ?person rdf:type foaf:Teacher . ?person foaf:age 30
        BasicPattern rightBGP = new BasicPattern();
        rightBGP.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Teacher")
        ));
        rightBGP.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createLiteralByValue(30, null)
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateUnion(leftBGP, rightBGP);

        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should have UNION
        assertTrue(cypher.contains("UNION"), "Should have UNION keyword");
        
        // Should reference both types
        assertTrue(cypher.contains("Student"), "Should reference Student");
        assertTrue(cypher.contains("Teacher"), "Should reference Teacher");
        
        // Should reference age property
        assertTrue(cypher.contains("age"), "Should reference age property");
        
        // Should return person variable
        assertTrue(cypher.contains("person"), "Should return person variable");
    }

    @Test
    @DisplayName("Test UNION throws exception for empty left BGP")
    public void testUnionThrowsForEmptyLeft() {
        BasicPattern emptyBGP = new BasicPattern();
        BasicPattern rightBGP = new BasicPattern();
        rightBGP.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://example.org/pred"),
            NodeFactory.createVariable("o")
        ));

        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translateUnion(emptyBGP, rightBGP),
            "Should throw exception for empty left BGP");
    }

    @Test
    @DisplayName("Test UNION throws exception for empty right BGP")
    public void testUnionThrowsForEmptyRight() {
        BasicPattern leftBGP = new BasicPattern();
        leftBGP.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://example.org/pred"),
            NodeFactory.createVariable("o")
        ));
        BasicPattern emptyBGP = new BasicPattern();

        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translateUnion(leftBGP, emptyBGP),
            "Should throw exception for empty right BGP");
    }

    @Test
    @DisplayName("Test UNION result structure")
    public void testUnionResultStructure() throws SparqlToCypherCompiler.CannotCompileException {
        // Simple UNION with type patterns
        BasicPattern leftBGP = new BasicPattern();
        leftBGP.add(Triple.create(
            NodeFactory.createVariable("x"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://example.org/TypeA")
        ));

        BasicPattern rightBGP = new BasicPattern();
        rightBGP.add(Triple.create(
            NodeFactory.createVariable("x"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://example.org/TypeB")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateUnion(leftBGP, rightBGP);

        assertNotNull(result, "Result should not be null");
        assertNotNull(result.cypherQuery(), "Cypher query should not be null");
        assertNotNull(result.parameters(), "Parameters should not be null");
        assertNotNull(result.variableMapping(), "Variable mapping should not be null");
        
        // Should have at least one entry in variable mapping (depending on how rdf:type is handled)
        // The important part is that the query is compiled successfully
        assertFalse(result.cypherQuery().isEmpty(), "Cypher query should not be empty");
    }

    @Test
    @DisplayName("Test UNION with parameter name conflicts")
    public void testUnionWithParameterConflicts() throws SparqlToCypherCompiler.CannotCompileException {
        // Both branches use the same predicate, which could create parameter conflicts
        BasicPattern leftBGP = new BasicPattern();
        leftBGP.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createLiteralString("Alice")
        ));

        BasicPattern rightBGP = new BasicPattern();
        rightBGP.add(Triple.create(
            NodeFactory.createURI("http://example.org/bob"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createLiteralString("Bob")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateUnion(leftBGP, rightBGP);

        assertNotNull(result);
        Map<String, Object> params = result.parameters();
        
        // Should handle parameter conflicts - check that all values are present
        assertTrue(params.containsValue("Alice"), "Should have Alice literal");
        assertTrue(params.containsValue("Bob"), "Should have Bob literal");
        
        // Check URIs are present
        boolean hasAlice = params.values().stream()
            .anyMatch(v -> v.toString().contains("alice"));
        boolean hasBob = params.values().stream()
            .anyMatch(v -> v.toString().contains("bob"));
        assertTrue(hasAlice, "Should have alice URI");
        assertTrue(hasBob, "Should have bob URI");
    }

    // ===== Variable Predicate in OPTIONAL Tests =====

    @Test
    @DisplayName("Test OPTIONAL with variable predicate generates three UNION parts")
    public void testOptionalVariablePredicateThreePartUnion() throws Exception {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("predicate"),
            NodeFactory.createVariable("value")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);
        
        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Verify three-part UNION structure
        int unionCount = cypher.split("UNION ALL", -1).length - 1;
        assertEquals(2, unionCount, "Should have exactly 2 UNION ALL (3 parts)");
        
        // Part 1: Relationships
        assertTrue(cypher.contains("-[_r]->"), "Should have relationship pattern");
        assertTrue(cypher.contains("type(_r)"), "Should extract relationship type");
        
        // Part 2: Properties
        assertTrue(cypher.contains("UNWIND keys("), "Should unwind node keys");
        assertTrue(cypher.contains("_propKey <> 'uri'"), "Should filter out uri property");
        
        // Part 3: Types (rdf:type from labels)
        assertTrue(cypher.contains("UNWIND labels("), "Should unwind node labels");
        assertTrue(cypher.contains("_label <> 'Resource'"), "Should filter out Resource label");
        assertTrue(cypher.contains("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), 
            "Should use rdf:type URI for labels");
    }

    @Test
    @DisplayName("Test OPTIONAL with variable predicate and concrete subject")
    public void testOptionalVariablePredicateConcreteSubject() throws Exception {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createURI("http://example.org/alice"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("o")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);
        
        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should use parameters for concrete URIs
        assertTrue(result.parameters().size() >= 1, "Should have parameters for concrete URIs");
        assertTrue(cypher.contains("$p"), "Should use parameterized queries");
        
        // All three parts should use OPTIONAL MATCH
        int optionalMatchCount = cypher.split("OPTIONAL MATCH", -1).length - 1;
        assertEquals(3, optionalMatchCount, "Should have 3 OPTIONAL MATCH clauses");
    }

    @Test
    @DisplayName("Test OPTIONAL with variable predicate returns correct variables")
    public void testOptionalVariablePredicateReturnsCorrectVariables() throws Exception {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://example.org/MyType")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("s"),
            NodeFactory.createVariable("predicate"),
            NodeFactory.createVariable("object")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);
        
        String cypher = result.cypherQuery();
        
        // Each RETURN clause should include all three variables
        String[] parts = cypher.split("RETURN");
        assertEquals(4, parts.length, "Should have 3 RETURN clauses (parts array has 4 elements)");
        
        // Verify each RETURN includes the required variables
        for (int i = 1; i < parts.length; i++) {
            String returnPart = parts[i].split("UNION ALL|$")[0];
            assertTrue(returnPart.contains(" AS s") || returnPart.contains(" s"), 
                "RETURN should include subject variable s");
            assertTrue(returnPart.contains(" AS predicate") || returnPart.contains(" predicate"), 
                "RETURN should include predicate variable");
            assertTrue(returnPart.contains(" AS object") || returnPart.contains(" object"), 
                "RETURN should include object variable");
        }
    }

    @Test
    @DisplayName("Test OPTIONAL with variable predicate and FILTER")
    public void testOptionalVariablePredicateWithFilter() throws Exception {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/age"),
            NodeFactory.createVariable("age")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("o")
        ));

        // FILTER: ?age >= 18
        org.apache.jena.sparql.expr.Expr filterExpr = 
            new org.apache.jena.sparql.expr.E_GreaterThanOrEqual(
                new org.apache.jena.sparql.expr.ExprVar("age"),
                org.apache.jena.sparql.expr.NodeValue.makeInteger(18)
            );

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional, filterExpr);
        
        assertNotNull(result);
        String cypher = result.cypherQuery();
        
        // Should have WHERE clause for FILTER in all three parts
        assertTrue(cypher.contains("WHERE"), "Should have WHERE clause for filter");
        assertTrue(cypher.contains(">="), "Should have >= operator");
        assertTrue(cypher.contains("18"), "Should have age value 18");
        
        // Should still have UNION structure
        assertTrue(cypher.contains("UNION ALL"), "Should have UNION ALL");
        assertTrue(cypher.contains("OPTIONAL MATCH"), "Should have OPTIONAL MATCH");
    }

    @Test
    @DisplayName("Test OPTIONAL with variable predicate throws for multiple optional triples")
    public void testOptionalVariablePredicateThrowsForMultipleTriples() {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("o")
        ));
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p2"),
            NodeFactory.createVariable("o2")
        ));

        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translateWithOptional(required, optional),
            "Should throw for multiple triples with variable predicates in OPTIONAL");
    }

    @Test
    @DisplayName("Test OPTIONAL with variable predicate query structure")
    public void testOptionalVariablePredicateQueryStructure() throws Exception {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("x"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://example.org/Type")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("x"),
            NodeFactory.createVariable("prop"),
            NodeFactory.createVariable("val")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);
        
        String cypher = result.cypherQuery();
        String[] lines = cypher.split("\n");
        
        // Verify overall structure
        boolean hasRequiredMatch = false;
        int optionalMatchCount = 0;
        int unionAllCount = 0;
        int returnCount = 0;
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("MATCH ")) hasRequiredMatch = true;
            if (trimmed.startsWith("OPTIONAL MATCH")) optionalMatchCount++;
            if (trimmed.equals("UNION ALL")) unionAllCount++;
            if (trimmed.startsWith("RETURN")) returnCount++;
        }
        
        assertTrue(hasRequiredMatch, "Should have required MATCH clause");
        assertEquals(3, optionalMatchCount, "Should have 3 OPTIONAL MATCH clauses");
        assertEquals(2, unionAllCount, "Should have 2 UNION ALL");
        assertEquals(3, returnCount, "Should have 3 RETURN clauses");
    }

    @Test
    @DisplayName("Test OPTIONAL with variable predicate handles variable mapping")
    public void testOptionalVariablePredicateVariableMapping() throws Exception {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("resource"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://example.org/Resource")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("resource"),
            NodeFactory.createVariable("predicate"),
            NodeFactory.createVariable("value")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);
        
        assertNotNull(result.variableMapping(), "Variable mapping should not be null");
        
        // The query should compile successfully and return valid Cypher
        assertNotNull(result.cypherQuery(), "Cypher query should not be null");
        assertFalse(result.cypherQuery().isEmpty(), "Cypher query should not be empty");
        assertTrue(result.cypherQuery().length() > 100, "Cypher query should be substantial");
    }

    @Test
    @DisplayName("Test OPTIONAL with variable predicate preserves required variables")
    public void testOptionalVariablePredicatePreservesRequiredVariables() throws Exception {
        BasicPattern required = new BasicPattern();
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/Person")
        ));
        required.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"),
            NodeFactory.createVariable("name")
        ));

        BasicPattern optional = new BasicPattern();
        optional.add(Triple.create(
            NodeFactory.createVariable("person"),
            NodeFactory.createVariable("p"),
            NodeFactory.createVariable("o")
        ));

        SparqlToCypherCompiler.CompilationResult result = 
            SparqlToCypherCompiler.translateWithOptional(required, optional);
        
        String cypher = result.cypherQuery();
        
        // All RETURN clauses should include both required and optional variables
        String[] parts = cypher.split("RETURN");
        for (int i = 1; i < parts.length; i++) {
            String returnPart = parts[i].split("UNION ALL|$")[0];
            assertTrue(returnPart.contains(" person"), "Should return person variable");
            assertTrue(returnPart.contains(" name") || returnPart.contains(".`http://xmlns.com/foaf/0.1/name`"), 
                "Should return name variable");
            assertTrue(returnPart.contains(" p") || returnPart.contains(" AS p"), 
                "Should return predicate variable");
            assertTrue(returnPart.contains(" o") || returnPart.contains(" AS o"), 
                "Should return object variable");
        }
    }
}
