# Query Pushdown - Examples

Query pushdown translates SPARQL BGPs to native Cypher queries, executing in a single database operation instead of N+1 queries.

## Files

- `QueryPushdownExample.java`: Complete examples (Variable Predicates, Closed-Chain, Type queries, Multi-hop)
- `queries.sparql`: SPARQL query patterns
- `data-example.ttl`: Sample social network data

## Key Patterns

### Variable Predicates
`?s ?p ?o` - Generates UNION for relationships, properties, and types

### Closed-Chain
`?a knows ?b . ?b knows ?a` - Bidirectional match for mutual connections

### Multi-Hop
`alice knows ?f1 . ?f1 knows ?f2` - 2-hop traversal in single query

## Performance
- Variable predicates: 3x fewer queries (single UNION)
- Friends of friends: Nx fewer calls (1 vs N+1)
- 3-hop traversal: N²x improvement

See [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md#query-pushdown-sparql-to-cypher)
