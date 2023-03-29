# getting-started-kafka

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .


## Project Purpose

This project has been realized to test the partitioning and concurrency processing of data from Kafka on Quarkus.
The original project can be found at the following link: [Startup](https://quarkus.io/blog/getting-started-kafka/)

The first problem that has to been addressed is the fact that each Kafka partition is not being processed by a specific thread on Quarkus.

From [HERE](https://github.com/quarkusio/quarkus/issues/21306) we can learn that processing from multiple partitions is not something 
that is addressed by default from Quarkus due to its generic purpose.
What we realized is that using Multi - Multi from read-write method connected to kafka we can read in unordered mode from all topics.
Using then the method ``.group().by()`` we can regroup the data with the same logic as it has been partitioned in the topic.
With ``.emitOn()`` the result of the ``.group().by()`` is sent to a specific WorkerPool, so a new thread is created.

The method ``.transformToMultiAndMerge()`` has been used to invoke the method that process the ``Message<String>`` of a single partition.

Inside the method for the specific partition a ``.transformToMultiAndConcatenate()`` has been used to keep the ordering of the topic's partition.

To check if the processing is really multi-threaded an external service is invoked that process the response with a random timing.

An error handling method as been realized too ``handleMessageError``: we don't want to stop the stream processing in case of error so as explained [HERE](https://quarkus.io/blog/mutiny-failure-handling/) with this method we send the error message to a specific topic, ack the message and proceed to next message.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

## Packaging and running the application

The application can be packaged using:
```shell script
./mvnw package
```
It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./mvnw package -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using: 
```shell script
./mvnw package -Pnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using: 
```shell script
./mvnw package -Pnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/getting-started-kafka-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.

## Related Guides

- SmallRye Reactive Messaging - Kafka Connector ([guide](https://quarkus.io/guides/kafka-reactive-getting-started)): Connect to Kafka with Reactive Messaging

## Useful docs 
- [Life-saving Stackoverflow post](https://stackoverflow.com/questions/65799846/quarkus-kafka-batch-bulk-message-consumer)
- [Useful topic - Multi Handling](https://github.com/smallrye/smallrye-reactive-messaging/issues/804)
- [Useful topic - Exceptions on Bluk Processing](https://github.com/smallrye/smallrye-reactive-messaging/issues/925)
- [First solution to multithread processing](https://github.com/quarkusio/quarkus/issues/21306)
- [Handling failure on Multi](https://quarkus.io/blog/mutiny-failure-handling/)