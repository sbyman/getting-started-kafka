package org.acme;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Arrays;
import java.util.List;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;

@ApplicationScoped
public class MovieConsumer {
    
    private final Logger logger = Logger.getLogger(MovieConsumer.class);

    private final static ExecutorService executorService = Executors.newFixedThreadPool(20, r -> new Thread(r, "group-thread"));
    private final static ExecutorService processingService = Executors.newFixedThreadPool(20, r -> new Thread(r, "processing-thread"));
    private final static ExecutorService blockingService = Executors.newFixedThreadPool(20, r -> new Thread(r, "blocking-thread"));

    @Inject @Channel("movies-out")
    Emitter<Movie> emitter;

    @Inject
    @RestClient
    ExternalResource externalResource;

    private Uni<Message<Movie>> handleMessageError(Message<Movie> message, Throwable cause){
        return Uni.createFrom().item(message)
            .onItem().invoke(ex -> logger.errorf("MY ERROR - %s", cause.getMessage()))
            .onItem().call(m -> {
                return Uni.createFrom().item(m)
                    .onItem().invoke(msg -> emitter.send(m))
                    .onItem().call(msg -> Uni.createFrom().completionStage(m.ack()))
                    ;
            })
            .onItem().transformToUni(m -> Uni.createFrom().nullItem()) //Used to return a null item. ".continueWithNull()" return a void instead
            ;
    }

    private Uni<Message<Movie>> handleMessageResponse(Message<Movie> message){
        return Uni.createFrom().item(message)
            .onItem().invoke(m -> logger.infof("Sending out message: %d - %s - To topic movies-out - partition %d", m.getPayload().getId(), m.getPayload().getTitle(), m.getPayload().getPartition()))
            .onItem().invoke(m -> emitter.send(m))
            .onItem().transformToUni(m -> Uni.createFrom().nullItem())
        ;
    }

    private Multi<Message<Movie>> processTopic(Multi<Message<Movie>> messages){
        return messages
            .onItem().transformToUniAndConcatenate(m -> {
                IncomingKafkaRecordMetadata<Long, Movie> meta = m.getMetadata(IncomingKafkaRecordMetadata.class).orElseThrow();
                logger.infof("Got a movie: %d - %s - From topic %s - partition %d", meta.getKey(), m.getPayload().getTitle(), meta.getTopic(), meta.getPartition());

                return Uni.createFrom().item(m)
                    .onItem().transform(record -> {
                        Movie requestMovie = new Movie();
                        requestMovie.setId(meta.getKey());
                        requestMovie.setTitle(m.getPayload().getTitle());
                        requestMovie.setPartition(meta.getPartition());
                        return requestMovie;
                    })
                    .onItem().call(rm -> {

                        return externalResource.touchMovies(rm)
                            .onItem().invoke(res -> rm.setTitle(res.getTitle()))
                        ;
                    })
                    // .onItem().invoke(rm -> {
                    //     int value = Integer.parseInt(m.getPayload().split("_")[1]) % 7;
                    //     if(value % 7 == 0){
                    //         throw new IllegalArgumentException("NUMBER " + m.getPayload().split("_")[1] + " in error");
                    //     }
                    // })
                    .onItem().transform(rm -> m.withPayload(rm))
                    .onFailure().recoverWithUni(ex -> handleMessageError(m, ex))//.emitOn(executors.get(m.getPayload().getId().intValue())))
                    ;
            })
            .emitOn(Infrastructure.getDefaultWorkerPool())
            .onItem().invoke(m -> {
                IncomingKafkaRecordMetadata<Long, Movie> meta = m.getMetadata(IncomingKafkaRecordMetadata.class).orElseThrow();
                logger.infof("Send movie: %d - %s - to partiton %d", meta.getKey() , m.getPayload().getTitle(), meta.getPartition());
            })
            .onItem().call(m -> Uni.createFrom().completionStage(m.ack()))
            .emitOn(Infrastructure.getDefaultWorkerPool())

        ;
    }

