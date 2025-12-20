package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.traccar.api.BaseResource;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;
import org.traccar.sse.PositionSseListener;
import org.traccar.storage.StorageException;

import java.util.List;

@Path("/sse")
public class SseResource extends BaseResource {

    @Inject
    private ConnectionManager connectionManager;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void subscribe(@Context SseEventSink sink, @Context Sse sse) throws StorageException {
        long userId = getUserId();

        final PositionSseListener[] listenerRef = new PositionSseListener[1];

        Runnable cleanupTask = () -> {
            if (listenerRef[0] != null) {
                connectionManager.removeListener(userId, listenerRef[0]);
            }
        };

        listenerRef[0] = new PositionSseListener(sink, sse, cleanupTask);

        connectionManager.addListener(userId, listenerRef[0]);

        List<Position> latest = PositionUtil.getLatestPositions(storage, userId);
        latest.forEach(p -> listenerRef[0].onUpdatePosition(p));
    }
}