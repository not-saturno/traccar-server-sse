package org.traccar.publicaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.LogRecord;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

/**
 * Streams updates of public devices to an anonymous SSE client. Events and logs are never sent.
 */
public class PublicSseListener implements ConnectionManager.UpdateListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicSseListener.class);

    private final SseEventSink sink;
    private final Sse sse;
    private final PublicAccessService publicAccessService;
    private Runnable onCleanup;
    private boolean removed;

    public PublicSseListener(SseEventSink sink, Sse sse, PublicAccessService publicAccessService) {
        this.sink = sink;
        this.sse = sse;
        this.publicAccessService = publicAccessService;
    }

    public void setOnCleanup(Runnable onCleanup) {
        this.onCleanup = onCleanup;
    }

    private synchronized void cleanup() {
        if (!removed) {
            LOGGER.debug("Public SSE listener cleanup");
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
            sink.send(sse.newEventBuilder()
                    .name(name)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .data(data)
                    .build())
                    .exceptionally(t -> {
                        cleanup();
                        return null;
                    });
        } catch (RuntimeException e) {
            cleanup();
        }
    }

    @Override
    public void onKeepalive() {
        if (sink.isClosed()) {
            cleanup();
        } else {
            sink.send(sse.newEventBuilder().comment("keepalive").build())
                    .exceptionally(t -> {
                        cleanup();
                        return null;
                    });
        }
    }

    @Override
    public void onUpdateDevice(Device device) {
        if (publicAccessService.isPublic(device.getId())) {
            send("devices", PublicDevice.of(device));
        }
    }

    @Override
    public void onUpdatePosition(Position position) {
        if (publicAccessService.isPublic(position.getDeviceId())) {
            send("positions", PublicPosition.of(position));
        }
    }

    @Override
    public void onUpdateEvent(Event event) {
    }

    @Override
    public void onUpdateLog(LogRecord record) {
    }

}
