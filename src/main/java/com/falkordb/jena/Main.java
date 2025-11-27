package com.falkordb.jena;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple demo application that shows basic usage of the FalkorDB-Jena adapter.
 * <p>
 * This class is intended for local manual testing and examples; it connects to
 * a FalkorDB instance, creates a small RDF dataset and prints the results.
 */
public final class Main {
    /** Logger instance for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

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
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("FalkorDB-Jena Adapter Demo");
            LOGGER.info("===============================================");
        }

        try {
            // Create a model using FalkorDB
            Model model = FalkorDBModelFactory.createModel("demo_graph");

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("✓ Connected to FalkorDB using FalkorDB driver");
            }
            // Create some sample RDF data and print initial statements
            Property name = model.createProperty("http://example.org/name");
            Property age = model.createProperty("http://example.org/age");
            Resource person = setupSampleData(model, name, age);
            printAllStatements(model);
            queryTypes(model);
            queryPropertiesOfPerson(model, person);
            queryResourcesWithName(model, name);

            // Delete operations
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("\n=== Deleting: Remove age property ===");
            }
            person.removeAll(age);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("✓ Age property removed");
                LOGGER.info("Model size after delete: {}", model.size());
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                    "\n=== Querying: List statements after deletion ===");
            }
            StmtIterator afterDeleteIter = model.listStatements();
            while (afterDeleteIter.hasNext()) {
                Statement stmt = afterDeleteIter.nextStatement();
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("  {}", stmt);
                }
            }

            // Add another resource to demonstrate more complex queries
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("\n=== Adding: Another person ===");
            }
            Resource person2 = model.createResource(
                "http://example.org/person/jane"
            );
            Resource personType = model.createResource(
                "http://example.org/Person"
            );
            person2.addProperty(RDF.type, personType);
            person2.addProperty(name, "Jane Smith");
            Property email = model.createProperty(
                "http://example.org/email"
            );
            person2.addProperty(email, "jane@example.org");
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("✓ Added Jane with email");
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("\n=== Querying: Find all Persons ===");
            }
            StmtIterator personsIter = model.listStatements(
                null,
                RDF.type,
                personType
            );
            while (personsIter.hasNext()) {
                Statement stmt = personsIter.nextStatement();
                Resource personRes = stmt.getSubject();
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("  Person: {}", personRes.getURI());
                }

                // Get name if available
                Statement nameStmt = personRes.getProperty(name);
                if (nameStmt != null && LOGGER.isInfoEnabled()) {
                    LOGGER.info("    Name: {}", nameStmt.getObject());
                }
            }

            // Delete a complete resource
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("\n=== Deleting: Remove Jane completely ===");
            }
            person2.removeProperties();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("✓ All properties of Jane removed");
                LOGGER.info("Final model size: {}", model.size());
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("\n=== Final State: All remaining statements ===");
            }
            StmtIterator finalIter = model.listStatements();
            while (finalIter.hasNext()) {
                Statement stmt = finalIter.nextStatement();
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("  {}", stmt);
                }
            }

            model.close();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("\n✓ Demo completed successfully");
            }

        } catch (RuntimeException e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("✗ Error: {}", e.getMessage());
                LOGGER.error("Make sure FalkorDB is running on localhost:{}",
                    FalkorDBModelFactory.DEFAULT_PORT);
            }
            e.printStackTrace();
        }
    }

    private static Resource setupSampleData(final Model model,
            final Property name, final Property age) {
        Resource person = model.createResource(
            "http://example.org/person/john"
        );
        person.addProperty(RDF.type, model.createResource(
            "http://example.org/Person"
        ));
        person.addProperty(name, "John Doe");
        person.addProperty(age, model.createTypedLiteral(SAMPLE_AGE));

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("✓ Added sample RDF data");
            LOGGER.info("Model size: {}", model.size());
        }
        return person;
    }

    private static void printAllStatements(final Model model) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("\n=== Querying: List All Statements ===");
        }
        StmtIterator iter = model.listStatements();
        while (iter.hasNext()) {
            Statement stmt = iter.nextStatement();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("{}", stmt);
            }
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Total statements: {}", model.size());
        }
    }

    private static void queryTypes(final Model model) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("\n=== Querying: Find all rdf:type triples ===");
        }
        StmtIterator typeIter = model.listStatements(
            null,
            RDF.type,
            (Resource) null
        );
        while (typeIter.hasNext()) {
            Statement stmt = typeIter.nextStatement();
            String s = stmt.getSubject().toString();
            String o = stmt.getObject().toString();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("  {} is a {}", s, o);
            }
        }
    }

    private static void queryPropertiesOfPerson(final Model model,
            final Resource person) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("\n=== Querying: Find all properties of John ===");
        }
        StmtIterator johnIter = model.listStatements(
            person,
            null,
            (Resource) null
        );
        while (johnIter.hasNext()) {
            Statement stmt = johnIter.nextStatement();
            String p = stmt.getPredicate().toString();
            String o = stmt.getObject().toString();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("  {} -> {}", p, o);
            }
        }
    }

    private static void queryResourcesWithName(final Model model,
            final Property name) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                "\n=== Querying: Find all resources with name property ==="
            );
        }
        StmtIterator nameIter = model.listStatements(
            null,
            name,
            (Resource) null
        );
        while (nameIter.hasNext()) {
            Statement stmt = nameIter.nextStatement();
            String s = stmt.getSubject().toString();
            String o = stmt.getObject().toString();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("  {} has name: {}", s, o);
            }
        }
    }
}
