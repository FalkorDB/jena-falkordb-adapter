# Batch Writes via Transactions - Examples

This directory contains examples demonstrating the **Batch Writes optimization** in FalkorDB Jena Adapter.

## What is Batch Write Optimization?

By default, Jena's `Graph.add(Triple t)` method is "chatty" - it sends one database command per triple. The Batch Write optimization buffers multiple triple operations during a transaction and flushes them in bulk using Cypher's `UNWIND` clause.

## Files in this Directory

### 1. `BatchWriteExample.java`
A complete Java example demonstrating:
- Bulk loading 1000+ triples efficiently
- Loading RDF files with transactions
- Batch delete operations
- Error handling and rollback

**Run the example:**
```bash
cd jena-falkordb-adapter
mvn compile exec:java -Dexec.mainClass="com.falkordb.samples.BatchWriteExample"
```

### 2. `queries.sparql`
SPARQL Update examples showing:
- INSERT DATA with multiple triples
- DELETE DATA in batches
- UPDATE operations (DELETE + INSERT)
- Bulk construction queries

### 3. `data-example.ttl`
Sample RDF data in Turtle format (~180 triples) demonstrating:
- People with properties
- Social network relationships
- Organizations and employment
- Projects and skills

## How the Optimization Works

### Before (Without Transactions)
```java
// 1000 triples = 1000 database calls
for (Triple t : triples) {
    graph.add(t);  // Each call hits the database immediately
}
```

### After (With Transactions)
```java
// 1000 triples = ~1 database call
model.begin(ReadWrite.WRITE);
try {
    for (Triple t : triples) {
        model.add(t);  // Buffered in memory
    }
    model.commit();  // Single bulk operation using UNWIND
} finally {
    model.end();
}
```

### Generated Cypher (Bulk Operation)
```cypher
UNWIND range(0, size($subjects)-1) AS i
WITH $subjects[i] AS subj, $objects[i] AS obj
MERGE (s:Resource {uri: subj})
MERGE (o:Resource {uri: obj})
MERGE (s)-[r:`predicate`]->(o)
```

## Key Benefits

✅ **Massive Performance Gain**: 100-1000x faster for bulk operations  
✅ **Single Round Trip**: All operations flushed in bulk  
✅ **UNWIND Batching**: Operations grouped by type (literals, types, relationships)  
✅ **Transaction Safety**: Rollback on error, no partial data  
✅ **Batch Size**: Automatically batched in groups of 1000  

## Performance Comparison

| Operation | Without Transaction | With Transaction | Improvement |
|-----------|--------------------|--------------------|-------------|
| 100 triples | 100 round trips | 1 round trip | 100x faster |
| 1000 triples | 1000 round trips | 1 round trip | 1000x faster |
| 10000 triples | 10000 round trips | 10 round trips | 1000x faster |

## Usage Patterns

### Pattern 1: Bulk Load from File
```java
Model model = FalkorDBModelFactory.createModel("myGraph");

model.begin(ReadWrite.WRITE);
try {
    RDFDataMgr.read(model, "data-example.ttl");
    model.commit();  // Bulk flush
} catch (Exception e) {
    model.abort();   // Rollback on error
} finally {
    model.end();
}
```

### Pattern 2: Programmatic Bulk Insert
```java
model.begin(ReadWrite.WRITE);
try {
    for (int i = 0; i < 1000; i++) {
        Resource person = model.createResource("http://example.org/person/" + i);
        person.addProperty(FOAF.name, "Person " + i);
        person.addProperty(RDF.type, FOAF.Person);
    }
    model.commit();
} finally {
    model.end();
}
```

### Pattern 3: Error Handling
```java
model.begin(ReadWrite.WRITE);
try {
    // ... add operations
    
    if (errorCondition) {
        throw new Exception("Validation failed");
    }
    
    model.commit();
} catch (Exception e) {
    model.abort();  // All buffered operations discarded
    logger.error("Transaction rolled back", e);
} finally {
    model.end();
}
```

## Testing

Unit tests: [`FalkorDBTransactionHandlerTest.java`](../../jena-falkordb-adapter/src/test/java/com/falkordb/jena/FalkorDBTransactionHandlerTest.java)
- `testTransactionWithBatchedAdds()`
- `testTransactionAbortDiscardsBufferedOperations()`
- `testNestedTransactionsNotSupported()`
- `testLargeTransactionBatching()`

## Best Practices

1. **Always use transactions for bulk operations**: Wrap multiple adds/removes in `begin()/commit()`
2. **Use try-finally**: Ensure `model.end()` is called even on errors
3. **Monitor batch sizes**: The adapter batches operations in groups of 1000 automatically
4. **Validate before commit**: Check data consistency before calling `commit()`
5. **Use abort on errors**: Call `model.abort()` to discard buffered operations

## Common Mistakes to Avoid

❌ **Don't** add triples one by one without a transaction:
```java
// BAD: 1000 database calls
for (int i = 0; i < 1000; i++) {
    model.add(triple);
}
```

✅ **Do** wrap in a transaction:
```java
// GOOD: ~1 database call
model.begin(ReadWrite.WRITE);
try {
    for (int i = 0; i < 1000; i++) {
        model.add(triple);
    }
    model.commit();
} finally {
    model.end();
}
```

❌ **Don't** forget to call `model.end()`:
```java
// BAD: Resources not cleaned up
model.begin(ReadWrite.WRITE);
model.add(triple);
model.commit();
// Missing model.end()!
```

✅ **Do** use try-finally:
```java
// GOOD: Always cleans up
model.begin(ReadWrite.WRITE);
try {
    model.add(triple);
    model.commit();
} finally {
    model.end();  // Always called
}
```

## Documentation

For complete documentation, see:
- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#batch-writes-via-transactions) - Detailed explanation
- [README.md](../../README.md) - Project overview

## Related Optimizations

- **Query Pushdown**: Efficient SPARQL to Cypher translation
- **Variable Objects**: Query both properties and relationships with UNION
- **Magic Property**: Direct Cypher execution with `falkor:cypher`

See [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md) for details on all optimizations.
