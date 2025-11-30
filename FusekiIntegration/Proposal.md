# Proposal for Falkor Db to Jena Fuseki Server Integration

## Overview

This document outlines a proposal for integrating Falkor Db with Jena Fuseki Server,  
see [Fuseki Server Overview](#fuseki-server-overview) section.  
The goal is to enable Falkor Db as Jena Fuseki Server Memory Model.  
see [Falkor Db As Jena Fuseki Memory Model Proposal](#falkor-db-as-jena-fuseki-server-memory-model-proposal) section.

## Falkor Db As Jena Fuseki Server Memory Model Proposal

Out of [Jena Falkor Db Adapter GitHub] page,  
You've implemented the `FalkorDbModel` already.  
What is missing, is FalkorDb `Assembler` implementation, which, on my opinion,  
may be same as `GeoSPARQL` `Assembler` implementation.  
See [GeoSPARQL Documentation], [GeoSPARQL Assembler GitHub] and  
[GeoSPARQL Support Example](#geosparql-support-example) section below.

Than, I believe, the following configuration will do the job:  
See [Execution With Configuration](#execution-with-configuration) section.

```ttl
@prefix :        <#> .
@prefix fuseki:  <http://jena.apache.org/fuseki#> .
@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix tdb2:    <http://jena.apache.org/2016/tdb#> .
@prefix tdb1:    <http://jena.hpl.hp.com/2008/tdb#> .
@prefix ja:      <http://jena.hpl.hp.com/2005/11/Assembler#> .
@prefix falkor:  <http://jena.apache.org/falkor#> .

[] a fuseki:Server ;
  fuseki:services (:service) ;
  .

:service a fuseki:Service ;
  fuseki:name "dataset" ;
  fuseki:endpoint [ fuseki:operation fuseki:query ; ] ;
  fuseki:endpoint [ fuseki:operation fuseki:update ; ] ;
  fuseki:endpoint [ fuseki:operation fuseki:gsp-r ;  ] ;
  fuseki:endpoint [ fuseki:operation fuseki:gsp-rw ; ] ;
  fuseki:endpoint [ fuseki:operation fuseki:upload ; ] ;
  fuseki:endpoint [ fuseki:operation fuseki:patch ; ] ;
  fuseki:dataset :dataset_rdf ;
  .

# RDF Dataset for inference model.
:dataset_rdf a ja:RDFDataset ;
  ja:defaultGraph :falkor_db_model ;
  .

# Falkor DB model in memory.
:falkor_db_model a falkor:FalkorDBModel .
```

## Fuseki Server Overview

See [Apache Jena Fuseki documentation].  

### Related Features

- **Gui Support**: Fuseki provides a user-friendly web interface for managing datasets and executing SPARQL queries.
- **Restful API**: Fuseki offers a RESTful API for SPARQL queries.
- **Configuration Flexibility**: Fuseki can be easily configured to use different storage backends,  
  including in-memory databases like FalkorDb. See [Jena Assembler documentation].  

### Execution Example

```cmd
java -Xmx4G -cp fuseki-server.jar org.apache.jena.fuseki.main.cmds.FusekiServerCmd --help
```

### Execution With Configuration

- **Server:**

```cmd
java -Xmx4G -cp fuseki-server.jar org.apache.jena.fuseki.main.cmds.FusekiServerCmd --config .\config\cfg_ff_mem.ttl
```

- **GUI:**

```cmd
start msedge http://localhost:3030/#/dataset/dataset/query
```

- **GUI SPARQL Queries**
  - Insertion
  
  ```sparql
  PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
  PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
  PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
  INSERT DATA
  {
      ff:Jacob a ff:Male.
      ff:Isaac a ff:Male;
          ff:father_of ff:Jacob.
  }
  ```

  - Selection

  ```sparql
  PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
  PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
  PREFIX ff: <http://www.semanticweb.org/ontologies/2023/1/fathers_father#>
  SELECT ?father ?son
  WHERE {
      ?father ff:father_of ?son.
  }
  ```

### Configuration Examples

- **In-Memory Configuration:** See [cfg_ff_mem.ttl] ,  
  Where the in-memory configuration is defined.  
- **Inference Configuration:** See [cfg_ff_mem_inf.ttl] ,  
  Where the inference configuration is defined.  
  Pay attention to **MemoryModel** rather than **MemoryDataset** as before.  
  See [Jena Inference documentation].  
- **GeoSPARQL Configuration:** See [cfg_geo_mem.ttl] ,  
  Where the GeoSPARQL configuration is defined.  
  See [GeoSPARQL documentation].  
  - Important to mention: as for `apache-jena-fuseki-5.3.0`,  
    We've added GeoSPARQL support manually, See [GeoSPARQL Support Example](#geosparql-support-example) below.  

```cmd
java -Xmx4G -cp fuseki-server.jar;lib/* org.apache.jena.fuseki.main.cmds.FusekiServerCmd --config .\config\cfg_geo_mem.ttl
```

### GeoSPARQL Support Example

Since GeoSPARQL [GeoSPARQL GitHub] is not included in the default `apache-jena-fuseki-5.3.0` package,  
but it can be added manually since it's `Assembler` implementation, See [GeoSPARQL Assembler GitHub].  
So, with Maven and the following [pom.xml](#pomxml) we can create a `jena-geosparql.jar`
that bundles all `jena-geosparql-5.3.0` dependencies.  
See [Create Geosparql Jar With Dependencies](#create-geosparql-jar-with-dependencies) below.  

#### Create Geosparql Jar With Dependencies

- Create Maven working directory with [pom.xml](#pomxml) in the following structure:

```text
jena-geosparql-5.3.0
└── .mvn
    └── pom.xml
```

- From `.mvn` directory run `mvn clean package`
- Copy the `jena-geosparql-5.3.0/.mvn/target/jena-geosparql-download-1.0-SNAPSHOT-jar-with-dependencies.jar`
  as `jena-geosparql.jar` to `apache-jena-fuseki-5.3.0/lib/`
- Execute: `java -Xmx4G -cp fuseki-server.jar;lib/* org.apache.jena.fuseki.main.cmds.FusekiServerCmd --help`

#### pom.xml

```xml
<project 
  xmlns="http://maven.apache.org/POM/4.0.0" 
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>custom.download</groupId>
  <artifactId>jena-geosparql-download</artifactId>
  <version>1.0-SNAPSHOT</version>
  <dependencies>
    <dependency>
      <groupId>org.apache.jena</groupId>
      <artifactId>jena-geosparql</artifactId>
      <version>5.3.0</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-assembly-plugin</artifactId>
        <version>3.6.0</version>
        <configuration>
          <descriptorRefs>
            <descriptorRef>jar-with-dependencies</descriptorRef>
          </descriptorRefs>
          <archive>
            <manifest>
              <!--  Leave out Main-Class to make it non-runnable  -->
            </manifest>
          </archive>
        </configuration>
        <executions>
          <execution>
            <id>make-assembly</id>
            <phase>package</phase>
            <goals>
              <goal>single</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

[Apache Jena Fuseki documentation]:https://jena.apache.org/documentation/fuseki2/
[Jena Assembler documentation]:https://jena.apache.org/documentation/assembler/
[Jena Inference documentation]:https://jena.apache.org/documentation/inference/
[GeoSPARQL documentation]:https://jena.apache.org/documentation/geosparql/
[GeoSPARQL GitHub]:https://github.com/apache/jena/tree/main/jena-geosparql
[GeoSPARQL Assembler GitHub]:https://github.com/apache/jena/tree/main/jena-geosparql/src/main/java/org/apache/jena/geosparql/assembler
[cfg_ff_mem.ttl]:.\config\cfg_ff_mem.ttl
[cfg_ff_mem_inf.ttl]:.\config\cfg_ff_mem_inf.ttl
[cfg_geo_mem.ttl]:.\config\cfg_geo_mem.ttl
[Jena Falkor Db Adapter GitHub]:https://github.com/FalkorDB/jena-falkordb-adapter/tree/main/src/main/java/com/falkordb/jena