    private Uni<Message<Movie>> processTopicMessage(Message<Movie> message){
        
        return Uni.createFrom().item(message)
            .onItem().invoke(m -> logger.infof("Got a movie: %d - %s - partition %d", m.getPayload().getId(), m.getPayload().getTitle(), m.getPayload().getPartition()))
            .onItem().transform(record -> {
                Movie requestMovie = new Movie();
                requestMovie.setId(record.getPayload().getId());
                requestMovie.setTitle(record.getPayload().getTitle());
                requestMovie.setPartition(record.getPayload().getPartition());
                return requestMovie;
            })
            .onItem().call(rm -> {
                return Uni.createFrom().item(rm)
                    .onItem().invoke(req -> logger.infof("Sending request for movie: %d - %s - partition %d", req.getId(), req.getTitle(), req.getPartition()))
                    .onItem().transformToUni(req -> externalResource.touchMovies(req))
                    .onItem().invoke(res -> rm.setTitle(res.getTitle()))
                    .runSubscriptionOn(blockingService)
                ;
            })
            .emitOn(processingService)
            .onItem().transform(rm -> message.withPayload(rm))
            .onItem().invoke(m -> logger.infof("Sent movie: %d - %s - partition %d", m.getPayload().getId(), m.getPayload().getTitle(), m.getPayload().getPartition()))
            .onItem().transformToUni(m -> handleMessageResponse(m)
                .runSubscriptionOn(processingService))
            .onItem().call(rm -> Uni.createFrom().completionStage(message.ack())
                .runSubscriptionOn(blockingService)
            )
            .emitOn(processingService)
            .onFailure().recoverWithItem(ex -> Message.of(message.getPayload()).withAck(() -> message.ack()))
            ;
    
    }


    // @Incoming("movies-in")
    // @Outgoing("movies-out")
    // public Multi<Message<Movie>> consume(Multi<Message<Movie>> messages){
    //     return messages
    //         .onRequest().invoke(r -> Log.infof("%s requested %d elements", Thread.currentThread().getName(), r))
    //         .group().by(input -> input.getPayload().getId()) //We can read disordered events thanks to this code that aggregate them by key
    //         //.emitOn(Infrastructure.getDefaultWorkerPool()) //We emit to another working thread the result so anything comes after is worked on the specific thread
    //         .onItem().transform(r -> r.emitOn(executors.get(r.key().intValue())))
    //         .onItem().transformToMultiAndMerge(r -> processTopic(r)) //We use merge because the returned result is already ordered so we don't care if we got first the topic 1 or topic 2 etc result. The only inportant thing is thath is ordered by partition 
    //         .emitOn(Infrastructure.getDefaultWorkerPool())
    //     ;
    // }

    @Incoming("movies-in")
    @Outgoing("movies-error")
    public Multi<Message<Movie>> consumeNoGroup(Multi<Message<Movie>> messages){
        return messages
            .onRequest().invoke(r -> Log.infof("%s requested %d elements", Thread.currentThread().getName(), r))
            .group().by(r -> r.getPayload().getId())
            .runSubscriptionOn(executorService)
            .onItem().transformToMultiAndMerge(r -> 
                r
                .emitOn(processingService)
                .onItem().transformToUniAndConcatenate(m -> 
                    processTopicMessage(m)
                        .runSubscriptionOn(processingService)
                )
                //.runSubscriptionOn(executors.get(r.key().intValue()))
            )
            .onFailure().invoke(ex -> Log.errorf("Failing on consuming from kafka %s", ex.getMessage()))
            .onFailure().recoverWithMulti(Multi.createFrom().nothing())
             //We use merge because the returned result is already ordered so we don't care if we got first the topic 1 or topic 2 etc result. The only inportant thing is thath is ordered by partition 
            //.emitOn(Infrastructure.getDefaultWorkerPool())
        ;
    }
}
