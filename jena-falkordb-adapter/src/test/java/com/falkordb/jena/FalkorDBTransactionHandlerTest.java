package com.falkordb.jena;

import com.falkordb.FalkorDB;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FalkorDBTransactionHandler.
 *
 * Prerequisites: FalkorDB must be running on localhost:6379
 * Run: docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
 */
public class FalkorDBTransactionHandlerTest {

    private static final String TEST_GRAPH = "transaction_test_graph";
    private FalkorDBGraph graph;
    private Model model;

    @BeforeEach
    public void setUp() {
        graph = new FalkorDBGraph("localhost", 6379, TEST_GRAPH);
        graph.clear();
        model = ModelFactory.createModelForGraph(graph);
    }

    @AfterEach
    public void tearDown() {
        if (model != null) {
            model.close();
        }
    }

    @Test
    @DisplayName("Test transaction handler is supported")
    public void testTransactionsSupported() {
        var handler = graph.getTransactionHandler();
        assertNotNull(handler, "Transaction handler should not be null");
        assertTrue(handler.transactionsSupported(),
            "Transactions should be supported");
        assertInstanceOf(FalkorDBTransactionHandler.class, handler,
            "Handler should be FalkorDBTransactionHandler");
    }

    @Test
    @DisplayName("Test begin transaction")
    public void testBeginTransaction() {
        var handler = (FalkorDBTransactionHandler) graph.getTransactionHandler();
        assertFalse(handler.isInTransaction(),
            "Should not be in transaction initially");

        handler.begin();
        assertTrue(handler.isInTransaction(),
            "Should be in transaction after begin");

        // Clean up
        handler.abort();
    }

    @Test
    @DisplayName("Test commit transaction")
    public void testCommitTransaction() {
        var handler = (FalkorDBTransactionHandler) graph.getTransactionHandler();

        handler.begin();
        assertTrue(handler.isInTransaction());

        handler.commit();
        assertFalse(handler.isInTransaction(),
            "Should not be in transaction after commit");
    }

    @Test
    @DisplayName("Test abort transaction")
    public void testAbortTransaction() {
        var handler = (FalkorDBTransactionHandler) graph.getTransactionHandler();

        handler.begin();
        assertTrue(handler.isInTransaction());

        handler.abort();
        assertFalse(handler.isInTransaction(),
            "Should not be in transaction after abort");
    }

    @Test
    @DisplayName("Test nested transaction not supported")
    public void testNestedTransactionNotSupported() {
        var handler = graph.getTransactionHandler();
        handler.begin();

        assertThrows(UnsupportedOperationException.class, handler::begin,
            "Nested transactions should throw exception");

        // Clean up
        handler.abort();
    }

    @Test
    @DisplayName("Test commit without begin throws exception")
    public void testCommitWithoutBegin() {
        var handler = graph.getTransactionHandler();
        assertThrows(UnsupportedOperationException.class, handler::commit,
            "Commit without begin should throw exception");
    }

    @Test
    @DisplayName("Test abort without begin throws exception")
    public void testAbortWithoutBegin() {
        var handler = graph.getTransactionHandler();
        assertThrows(UnsupportedOperationException.class, handler::abort,
            "Abort without begin should throw exception");
    }

    @Test
    @DisplayName("Test buffered adds are flushed on commit")
    public void testBufferedAddsOnCommit() {
        var handler = graph.getTransactionHandler();

        // Start transaction
        handler.begin();

        // Add triples within transaction
        var subject = model.createResource("http://test.example.org/person1");
        var name = model.createProperty("http://test.example.org/name");
        var age = model.createProperty("http://test.example.org/age");

        subject.addProperty(name, "John Doe");
        subject.addProperty(age, model.createTypedLiteral(30));

        // Commit transaction
        handler.commit();

        // Verify data was persisted
        assertEquals(2, model.size(), "Model should have 2 triples after commit");
        assertTrue(model.contains(subject, name),
            "Model should contain name triple");
        assertTrue(model.contains(subject, age),
            "Model should contain age triple");
    }

    @Test
    @DisplayName("Test buffered adds are discarded on abort")
    public void testBufferedAddsDiscardedOnAbort() {
        var handler = graph.getTransactionHandler();

        // Start transaction
        handler.begin();

        // Add triples within transaction
        var subject = model.createResource("http://test.example.org/person1");
        var name = model.createProperty("http://test.example.org/name");

        subject.addProperty(name, "John Doe");

        // Abort transaction
        handler.abort();

        // Verify data was not persisted
        assertEquals(0, model.size(), "Model should be empty after abort");
    }

    @Test
    @DisplayName("Test non-transactional adds work immediately")
    public void testNonTransactionalAdds() {
        // Without transaction, adds should work immediately
        var subject = model.createResource("http://test.example.org/person1");
        var name = model.createProperty("http://test.example.org/name");

        subject.addProperty(name, "John Doe");

        assertEquals(1, model.size(), "Model should have 1 triple immediately");
    }

    @Test
    @DisplayName("Test batch literal property adds")
    public void testBatchLiteralAdds() {
        var handler = graph.getTransactionHandler();

        handler.begin();

        // Add multiple literal properties
        for (int i = 0; i < 100; i++) {
            var subject = model.createResource(
                "http://test.example.org/person" + i);
            var name = model.createProperty("http://test.example.org/name");
            subject.addProperty(name, "Person " + i);
        }

        handler.commit();

        assertEquals(100, model.size(),
            "Model should have 100 triples after batch add");
    }

