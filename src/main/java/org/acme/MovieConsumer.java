package org.acme;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;

@ApplicationScoped
public class MovieConsumer {
    
    private final Logger logger = Logger.getLogger(MovieConsumer.class);

    List<ExecutorService> executors = Arrays.asList(
        Executors.newSingleThreadExecutor(),
        Executors.newSingleThreadExecutor(),
        Executors.newSingleThreadExecutor(),
        Executors.newSingleThreadExecutor()
    );

    @Inject @Channel("movies-error")
    Emitter<String> emitter;

    @Inject
    @RestClient
    ExternalResource externalResource;

    private Uni<Message<String>> handleMessageError(Message<String> message, Throwable cause){
        return Uni.createFrom().item(message)
            //.emitOn(Infrastructure.getDefaultWorkerPool())
            .onItem().call(m -> {
                return Uni.createFrom().item(m)
                    .onItem().invoke(msg -> {
                        emitter.send(m);
                        m.ack();
                    });
            })
            //.emitOn(Infrastructure.getDefaultWorkerPool())
            .onItem().transformToUni(m -> Uni.createFrom().nullItem()) //Used to return a null item. ".continueWithNull()" return a void instead
            ;
    }

    private Multi<Message<String>> consume(Multi<Message<String>> messages){
        return messages
            .onItem().transformToUniAndConcatenate(m -> {
                IncomingKafkaRecordMetadata<Long, String> meta = m.getMetadata(IncomingKafkaRecordMetadata.class).orElseThrow();

                logger.infof("Got a movie: %d - %s - From topic %s - partition %d", meta.getKey(), m.getPayload(), meta.getTopic(), meta.getPartition());

                return Uni.createFrom().item(m)
                    //.emitOn(Infrastructure.getDefaultWorkerPool())
                    .onItem().transform(record -> {
                        Movie requestMovie = new Movie();
                        requestMovie.setId(meta.getKey());
                        requestMovie.setTitle(m.getPayload());
                        requestMovie.setPartition(meta.getPartition());
                        return requestMovie;
                    })
                    //.emitOn(Infrastructure.getDefaultWorkerPool())
                    .onItem().call(rm -> {

                        return externalResource.touchMovies(rm)
                            .onItem().invoke(res -> rm.setTitle(res.getTitle()))
                        ;
                    })
                    //.emitOn(Infrastructure.getDefaultWorkerPool())
                    .onItem().invoke(rm -> {
                        int value = Integer.parseInt(m.getPayload().split("_")[1]) % 7;
                        if(value % 7 == 0){
                            throw new IllegalArgumentException("NUMBER " + m.getPayload().split("_")[1] + " in error");
                        }
                    })
                    .onItem().transform(rm -> m.withPayload(rm.getTitle()))
                    .onFailure().invoke(ex -> logger.errorf("MY ERROR - %s", ex.getMessage()))
                    .onFailure().invoke(ex -> m.ack())
                    .onFailure().recoverWithUni(ex -> handleMessageError(m, ex).emitOn(Infrastructure.getDefaultWorkerPool()))
                    ;
            })
            .emitOn(Infrastructure.getDefaultWorkerPool())
            .onItem().invoke(m -> {
                IncomingKafkaRecordMetadata<Long, String> meta = m.getMetadata(IncomingKafkaRecordMetadata.class).orElseThrow();

                logger.infof("Send movie: %d - %s - to partiton %d", meta.getKey() , m.getPayload(), meta.getPartition());
                m.ack();
            })
            .emitOn(Infrastructure.getDefaultWorkerPool())

        ;
    }

    @Incoming("movies-in")
    @Outgoing("movies-out")
    public Multi<Message<String>> reactive(Multi<Message<String>> messages){
        System.out.println("START METHOD");

        return messages
            .group().by(input -> {
                IncomingKafkaRecordMetadata<Long, String> meta = input.getMetadata(IncomingKafkaRecordMetadata.class).orElseThrow();
                return meta.getKey();
            }) //We can read disordered events thanks to this code that aggregate them by key
            .emitOn(Infrastructure.getDefaultWorkerPool()) //We emit to another working thread the result so anything comes after is worked on the specific thread
            .onItem().transformToMultiAndMerge(r -> consume(r)) //We use merge because the returned result is already ordered so we don't care if we got first the topic 1 or topic 2 etc result. The only inportant thing is thath is ordered by partition 
            .emitOn(Infrastructure.getDefaultWorkerPool())
        ;
    }
}
