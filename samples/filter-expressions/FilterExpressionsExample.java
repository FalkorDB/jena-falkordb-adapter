package com.falkordb.samples;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

/**
 * Examples demonstrating FILTER expression optimization in SPARQL queries.
 * 
 * <p>The FalkorDB adapter automatically pushes FILTER expressions down to Cypher WHERE clauses,
 * reducing network round-trips and improving query performance.</p>
 * 
 * <h2>Prerequisites:</h2>
 * <ul>
 *   <li>FalkorDB running on localhost:6379</li>
 *   <li>Java 21+</li>
 * </ul>
 * 
 * <h2>Supported FILTER Operators:</h2>
 * <ul>
 *   <li>Comparison: {@code <}, {@code <=}, {@code >}, {@code >=}, {@code =}, {@code !=} (mapped to {@code <>} in Cypher)</li>
 *   <li>Logical: {@code &&} (AND), {@code ||} (OR), {@code !} (NOT)</li>
 *   <li>Operands: Variables, numeric literals, string literals, boolean literals</li>
 * </ul>
 */
public class FilterExpressionsExample {

    /**
     * Example 1: Simple numeric comparison with less than operator.
     * 
     * <p>Finds all persons younger than 30 years old.</p>
     * 
     * <p>SPARQL query is pushed down as single Cypher query with WHERE clause.</p>
     */
    public static void example1_SimpleLessThan() {
        System.out.println("\n=== Example 1: Simple FILTER with < ===");
        
        Model model = FalkorDBModelFactory.createModel("filter_examples");
        
        try {
            // Setup: Add test data
            Property age = model.createProperty("http://xmlns.com/foaf/0.1/age");
            Property name = model.createProperty("http://xmlns.com/foaf/0.1/name");
            
            Resource alice = model.createResource("http://example.org/person/alice");
            alice.addProperty(name, "Alice");
            alice.addProperty(age, model.createTypedLiteral(25));
            
            Resource bob = model.createResource("http://example.org/person/bob");
            bob.addProperty(name, "Bob");
            bob.addProperty(age, model.createTypedLiteral(35));
            
            Resource charlie = model.createResource("http://example.org/person/charlie");
            charlie.addProperty(name, "Charlie");
            charlie.addProperty(age, model.createTypedLiteral(45));
            
            // Query: Find persons under 30
            String sparql = """
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                SELECT ?name ?age WHERE {
                    ?person foaf:name ?name .
                    ?person foaf:age ?age .
                    FILTER(?age < 30)
                }
                ORDER BY ?name
                """;
            
            System.out.println("SPARQL Query:");
            System.out.println(sparql);
            
            // Execute query - automatically uses FILTER pushdown
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                System.out.println("\nResults:");
                while (results.hasNext()) {
                    QuerySolution solution = results.nextSolution();
                    String personName = solution.getLiteral("name").getString();
                    int personAge = solution.getLiteral("age").getInt();
                    System.out.println("  " + personName + " (age " + personAge + ")");
                }
            }
            
            System.out.println("\nGenerated Cypher (conceptual):");
            System.out.println("MATCH (person:Resource)");
            System.out.println("WHERE person.`foaf:name` IS NOT NULL");
            System.out.println("  AND person.`foaf:age` IS NOT NULL");
            System.out.println("  AND person.`foaf:age` < 30");
            System.out.println("RETURN person.`foaf:name` AS name, person.`foaf:age` AS age");
            
        } finally {
            model.close();
        }
    }

    /**
     * Example 2: Numeric range with AND operator.
     * 
     * <p>Finds all persons of working age (18-65).</p>
     */
    public static void example2_RangeWithAnd() {
        System.out.println("\n=== Example 2: FILTER with AND (numeric range) ===");
        
        Model model = FalkorDBModelFactory.createModel("filter_examples");
        
        try {
            // Setup: Add test data with various ages
            Property age = model.createProperty("http://xmlns.com/foaf/0.1/age");
            Property name = model.createProperty("http://xmlns.com/foaf/0.1/name");
            
            Resource[] persons = {
                model.createResource("http://example.org/person/child"),
                model.createResource("http://example.org/person/young"),
                model.createResource("http://example.org/person/middle"),
                model.createResource("http://example.org/person/senior")
            };
            
            String[] names = {"Child", "Young Adult", "Middle Age", "Senior"};
            int[] ages = {15, 25, 45, 70};
            
            for (int i = 0; i < persons.length; i++) {
                persons[i].addProperty(name, names[i]);
                persons[i].addProperty(age, model.createTypedLiteral(ages[i]));
            }
            
            // Query: Working age (18 <= age < 65)
            String sparql = """
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                SELECT ?name ?age WHERE {
                    ?person foaf:name ?name .
                    ?person foaf:age ?age .
                    FILTER(?age >= 18 && ?age < 65)
                }
                ORDER BY ?age
                """;
            
            System.out.println("SPARQL Query:");
            System.out.println(sparql);
            
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                System.out.println("\nWorking age persons:");
                while (results.hasNext()) {
                    QuerySolution solution = results.nextSolution();
                    System.out.println("  " + solution.getLiteral("name").getString() + 
                                     " (age " + solution.getLiteral("age").getInt() + ")");
                }
            }
            
        } finally {
            model.close();
        }
    }

    /**
     * Example 3: String equality comparison.
     * 
     * <p>Filters persons by specific name.</p>
     */
    public static void example3_StringEquals() {
        System.out.println("\n=== Example 3: FILTER with string equals ===");
        
        Model model = FalkorDBModelFactory.createModel("filter_examples");
        
        try {
            // Setup: Add multiple persons
            Property name = model.createProperty("http://xmlns.com/foaf/0.1/name");
            Property email = model.createProperty("http://xmlns.com/foaf/0.1/email");
            
            String[] names = {"Alice", "Bob", "Charlie"};
            String[] emails = {"alice@example.org", "bob@example.org", "charlie@example.org"};
            
            for (int i = 0; i < names.length; i++) {
                Resource person = model.createResource("http://example.org/person/" + names[i].toLowerCase());
                person.addProperty(name, names[i]);
                person.addProperty(email, emails[i]);
            }
            
            // Query: Find Alice's email
            String sparql = """
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                SELECT ?email WHERE {
                    ?person foaf:name ?name .
                    ?person foaf:email ?email .
                    FILTER(?name = "Alice")
                }
                """;
            
            System.out.println("SPARQL Query:");
            System.out.println(sparql);
            
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                System.out.println("\nResult:");
                if (results.hasNext()) {
                    QuerySolution solution = results.nextSolution();
                    System.out.println("  Alice's email: " + solution.getLiteral("email").getString());
                }
            }
            
        } finally {
            model.close();
        }
    }

    /**
     * Example 4: NOT operator for negation.
     * 
     * <p>Finds all adults (NOT age < 18).</p>
     */
    public static void example4_NotOperator() {
        System.out.println("\n=== Example 4: FILTER with NOT operator ===");
        
        Model model = FalkorDBModelFactory.createModel("filter_examples");
        
        try {
            // Setup: Add persons of various ages
            Property age = model.createProperty("http://xmlns.com/foaf/0.1/age");
            Property name = model.createProperty("http://xmlns.com/foaf/0.1/name");
            
            String[] names = {"Child1", "Child2", "Adult1", "Adult2"};
            int[] ages = {10, 15, 25, 35};
            
            for (int i = 0; i < names.length; i++) {
                Resource person = model.createResource("http://example.org/person/" + i);
                person.addProperty(name, names[i]);
                person.addProperty(age, model.createTypedLiteral(ages[i]));
            }
            
            // Query: Find adults using NOT
            String sparql = """
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                SELECT ?name ?age WHERE {
                    ?person foaf:name ?name .
                    ?person foaf:age ?age .
                    FILTER(! (?age < 18))
                }
                ORDER BY ?age
                """;
            
            System.out.println("SPARQL Query:");
            System.out.println(sparql);
            
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                System.out.println("\nAdults (age >= 18):");
                while (results.hasNext()) {
                    QuerySolution solution = results.nextSolution();
                    System.out.println("  " + solution.getLiteral("name").getString() + 
                                     " (age " + solution.getLiteral("age").getInt() + ")");
                }
            }
            
        } finally {
            model.close();
        }
    }

    /**
     * Example 5: OR operator for alternatives.
     * 
     * <p>Finds persons who are either very young or seniors.</p>
     */
    public static void example5_OrOperator() {
        System.out.println("\n=== Example 5: FILTER with OR operator ===");
        
        Model model = FalkorDBModelFactory.createModel("filter_examples");
        
        try {
            // Setup: Add persons across age spectrum
            Property age = model.createProperty("http://xmlns.com/foaf/0.1/age");
            Property name = model.createProperty("http://xmlns.com/foaf/0.1/name");
            
            String[] names = {"Child", "Young Adult", "Middle Age", "Senior"};
            int[] ages = {10, 25, 45, 70};
            
            for (int i = 0; i < names.length; i++) {
                Resource person = model.createResource("http://example.org/person/" + i);
                person.addProperty(name, names[i]);
                person.addProperty(age, model.createTypedLiteral(ages[i]));
            }
            
            // Query: Very young OR senior (dependency age groups)
            String sparql = """
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                SELECT ?name ?age WHERE {
                    ?person foaf:name ?name .
                    ?person foaf:age ?age .
                    FILTER(?age < 18 || ?age > 65)
                }
                ORDER BY ?age
                """;
            
            System.out.println("SPARQL Query:");
            System.out.println(sparql);
            
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                System.out.println("\nDependent age groups:");
                while (results.hasNext()) {
                    QuerySolution solution = results.nextSolution();
                    System.out.println("  " + solution.getLiteral("name").getString() + 
                                     " (age " + solution.getLiteral("age").getInt() + ")");
                }
            }
            
        } finally {
            model.close();
        }
    }

    /**
     * Example 6: Not equals operator.
     * 
     * <p>Finds all persons except Bob.</p>
     */
    public static void example6_NotEquals() {
        System.out.println("\n=== Example 6: FILTER with != operator ===");
        
        Model model = FalkorDBModelFactory.createModel("filter_examples");
        
        try {
            // Setup: Add multiple persons
            Property name = model.createProperty("http://xmlns.com/foaf/0.1/name");
            
            String[] names = {"Alice", "Bob", "Charlie", "David"};
            
            for (String personName : names) {
                Resource person = model.createResource("http://example.org/person/" + personName.toLowerCase());
                person.addProperty(name, personName);
            }
            
            // Query: Everyone except Bob
            String sparql = """
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                SELECT ?name WHERE {
                    ?person foaf:name ?name .
                    FILTER(?name != "Bob")
                }
                ORDER BY ?name
                """;
            
            System.out.println("SPARQL Query:");
            System.out.println(sparql);
            
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                
                System.out.println("\nAll persons except Bob:");
                while (results.hasNext()) {
                    QuerySolution solution = results.nextSolution();
                    System.out.println("  " + solution.getLiteral("name").getString());
                }
            }
            
        } finally {
            model.close();
        }
    }

    /**
     * Main method to run all examples.
     */
    public static void main(String[] args) {
        System.out.println("FILTER Expression Optimization Examples");
        System.out.println("=======================================\n");
        System.out.println("These examples demonstrate automatic query pushdown of FILTER expressions");
        System.out.println("from SPARQL to Cypher WHERE clauses, reducing network round-trips.\n");
        
        example1_SimpleLessThan();
        example2_RangeWithAnd();
        example3_StringEquals();
        example4_NotOperator();
        example5_OrOperator();
        example6_NotEquals();
        
        System.out.println("\n=== All examples completed successfully ===");
    }
}
