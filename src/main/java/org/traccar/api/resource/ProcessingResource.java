package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.storage.StorageException;
import org.traccar.timing.ProcessingTimings;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Fork: time spent in each position processing step since the server started (or the last reset). Administrators
 * only.
 */
@Path("processing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProcessingResource extends BaseResource {

    @Inject
    private ProcessingTimings timings;

    @GET
    @Path("timings")
    public ProcessingTimings.Snapshot get() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        return timings.snapshot();
    }

    @DELETE
    @Path("timings")
    public Response reset() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        timings.reset();
        return Response.noContent().build();
    }
}
