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
public class Main {
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
        System.out.println("==========================");

        try {
            // Create a model using JFalkorDB
            Model model = FalkorDBModelFactory.createModel("demo_graph");

            System.out.println(
                "✓ Connected to FalkorDB using JFalkorDB driver"
            );

            // Create some sample RDF data
            Resource person = model.createResource(
                "http://example.org/person/john"
            );
            Property name = model.createProperty("http://example.org/name");
            Property age = model.createProperty("http://example.org/age");

            // Add triples
            person.addProperty(
                RDF.type,
                model.createResource("http://example.org/Person")
            );
            person.addProperty(name, "John Doe");
            person.addProperty(age, model.createTypedLiteral(SAMPLE_AGE));

            System.out.println("✓ Added sample RDF data");
            System.out.println("Model size: " + model.size());

            // List all statements
            StmtIterator iter = model.listStatements();
            System.out.println("\nRDF Statements:");
            while (iter.hasNext()) {
                Statement stmt = iter.nextStatement();
                System.out.println(stmt);
            }

            model.close();
            System.out.println("\n✓ Demo completed successfully");

        } catch (Exception e) {
            System.err.println("✗ Error: " + e.getMessage());
            System.err.println("Make sure FalkorDB is running on localhost:" + FalkorDBModelFactory.DEFAULT_PORT);
            e.printStackTrace();
        }
    }
}
