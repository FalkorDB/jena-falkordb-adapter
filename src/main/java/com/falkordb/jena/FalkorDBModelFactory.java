package com.falkordb.jena;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * Factory and convenience methods for creating Jena {@link Model} instances
 * backed by a FalkorDB graph via the JFalkorDB driver.
 */
public class FalkorDBModelFactory {
    /** Default FalkorDB port. */
    public static final int DEFAULT_PORT = 6379;

    private FalkorDBModelFactory() {
        throw new AssertionError("No instances");
    }

    /**
     * Create a {@link Model} backed by a FalkorDB instance at the given host and port.
     *
     * @param host the FalkorDB host
     * @param port the FalkorDB port
     * @param graphName the FalkorDB graph name to use for the model
     * @return a Jena {@link Model} backed by FalkorDB
     */
    public static Model createModel(final String host, final int port, final String graphName) {
        org.apache.jena.graph.Graph graph = new FalkorDBGraph(host, port, graphName);
        return ModelFactory.createModelForGraph(graph);
    }

    /**
     * Create a {@link Model} backed by a FalkorDB graph on the default host and port.
     *
     * @param graphName the FalkorDB graph name to use for the model
     * @return a Jena {@link Model} backed by FalkorDB
     */
    public static Model createModel(final String graphName) {
        org.apache.jena.graph.Graph graph = new FalkorDBGraph(graphName);
        return ModelFactory.createModelForGraph(graph);
    }

    /**
     * Create a {@link Model} backed by a FalkorDB graph using default graph name "rdf_graph".
     *
     * @return a Jena {@link Model} backed by FalkorDB with the default graph name
     */
    public static Model createDefaultModel() {
        return createModel("rdf_graph");
    }

    /**
     * Obtain a {@link Builder} to configure and create a FalkorDB-backed {@link Model}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating FalkorDB-backed Jena {@link Model} instances.
     */
    public static class Builder {
        private String host = "localhost";
        private int port = DEFAULT_PORT;
        private String graphName = "rdf_graph";

        /**
         * Set the FalkorDB host to connect to.
         *
         * @param host the FalkorDB host
         * @return this builder
         */
        public Builder host(final String host) {
            this.host = host;
            return this;
        }

        /**
         * Set the FalkorDB port to connect to.
         *
         * @param port the FalkorDB port
         * @return this builder
         */
        public Builder port(final int port) {
            this.port = port;
            return this;
        }

        /**
         * Set the FalkorDB graph name to use for the created model.
         *
         * @param graphName the graph name
         * @return this builder
         */
        public Builder graphName(final String graphName) {
            this.graphName = graphName;
            return this;
        }

        /**
         * Build and return a Jena {@link Model} configured with the builder values.
         *
         * @return a FalkorDB-backed {@link Model}
         */
        public Model build() {
            return createModel(host, port, graphName);
        }
    }
}
