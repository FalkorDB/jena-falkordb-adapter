package com.falkordb.jena;

import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

public class FalkorDBModelFactory {
    
    public static Model createModel(String host, int port, String graphName) {
        Graph graph = new FalkorDBGraph(host, port, graphName);
        return ModelFactory.createModelForGraph(graph);
    }
    
    public static Model createModel(String graphName) {
        Graph graph = new FalkorDBGraph(graphName);
        return ModelFactory.createModelForGraph(graph);
    }
    
    public static Model createDefaultModel() {
        return createModel("rdf_graph");
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String host = "localhost";
        private int port = 6379;
        private String graphName = "rdf_graph";
        
        public Builder host(String host) {
            this.host = host;
            return this;
        }
        
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        
        public Builder graphName(String graphName) {
            this.graphName = graphName;
            return this;
        }
        
        public Model build() {
            return createModel(host, port, graphName);
        }
    }
}