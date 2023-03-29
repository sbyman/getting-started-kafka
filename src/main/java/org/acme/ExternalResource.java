package org.acme;

import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import io.smallrye.mutiny.Uni;

@RegisterRestClient
public interface ExternalResource {

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Movie> touchMovies(Movie movie);
    
}
