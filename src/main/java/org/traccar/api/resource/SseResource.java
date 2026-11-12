package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.LogRecord;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;
import org.traccar.sse.PositionSseListener;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.util.List;

@Path("/sse")
public class SseResource extends BaseResource {

    @Inject
    private ConnectionManager connectionManager;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void subscribe(@Context SseEventSink sink, @Context Sse sse) throws StorageException {
        long userId = getUserId();
        if (!permissionsService.notAdmin(userId)) {
            subscribeAdministrator(userId, sink, sse);
            return;
        }

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

    /**
     * Administrators see every device, including ones created later by other users, not only the devices linked
     * to them. Positions and device updates come from a global listener; events and logs still come from the
     * user listener (they are addressed per user), through an adapter that ignores everything else so nothing is
     * sent twice.
     */
    private void subscribeAdministrator(long userId, SseEventSink sink, Sse sse) throws StorageException {
        List<Position> latest = storage.getObjects(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions()));

        final PositionSseListener listener = new PositionSseListener(sink, sse, null);
        final ConnectionManager.UpdateListener eventsOnly = new ConnectionManager.UpdateListener() {
            @Override
            public void onKeepalive() {
            }

            @Override
            public void onUpdateDevice(Device device) {
            }

            @Override
            public void onUpdatePosition(Position position) {
            }

            @Override
            public void onUpdateEvent(Event event) {
                listener.onUpdateEvent(event);
            }

            @Override
            public void onUpdateLog(LogRecord record) {
                listener.onUpdateLog(record);
            }
        };
        listener.setOnCleanup(() -> {
            connectionManager.removeGlobalListener(listener);
            connectionManager.removeListener(userId, eventsOnly);
        });

        connectionManager.addGlobalListener(listener);
        connectionManager.addListener(userId, eventsOnly);

        for (Position p : latest) {
            listener.onUpdatePosition(p);
        }
    }
}
