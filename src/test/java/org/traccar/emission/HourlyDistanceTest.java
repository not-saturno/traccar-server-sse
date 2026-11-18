package org.traccar.emission;

import org.junit.jupiter.api.Test;
import org.traccar.model.Position;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HourlyDistanceTest {

    private static final long HOUR = HourlyDistance.HOUR;

    private static Position position(long time, double totalDistance) {
        Position position = new Position();
        position.setFixTime(new Date(time));
        position.setLatitude(-1.47);
        position.setLongitude(-48.44);
        position.set(Position.KEY_TOTAL_DISTANCE, totalDistance);
        return position;
    }

    @Test
    public void testDistancePerHour() {
        HourlyDistance distance = new HourlyDistance(0, 3 * HOUR - 1, 150, 0);
        distance.add(position(-10, 1000)); // before the interval: reference only
        distance.add(position(10, 3000)); // 2 km in hour 0
        distance.add(position(HOUR + 10, 8000)); // 5 km in hour 1
        distance.add(position(HOUR + 20, 500)); // odometer reset: ignored
        distance.add(position(2 * HOUR + 10, 1500)); // 1 km in hour 2
        assertArrayEquals(new double[] {2, 5, 1}, distance.getKilometers(), 1e-9);
    }

    @Test
    public void testGlitchDiscarded() {
        HourlyDistance distance = new HourlyDistance(0, 2 * HOUR - 1, 150, 0);
        distance.add(position(10, 0));
        distance.add(position(20, 200_000)); // 200 km in one hour
        distance.add(position(HOUR + 10, 210_000));
        assertArrayEquals(new double[] {0, 10}, distance.getKilometers(), 1e-9);
    }

    @Test
    public void testMissingTotalDistanceSkipped() {
        HourlyDistance distance = new HourlyDistance(0, HOUR - 1, 150, 0);
        distance.add(position(10, 1000));
        Position noAttribute = position(20, 0);
        noAttribute.getAttributes().clear();
        distance.add(noAttribute);
        distance.add(position(30, 1500));
        assertArrayEquals(new double[] {0.5}, distance.getKilometers(), 1e-9);
    }

    @Test
    public void testRouteThinned() {
        HourlyDistance distance = new HourlyDistance(0, 100 * HOUR, 150, 50);
        for (int i = 0; i < 10_000; i++) {
            Position position = position(i * 1000L, i);
            position.setLatitude(i / 1000.0);
            distance.add(position);
        }
        var route = distance.getRoute();
        assertEquals(50, route.size());
        assertEquals(0, route.get(0)[0]);
        assertEquals(9.999, route.get(route.size() - 1)[0]);
    }

}
