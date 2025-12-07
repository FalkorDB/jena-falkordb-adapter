package com.falkordb.samples;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.FOAF;

/**
 * Example demonstrating Variable Object optimization in FalkorDB Jena Adapter.
 * 
 * This optimization allows querying both properties (literals) and relationships (URIs)
 * with a single SPARQL query using variable objects. The adapter generates a UNION query
 * to fetch both types of values efficiently.
 * 
 * For more details, see: OPTIMIZATIONS.md#variable-object-support
 */
public class VariableObjectExample {

    public static void main(String[] args) {
        // Create a model backed by FalkorDB
        Model model = FalkorDBModelFactory.createDefaultModel();

        try {
            // Example 1: Query both properties and relationships
            setupMixedData(model);
            queryMixedData(model);

            System.out.println("\n---\n");

            // Example 2: Query with variable subject and object
            setupNamedPeople(model);
            queryAllNames(model);

            System.out.println("\n---\n");

            // Example 3: Query relationships only
            setupFriendships(model);
            queryFriendships(model);

        } finally {
            model.close();
        }
    }

    /**
     * Example 1: Setup data with both properties and relationships
     */
    private static void setupMixedData(Model model) {
        System.out.println("Example 1: Querying Mixed Properties and Relationships");
        System.out.println("======================================================");

        org.apache.jena.rdf.model.Resource alice = model.createResource("http://example.org/person/alice");
        org.apache.jena.rdf.model.Resource bob = model.createResource("http://example.org/person/bob");
        org.apache.jena.rdf.model.Property knows = model.createProperty("http://xmlns.com/foaf/0.1/knows");

        // Alice has a name property (literal)
        alice.addProperty(FOAF.name, "Alice");
        // Alice knows Bob (relationship)
        alice.addProperty(knows, bob);

        System.out.println("Added: Alice with name 'Alice' (property)");
        System.out.println("Added: Alice knows Bob (relationship)");
    }

    /**
     * Example 1: Query for all values (both properties and relationships)
     */
    private static void queryMixedData(Model model) {
        // This query uses variable object optimization
        // The adapter generates a UNION query to fetch both literal properties
        // and relationship targets in a single database round-trip
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?value WHERE {
                <http://example.org/person/alice> foaf:knows ?value .
            }
            """;

        System.out.println("\nSPARQL Query:");
        System.out.println(sparql);
        System.out.println("Results:");

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                System.out.println("  - " + solution.get("value"));
            }
        }

        System.out.println("\nNote: The query automatically retrieves Bob (relationship target)");
        System.out.println("using a single UNION query that checks both edges and properties.");
    }

    /**
     * Example 2: Setup multiple people with names
     */
    private static void setupNamedPeople(Model model) {
        System.out.println("Example 2: Variable Subject and Object");
        System.out.println("======================================");

        org.apache.jena.rdf.model.Resource alice = model.createResource("http://example.org/person/alice");
        org.apache.jena.rdf.model.Resource bob = model.createResource("http://example.org/person/bob");
        org.apache.jena.rdf.model.Resource charlie = model.createResource("http://example.org/person/charlie");

        alice.addProperty(FOAF.name, "Alice");
        bob.addProperty(FOAF.name, "Bob");
        charlie.addProperty(FOAF.name, "Charlie");

        System.out.println("Added: Three people with names");
    }

    /**
     * Example 2: Query all names (variable subject and object)
     */
    private static void queryAllNames(Model model) {
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?person ?name WHERE {
                ?person foaf:name ?name .
            }
            ORDER BY ?name
            """;

        System.out.println("\nSPARQL Query:");
        System.out.println(sparql);
        System.out.println("Results:");

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                String person = solution.getResource("person").getLocalName();
                String name = solution.getLiteral("name").getString();
                System.out.println("  - " + person + " is named " + name);
            }
        }
    }

    /**
     * Example 3: Setup friendship relationships
     */
    private static void setupFriendships(Model model) {
        System.out.println("Example 3: Querying Relationships Only");
        System.out.println("======================================");

        org.apache.jena.rdf.model.Resource alice = model.createResource("http://example.org/person/alice");
        org.apache.jena.rdf.model.Resource bob = model.createResource("http://example.org/person/bob");
        org.apache.jena.rdf.model.Resource charlie = model.createResource("http://example.org/person/charlie");
        org.apache.jena.rdf.model.Property knows = model.createProperty("http://xmlns.com/foaf/0.1/knows");

        alice.addProperty(knows, bob);
        alice.addProperty(knows, charlie);

        System.out.println("Added: Alice knows Bob and Charlie");
    }

    /**
     * Example 3: Query friendships (relationships only)
     */
    private static void queryFriendships(Model model) {
        String sparql = """
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            SELECT ?friend WHERE {
                <http://example.org/person/alice> foaf:knows ?friend .
            }
            """;

        System.out.println("\nSPARQL Query:");
        System.out.println(sparql);
        System.out.println("Results:");

        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                String friend = solution.getResource("friend").getLocalName();
                System.out.println("  - Alice knows " + friend);
            }
        }
    }
}
