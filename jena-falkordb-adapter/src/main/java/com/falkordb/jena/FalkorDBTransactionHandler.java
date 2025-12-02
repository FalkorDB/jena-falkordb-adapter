package com.falkordb.jena;

import com.falkordb.jena.tracing.TracedGraph;
import com.falkordb.jena.tracing.TracingUtil;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.TransactionHandlerBase;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction handler for FalkorDB-backed graphs.
 *
 * <p>This handler implements batch operations by buffering triples during a
 * transaction and flushing them in bulk using Cypher's UNWIND for efficient
 * writes. This significantly improves performance compared to individual
 * triple-by-triple operations.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Buffers add and delete operations during transactions</li>
 *   <li>Uses Cypher UNWIND for efficient batch inserts/deletes</li>
 *   <li>Full OpenTelemetry tracing support</li>
 *   <li>Thread-safe transaction state management</li>
 * </ul>
 */
public final class FalkorDBTransactionHandler extends TransactionHandlerBase {

    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        FalkorDBTransactionHandler.class);

    /** The FalkorDB graph instance. */
    private final TracedGraph graph;

    /** The graph name for tracing. */
    private final String graphName;

    /** Buffer for triples to be added. */
    private List<Triple> addBuffer;

    /** Buffer for triples to be deleted. */
    private List<Triple> deleteBuffer;

    /** Whether a transaction is currently active. */
    private volatile boolean inTransaction = false;

    /** Tracer for OpenTelemetry. */
    private final Tracer tracer;

    /** Maximum batch size for UNWIND operations. */
    private static final int MAX_BATCH_SIZE = 1000;

    /** Attribute key for operation type. */
    private static final AttributeKey<String> ATTR_OPERATION =
        AttributeKey.stringKey("falkordb.operation");

    /** Attribute key for graph name. */
    private static final AttributeKey<String> ATTR_GRAPH_NAME =
        AttributeKey.stringKey("falkordb.graph_name");

    /** Attribute key for batch size. */
    private static final AttributeKey<Long> ATTR_BATCH_SIZE =
        AttributeKey.longKey("falkordb.batch_size");

    /** Attribute key for triple count. */
    private static final AttributeKey<Long> ATTR_TRIPLE_COUNT =
        AttributeKey.longKey("rdf.triple_count");

    /**
     * Callback interface for executing immediate (non-transactional) adds.
     */
    @FunctionalInterface
    interface ImmediateAddCallback {
        /**
         * Perform an immediate add of a single triple.
         * @param triple the triple to add
         */
        void add(Triple triple);
    }

    /** Callback for immediate adds when not in transaction. */
    private final ImmediateAddCallback immediateAddCallback;

    /**
     * Callback interface for executing immediate (non-transactional) deletes.
     */
    @FunctionalInterface
    interface ImmediateDeleteCallback {
        /**
         * Perform an immediate delete of a single triple.
         * @param triple the triple to delete
         */
        void delete(Triple triple);
    }

    /** Callback for immediate deletes when not in transaction. */
    private final ImmediateDeleteCallback immediateDeleteCallback;

    /**
     * Create a transaction handler for the given graph.
     *
     * @param graph the traced graph instance
     * @param graphName the graph name for tracing
     * @param immediateAddCallback callback for non-transactional adds
     * @param immediateDeleteCallback callback for non-transactional deletes
     */
    public FalkorDBTransactionHandler(
            final TracedGraph graph,
            final String graphName,
            final ImmediateAddCallback immediateAddCallback,
            final ImmediateDeleteCallback immediateDeleteCallback) {
        this.graph = graph;
        this.graphName = graphName;
        this.immediateAddCallback = immediateAddCallback;
        this.immediateDeleteCallback = immediateDeleteCallback;
        this.tracer = TracingUtil.getTracer(TracingUtil.SCOPE_FALKORDB_GRAPH);
    }

    @Override
    public boolean transactionsSupported() {
        return true;
    }

    @Override
    public void begin() {
        Span span = tracer.spanBuilder("FalkorDBTransaction.begin")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "begin")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            if (inTransaction) {
                throw new UnsupportedOperationException(
                    "Nested transactions are not supported");
            }
            inTransaction = true;
            addBuffer = new ArrayList<>();
            deleteBuffer = new ArrayList<>();
            span.setStatus(StatusCode.OK);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Transaction started on graph: {}", graphName);
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public void commit() {
        Span span = tracer.spanBuilder("FalkorDBTransaction.commit")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "commit")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            if (!inTransaction) {
                throw new UnsupportedOperationException(
                    "No transaction in progress");
            }

            int addCount = addBuffer != null ? addBuffer.size() : 0;
            int deleteCount = deleteBuffer != null ? deleteBuffer.size() : 0;

            span.setAttribute(ATTR_TRIPLE_COUNT, (long) (addCount + deleteCount));

            // Flush all buffered operations
            flushAdds();
            flushDeletes();

            inTransaction = false;
            addBuffer = null;
            deleteBuffer = null;

            span.setStatus(StatusCode.OK);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Transaction committed on graph: {} "
                    + "(adds: {}, deletes: {})", graphName, addCount,
                    deleteCount);
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            // Abort on error
            try {
                abort();
            } catch (Exception abortException) {
                e.addSuppressed(abortException);
            }
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public void abort() {
        Span span = tracer.spanBuilder("FalkorDBTransaction.abort")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "abort")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            if (!inTransaction) {
                throw new UnsupportedOperationException(
                    "No transaction in progress");
            }

            int addCount = addBuffer != null ? addBuffer.size() : 0;
            int deleteCount = deleteBuffer != null ? deleteBuffer.size() : 0;

            // Discard buffers without executing
            inTransaction = false;
            addBuffer = null;
            deleteBuffer = null;

            span.setStatus(StatusCode.OK);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Transaction aborted on graph: {} "
                    + "(discarded adds: {}, discarded deletes: {})",
                    graphName, addCount, deleteCount);
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Buffer an add operation or execute immediately if not in transaction.
     *
     * @param triple the triple to add
     */
    public void bufferAdd(final Triple triple) {
        if (inTransaction) {
            addBuffer.add(triple);
        } else {
            // Non-transactional add: execute immediately
            immediateAddCallback.add(triple);
        }
    }

    /**
     * Buffer a delete operation or execute immediately if not in transaction.
     *
     * @param triple the triple to delete
     */
    public void bufferDelete(final Triple triple) {
        if (inTransaction) {
            deleteBuffer.add(triple);
        } else {
            // Non-transactional delete: execute immediately
            immediateDeleteCallback.delete(triple);
        }
    }

    /**
     * Check if a transaction is currently in progress.
     *
     * @return true if a transaction is active
     */
    public boolean isInTransaction() {
        return inTransaction;
    }

    /**
     * Flush all buffered add operations using efficient bulk Cypher queries.
     */
    private void flushAdds() {
        if (addBuffer == null || addBuffer.isEmpty()) {
            return;
        }

        Span span = tracer.spanBuilder("FalkorDBTransaction.flushAdds")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "flush_adds")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .setAttribute(ATTR_TRIPLE_COUNT, (long) addBuffer.size())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Group triples by type for efficient processing
            List<Triple> literalTriples = new ArrayList<>();
            List<Triple> typeTriples = new ArrayList<>();
            List<Triple> relationshipTriples = new ArrayList<>();

            for (Triple triple : addBuffer) {
                if (triple.getObject().isLiteral()) {
                    literalTriples.add(triple);
                } else if (triple.getPredicate().getURI()
                        .equals(RDF.type.getURI())) {
                    typeTriples.add(triple);
                } else {
                    relationshipTriples.add(triple);
                }
            }

            // Execute batch operations for each type
            if (!literalTriples.isEmpty()) {
                flushLiteralAdds(literalTriples);
            }
            if (!typeTriples.isEmpty()) {
                flushTypeAdds(typeTriples);
            }
            if (!relationshipTriples.isEmpty()) {
                flushRelationshipAdds(relationshipTriples);
            }

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Flush literal property additions using UNWIND.
     */
    private void flushLiteralAdds(final List<Triple> triples) {
        // Process in batches
        for (int i = 0; i < triples.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, triples.size());
            List<Triple> batch = triples.subList(i, end);
            flushLiteralAddBatch(batch);
        }
    }

    /**
     * Flush a batch of literal property additions.
     */
    private void flushLiteralAddBatch(final List<Triple> batch) {
        Span span = tracer.spanBuilder("FalkorDBTransaction.flushLiteralBatch")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(ATTR_OPERATION, "batch_literal_add")
            .setAttribute(ATTR_BATCH_SIZE, (long) batch.size())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Group by predicate for efficient property setting
            // Use parallel lists since FalkorDB doesn't handle List<Map> params
            Map<String, List<String>> predicateSubjects = new HashMap<>();
            Map<String, List<String>> predicateValues = new HashMap<>();

            for (Triple triple : batch) {
                String predicate = nodeToString(triple.getPredicate());
                predicateSubjects.computeIfAbsent(predicate,
                    k -> new ArrayList<>());
                predicateValues.computeIfAbsent(predicate,
                    k -> new ArrayList<>());

                predicateSubjects.get(predicate).add(
                    nodeToString(triple.getSubject()));
                predicateValues.get(predicate).add(
                    triple.getObject().getLiteralLexicalForm());
            }

            // Execute batch for each predicate
            for (String predicate : predicateSubjects.keySet()) {
                List<String> subjects = predicateSubjects.get(predicate);
                List<String> values = predicateValues.get(predicate);

                Map<String, Object> params = new HashMap<>();
                params.put("subjects", subjects);
                params.put("values", values);

                // Use UNWIND with range to iterate parallel arrays
                // Sanitize predicate to prevent Cypher injection
                String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
                String cypher = """
                    UNWIND range(0, size($subjects)-1) AS i
                    WITH $subjects[i] AS subj, $values[i] AS val
                    MERGE (s:Resource {uri: subj})
                    SET s.`%s` = val""".formatted(sanitizedPredicate);

                graph.query(cypher, params);
            }

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Flush rdf:type additions using UNWIND.
     */
    private void flushTypeAdds(final List<Triple> triples) {
        for (int i = 0; i < triples.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, triples.size());
            List<Triple> batch = triples.subList(i, end);
            flushTypeAddBatch(batch);
        }
    }

    /**
     * Flush a batch of rdf:type additions.
     */
    private void flushTypeAddBatch(final List<Triple> batch) {
        Span span = tracer.spanBuilder("FalkorDBTransaction.flushTypeBatch")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(ATTR_OPERATION, "batch_type_add")
            .setAttribute(ATTR_BATCH_SIZE, (long) batch.size())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Group by type for efficient label setting
            Map<String, List<String>> typeGroups = new HashMap<>();

            for (Triple triple : batch) {
                String type = nodeToString(triple.getObject());
                typeGroups.computeIfAbsent(type, k -> new ArrayList<>());
                typeGroups.get(type).add(nodeToString(triple.getSubject()));
            }

            // Execute batch for each type
            for (Map.Entry<String, List<String>> entry
                    : typeGroups.entrySet()) {
                String type = entry.getKey();
                List<String> subjects = entry.getValue();

                Map<String, Object> params = new HashMap<>();
                params.put("subjects", subjects);

                // Use UNWIND with dynamic label
                // Sanitize type to prevent Cypher injection
                String sanitizedType = sanitizeCypherIdentifier(type);
                String cypher = """
                    UNWIND $subjects AS uri
                    MERGE (s:Resource {uri: uri})
                    SET s:`%s`""".formatted(sanitizedType);

                graph.query(cypher, params);
            }

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Flush relationship additions using UNWIND.
     */
    private void flushRelationshipAdds(final List<Triple> triples) {
        for (int i = 0; i < triples.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, triples.size());
            List<Triple> batch = triples.subList(i, end);
            flushRelationshipAddBatch(batch);
        }
    }

    /**
     * Flush a batch of relationship additions.
     */
    private void flushRelationshipAddBatch(final List<Triple> batch) {
        Span span = tracer.spanBuilder(
                "FalkorDBTransaction.flushRelationshipBatch")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(ATTR_OPERATION, "batch_relationship_add")
            .setAttribute(ATTR_BATCH_SIZE, (long) batch.size())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Group by predicate for efficient relationship creation
            // Use parallel lists since FalkorDB doesn't handle List<Map> params
            Map<String, List<String>> predicateSubjects = new HashMap<>();
            Map<String, List<String>> predicateObjects = new HashMap<>();

            for (Triple triple : batch) {
                String predicate = nodeToString(triple.getPredicate());
                predicateSubjects.computeIfAbsent(predicate,
                    k -> new ArrayList<>());
                predicateObjects.computeIfAbsent(predicate,
                    k -> new ArrayList<>());

                predicateSubjects.get(predicate).add(
                    nodeToString(triple.getSubject()));
                predicateObjects.get(predicate).add(
                    nodeToString(triple.getObject()));
            }

            // Execute batch for each predicate
            for (String predicate : predicateSubjects.keySet()) {
                List<String> subjects = predicateSubjects.get(predicate);
                List<String> objects = predicateObjects.get(predicate);

                Map<String, Object> params = new HashMap<>();
                params.put("subjects", subjects);
                params.put("objects", objects);

                // Use UNWIND with range to iterate parallel arrays
                // Sanitize predicate to prevent Cypher injection
                String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
                String cypher = """
                    UNWIND range(0, size($subjects)-1) AS i
                    WITH $subjects[i] AS subj, $objects[i] AS obj
                    MERGE (s:Resource {uri: subj})
                    MERGE (o:Resource {uri: obj})
                    MERGE (s)-[r:`%s`]->(o)""".formatted(sanitizedPredicate);

                graph.query(cypher, params);
            }

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Flush all buffered delete operations using efficient bulk Cypher.
     */
    private void flushDeletes() {
        if (deleteBuffer == null || deleteBuffer.isEmpty()) {
            return;
        }

        Span span = tracer.spanBuilder("FalkorDBTransaction.flushDeletes")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ATTR_OPERATION, "flush_deletes")
            .setAttribute(ATTR_GRAPH_NAME, graphName)
            .setAttribute(ATTR_TRIPLE_COUNT, (long) deleteBuffer.size())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Group triples by type
            List<Triple> literalTriples = new ArrayList<>();
            List<Triple> typeTriples = new ArrayList<>();
            List<Triple> relationshipTriples = new ArrayList<>();

            for (Triple triple : deleteBuffer) {
                if (triple.getObject().isLiteral()) {
                    literalTriples.add(triple);
                } else if (triple.getPredicate().getURI()
                        .equals(RDF.type.getURI())) {
                    typeTriples.add(triple);
                } else {
                    relationshipTriples.add(triple);
                }
            }

            // Execute batch operations for each type
            if (!literalTriples.isEmpty()) {
                flushLiteralDeletes(literalTriples);
            }
            if (!typeTriples.isEmpty()) {
                flushTypeDeletes(typeTriples);
            }
            if (!relationshipTriples.isEmpty()) {
                flushRelationshipDeletes(relationshipTriples);
            }

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Flush literal property deletions using UNWIND.
     */
    private void flushLiteralDeletes(final List<Triple> triples) {
        for (int i = 0; i < triples.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, triples.size());
            List<Triple> batch = triples.subList(i, end);
            flushLiteralDeleteBatch(batch);
        }
    }

    /**
     * Flush a batch of literal property deletions.
     */
    private void flushLiteralDeleteBatch(final List<Triple> batch) {
        Span span = tracer.spanBuilder(
                "FalkorDBTransaction.flushLiteralDeleteBatch")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(ATTR_OPERATION, "batch_literal_delete")
            .setAttribute(ATTR_BATCH_SIZE, (long) batch.size())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Group by predicate, tracking subjects and values
            // Use parallel lists since FalkorDB doesn't handle List<Map> params
            Map<String, List<String>> predicateSubjects = new HashMap<>();
            Map<String, List<String>> predicateValues = new HashMap<>();

            for (Triple triple : batch) {
                String predicate = nodeToString(triple.getPredicate());
                predicateSubjects.computeIfAbsent(predicate,
                    k -> new ArrayList<>());
                predicateValues.computeIfAbsent(predicate,
                    k -> new ArrayList<>());

                predicateSubjects.get(predicate).add(
                    nodeToString(triple.getSubject()));
                predicateValues.get(predicate).add(
                    triple.getObject().getLiteralLexicalForm());
            }

            // Execute batch for each predicate
            for (String predicate : predicateSubjects.keySet()) {
                List<String> subjects = predicateSubjects.get(predicate);
                List<String> values = predicateValues.get(predicate);

                Map<String, Object> params = new HashMap<>();
                params.put("subjects", subjects);
                params.put("values", values);

                // Use UNWIND with range to iterate parallel arrays
                // Only remove property if value matches exactly
                // Sanitize predicate to prevent Cypher injection
                String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
                String cypher = """
                    UNWIND range(0, size($subjects)-1) AS i
                    WITH $subjects[i] AS subj, $values[i] AS val
                    MATCH (s:Resource {uri: subj})
                    WHERE s.`%s` = val
                    REMOVE s.`%s`""".formatted(sanitizedPredicate,
                        sanitizedPredicate);

                graph.query(cypher, params);
            }

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Flush rdf:type deletions using UNWIND.
     */
    private void flushTypeDeletes(final List<Triple> triples) {
        for (int i = 0; i < triples.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, triples.size());
            List<Triple> batch = triples.subList(i, end);
            flushTypeDeleteBatch(batch);
        }
    }

    /**
     * Flush a batch of rdf:type deletions.
     */
    private void flushTypeDeleteBatch(final List<Triple> batch) {
        Span span = tracer.spanBuilder(
                "FalkorDBTransaction.flushTypeDeleteBatch")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(ATTR_OPERATION, "batch_type_delete")
            .setAttribute(ATTR_BATCH_SIZE, (long) batch.size())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Group by type
            Map<String, List<String>> typeGroups = new HashMap<>();

            for (Triple triple : batch) {
                String type = nodeToString(triple.getObject());
                typeGroups.computeIfAbsent(type, k -> new ArrayList<>());
                typeGroups.get(type).add(nodeToString(triple.getSubject()));
            }

            // Execute batch for each type
            for (Map.Entry<String, List<String>> entry
                    : typeGroups.entrySet()) {
                String type = entry.getKey();
                List<String> subjects = entry.getValue();

                Map<String, Object> params = new HashMap<>();
                params.put("subjects", subjects);

                // Use UNWIND for batch label removal
                // Sanitize type to prevent Cypher injection
                String sanitizedType = sanitizeCypherIdentifier(type);
                String cypher = """
                    UNWIND $subjects AS uri
                    MATCH (s:Resource:`%s` {uri: uri})
                    REMOVE s:`%s`""".formatted(sanitizedType, sanitizedType);

                graph.query(cypher, params);
            }

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Flush relationship deletions using UNWIND.
     */
    private void flushRelationshipDeletes(final List<Triple> triples) {
        for (int i = 0; i < triples.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, triples.size());
            List<Triple> batch = triples.subList(i, end);
            flushRelationshipDeleteBatch(batch);
        }
    }

    /**
     * Flush a batch of relationship deletions.
     */
    private void flushRelationshipDeleteBatch(final List<Triple> batch) {
        Span span = tracer.spanBuilder(
                "FalkorDBTransaction.flushRelationshipDeleteBatch")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(ATTR_OPERATION, "batch_relationship_delete")
            .setAttribute(ATTR_BATCH_SIZE, (long) batch.size())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Group by predicate
            // Use parallel lists since FalkorDB doesn't handle List<Map> params
            Map<String, List<String>> predicateSubjects = new HashMap<>();
            Map<String, List<String>> predicateObjects = new HashMap<>();

            for (Triple triple : batch) {
                String predicate = nodeToString(triple.getPredicate());
                predicateSubjects.computeIfAbsent(predicate,
                    k -> new ArrayList<>());
                predicateObjects.computeIfAbsent(predicate,
                    k -> new ArrayList<>());

                predicateSubjects.get(predicate).add(
                    nodeToString(triple.getSubject()));
                predicateObjects.get(predicate).add(
                    nodeToString(triple.getObject()));
            }

            // Execute batch for each predicate
            for (String predicate : predicateSubjects.keySet()) {
                List<String> subjects = predicateSubjects.get(predicate);
                List<String> objects = predicateObjects.get(predicate);

                Map<String, Object> params = new HashMap<>();
                params.put("subjects", subjects);
                params.put("objects", objects);

                // Use UNWIND with range to iterate parallel arrays
                // Sanitize predicate to prevent Cypher injection
                String sanitizedPredicate = sanitizeCypherIdentifier(predicate);
                String cypher = """
                    UNWIND range(0, size($subjects)-1) AS i
                    WITH $subjects[i] AS subj, $objects[i] AS obj
                    MATCH (s:Resource {uri: subj})-[r:`%s`]->
                    (o:Resource {uri: obj})
                    DELETE r""".formatted(sanitizedPredicate);

                graph.query(cypher, params);
            }

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Sanitize a string for use as a Cypher identifier (label, relationship
     * type, or property name).
     *
     * <p>This method escapes backticks and other special characters to
     * prevent Cypher injection attacks when the value is used in backtick-
     * quoted identifiers.</p>
     *
     * @param value the value to sanitize
     * @return the sanitized value safe for use in Cypher identifiers
     */
    private String sanitizeCypherIdentifier(final String value) {
        if (value == null) {
            return "";
        }
        // Escape backticks by doubling them (`` -> ````)
        // Also remove any null characters which could cause issues
        return value.replace("`", "``").replace("\0", "");
    }

    /**
     * Convert a Jena Node to its string representation.
     */
    private String nodeToString(final org.apache.jena.graph.Node node) {
        if (node.isURI()) {
            return node.getURI();
        } else if (node.isLiteral()) {
            return node.getLiteralLexicalForm();
        } else if (node.isBlank()) {
            return "_:" + node.getBlankNodeLabel();
        }
        return node.toString();
    }
}
