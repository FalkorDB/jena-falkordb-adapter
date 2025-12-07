package com.falkordb.samples;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.FOAF;

/**
 * Example demonstrating OPTIONAL patterns optimization in FalkorDB Jena Adapter.
 * 
 * <p>OPTIONAL patterns are translated to Cypher OPTIONAL MATCH clauses, which return
 * NULL for missing optional data instead of requiring separate queries. This avoids
 * the N+1 query problem.</p>
 * 
 * <h2>Key Benefits:</h2>
 * <ul>
 *   <li>Single database roundtrip for required + optional data</li>
 *   <li>Returns NULL for missing optional values (not empty result set)</li>
 *   <li>Native Cypher OPTIONAL MATCH optimization</li>
 *   <li>Nx performance improvement over separate queries</li>
 * </ul>
 * 
 * <p>For more details, see: OPTIMIZATIONS.md#optional-patterns</p>
 */
public class OptionalPatternsExample {

    public static void main(String[] args) {
        // Create a model backed by FalkorDB
        Model model = FalkorDBModelFactory.createDefaultModel();

        try {
            // Setup test data
            setupTestData(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 1: Basic OPTIONAL pattern
            basicOptionalExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 2: Multiple OPTIONAL clauses
            multipleOptionalExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 3: OPTIONAL with literal properties
            optionalLiteralExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 4: OPTIONAL with multiple triples
            optionalMultipleTriples(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 5: Concrete subject with OPTIONAL
            concreteSubjectOptional(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 6: OPTIONAL with FILTER
            optionalWithFilter(model);

        } finally {
            model.close();
        }
    }

    /**
     * Setup test data with partial information for demonstrating OPTIONAL patterns.
     */
    private static void setupTestData(Model model) {
        System.out.println("Setting up test data with partial information...");
        
        model.begin(ReadWrite.WRITE);
        try {
            Property email = model.createProperty("http://xmlns.com/foaf/0.1/email");
            Property phone = model.createProperty("http://xmlns.com/foaf/0.1/phone");
            
            // Alice - complete information
            Resource alice = model.createResource("http://example.org/person/alice");
            alice.addProperty(RDF.type, FOAF.Person);
            alice.addProperty(FOAF.name, "Alice");
            alice.addProperty(FOAF.age, model.createTypedLiteral(30));
            alice.addProperty(email, model.createResource("mailto:alice@example.org"));
            alice.addProperty(phone, "+1-555-0101");
            
            // Bob - name and age only (no email or phone)
            Resource bob = model.createResource("http://example.org/person/bob");
            bob.addProperty(RDF.type, FOAF.Person);
            bob.addProperty(FOAF.name, "Bob");
            bob.addProperty(FOAF.age, model.createTypedLiteral(35));
            
            // Charlie - name only
            Resource charlie = model.createResource("http://example.org/person/charlie");
            charlie.addProperty(RDF.type, FOAF.Person);
            charlie.addProperty(FOAF.name, "Charlie");
            
            // Diana - name and email, but no age
            Resource diana = model.createResource("http://example.org/person/diana");
            diana.addProperty(RDF.type, FOAF.Person);
            diana.addProperty(FOAF.name, "Diana");
            diana.addProperty(email, model.createResource("mailto:diana@example.org"));
            
            // Eve - name, email, and phone (no age)
            Resource eve = model.createResource("http://example.org/person/eve");
            eve.addProperty(RDF.type, FOAF.Person);
            eve.addProperty(FOAF.name, "Eve");
            eve.addProperty(email, model.createResource("mailto:eve@example.org"));
            eve.addProperty(phone, "+1-555-0105");
            
            // Create some friendships
            alice.addProperty(FOAF.knows, bob);
            bob.addProperty(FOAF.knows, charlie);
            
            model.commit();
            System.out.println("✓ Test data created with partial information");
            System.out.println("  - Alice: has all information");
            System.out.println("  - Bob: has name and age only");
            System.out.println("  - Charlie: has name only");
            System.out.println("  - Diana: has name and email");
            System.out.println("  - Eve: has name, email, and phone");
            
        } finally {
            model.end();
        }
    }

    /**
     * Example 1: Basic OPTIONAL pattern - Find all persons with optional email.
     * 
     * <p>This demonstrates the fundamental OPTIONAL pattern where we want all persons
     * but only show email if it exists.</p>
     */
    private static void basicOptionalExample(Model model) {
        System.out.println("Example 1: Basic OPTIONAL - All persons with optional email");
        System.out.println("-".repeat(70));
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            
            SELECT ?person ?name ?email WHERE {
                ?person a foaf:Person .
                ?person foaf:name ?name .
                OPTIONAL { ?person foaf:email ?email }
            }
            ORDER BY ?name
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        System.out.println("\nResults:");
        
        Query query = QueryFactory.create(sparql);
        Dataset dataset = DatasetFactory.create(model);
        
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            int count = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                count++;
                
                String name = solution.getLiteral("name").getString();
                String emailValue = solution.contains("email") 
                    ? solution.getResource("email").getURI() 
                    : "(no email)";
                
                System.out.printf("%d. %s - %s%n", count, name, emailValue);
            }
            
            System.out.println("\n✓ Returned " + count + " persons (all persons, some with email)");
            System.out.println("  Performance: Single query for all data (OPTIONAL MATCH)");
        }
    }

    /**
     * Example 2: Multiple OPTIONAL clauses - Contact information.
     * 
     * <p>Demonstrates multiple OPTIONAL patterns for different optional fields.</p>
     */
    private static void multipleOptionalExample(Model model) {
        System.out.println("Example 2: Multiple OPTIONAL clauses - Complete contact information");
        System.out.println("-".repeat(70));
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            
            SELECT ?person ?name ?email ?phone WHERE {
                ?person a foaf:Person .
                ?person foaf:name ?name .
                OPTIONAL { ?person foaf:email ?email }
                OPTIONAL { ?person foaf:phone ?phone }
            }
            ORDER BY ?name
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        System.out.println("\nResults:");
        
        Query query = QueryFactory.create(sparql);
        Dataset dataset = DatasetFactory.create(model);
        
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            int count = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                count++;
                
                String name = solution.getLiteral("name").getString();
                String email = solution.contains("email") 
                    ? solution.getResource("email").getURI() 
                    : "(no email)";
                String phone = solution.contains("phone") 
                    ? solution.getLiteral("phone").getString() 
                    : "(no phone)";
                
                System.out.printf("%d. %s%n", count, name);
                System.out.printf("   Email: %s%n", email);
                System.out.printf("   Phone: %s%n", phone);
            }
            
            System.out.println("\n✓ Returned " + count + " persons with any available contact info");
            System.out.println("  Performance: Single query with 2 OPTIONAL MATCH clauses");
        }
    }

    /**
     * Example 3: OPTIONAL with literal properties - Age information.
     * 
     * <p>Shows OPTIONAL patterns with literal values stored as node properties.</p>
     */
    private static void optionalLiteralExample(Model model) {
        System.out.println("Example 3: OPTIONAL with literal property - Age information");
        System.out.println("-".repeat(70));
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            
            SELECT ?person ?name ?age WHERE {
                ?person a foaf:Person .
                ?person foaf:name ?name .
                OPTIONAL { ?person foaf:age ?age }
            }
            ORDER BY ?name
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        System.out.println("\nResults:");
        
        Query query = QueryFactory.create(sparql);
        Dataset dataset = DatasetFactory.create(model);
        
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            int count = 0;
            int withAge = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                count++;
                
                String name = solution.getLiteral("name").getString();
                String age = solution.contains("age") 
                    ? String.valueOf(solution.getLiteral("age").getInt()) 
                    : "(age not provided)";
                
                if (solution.contains("age")) {
                    withAge++;
                }
                
                System.out.printf("%d. %s - Age: %s%n", count, name, age);
            }
            
            System.out.println("\n✓ Returned " + count + " persons (" + withAge + " with age)");
            System.out.println("  Note: Literal properties use OPTIONAL MATCH with WHERE clause");
        }
    }

    /**
     * Example 4: OPTIONAL with multiple triples - Friend information.
     * 
     * <p>Demonstrates OPTIONAL block containing multiple triple patterns.</p>
     */
    private static void optionalMultipleTriples(Model model) {
        System.out.println("Example 4: OPTIONAL with multiple triples - Friend information");
        System.out.println("-".repeat(70));
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            
            SELECT ?person ?name ?friend ?friendName WHERE {
                ?person a foaf:Person .
                ?person foaf:name ?name .
                OPTIONAL { 
                    ?person foaf:knows ?friend .
                    ?friend foaf:name ?friendName .
                }
            }
            ORDER BY ?name
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        System.out.println("\nResults:");
        
        Query query = QueryFactory.create(sparql);
        Dataset dataset = DatasetFactory.create(model);
        
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            int count = 0;
            String currentPerson = "";
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                count++;
                
                String name = solution.getLiteral("name").getString();
                
                if (!name.equals(currentPerson)) {
                    currentPerson = name;
                    System.out.printf("%s:%n", name);
                }
                
                if (solution.contains("friend") && solution.contains("friendName")) {
                    String friendName = solution.getLiteral("friendName").getString();
                    System.out.printf("  → knows %s%n", friendName);
                } else if (!solution.contains("friend")) {
                    System.out.println("  → (no friends listed)");
                }
            }
            
            System.out.println("\n✓ Returned " + count + " result rows");
            System.out.println("  Note: Multiple OPTIONAL MATCH clauses for nested pattern");
        }
    }

    /**
     * Example 5: Concrete subject with OPTIONAL - Specific person query.
     * 
     * <p>Shows OPTIONAL patterns when querying a specific resource.</p>
     */
    private static void concreteSubjectOptional(Model model) {
        System.out.println("Example 5: Concrete subject with OPTIONAL - Bob's information");
        System.out.println("-".repeat(70));
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            
            SELECT ?name ?age ?email WHERE {
                <http://example.org/person/bob> foaf:name ?name .
                OPTIONAL { <http://example.org/person/bob> foaf:age ?age }
                OPTIONAL { <http://example.org/person/bob> foaf:email ?email }
            }
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        System.out.println("\nResults:");
        
        Query query = QueryFactory.create(sparql);
        Dataset dataset = DatasetFactory.create(model);
        
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            if (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                
                System.out.println("Bob's Information:");
                System.out.printf("  Name: %s%n", solution.getLiteral("name").getString());
                System.out.printf("  Age: %s%n", 
                    solution.contains("age") 
                        ? solution.getLiteral("age").getInt() 
                        : "(not provided)");
                System.out.printf("  Email: %s%n", 
                    solution.contains("email") 
                        ? solution.getResource("email").getURI() 
                        : "(not provided)");
                
                System.out.println("\n✓ Single query fetched all available information");
            }
        }
    }

    /**
     * Example 6: OPTIONAL with FILTER in required part.
     * 
     * <p>Demonstrates filtering on required data before applying OPTIONAL pattern.</p>
     */
    private static void optionalWithFilter(Model model) {
        System.out.println("Example 6: OPTIONAL with FILTER - Young persons with optional email");
        System.out.println("-".repeat(70));
        
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            
            SELECT ?person ?name ?age ?email WHERE {
                ?person a foaf:Person .
                ?person foaf:name ?name .
                ?person foaf:age ?age .
                FILTER(?age < 35)
                OPTIONAL { ?person foaf:email ?email }
            }
            ORDER BY ?age
            """;
        
        System.out.println("SPARQL Query:");
        System.out.println(sparql);
        System.out.println("\nResults (persons under 35 years old):");
        
        Query query = QueryFactory.create(sparql);
        Dataset dataset = DatasetFactory.create(model);
        
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            
            int count = 0;
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                count++;
                
                String name = solution.getLiteral("name").getString();
                int age = solution.getLiteral("age").getInt();
                String email = solution.contains("email") 
                    ? solution.getResource("email").getURI() 
                    : "(no email)";
                
                System.out.printf("%d. %s (age %d) - %s%n", count, name, age, email);
            }
            
            System.out.println("\n✓ Filter applied before OPTIONAL pattern");
            System.out.println("  Performance: FILTER + OPTIONAL MATCH in single query");
        }
    }
}
