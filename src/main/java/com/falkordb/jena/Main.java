package com.falkordb.jena;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

public class Main {
    
    public static void main(String[] args) {
        System.out.println("FalkorDB-Jena Adapter Demo");
        System.out.println("==========================");
        
        try {
            // Create a model using JFalkorDB
            Model model = FalkorDBModelFactory.createModel("demo_graph");
            
            System.out.println("✓ Connected to FalkorDB using JFalkorDB driver");
            
            // Create some sample RDF data
            Resource person = model.createResource("http://example.org/person/john");
            Property name = model.createProperty("http://example.org/name");
            Property age = model.createProperty("http://example.org/age");
            
            // Add triples
            person.addProperty(RDF.type, model.createResource("http://example.org/Person"));
            person.addProperty(name, "John Doe");
            person.addProperty(age, model.createTypedLiteral(30));
            
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
            System.err.println("Make sure FalkorDB is running on localhost:6379");
            e.printStackTrace();
        }
    }
}
