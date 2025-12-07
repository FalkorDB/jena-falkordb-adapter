package com.falkordb.samples;

import com.falkordb.jena.FalkorDBModelFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.FOAF;

/**
 * Magic Property (Direct Cypher Execution) Examples
 * 
 * The falkor:cypher magic property allows direct Cypher execution within SPARQL.
 * Use for complex graph traversals, aggregations, and patterns not supported by pushdown.
 * 
 * See: OPTIMIZATIONS.md#magic-property-direct-cypher-execution
 */
public class MagicPropertyExample {
    
    public static void main(String[] args) {
        Model model = FalkorDBModelFactory.createDefaultModel();
        
        try {
            setupData(model);
            
            // Example 1: Simple Cypher query
            simpleCypherExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 2: Aggregation
            aggregationExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 3: Path patterns
            pathPatternExample(model);
            
            System.out.println("\n" + "=".repeat(70) + "\n");
            
            // Example 4: Complex traversal
            complexTraversalExample(model);
            
        } finally {
            model.close();
        }
    }
    
    private static void setupData(Model model) {
        model.begin(ReadWrite.WRITE);
        try {
            for (int i = 0; i < 10; i++) {
                var person = model.createResource("http://example.org/person/" + i);
                person.addProperty(FOAF.name, "Person " + i);
                if (i > 0) {
                    var prev = model.createResource("http://example.org/person/" + (i-1));
                    person.addProperty(FOAF.knows, prev);
                }
            }
            model.commit();
        } finally {
            model.end();
        }
    }
    
    private static void simpleCypherExample(Model model) {
        System.out.println("Example 1: Simple Cypher Query");
        System.out.println("===============================");
        
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?uri ?name WHERE {
                (?uri ?name) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
                    RETURN p.uri AS uri, p.`http://xmlns.com/foaf/0.1/name` AS name
                    LIMIT 5
                '''
            }
            """;
        
        executeQuery(model, sparql);
    }
    
    private static void aggregationExample(Model model) {
        System.out.println("Example 2: Aggregation (Count Connections)");
        System.out.println("==========================================");
        
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?person ?connections WHERE {
                (?person ?connections) falkor:cypher '''
                    MATCH (p:Resource)-[r:`http://xmlns.com/foaf/0.1/knows`]->(:Resource)
                    RETURN p.uri AS person, count(r) AS connections
                    ORDER BY connections DESC
                '''
            }
            """;
        
        executeQuery(model, sparql);
    }
    
    private static void pathPatternExample(Model model) {
        System.out.println("Example 3: Variable Length Path");
        System.out.println("================================");
        
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?connected WHERE {
                (?connected) falkor:cypher '''
                    MATCH (start:Resource {uri: "http://example.org/person/0"})
                          -[:`http://xmlns.com/foaf/0.1/knows`*1..3]->(end:Resource)
                    RETURN DISTINCT end.uri AS connected
                '''
            }
            """;
        
        executeQuery(model, sparql);
    }
    
    private static void complexTraversalExample(Model model) {
        System.out.println("Example 4: Complex Pattern with WHERE");
        System.out.println("======================================");
        
        String sparql = """
            PREFIX falkor: <http://falkordb.com/jena#>
            SELECT ?person ?nameLength WHERE {
                (?person ?nameLength) falkor:cypher '''
                    MATCH (p:Resource)
                    WHERE p.`http://xmlns.com/foaf/0.1/name` IS NOT NULL
                    RETURN p.uri AS person, 
                           size(p.`http://xmlns.com/foaf/0.1/name`) AS nameLength
                    ORDER BY nameLength DESC
                    LIMIT 5
                '''
            }
            """;
        
        executeQuery(model, sparql);
    }
    
    private static void executeQuery(Model model, String sparql) {
        Query query = QueryFactory.create(sparql);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.out(System.out, results);
        }
    }
}
