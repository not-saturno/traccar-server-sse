package org.traccar.sse;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.LogRecord;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;

public class PositionSseListener implements ConnectionManager.UpdateListener {

    private final SseEventSink sink;
    private final Sse sse;
    private Runnable onCleanup; // Removed final
    private boolean removed = false;
    private static final Logger LOGGER = LoggerFactory.getLogger(PositionSseListener.class);

    public PositionSseListener(SseEventSink sink, Sse sse, Runnable onCleanup) {
        this.sink = sink;
        this.sse = sse;
        this.onCleanup = onCleanup;
    }

    // New setter to allow the Resource to pass the correct lambda
    public void setOnCleanup(Runnable onCleanup) {
        this.onCleanup = onCleanup;
    }

    private synchronized void cleanup() {
        if (!removed) {
            LOGGER.info("SSE Listener cleanup triggered");
            removed = true;
            if (onCleanup != null) {
                onCleanup.run();
            }
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

            sink.send(event).exceptionally(t -> {
                cleanup();
                return null;
            });
        } catch (Exception e) {
            cleanup();
        }
    }

    @Override
    public void onKeepalive() {
        if (sink.isClosed()) {
            cleanup();
        } else {
            // Also handle keepalive failure
            sink.send(sse.newEventBuilder().comment("keepalive").build())
                    .exceptionally(t -> {
                        cleanup();
                        return null;
                    });
        }
    }

    @Override
    public void onUpdatePosition(Position position) {
        send("positions", position);
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
