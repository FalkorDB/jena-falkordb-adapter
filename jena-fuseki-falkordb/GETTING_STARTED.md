# Getting Started with Jena-Fuseki-FalkorDB

This guide will help you set up and start using the Jena-Fuseki-FalkorDB in under 10 minutes.

## Quick Start (3 Steps)

### Step 1: Start FalkorDB

```bash
docker run -p 6379:6379 -it --rm falkordb/falkordb:latest
```

### Step 2: Build and Run

```bash
mvn clean install
mvn exec:java -Dexec.mainClass="com.falkordb.FalkorFuseki"
```

### Step 3: Insert and Query data

```bash
curl -X POST --data "INSERT DATA { <http://example/s> <http://example/p> 123 }" \
     -H "Content-Type: application/sparql-update" \
     http://localhost:3330/falkor/update

curl -G --data-urlencode "query=SELECT * WHERE { ?s ?p ?o }" \
     http://localhost:3330/falkor/query
```

