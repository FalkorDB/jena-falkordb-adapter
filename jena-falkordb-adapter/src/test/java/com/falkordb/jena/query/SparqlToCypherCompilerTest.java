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
        assertTrue(cypher.contains("email.uri AS email"));
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
        
        // Should return both name and age
        assertTrue(cypher.contains("person.`http://xmlns.com/foaf/0.1/name` AS name"));
        assertTrue(cypher.contains("person.`http://xmlns.com/foaf/0.1/age` AS age"));
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
    @DisplayName("Test OPTIONAL throws exception for variable predicate in optional part")
    public void testOptionalThrowsForVariablePredicate() {
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

        assertThrows(SparqlToCypherCompiler.CannotCompileException.class,
            () -> SparqlToCypherCompiler.translateWithOptional(required, optional));
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
}
