# FalkorDB Jena Adapter - Sample Code and Examples

This directory contains comprehensive examples demonstrating all optimizations available in the FalkorDB Jena Adapter.

## Directory Structure

```
samples/
├── README.md (this file)
├── batch-writes/          # Transaction batching for bulk operations
├── query-pushdown/        # SPARQL to Cypher translation
├── variable-objects/      # Query both properties and relationships
├── optional-patterns/     # Efficient optional data retrieval
├── filter-expressions/    # FILTER expression optimization
└── magic-property/        # Direct Cypher execution
```

## Optimizations Covered

### 1. Batch Writes via Transactions
**Location:** [`batch-writes/`](batch-writes/)

Buffers multiple triple operations and flushes them in bulk using Cypher's UNWIND, reducing database round trips from N to ~1.

**Performance:** 100-1000x faster for bulk operations

**Examples:**
- Bulk loading 1000+ triples
- RDF file loading with transactions
- Batch delete operations
- Error handling and rollback

### 2. Query Pushdown (SPARQL to Cypher)
**Location:** [`query-pushdown/`](query-pushdown/)

Translates SPARQL Basic Graph Patterns to native Cypher queries, executing in a single database operation.

**Performance:** Nx-N²x fewer database calls

**Examples:**
- Variable predicates (`?s ?p ?o`)
- Closed-chain patterns (mutual references)
- Type-based queries
- Friends of friends pattern
- Multi-hop traversal

### 3. Variable Objects Optimization
**Location:** [`variable-objects/`](variable-objects/)

When a variable object could be either a literal or URI, generates a UNION query to fetch both types efficiently.

**Performance:** 2x fewer round trips

**Examples:**
- Querying both properties and relationships
- Mixed data type retrieval
- Variable subject and object patterns

### 4. OPTIONAL Patterns
**Location:** [`optional-patterns/`](optional-patterns/)

Translates SPARQL OPTIONAL patterns to Cypher OPTIONAL MATCH, returning all required matches with NULL for missing optional data in a single query.

**Performance:** Nx fewer round trips (avoids N+1 queries)

**Examples:**
- Basic OPTIONAL with relationships
- Multiple OPTIONAL clauses
- OPTIONAL with literal properties
- OPTIONAL with multiple triples
- Concrete subjects with OPTIONAL
- OPTIONAL with FILTER

### 5. FILTER Expressions
**Location:** [`filter-expressions/`](filter-expressions/)

Automatically pushes FILTER expressions down to Cypher WHERE clauses, eliminating client-side filtering.

**Performance:** Reduces data transfer, enables database indexes, eliminates client-side filtering

**Examples:**
- Numeric comparisons (`<`, `<=`, `>`, `>=`, `=`, `!=`)
- Logical operators (`AND`, `OR`, `NOT`)
- String equality and inequality
- Numeric range filters
- Complex combined expressions
- FILTER with UNION queries

### 6. Magic Property (Direct Cypher)
**Location:** [`magic-property/`](magic-property/)

Allows direct Cypher execution within SPARQL for maximum control and performance.

**Use Cases:** Variable length paths, aggregations, complex patterns

**Examples:**
- Simple Cypher queries
- Aggregations and counting
- Path patterns
- Complex WHERE conditions

## Quick Start

Each subdirectory contains:
- **Java Example** (`*Example.java`): Complete working code with multiple use cases
- **SPARQL Queries** (`queries.sparql`): Query patterns and examples
- **Sample Data** (`data-example.ttl`): RDF data in Turtle format
- **README** (`README.md`): Detailed documentation and usage guide

### Running the Examples

1. **Start FalkorDB:**
   ```bash
   docker run -p 6379:6379 -d falkordb/falkordb:latest
   ```

2. **Build the project:**
   ```bash
   cd jena-falkordb-adapter
   mvn clean install
   ```

3. **Run an example:**
   ```bash
   # Batch Writes
   mvn exec:java -Dexec.mainClass="com.falkordb.samples.BatchWriteExample"
   
   # Query Pushdown
   mvn exec:java -Dexec.mainClass="com.falkordb.samples.QueryPushdownExample"
   
   # Variable Objects
   mvn exec:java -Dexec.mainClass="com.falkordb.samples.VariableObjectExample"
   
   # OPTIONAL Patterns
   mvn exec:java -Dexec.mainClass="com.falkordb.samples.OptionalPatternsExample"
   
   # Magic Property
   mvn exec:java -Dexec.mainClass="com.falkordb.samples.MagicPropertyExample"
   ```

4. **Load sample data:**
   ```java
   Model model = FalkorDBModelFactory.createDefaultModel();
   model.begin(ReadWrite.WRITE);
   try {
       RDFDataMgr.read(model, "samples/batch-writes/data-example.ttl");
       model.commit();
   } finally {
       model.end();
   }
   ```

## Performance Summary

| Optimization | Improvement | Use Case |
|-------------|-------------|----------|
| **Batch Writes** | 100-1000x | Bulk loading, data import |
| **Query Pushdown** | Nx-N²x | Multi-hop queries, graph traversal |
| **Variable Objects** | 2x | Mixed property/relationship queries |
| **OPTIONAL Patterns** | Nx | Partial data retrieval |
| **FILTER Expressions** | Reduces data transfer | Filtering, range queries, complex conditions |
| **Magic Property** | Maximum | Complex Cypher patterns |

## File Formats

All examples are provided in multiple formats:

### Java Examples
Complete, runnable code with:
- Setup and teardown
- Multiple use cases
- Error handling
- Performance notes

### SPARQL Queries
Query patterns with:
- Syntax examples
- Generated Cypher (where applicable)
- Performance notes
- Usage comments

### Turtle Data Files
Sample RDF data with:
- People and relationships
- Organizations
- Properties and literals
- Complex graph structures

### README Files
Detailed documentation with:
- Optimization explanation
- How it works
- Key benefits
- Performance comparison
- Usage patterns
- Common mistakes
- Links to tests

## Testing

Each optimization has comprehensive tests:

- **Unit Tests:** Test compilation and translation logic
- **Integration Tests:** End-to-end verification with FalkorDB

Run tests:
```bash
mvn test -pl jena-falkordb-adapter
```

## Documentation Links

- [OPTIMIZATIONS.md](../OPTIMIZATIONS.md) - Complete optimization guide
- [README.md](../README.md) - Project overview
- [MAGIC_PROPERTY.md](../MAGIC_PROPERTY.md) - Magic property documentation

## Contributing

To add new examples:
1. Create a new subdirectory under `samples/`
2. Include all four file types (Java, SPARQL, TTL, README)
3. Follow the existing format and structure
4. Add comprehensive comments and explanations
5. Link from this README

## Support

For issues or questions:
- Review the [troubleshooting section](../README.md#troubleshooting)
- Check [existing issues](https://github.com/FalkorDB/jena-falkordb-adapter/issues)
- Create a new issue with the `examples` label

## License

These examples are part of the FalkorDB Jena Adapter project and follow the same MIT license.
