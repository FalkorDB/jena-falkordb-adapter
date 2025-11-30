package com.falkordb;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import com.falkordb.jena.FalkorDBModelFactory;

// Note: Verify the exact factory/class name in your IDE from the adapter library.
// It is likely 'com.falkordb.jena.FalkorDBModelFactory' or similar.

public class FalkorFuseki {
    public static void main(String[] args) {
        // 1. Connection Details for FalkorDB (Redis)
        String host = "localhost";
        int port = 6379;
        String graphKey = "my_knowledge_graph"; // The key in Redis where the graph is stored

        System.out.println("Connecting to FalkorDB at " + host + ":" + port + "...");

        // 2. Create the FalkorDB-backed Jena Model
        // (You may need to adjust this constructor based on the specific adapter
        // version API)
        Model falkorGraph = FalkorDBModelFactory.builder()
                .host(host)
                .port(port)
                .graphName(graphKey)
                .build();

        // 3. Wrap the Model in a Dataset (Fuseki serves Datasets, not just Models)
        Dataset ds = DatasetFactory.create(falkorGraph);

        // 4. Build and Start the Fuseki Server
        System.out.println("Starting Fuseki Server on port 3330...");

        FusekiServer server = FusekiServer.create()
                .port(3330)
                .add("/falkor", ds)
                .staticFileBase("src/main/resources/webapp")
                .build();

        server.start();

        System.out.println("Server running. SPARQL endpoint: http://localhost:3330/falkor");
    }
}