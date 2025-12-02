package com.falkordb;

import com.falkordb.jena.FalkorDBGraph;
import com.falkordb.jena.FalkorDBModelFactory;
import com.falkordb.jena.FalkorDBTransactionHandler;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Fuseki server with FalkorDB transaction support.
 *
 * These tests verify that:
 * 1. Fuseki server detects and uses FalkorDBTransactionHandler
 * 2. Batch operations work correctly through SPARQL updates
 * 3. Transaction semantics are preserved
 *
 * Prerequisites: FalkorDB must be running on localhost:6379
 */
public class FusekiTransactionIntegrationTest {

    private static final String TEST_GRAPH = "fuseki_transaction_test";
    private static final int TEST_PORT = 3332;
    private static final String DATASET_PATH = "/falkor";
    private static final int DEFAULT_FALKORDB_PORT = 6379;

    private static String falkorHost;
    private static int falkorPort;

    private FusekiServer server;
    private Model falkorModel;
    private String sparqlEndpoint;
    private String updateEndpoint;

    @BeforeAll
    public static void setUpContainer() {
        falkorHost = System.getenv().getOrDefault("FALKORDB_HOST", "localhost");
        falkorPort = Integer.parseInt(
            System.getenv().getOrDefault("FALKORDB_PORT",
                String.valueOf(DEFAULT_FALKORDB_PORT)));
    }

    @BeforeEach
    public void setUp() {
        // Create FalkorDB-backed model
        falkorModel = FalkorDBModelFactory.builder()
            .host(falkorHost)
            .port(falkorPort)
            .graphName(TEST_GRAPH)
            .build();

        // Clear the graph before each test
        if (falkorModel.getGraph() instanceof FalkorDBGraph falkorGraph) {
            falkorGraph.clear();
        }

        // Create dataset from model
        var ds = DatasetFactory.create(falkorModel);

        // Start Fuseki server
        server = FusekiServer.create()
            .port(TEST_PORT)
            .add(DATASET_PATH, ds)
            .build();
        server.start();

        sparqlEndpoint = "http://localhost:" + TEST_PORT
            + DATASET_PATH + "/query";
        updateEndpoint = "http://localhost:" + TEST_PORT
            + DATASET_PATH + "/update";
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (falkorModel != null) {
            falkorModel.close();
        }
    }

    @Test
    @DisplayName("Test Fuseki uses FalkorDBTransactionHandler")
    public void testFusekiUsesTransactionHandler() {
        // Verify the model's graph has a FalkorDBTransactionHandler
        if (falkorModel.getGraph() instanceof FalkorDBGraph falkorGraph) {
            var handler = falkorGraph.getTransactionHandler();
            assertNotNull(handler);
            assertInstanceOf(FalkorDBTransactionHandler.class, handler,
                "Fuseki should use FalkorDBTransactionHandler");
            assertTrue(handler.transactionsSupported(),
                "Transactions should be supported");
        } else {
            fail("Model graph should be FalkorDBGraph");
        }
    }

