package com.falkordb.jena;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;

/**
 * Simple demo application that shows basic usage of the FalkorDB-Jena adapter.
 * <p>
 * This class is intended for local manual testing and examples; it connects to
 * a FalkorDB instance, creates a small RDF dataset and prints the results.
 */
public final class Main {
    /** Sample age used in the demo person resource. */
    private static final int SAMPLE_AGE = 30;

    /** Prevent instantiation of this utility/demo class. */
    private Main() {
        throw new AssertionError("No instances");
    }

    /**
     * Demo entry point.
     *
     * @param args command line arguments (ignored)
     */
    public static void main(final String[] args) {
        System.out.println("FalkorDB-Jena Adapter Demo");
        System.out.println("===============================================");

        try {
            // Create a model using FalkorDB
            Model model = FalkorDBModelFactory.createModel("demo_graph");

            System.out.println("✓ Connected to FalkorDB using FalkorDB driver");

            // Create some sample RDF data
            Resource person = model.createResource(
                    "http://example.org/person/john");
            Property name = model.createProperty("http://example.org/name");
            Property age = model.createProperty("http://example.org/age");

            // Add triples
            person.addProperty(
                    RDF.type,
                    model.createResource("http://example.org/Person"));
            person.addProperty(name, "John Doe");
            person.addProperty(age, model.createTypedLiteral(SAMPLE_AGE));

            System.out.println("✓ Added sample RDF data");
            System.out.println("Model size: " + model.size());

            // List all statements
            System.out.println("\n=== Querying: List All Statements ===");
            StmtIterator iter = model.listStatements();
            while (iter.hasNext()) {
                Statement stmt = iter.nextStatement();
                System.out.println(stmt);
            }
            System.out.println("Total statements: " + model.size());

            // Query specific patterns
            System.out.println("\n=== Querying: Find all rdf:type triples ===");
            StmtIterator typeIter = model.listStatements(
                null,
                RDF.type,
                (Resource) null
            );
            while (typeIter.hasNext()) {
                Statement stmt = typeIter.nextStatement();
                String s = stmt.getSubject().toString();
                String o = stmt.getObject().toString();
                System.out.println("  " + s + " is a " + o);
            }

            System.out.println("\n=== Querying: Find all properties of John ===");
            StmtIterator johnIter = model.listStatements(
                person,
                null,
                (Resource) null
            );
            while (johnIter.hasNext()) {
                Statement stmt = johnIter.nextStatement();
                String p = stmt.getPredicate().toString();
                String o = stmt.getObject().toString();
                System.out.println("  " + p + " -> " + o);
            }

            System.out.println("\n=== Querying: Find all resources with name property ===");
            StmtIterator nameIter = model.listStatements(
                null,
                name,
                (Resource) null
            );
            while (nameIter.hasNext()) {
                Statement stmt = nameIter.nextStatement();
                String s = stmt.getSubject().toString();
                String o = stmt.getObject().toString();
                System.out.println("  " + s + " has name: " + o);
            }

            // Delete operations
            System.out.println("\n=== Deleting: Remove age property ===");
            person.removeAll(age);
            System.out.println("✓ Age property removed");
            System.out.println("Model size after delete: " + model.size());

            System.out.println("\n=== Querying: List statements after deletion ===");
            StmtIterator afterDeleteIter = model.listStatements();
            while (afterDeleteIter.hasNext()) {
                Statement stmt = afterDeleteIter.nextStatement();
                System.out.println("  " + stmt);
            }

            // Add another resource to demonstrate more complex queries
            System.out.println("\n=== Adding: Another person ===");
            Resource person2 = model.createResource("http://example.org/person/jane");
            person2.addProperty(RDF.type, model.createResource("http://example.org/Person"));
            person2.addProperty(name, "Jane Smith");
            Property email = model.createProperty("http://example.org/email");
            person2.addProperty(email, "jane@example.org");
            System.out.println("✓ Added Jane with email");

            System.out.println("\n=== Querying: Find all Persons ===");
            Resource personType = model.createResource(
                "http://example.org/Person"
            );
            StmtIterator personsIter = model.listStatements(
                null,
                RDF.type,
                personType
            );
            while (personsIter.hasNext()) {
                Statement stmt = personsIter.nextStatement();
                Resource personRes = stmt.getSubject();
                System.out.println("  Person: " + personRes.getURI());

                // Get name if available
                Statement nameStmt = personRes.getProperty(name);
                if (nameStmt != null) {
                    System.out.println("    Name: " + nameStmt.getObject());
                }
            }

            // Delete a complete resource
            System.out.println("\n=== Deleting: Remove Jane completely ===");
            person2.removeProperties();
            System.out.println("✓ All properties of Jane removed");
            System.out.println("Final model size: " + model.size());

            System.out.println("\n=== Final State: All remaining statements ===");
            StmtIterator finalIter = model.listStatements();
            while (finalIter.hasNext()) {
                Statement stmt = finalIter.nextStatement();
                System.out.println("  " + stmt);
            }

            model.close();
            System.out.println("\n✓ Demo completed successfully");

        } catch (Exception e) {
            System.err.println("✗ Error: " + e.getMessage());
            System.err.println(
                "Make sure FalkorDB is running on localhost:" +
                FalkorDBModelFactory.DEFAULT_PORT
            );
            e.printStackTrace();
        }
    }
}
