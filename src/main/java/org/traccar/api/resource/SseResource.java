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

        // Fetch history first to keep logic clean
        List<Position> latest = PositionUtil.getLatestPositions(storage, userId);

        // We use a final reference so the lambda below captures the exact object
        final PositionSseListener listener = new PositionSseListener(sink, sse, null);

        // Define the cleanup task to remove THIS specific listener instance
        listener.setOnCleanup(() -> connectionManager.removeListener(userId, listener));

        connectionManager.addListener(userId, listener);

        for (Position p : latest) {
            listener.onUpdatePosition(p);
        }
    }
}
