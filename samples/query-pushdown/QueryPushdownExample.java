package com.falkordb.samples;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.FOAF;

/**
 * Example demonstrating Query Pushdown optimization in FalkorDB Jena Adapter.
 * 
 * Query pushdown translates SPARQL Basic Graph Patterns (BGPs) to native Cypher queries,
 * avoiding the N+1 query problem and executing queries in a single database operation.
 * 
 * For more details, see: OPTIMIZATIONS.md#query-pushdown-sparql-to-cypher
 */
public class QueryPushdownExample {

    public static void main(String[] args) {
        // Create a model backed by FalkorDB
        Model model = FalkorDBModelFactory.createDefaultModel();

        try {
            // Setup test data
            setupTestData(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 1: Variable Predicates (get all properties)
            variablePredicateExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 2: Closed-Chain Patterns (mutual references)
            closedChainExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 3: Type-based queries
            typeBasedQueryExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 4: Friends of Friends pattern
            friendsOfFriendsExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 5: Multiple hop traversal
            multiHopTraversalExample(model);

        } finally {
            model.close();
        }
    }

    /**
     * Setup test data for examples
     */
    private static void setupTestData(Model model) {
        System.out.println("Setting up test data...");
        
        model.begin(ReadWrite.WRITE);
        try {
            // Create people with properties
            Resource alice = model.createResource("http://example.org/person/alice");
            alice.addProperty(RDF.type, FOAF.Person);
            alice.addProperty(FOAF.name, "Alice");
            alice.addProperty(FOAF.age, model.createTypedLiteral(30));
            
            Resource bob = model.createResource("http://example.org/person/bob");
            bob.addProperty(RDF.type, FOAF.Person);
            bob.addProperty(FOAF.name, "Bob");
            bob.addProperty(FOAF.age, model.createTypedLiteral(35));
            
            Resource charlie = model.createResource("http://example.org/person/charlie");
            charlie.addProperty(RDF.type, FOAF.Person);
            charlie.addProperty(FOAF.name, "Charlie");
            charlie.addProperty(FOAF.age, model.createTypedLiteral(28));
            
            Resource diana = model.createResource("http://example.org/person/diana");
            diana.addProperty(RDF.type, FOAF.Person);
            diana.addProperty(FOAF.name, "Diana");
            diana.addProperty(FOAF.age, model.createTypedLiteral(32));
            
            // Create social network
            alice.addProperty(FOAF.knows, bob);
            alice.addProperty(FOAF.knows, charlie);
            bob.addProperty(FOAF.knows, charlie);
            bob.addProperty(FOAF.knows, diana);
            charlie.addProperty(FOAF.knows, diana);
            diana.addProperty(FOAF.knows, alice);  // Mutual connection
            
            model.commit();
            System.out.println("✓ Test data created");
            
        } finally {
            model.end();
        }
    }

    /**
     * Example 1: Variable Predicates - Get all properties and relationships
     */
    private static void variablePredicateExample(Model model) {
        System.out.println("Example 1: Variable Predicate (Get All Properties)");
        System.out.println("===================================================");
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?predicate ?value WHERE {
                <http://example.org/person/alice> ?predicate ?value .
            }
            ORDER BY ?predicate
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        
        System.out.println("\nQuery automatically uses pushdown with triple UNION:");
        System.out.println("1. Query relationships (edges)");
        System.out.println("2. Query properties (node attributes)");
        System.out.println("3. Query types (labels as rdf:type)");
        
        System.out.println("\nResults:");
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            int count = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                String predicate = solution.get("predicate").toString();
                String value = solution.get("value").toString();
                System.out.println("  " + predicate + " -> " + value);
                count++;
            }
            System.out.println("\n✓ Retrieved " + count + " triples in single query");
        }
    }

    /**
     * Example 2: Closed-Chain Pattern - Mutual friendships
     */
    private static void closedChainExample(Model model) {
        System.out.println("Example 2: Closed-Chain Pattern (Mutual Friendships)");
        System.out.println("====================================================");
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person1 ?person2 WHERE {
                ?person1 foaf:knows ?person2 .
                ?person2 foaf:knows ?person1 .
            }
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        
        System.out.println("\nThis pattern is recognized and pushed down:");
        System.out.println("- Both variables used as subjects (closed chain)");
        System.out.println("- Generates efficient Cypher with bidirectional match");
        
        System.out.println("\nResults (mutual friendships):");
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            int count = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                String p1 = solution.getResource("person1").getLocalName();
                String p2 = solution.getResource("person2").getLocalName();
                System.out.println("  " + p1 + " ↔ " + p2);
                count++;
            }
            System.out.println("\n✓ Found " + count + " mutual connections in single query");
        }
    }

    /**
     * Example 3: Type-based queries using labels
     */
    private static void typeBasedQueryExample(Model model) {
        System.out.println("Example 3: Type-Based Query");
        System.out.println("============================");
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            SELECT ?person ?name WHERE {
                ?person rdf:type foaf:Person .
                ?person foaf:name ?name .
            }
            ORDER BY ?name
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        
        System.out.println("\nOptimization:");
        System.out.println("- rdf:type queries use native graph labels");
        System.out.println("- Efficient MATCH (person:Resource:Person)");
        
        System.out.println("\nResults:");
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                String name = solution.getLiteral("name").getString();
                System.out.println("  Person: " + name);
            }
        }
    }

    /**
     * Example 4: Friends of Friends pattern
     */
    private static void friendsOfFriendsExample(Model model) {
        System.out.println("Example 4: Friends of Friends Pattern");
        System.out.println("======================================");
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT DISTINCT ?fof WHERE {
                <http://example.org/person/alice> foaf:knows ?friend .
                ?friend foaf:knows ?fof .
                FILTER(?fof != <http://example.org/person/alice>)
            }
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        
        System.out.println("\nWithout pushdown: N+1 queries (1 for friends, N for each friend's friends)");
        System.out.println("With pushdown:    1 query (2-hop pattern compiled to Cypher)");
        
        System.out.println("\nResults (Alice's friends of friends):");
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                String fof = solution.getResource("fof").getLocalName();
                System.out.println("  " + fof);
            }
        }
    }

    /**
     * Example 5: Multi-hop traversal
     */
    private static void multiHopTraversalExample(Model model) {
        System.out.println("Example 5: Multi-Hop Traversal (3 hops)");
        System.out.println("========================================");
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT DISTINCT ?person3 WHERE {
                <http://example.org/person/alice> foaf:knows ?person1 .
                ?person1 foaf:knows ?person2 .
                ?person2 foaf:knows ?person3 .
            }
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        
        System.out.println("\nPerformance:");
        System.out.println("- Without pushdown: ~N² database calls");
        System.out.println("- With pushdown:    1 database call");
        
        System.out.println("\nResults (3 hops from Alice):");
        
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            int count = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                String person = solution.getResource("person3").getLocalName();
                System.out.println("  " + person);
                count++;
            }
            System.out.println("\n✓ Traversed 3 hops in single query (found " + count + " results)");
        }
    }
}
