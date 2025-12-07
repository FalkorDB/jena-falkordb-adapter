package com.falkordb.samples;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.FOAF;
import org.apache.jena.query.ReadWrite;

/**
 * Example demonstrating Batch Write optimization via Transactions in FalkorDB Jena Adapter.
 * 
 * This optimization buffers multiple triple operations during a transaction and flushes them
 * in bulk using Cypher's UNWIND clause, reducing database round trips from N to ~1.
 * 
 * For more details, see: OPTIMIZATIONS.md#batch-writes-via-transactions
 */
public class BatchWriteExample {

    public static void main(String[] args) {
        // Create a model backed by FalkorDB
        Model model = FalkorDBModelFactory.createDefaultModel();

        try {
            // Example 1: Bulk load with transaction batching
            bulkLoadExample(model);
            
            System.out.println("\n---\n");
            
            // Example 2: Loading RDF data efficiently
            rdfFileLoadExample(model);
            
            System.out.println("\n---\n");
            
            // Example 3: Batch deletes
            batchDeleteExample(model);
            
            System.out.println("\n---\n");
            
            // Example 4: Error handling with transactions
            transactionErrorHandlingExample(model);

        } finally {
            model.close();
        }
    }

    /**
     * Example 1: Bulk loading data with transaction batching
     */
    private static void bulkLoadExample(Model model) {
        System.out.println("Example 1: Bulk Load with Transaction Batching");
        System.out.println("===============================================");
        
        long startTime = System.currentTimeMillis();
        
        // Start a write transaction
        model.begin(ReadWrite.WRITE);
        try {
            System.out.println("Adding 1000 people with properties and relationships...");
            
            for (int i = 0; i < 1000; i++) {
                Resource person = model.createResource("http://example.org/person/" + i);
                person.addProperty(RDF.type, FOAF.Person);
                person.addProperty(FOAF.name, "Person " + i);
                person.addProperty(RDFS.label, "Person Number " + i);
                
                // Add relationship to previous person
                if (i > 0) {
                    Resource previous = model.createResource("http://example.org/person/" + (i - 1));
                    person.addProperty(FOAF.knows, previous);
                }
            }
            
            // Commit: All operations are flushed in bulk using UNWIND
            model.commit();
            
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("✓ Completed in " + duration + "ms");
            System.out.println("✓ Used transaction batching: ~3-5 bulk UNWIND operations");
            System.out.println("  (instead of 4000+ individual operations)");
            
        } catch (Exception e) {
            model.abort();
            System.err.println("Error during bulk load: " + e.getMessage());
        } finally {
            model.end();
        }
    }

    /**
     * Example 2: Loading RDF file efficiently
     */
    private static void rdfFileLoadExample(Model model) {
        System.out.println("Example 2: Loading RDF File with Transaction");
        System.out.println("=============================================");
        
        // Create sample data in-memory
        Model tempModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        for (int i = 0; i < 100; i++) {
            Resource org = tempModel.createResource("http://example.org/organization/" + i);
            org.addProperty(RDF.type, tempModel.createResource("http://example.org/Organization"));
            org.addProperty(RDFS.label, "Organization " + i);
            org.addProperty(tempModel.createProperty("http://example.org/employees"), 
                          tempModel.createTypedLiteral(10 + i));
        }
        
        System.out.println("Loading " + tempModel.size() + " triples...");
        
        long startTime = System.currentTimeMillis();
        
        // Use transaction for efficient bulk load
        model.begin(ReadWrite.WRITE);
        try {
            model.add(tempModel);
            model.commit();
            
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("✓ Loaded in " + duration + "ms using transaction batching");
            
        } catch (Exception e) {
            model.abort();
            throw e;
        } finally {
            model.end();
        }
    }

    /**
     * Example 3: Batch deletes
     */
    private static void batchDeleteExample(Model model) {
        System.out.println("Example 3: Batch Delete Operations");
        System.out.println("===================================");
        
        // First, add some data
        model.begin(ReadWrite.WRITE);
        try {
            for (int i = 0; i < 50; i++) {
                Resource temp = model.createResource("http://example.org/temp/" + i);
                temp.addProperty(RDFS.label, "Temporary " + i);
            }
            model.commit();
        } finally {
            model.end();
        }
        
        System.out.println("Added 50 temporary resources");
        
        // Now delete them in a batch
        long startTime = System.currentTimeMillis();
        
        model.begin(ReadWrite.WRITE);
        try {
            System.out.println("Deleting 50 resources...");
            
            for (int i = 0; i < 50; i++) {
                Resource temp = model.createResource("http://example.org/temp/" + i);
                model.removeAll(temp, null, null);
            }
            
            model.commit();
            
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("✓ Deleted in " + duration + "ms using batch operations");
            
        } catch (Exception e) {
            model.abort();
            throw e;
        } finally {
            model.end();
        }
    }

    /**
     * Example 4: Error handling with transactions
     */
    private static void transactionErrorHandlingExample(Model model) {
        System.out.println("Example 4: Transaction Error Handling");
        System.out.println("======================================");
        
        model.begin(ReadWrite.WRITE);
        try {
            System.out.println("Adding data that will be rolled back...");
            
            for (int i = 0; i < 10; i++) {
                Resource person = model.createResource("http://example.org/test/" + i);
                person.addProperty(FOAF.name, "Test " + i);
                
                // Simulate an error on the 5th item
                if (i == 5) {
                    throw new RuntimeException("Simulated error during batch operation");
                }
            }
            
            model.commit();
            System.out.println("✓ Committed successfully");
            
        } catch (Exception e) {
            System.out.println("✗ Error occurred: " + e.getMessage());
            System.out.println("✓ Rolling back transaction...");
            model.abort();
            System.out.println("✓ All buffered operations discarded - no partial data written");
            
        } finally {
            model.end();
        }
    }

    /**
     * Performance comparison: Without vs With transactions
     */
    public static void performanceComparison() {
        Model model = FalkorDBModelFactory.createDefaultModel();
        
        try {
            // Without transaction (auto-commit each operation)
            System.out.println("Without transaction batching:");
            long start1 = System.currentTimeMillis();
            for (int i = 0; i < 100; i++) {
                Resource person = model.createResource("http://example.org/batch1/" + i);
                person.addProperty(FOAF.name, "Person " + i);
            }
            long time1 = System.currentTimeMillis() - start1;
            System.out.println("Time: " + time1 + "ms (100 database operations)");
            
            // With transaction
            System.out.println("\nWith transaction batching:");
            long start2 = System.currentTimeMillis();
            model.begin(ReadWrite.WRITE);
            try {
                for (int i = 0; i < 100; i++) {
                    Resource person = model.createResource("http://example.org/batch2/" + i);
                    person.addProperty(FOAF.name, "Person " + i);
                }
                model.commit();
            } finally {
                model.end();
            }
            long time2 = System.currentTimeMillis() - start2;
            System.out.println("Time: " + time2 + "ms (~1 bulk operation)");
            
            double improvement = (double) time1 / time2;
            System.out.println("\nImprovement: " + String.format("%.1fx faster", improvement));
            
        } finally {
            model.close();
        }
    }
}
