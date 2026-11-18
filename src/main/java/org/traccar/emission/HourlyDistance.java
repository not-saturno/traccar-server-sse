package org.traccar.emission;

import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * Fork: distance travelled per hour, from the {@code totalDistance} attribute. Positions must be added in fix time
 * order; one position before the interval may be added first so the distance up to the first position inside the
 * interval is counted. The increase between two consecutive positions goes to the hour of the later one; decreases
 * (odometer reset) are ignored, and an hour above the glitch limit counts as zero.
 */
public final class HourlyDistance {

    public static final long HOUR = 3_600_000L;

    private final long from;
    private final long to;
    private final double[] meters;
    private final double maxHourMeters;
    private final int maxRoutePoints;
    private List<double[]> route = new ArrayList<>();
    private double[] lastPoint;
    private int stride = 1;
    private long pointCount;

    private double previous = Double.NaN;

    /**
     * @param maxRoutePoints zero to skip the route
     */
    public HourlyDistance(long from, long to, double maxHourKm, int maxRoutePoints) {
        this.from = from;
        this.to = to;
        this.meters = new double[hours(from, to)];
        this.maxHourMeters = maxHourKm * 1000;
        this.maxRoutePoints = maxRoutePoints > 0 ? Math.max(2, maxRoutePoints) : 0;
    }

    public static int hours(long from, long to) {
        return (int) Math.max(1, (to - from + HOUR - 1) / HOUR);
    }

    public void add(Position position) {
        long time = position.getFixTime().getTime();
        boolean inside = time >= from && time <= to;
        if (inside && maxRoutePoints > 0 && (position.getLatitude() != 0 || position.getLongitude() != 0)) {
            addPoint(new double[] {position.getLatitude(), position.getLongitude()});
        }
        if (!position.hasAttribute(Position.KEY_TOTAL_DISTANCE)) {
            return;
        }
        double odometer = position.getDouble(Position.KEY_TOTAL_DISTANCE);
        if (inside && !Double.isNaN(previous) && odometer > previous) {
            int index = (int) Math.min(meters.length - 1, (time - from) / HOUR);
            meters[index] += odometer - previous;
        }
        previous = odometer;
    }

    // Keeps every stride-th point; when the list reaches twice the limit, every other point is dropped and the
    // stride doubles, so memory stays bounded however long the interval is
    private void addPoint(double[] point) {
        lastPoint = point;
        if (pointCount++ % stride != 0) {
            return;
        }
        route.add(point);
        if (route.size() >= 2 * maxRoutePoints) {
            List<double[]> kept = new ArrayList<>(maxRoutePoints + 1);
            for (int i = 0; i < route.size(); i += 2) {
                kept.add(route.get(i));
            }
            route = kept;
            stride *= 2;
        }
    }

    /** Kilometers per hour bucket; bucket {@code i} starts at {@code from + i * HOUR}. */
    public double[] getKilometers() {
        double[] result = new double[meters.length];
        for (int i = 0; i < meters.length; i++) {
            result[i] = meters[i] > maxHourMeters ? 0 : meters[i] / 1000;
        }
        return result;
    }

    /** Route as {@code [latitude, longitude]} pairs, thinned out evenly to the point limit. */
    public List<double[]> getRoute() {
        List<double[]> points = new ArrayList<>(route);
        if (lastPoint != null && points.get(points.size() - 1) != lastPoint) {
            points.add(lastPoint);
        }
        if (points.size() <= maxRoutePoints) {
            return points;
        }
        List<double[]> result = new ArrayList<>(maxRoutePoints);
        double step = (double) (points.size() - 1) / (maxRoutePoints - 1);
        for (int i = 0; i < maxRoutePoints; i++) {
            result.add(points.get((int) Math.round(i * step)));
        }
        return result;
    }

}
