package org.traccar.api.resource;

import org.traccar.api.security.PublicFeature;
import org.traccar.model.Position;
import org.traccar.publicaccess.PublicAccessService;
import org.traccar.publicaccess.PublicDevice;
import org.traccar.publicaccess.PublicPosition;
import org.traccar.publicaccess.PublicSseListener;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only endpoints available without login. Each method is gated by {@link PublicFeature} and only exposes
 * devices marked as public.
 */
@Path("public")
@Produces(MediaType.APPLICATION_JSON)
public class PublicResource {

    @Inject
    private PublicAccessService publicAccessService;

    @Inject
    private ConnectionManager connectionManager;

    @PublicFeature("devices")
    @GET
    @Path("devices")
    public List<PublicDevice> getDevices() throws StorageException {
        return publicAccessService.getDevices().values().stream()
                .map(PublicDevice::of)
                .sorted(Comparator.comparing(PublicDevice::name, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    @PublicFeature("positions")
    @GET
    @Path("positions")
    public List<PublicPosition> getPositions() throws StorageException {
        return publicAccessService.getLatestPositions().stream().map(PublicPosition::of).toList();
    }

    @PublicFeature("stream")
    @GET
    @Path("stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@Context SseEventSink sink, @Context Sse sse) throws StorageException {
        PublicSseListener listener = new PublicSseListener(sink, sse, publicAccessService);
        listener.setOnCleanup(() -> connectionManager.removeGlobalListener(listener));
        for (Position position : publicAccessService.getLatestPositions()) {
            listener.onUpdatePosition(position);
        }
        connectionManager.addGlobalListener(listener);
    }

}
