package com.falkordb.samples;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

/**
 * Examples demonstrating UNION pattern optimization.
 * 
 * UNION patterns are translated to Cypher UNION queries, combining results
 * from alternative query patterns in a single database call.
 * 
 * For more details, see: OPTIMIZATIONS.md#union-patterns
 */
public class UnionPatternsExample {
    
    private static final String GRAPH_NAME = "union_patterns_example";
    
    public static void main(String[] args) {
        System.out.println("=== UNION Patterns Examples ===\n");
        
        // Create model and add test data
        Model model = FalkorDBModelFactory.createModel(GRAPH_NAME);
        setupTestData(model);
        
        try {
            // Example 1: Type union
            example1TypeUnion(model);
            
            // Example 2: Relationship union
            example2RelationshipUnion(model);
            
            // Example 3: Concrete subjects
            example3ConcreteSubjects(model);
            
            // Example 4: Property union
            example4PropertyUnion(model);
            
            // Example 5: Three-way union
            example5ThreeWayUnion(model);
            
        } finally {
            model.close();
        }
        
        System.out.println("\n=== Examples Complete ===");
    }
    
    private static void setupTestData(Model model) {
        System.out.println("Setting up test data...\n");
        
        // Define resources
        var studentType = model.createResource("http://example.org/Student");
        var teacherType = model.createResource("http://example.org/Teacher");
        var staffType = model.createResource("http://example.org/Staff");
        
        var name = model.createProperty("http://xmlns.com/foaf/0.1/name");
        var age = model.createProperty("http://xmlns.com/foaf/0.1/age");
        var email = model.createProperty("http://xmlns.com/foaf/0.1/email");
        var phone = model.createProperty("http://xmlns.com/foaf/0.1/phone");
        var knows = model.createProperty("http://xmlns.com/foaf/0.1/knows");
        var worksWith = model.createProperty("http://example.org/worksWith");
        
        // Students
        var alice = model.createResource("http://example.org/alice");
        alice.addProperty(RDF.type, studentType);
        alice.addProperty(name, "Alice");
        alice.addProperty(age, model.createTypedLiteral(20));
        alice.addProperty(email, "alice@example.org");
        
        var charlie = model.createResource("http://example.org/charlie");
        charlie.addProperty(RDF.type, studentType);
        charlie.addProperty(name, "Charlie");
        charlie.addProperty(age, model.createTypedLiteral(22));
        charlie.addProperty(phone, "555-0001");
        
        // Teachers
        var bob = model.createResource("http://example.org/bob");
        bob.addProperty(RDF.type, teacherType);
        bob.addProperty(name, "Bob");
        bob.addProperty(age, model.createTypedLiteral(35));
        bob.addProperty(email, "bob@example.org");
        
        var diana = model.createResource("http://example.org/diana");
        diana.addProperty(RDF.type, teacherType);
        diana.addProperty(name, "Diana");
        diana.addProperty(age, model.createTypedLiteral(40));
        diana.addProperty(phone, "555-0002");
        
        // Staff
        var eve = model.createResource("http://example.org/eve");
        eve.addProperty(RDF.type, staffType);
        eve.addProperty(name, "Eve");
        eve.addProperty(age, model.createTypedLiteral(30));
        
        // Relationships
        alice.addProperty(knows, charlie);
        charlie.addProperty(knows, alice);
        bob.addProperty(worksWith, diana);
    }
    
    private static void example1TypeUnion(Model model) {
        System.out.println("Example 1: Type Union - Find all students or teachers");
        System.out.println("------------------------------------------------------");
        
        String sparql = """
            PREFIX ex: <http://example.org/>
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?name WHERE {
                { ?person a ex:Student }
                UNION
                { ?person a ex:Teacher }
                ?person foaf:name ?name .
            }
            ORDER BY ?name
            """;
        
        executeQuery(model, sparql);
        System.out.println();
    }
    
    private static void example2RelationshipUnion(Model model) {
        System.out.println("Example 2: Relationship Union - Find all connections");
        System.out.println("---------------------------------------------------");
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            PREFIX ex: <http://example.org/>
            SELECT ?person ?connection WHERE {
                { ?person foaf:knows ?connection }
                UNION
                { ?person ex:worksWith ?connection }
            }
            ORDER BY ?person
            """;
        
        executeQuery(model, sparql);
        System.out.println();
    }
    
    private static void example3ConcreteSubjects(Model model) {
        System.out.println("Example 3: Concrete Subjects - Friends of specific people");
        System.out.println("----------------------------------------------------------");
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?friend WHERE {
                { <http://example.org/alice> foaf:knows ?friend }
                UNION
                { <http://example.org/charlie> foaf:knows ?friend }
            }
            """;
        
        executeQuery(model, sparql);
        System.out.println();
    }
    
    private static void example4PropertyUnion(Model model) {
        System.out.println("Example 4: Property Union - Any contact method");
        System.out.println("----------------------------------------------");
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?contact WHERE {
                { ?person foaf:email ?contact }
                UNION
                { ?person foaf:phone ?contact }
            }
            ORDER BY ?person
            """;
        
        executeQuery(model, sparql);
        System.out.println();
    }
    
    private static void example5ThreeWayUnion(Model model) {
        System.out.println("Example 5: Three-way Union - All academic personnel");
        System.out.println("---------------------------------------------------");
        
        String sparql = """
            PREFIX ex: <http://example.org/>
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?name WHERE {
                { ?person a ex:Student }
                UNION
                { ?person a ex:Teacher }
                UNION
                { ?person a ex:Staff }
                ?person foaf:name ?name .
            }
            ORDER BY ?name
            """;
        
        executeQuery(model, sparql);
        System.out.println();
    }
    
    private static void executeQuery(Model model, String sparql) {
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            
            // Print results
            int count = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                System.out.print("  ");
                
                // Print all variables in the solution
                solution.varNames().forEachRemaining(varName -> {
                    var node = solution.get(varName);
                    if (node.isLiteral()) {
                        System.out.print(varName + "=" + node.asLiteral().getString() + " ");
                    } else if (node.isResource()) {
                        String uri = node.asResource().getURI();
                        String localName = uri.substring(uri.lastIndexOf('/') + 1);
                        System.out.print(varName + "=" + localName + " ");
                    }
                });
                
                System.out.println();
                count++;
            }
            
            System.out.println("  Results: " + count);
        }
    }
}