    @Test
    @DisplayName("Test batch relationship adds")
    public void testBatchRelationshipAdds() {
        var handler = graph.getTransactionHandler();

        handler.begin();

        // Create multiple relationships
        var knows = model.createProperty("http://test.example.org/knows");
        for (int i = 0; i < 50; i++) {
            var person1 = model.createResource(
                "http://test.example.org/personA" + i);
            var person2 = model.createResource(
                "http://test.example.org/personB" + i);
            person1.addProperty(knows, person2);
        }

        handler.commit();

        assertEquals(50, model.size(),
            "Model should have 50 relationship triples");
    }

    @Test
    @DisplayName("Test batch rdf:type adds")
    public void testBatchTypeAdds() {
        var handler = graph.getTransactionHandler();

        handler.begin();

        // Create multiple typed resources
        var personType = model.createResource("http://test.example.org/Person");
        for (int i = 0; i < 50; i++) {
            var person = model.createResource(
                "http://test.example.org/person" + i);
            person.addProperty(RDF.type, personType);
        }

        handler.commit();

        // Query for all Person instances
        var iter = model.listStatements(null, RDF.type, personType);
        int count = 0;
        while (iter.hasNext()) {
            iter.next();
            count++;
        }

        assertEquals(50, count, "Should find 50 Person instances");
    }

    @Test
    @DisplayName("Test mixed batch operations")
    public void testMixedBatchOperations() {
        var handler = graph.getTransactionHandler();

        handler.begin();

        // Add different types of triples
        var personType = model.createResource("http://test.example.org/Person");
        var name = model.createProperty("http://test.example.org/name");
        var knows = model.createProperty("http://test.example.org/knows");

        for (int i = 0; i < 10; i++) {
            var person = model.createResource(
                "http://test.example.org/person" + i);
            person.addProperty(RDF.type, personType);
            person.addProperty(name, "Person " + i);

            if (i > 0) {
                var prevPerson = model.createResource(
                    "http://test.example.org/person" + (i - 1));
                person.addProperty(knows, prevPerson);
            }
        }

        handler.commit();

        // 10 types + 10 names + 9 relationships = 29
        assertEquals(29, model.size(),
            "Model should have 29 triples after mixed batch");
    }

    @Test
    @DisplayName("Test execute with runnable")
    public void testExecuteWithRunnable() {
        var handler = graph.getTransactionHandler();

        handler.execute(() -> {
            var subject = model.createResource("http://test.example.org/person1");
            var name = model.createProperty("http://test.example.org/name");
            subject.addProperty(name, "John Doe");
        });

        assertEquals(1, model.size(),
            "Model should have 1 triple after execute");
    }

    @Test
    @DisplayName("Test execute aborts on exception")
    public void testExecuteAbortsOnException() {
        var handler = graph.getTransactionHandler();

        assertThrows(RuntimeException.class, () -> {
            handler.execute(() -> {
                var subject = model.createResource(
                    "http://test.example.org/person1");
                var name = model.createProperty("http://test.example.org/name");
                subject.addProperty(name, "John Doe");

                throw new RuntimeException("Test exception");
            });
        });

        // Transaction should be aborted, no data persisted
        assertEquals(0, model.size(),
            "Model should be empty after failed execute");
    }

    @Test
    @DisplayName("Test calculate returns value")
    public void testCalculateReturnsValue() {
        var handler = graph.getTransactionHandler();

        Integer result = handler.calculate(() -> {
            var subject = model.createResource("http://test.example.org/person1");
            var name = model.createProperty("http://test.example.org/name");
            subject.addProperty(name, "John Doe");
            return 42;
        });

        assertEquals(42, result, "Calculate should return the value");
        assertEquals(1, model.size(),
            "Model should have 1 triple after calculate");
    }

    @Test
    @DisplayName("Test buffered deletes on commit")
    public void testBufferedDeletesOnCommit() {
        // First add some data
        var subject = model.createResource("http://test.example.org/person1");
        var name = model.createProperty("http://test.example.org/name");
        var age = model.createProperty("http://test.example.org/age");

        subject.addProperty(name, "John Doe");
        subject.addProperty(age, model.createTypedLiteral(30));

        assertEquals(2, model.size());

        // Now delete in a transaction
        var handler = graph.getTransactionHandler();
        handler.begin();

        // Remove age property
        subject.removeAll(age);

        handler.commit();

        assertEquals(1, model.size(),
            "Model should have 1 triple after delete");
        assertFalse(model.contains(subject, age),
            "Age property should be removed");
    }

    @Test
    @DisplayName("Test buffered deletes discarded on abort")
    public void testBufferedDeletesDiscardedOnAbort() {
        // First add some data
        var subject = model.createResource("http://test.example.org/person1");
        var name = model.createProperty("http://test.example.org/name");

        subject.addProperty(name, "John Doe");
        assertEquals(1, model.size());

        // Try to delete in a transaction but abort
        var handler = graph.getTransactionHandler();
        handler.begin();

        subject.removeAll(name);

        handler.abort();

        // Data should still be there
        assertEquals(1, model.size(),
            "Model should still have 1 triple after abort");
    }
}
