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

## Using curl with Fuseki

You can use curl to load data and execute queries via the Fuseki SPARQL endpoint.

### Prerequisites

First, start FalkorDB and Fuseki:

```bash
# Start FalkorDB
docker-compose -f docker-compose-tracing.yaml up -d

# In another terminal, start Fuseki (from project root)
mvn clean install -DskipTests
java -jar jena-fuseki-falkordb/target/jena-fuseki-falkordb-0.2.0-SNAPSHOT.jar \
  --config jena-fuseki-falkordb/src/main/resources/config-falkordb.ttl
```

The Fuseki server will start on `http://localhost:3330` with the default endpoint at `/falkor`.

### Loading Data with curl

**Load the sample data file (`data-example.ttl`):**

```bash
curl -X POST \
  -H "Content-Type: text/turtle" \
  --data-binary @samples/batch-writes/data-example.ttl \
  http://localhost:3330/falkor/data
```

This loads all ~180 triples from the sample file in a single efficient batch operation.

### Executing Queries with curl

**Example 1: Insert multiple triples**

```bash
curl -X POST \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

INSERT DATA {
    ex:person1 a foaf:Person ;
               foaf:name "Alice" ;
               foaf:age 30 .
    
    ex:person2 a foaf:Person ;
               foaf:name "Bob" ;
               foaf:age 35 .
    
    ex:person3 a foaf:Person ;
               foaf:name "Charlie" ;
               foaf:age 28 .
    
    ex:person1 foaf:knows ex:person2 , ex:person3 .
    ex:person2 foaf:knows ex:person3 .
}' \
  http://localhost:3330/falkor/update
```

**Example 2: Delete multiple triples**

```bash
curl -X POST \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

DELETE DATA {
    ex:person1 foaf:knows ex:person2 .
    ex:person1 foaf:knows ex:person3 .
    ex:person1 foaf:name "Alice" .
}' \
  http://localhost:3330/falkor/update
```

**Example 3: Update operation (delete + insert)**

```bash
curl -X POST \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

DELETE {
    ex:person1 foaf:name ?oldName .
}
INSERT {
    ex:person1 foaf:name "Alice Smith" .
}
WHERE {
    ex:person1 foaf:name ?oldName .
}' \
  http://localhost:3330/falkor/update
```

**Example 4: Bulk construction query**

```bash
curl -X POST \
  -H "Content-Type: application/sparql-update" \
  --data 'PREFIX foaf: <http://xmlns.com/foaf/0.1/>
PREFIX ex: <http://example.org/>

INSERT {
    ?person1 foaf:knows ?person2 .
}
WHERE {
    ?person1 a foaf:Person .
    ?person2 a foaf:Person .
    FILTER(?person1 != ?person2)
}' \
  http://localhost:3330/falkor/update
```

**Query the loaded data:**

```bash
curl -G \
  --data-urlencode "query=PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?person ?name ?age
WHERE {
    ?person a foaf:Person ;
            foaf:name ?name ;
            foaf:age ?age .
}
ORDER BY ?name" \
  http://localhost:3330/falkor/query
```

**Count all triples:**

```bash
curl -G \
  --data-urlencode "query=SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }" \
  http://localhost:3330/falkor/query
```

## Documentation

For complete documentation, see:
- [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#batch-writes-via-transactions) - Detailed explanation
- [README.md](../../README.md) - Project overview
- [Fuseki GETTING_STARTED.md](../../jena-fuseki-falkordb/GETTING_STARTED.md) - Fuseki setup and usage

## Related Optimizations

- **Query Pushdown**: Efficient SPARQL to Cypher translation
- **Variable Objects**: Query both properties and relationships with UNION
- **Magic Property**: Direct Cypher execution with `falkor:cypher`

See [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md) for details on all optimizations.
