# Magic Property (Direct Cypher Execution) - Examples

Direct Cypher execution within SPARQL for maximum control and performance.

## Files

- `MagicPropertyExample.java`: Complete examples (simple queries, aggregations, paths, complex patterns)
- `queries.sparql`: Cypher query patterns within SPARQL
- `data-example.ttl`: Sample data

## Usage

```sparql
PREFIX falkor: <http://falkordb.com/jena#>
SELECT ?result WHERE {
    (?result) falkor:cypher '''
        MATCH (n:Resource)
        RETURN n.uri AS result
    '''
}
```

## Use Cases

- Variable length paths (`-[:knows*1..3]->`)
- Aggregations (`count()`, `sum()`, `avg()`)
- Complex WHERE conditions
- OPTIONAL MATCH patterns
- Performance-critical queries

## Benefits
- Full Cypher power within SPARQL
- No translation overhead
- Complex patterns not supported by pushdown
- Works with InfModel (unwraps to base graph)

See [MAGIC_PROPERTY.md](../../MAGIC_PROPERTY.md) and [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#magic-property-direct-cypher-execution)
