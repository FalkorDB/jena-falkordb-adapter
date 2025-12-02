package com.falkordb.jena.tracing;

import com.falkordb.Graph;
import com.falkordb.ResultSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TracedGraph class.
 */
public class TracedGraphTest {

    private Graph mockGraph;
    private TracedGraph tracedGraph;
    private static final String GRAPH_NAME = "test_graph";

    @BeforeEach
    public void setUp() {
        mockGraph = mock(Graph.class);
        tracedGraph = new TracedGraph(mockGraph, GRAPH_NAME);
    }

    @Test
    @DisplayName("Test TracedGraph wraps delegate graph")
    public void testGetDelegate() {
        Graph delegate = tracedGraph.getDelegate();
        assertSame(mockGraph, delegate);
    }

    @Test
    @DisplayName("Test query without parameters delegates to wrapped graph")
    public void testQueryWithoutParams() {
        String cypher = "MATCH (n) RETURN n";
        ResultSet mockResult = mock(ResultSet.class);
        when(mockGraph.query(cypher)).thenReturn(mockResult);

        ResultSet result = tracedGraph.query(cypher);

        verify(mockGraph).query(cypher);
        assertSame(mockResult, result);
    }

    @Test
    @DisplayName("Test query with parameters delegates to wrapped graph")
    public void testQueryWithParams() {
        String cypher = "MATCH (n {name: $name}) RETURN n";
        Map<String, Object> params = new HashMap<>();
        params.put("name", "test");
        ResultSet mockResult = mock(ResultSet.class);
        when(mockGraph.query(cypher, params)).thenReturn(mockResult);

        ResultSet result = tracedGraph.query(cypher, params);

        verify(mockGraph).query(cypher, params);
        assertSame(mockResult, result);
    }

    @Test
    @DisplayName("Test query with empty parameters")
    public void testQueryWithEmptyParams() {
        String cypher = "MATCH (n) RETURN n";
        Map<String, Object> params = new HashMap<>();
        ResultSet mockResult = mock(ResultSet.class);
        when(mockGraph.query(cypher, params)).thenReturn(mockResult);

        ResultSet result = tracedGraph.query(cypher, params);

        verify(mockGraph).query(cypher, params);
        assertSame(mockResult, result);
    }

    @Test
    @DisplayName("Test query with null parameters")
    public void testQueryWithNullParams() {
        String cypher = "MATCH (n) RETURN n";
        ResultSet mockResult = mock(ResultSet.class);
        when(mockGraph.query(cypher, null)).thenReturn(mockResult);

        ResultSet result = tracedGraph.query(cypher, null);

        verify(mockGraph).query(cypher, null);
        assertSame(mockResult, result);
    }

    @Test
    @DisplayName("Test query propagates exceptions")
    public void testQueryPropagatesException() {
        String cypher = "INVALID QUERY";
        RuntimeException exception = new RuntimeException("Query failed");
        when(mockGraph.query(cypher)).thenThrow(exception);

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> tracedGraph.query(cypher));

        assertEquals("Query failed", thrown.getMessage());
    }

    @Test
    @DisplayName("Test query with parameters propagates exceptions")
    public void testQueryWithParamsPropagatesException() {
        String cypher = "INVALID QUERY";
        Map<String, Object> params = new HashMap<>();
        RuntimeException exception = new RuntimeException("Query failed");
        when(mockGraph.query(cypher, params)).thenThrow(exception);

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> tracedGraph.query(cypher, params));

        assertEquals("Query failed", thrown.getMessage());
    }

    @Test
    @DisplayName("Test constructor with different graph names")
    public void testConstructorWithDifferentNames() {
        TracedGraph graph1 = new TracedGraph(mockGraph, "graph1");
        TracedGraph graph2 = new TracedGraph(mockGraph, "graph2");

        assertSame(mockGraph, graph1.getDelegate());
        assertSame(mockGraph, graph2.getDelegate());
    }

    @Test
    @DisplayName("Test query with various parameter types")
    public void testQueryWithVariousParamTypes() {
        String cypher = "CREATE (n {str: $str, num: $num, bool: $bool})";
        Map<String, Object> params = new HashMap<>();
        params.put("str", "text");
        params.put("num", 42);
        params.put("bool", true);
        ResultSet mockResult = mock(ResultSet.class);
        when(mockGraph.query(cypher, params)).thenReturn(mockResult);

        ResultSet result = tracedGraph.query(cypher, params);

        verify(mockGraph).query(cypher, params);
        assertSame(mockResult, result);
    }
}
