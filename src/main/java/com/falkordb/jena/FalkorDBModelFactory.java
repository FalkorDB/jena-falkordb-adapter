package com.falkordb.jena;

import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * Factory and convenience methods for creating Jena {@link Model} instances
 * backed by a FalkorDB graph via the JFalkorDB driver.
 */
public class FalkorDBModelFactory {
    
    /**
     * Create a new Jena Model backed by a FalkorDB graph on the specified host
     * and port.
     *
     * @param host the FalkorDB host
     * @param port the FalkorDB port
     * @param graphName the name of the FalkorDB graph
     * @return a Jena {@link Model} backed by FalkorDB
     */
    public static Model createModel(String host, int port, String graphName) {
        Graph graph = new FalkorDBGraph(host, port, graphName);
        return ModelFactory.createModelForGraph(graph);
    }
    
    /**
     * Create a new Jena Model backed by a FalkorDB graph on the default host
     * and port.
     *
     * @param graphName the name of the FalkorDB graph
     * @return a Jena {@link Model}
     */
    public static Model createModel(String graphName) {
        Graph graph = new FalkorDBGraph(graphName);
        return ModelFactory.createModelForGraph(graph);
    }
    
    /**
     * Create a Model with a default graph name ("rdf_graph").
     *
     * @return a Jena {@link Model}
     */
    public static Model createDefaultModel() {
        return createModel("rdf_graph");
    }
    
    /**
     * Obtain a builder for configuring a FalkorDB-backed Jena Model.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for configuring host, port and graph name before creating a
     * FalkorDB-backed Jena {@link Model}.
     */
    public static class Builder {
        private String host = "localhost";
        private int port = 6379;
        private String graphName = "rdf_graph";
        
        /**
         * Set the FalkorDB host for the builder.
         *
         * @param host the FalkorDB host
         * @return this builder
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }
        
        /**
         * Set the FalkorDB port for the builder.
         *
         * @param port the FalkorDB port
         * @return this builder
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        
        /**
         * Set the FalkorDB graph name for the builder.
         *
         * @param graphName the graph name
         * @return this builder
         */
        public Builder graphName(String graphName) {
            this.graphName = graphName;
            return this;
        }
        
        /**
         * Build the configured Jena {@link Model} backed by FalkorDB.
         *
         * @return the configured Model
         */
        public Model build() {
            return createModel(host, port, graphName);
        }
    }
}