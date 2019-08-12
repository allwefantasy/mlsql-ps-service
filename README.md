# MLSQL PS Service 

This library is used to build a RPC service in both driver and 
executor sides. We hook spark metric system to start executor backend 
in executor, and it requires that the backend should be in executor classpath.

PythonAlg and several other ETs depends this library. You can download
the right jar from [mlsql-ps-service](http://download.mlsql.tech/1.3.0-SNAPSHOT).

Jar Example:
```
mlsql-ps-service_2.4.3_2.11-1.3.3.jar 
```

* 2.4.3  Spark version
* 2.11   Scala version
* 1.3.3  MLSQL version

The only two things to make this jar work is 

1. Put the jar into path $SPARK_HOME/libs
2. set -streaming.ps.cluster.enable true

## Deploy

```
# spark 2.4 
mvn clean deploy -DskipTests -Pspark-2.4.3 -Pspark-2.3.2 -Pdisable-java8-doclint -Prelease-sign-artifacts
``` 

## Build

```
# spark 2.4 
mvn clean package -DskipTests -Pspark-2.4.3  -Pshade

# spark 2.3 
mvn clean package -DskipTests -Pspark-2.3.2  -Pshade

``` 