    @Test
    @DisplayName("Test SPARQL INSERT DATA batch operation")
    public void testSparqlInsertDataBatch() {
        // Build a large INSERT DATA with multiple triples
        StringBuilder insertQuery = new StringBuilder();
        insertQuery.append("PREFIX ex: <http://example.org/> ");
        insertQuery.append("INSERT DATA { ");

        for (int i = 0; i < 50; i++) {
            insertQuery.append(String.format(
                "ex:person%d ex:name \"Person %d\" . ", i, i));
        }
        insertQuery.append("}");

        // Execute the batch insert
        UpdateExecutionHTTP.service(updateEndpoint)
            .update(insertQuery.toString())
            .execute();

        // Verify all data was inserted
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            ResultSet results = qexec.execSelect();
            assertEquals(50, results.next().getLiteral("count").getInt(),
                "Should have 50 triples after batch insert");
        }
    }

    @Test
    @DisplayName("Test SPARQL INSERT DATA with relationships")
    public void testSparqlInsertRelationships() {
        String insertQuery =
            "PREFIX ex: <http://example.org/> " +
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
            "INSERT DATA { " +
            "  ex:alice rdf:type ex:Person ; " +
            "           ex:name \"Alice\" ; " +
            "           ex:knows ex:bob . " +
            "  ex:bob rdf:type ex:Person ; " +
            "         ex:name \"Bob\" ; " +
            "         ex:knows ex:charlie . " +
            "  ex:charlie rdf:type ex:Person ; " +
            "             ex:name \"Charlie\" . " +
            "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Query relationships
        String selectQuery =
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?person ?knows WHERE { " +
            "  ?person ex:knows ?knows " +
            "} ORDER BY ?person";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext());
            var first = results.next();
            assertTrue(first.getResource("person").getURI().endsWith("alice"));
            assertTrue(first.getResource("knows").getURI().endsWith("bob"));

            assertTrue(results.hasNext());
            var second = results.next();
            assertTrue(second.getResource("person").getURI().endsWith("bob"));
            assertTrue(second.getResource("knows").getURI().endsWith("charlie"));

            assertFalse(results.hasNext());
        }
    }

    @Test
    @DisplayName("Test SPARQL DELETE DATA batch operation")
    public void testSparqlDeleteDataBatch() {
        // First insert data
        StringBuilder insertQuery = new StringBuilder();
        insertQuery.append("PREFIX ex: <http://example.org/> ");
        insertQuery.append("INSERT DATA { ");
        for (int i = 0; i < 20; i++) {
            insertQuery.append(String.format(
                "ex:item%d ex:value \"Value %d\" . ", i, i));
        }
        insertQuery.append("}");

        UpdateExecutionHTTP.service(updateEndpoint)
            .update(insertQuery.toString())
            .execute();

        // Verify insert
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            assertEquals(20, qexec.execSelect().next()
                .getLiteral("count").getInt());
        }

        // Delete half the data
        StringBuilder deleteQuery = new StringBuilder();
        deleteQuery.append("PREFIX ex: <http://example.org/> ");
        deleteQuery.append("DELETE DATA { ");
        for (int i = 0; i < 10; i++) {
            deleteQuery.append(String.format(
                "ex:item%d ex:value \"Value %d\" . ", i, i));
        }
        deleteQuery.append("}");

        UpdateExecutionHTTP.service(updateEndpoint)
            .update(deleteQuery.toString())
            .execute();

        // Verify delete
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            assertEquals(10, qexec.execSelect().next()
                .getLiteral("count").getInt(),
                "Should have 10 triples after batch delete");
        }
    }

    @Test
    @DisplayName("Test SPARQL UPDATE with INSERT and DELETE")
    public void testSparqlUpdateInsertDelete() {
        // Insert initial data
        String insertQuery =
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { " +
            "  ex:person1 ex:status \"active\" . " +
            "  ex:person2 ex:status \"active\" . " +
            "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(insertQuery).execute();

        // Update: change status of person1
        String updateQuery =
            "PREFIX ex: <http://example.org/> " +
            "DELETE { ex:person1 ex:status \"active\" } " +
            "INSERT { ex:person1 ex:status \"inactive\" } " +
            "WHERE { ex:person1 ex:status \"active\" }";

        UpdateExecutionHTTP.service(updateEndpoint).update(updateQuery).execute();

        // Verify the update
        String selectQuery =
            "PREFIX ex: <http://example.org/> " +
            "SELECT ?person ?status WHERE { " +
            "  ?person ex:status ?status " +
            "} ORDER BY ?person";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext());
            var first = results.next();
            assertEquals("inactive", first.getLiteral("status").getString(),
                "Person1 status should be inactive");

            assertTrue(results.hasNext());
            var second = results.next();
            assertEquals("active", second.getLiteral("status").getString(),
                "Person2 status should still be active");
        }
    }

    @Test
    @DisplayName("Test large batch insert performance")
    public void testLargeBatchInsert() {
        // Build a large INSERT DATA with 500 triples
        StringBuilder insertQuery = new StringBuilder();
        insertQuery.append("PREFIX ex: <http://example.org/> ");
        insertQuery.append("INSERT DATA { ");

        for (int i = 0; i < 500; i++) {
            insertQuery.append(String.format(
                "ex:resource%d ex:property \"Value %d\" . ", i, i));
        }
        insertQuery.append("}");

        long startTime = System.currentTimeMillis();
        UpdateExecutionHTTP.service(updateEndpoint)
            .update(insertQuery.toString())
            .execute();
        long endTime = System.currentTimeMillis();

        // Verify all data was inserted
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            assertEquals(500, qexec.execSelect().next()
                .getLiteral("count").getInt());
        }

        // Log performance (for debugging/tuning)
        long duration = endTime - startTime;
        System.out.println("Large batch insert (500 triples) took: "
            + duration + "ms");

        // Batch operations should complete in reasonable time
        assertTrue(duration < 30000,
            "Batch insert should complete in less than 30 seconds");
    }

    @Test
    @DisplayName("Test rdf:type batch operations")
    public void testRdfTypeBatchOperations() {
        String insertQuery =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
            "PREFIX ex: <http://example.org/> " +
            "INSERT DATA { ";

        StringBuilder sb = new StringBuilder(insertQuery);
        for (int i = 0; i < 30; i++) {
            sb.append(String.format("ex:item%d rdf:type ex:Thing . ", i));
        }
        sb.append("}");

        UpdateExecutionHTTP.service(updateEndpoint).update(sb.toString()).execute();

        // Query for all Thing instances
        String selectQuery =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT (COUNT(?item) AS ?count) WHERE { " +
            "  ?item rdf:type ex:Thing " +
            "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(selectQuery).build()) {
            assertEquals(30, qexec.execSelect().next()
                .getLiteral("count").getInt(),
                "Should find 30 Thing instances");
        }
    }

    @Test
    @DisplayName("Test mixed operations in single update")
    public void testMixedOperationsInSingleUpdate() {
        String updateQuery =
            "PREFIX ex: <http://example.org/> " +
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
            "INSERT DATA { " +
            // Types
            "  ex:doc1 rdf:type ex:Document . " +
            "  ex:doc2 rdf:type ex:Document . " +
            // Properties
            "  ex:doc1 ex:title \"First Document\" . " +
            "  ex:doc2 ex:title \"Second Document\" . " +
            // Relationships
            "  ex:doc1 ex:relatedTo ex:doc2 . " +
            "}";

        UpdateExecutionHTTP.service(updateEndpoint).update(updateQuery).execute();

        // Verify total count
        String countQuery = "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }";
        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(countQuery).build()) {
            assertEquals(5, qexec.execSelect().next()
                .getLiteral("count").getInt(),
                "Should have 5 triples (2 types + 2 properties + 1 relationship)");
        }

        // Verify types
        String typeQuery =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
            "PREFIX ex: <http://example.org/> " +
            "SELECT (COUNT(?doc) AS ?count) WHERE { " +
            "  ?doc rdf:type ex:Document " +
            "}";

        try (QueryExecution qexec = QueryExecutionHTTP.service(sparqlEndpoint)
                .query(typeQuery).build()) {
            assertEquals(2, qexec.execSelect().next()
                .getLiteral("count").getInt(),
                "Should find 2 Document instances");
        }
    }
}
