package org.traccar.handler;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DistanceHandlerTest {

    @Test
    public void testCalculateDistance() {

        DistanceHandler distanceHandler = new DistanceHandler(new Config(), mock(CacheManager.class));

        Position position = new Position();
        distanceHandler.handlePosition(position, p -> {});

        assertEquals(0.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(0.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

        position.set(Position.KEY_DISTANCE, 100);

        distanceHandler.handlePosition(position, p -> {});

        assertEquals(100.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(100.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

    }

    private static Position position(long time, double longitude, double totalDistance) {
        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(time));
        position.setLatitude(-1.4748);
        position.setLongitude(longitude);
        position.set(Position.KEY_TOTAL_DISTANCE, totalDistance);
        return position;
    }

    @Test
    public void testOutOfOrderUsesPreviousByFixTime() throws Exception {

        Position previous = position(1_000, -48.4500, 1000.0);
        Position latest = position(3_000, -48.4400, 5000.0);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(1)).thenReturn(latest);
        Storage storage = mock(Storage.class);
        when(storage.getObject(eq(Position.class), any(Request.class))).thenReturn(previous);

        DistanceHandler distanceHandler = new DistanceHandler(new Config(), cacheManager, storage, null);

        Position late = position(2_000, -48.4495, 0);
        late.getAttributes().remove(Position.KEY_TOTAL_DISTANCE);
        distanceHandler.handlePosition(late, p -> {});

        double distance = (double) late.getAttributes().get(Position.KEY_DISTANCE);
        assertEquals(55.6, distance, 1.0); // from previous (0.0005 degree), not from latest (~500 m)
        assertEquals(1000.0 + distance, (double) late.getAttributes().get(Position.KEY_TOTAL_DISTANCE), 0.001);

    }

}
