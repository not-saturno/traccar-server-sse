package org.traccar.sse;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.LogRecord;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;

public class PositionSseListener implements ConnectionManager.UpdateListener {

    private final SseEventSink sink;
    private final Sse sse;
    private final Runnable onCleanup;
    private boolean removed = false;

    public PositionSseListener(SseEventSink sink, Sse sse, Runnable onCleanup) {
        this.sink = sink;
        this.sse = sse;
        this.onCleanup = onCleanup;
    }

    private synchronized void cleanup() {
        if (!removed) {
            removed = true;
            onCleanup.run();
        }
    }

    private void send(String name, Object data) {
        if (sink.isClosed()) {
            cleanup();
            return;
        }

        try {
            OutboundSseEvent event = sse.newEventBuilder()
                    .name(name)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .data(data)
                    .build();

            sink.send(event).thenAccept(result -> {
                if (result == null) {
                    cleanup();
                }
            }).exceptionally(t -> {
                cleanup();
                return null;
            });
        } catch (Exception e) {
            cleanup();
        }
    }

    @Override
    public void onUpdatePosition(Position position) {
        send("positions", position);
    }

    @Override
    public void onKeepalive() {
        if (sink.isClosed()) {
            cleanup();
        } else {
            sink.send(sse.newEventBuilder().comment("keepalive").build());
        }
    }

    @Override
    public void onUpdateDevice(Device device) {
        send("devices", device);
    }

    @Override
    public void onUpdateEvent(Event event) {
        send("events", event);
    }

    @Override
    public void onUpdateLog(LogRecord record) {
        send("logs", record);
    }
}
