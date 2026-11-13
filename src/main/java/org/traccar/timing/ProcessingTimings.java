package org.traccar.timing;

import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fork: how long each step of position processing takes (ProcessingHandler), kept in memory only. Used to find
 * which handler delays the position after the protocol log goes out. Exposed to administrators by
 * {@code GET /api/processing/timings}.
 */
@Singleton
public class ProcessingTimings {

    public static final String BUFFER = "buffer";
    public static final String QUEUE = "queue";
    public static final String POST_PROCESS = "postProcess";
    public static final String TOTAL = "total";

    /** Positions released at once by the adaptive buffer. */
    public static final String COUNT_LIVE = "live";
    /** Positions held by the buffer (historical, out of order or behind a held one). */
    public static final String COUNT_BUFFERED = "buffered";
    /** Out of order positions whose distance was measured from the previous position by fix time. */
    public static final String COUNT_OUT_OF_ORDER = "outOfOrder";

    public enum Kind { STAGE, POSITION_HANDLER, EVENT_HANDLER }

    /** Recent samples kept per step for the recent average and the 95th percentile. */
    private static final int WINDOW = 500;

    private final Map<String, Step> steps = new ConcurrentHashMap<>();
    private final AtomicInteger nextOrder = new AtomicInteger();
    private volatile long since = System.currentTimeMillis();
    private final long bufferingThreshold;
    private final long liveTolerance;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Inject
    public ProcessingTimings(Config config) {
        bufferingThreshold = config.getLong(Keys.SERVER_BUFFERING_THRESHOLD);
        liveTolerance = config.getLong(Keys.SERVER_BUFFERING_LIVE_TOLERANCE);
    }

    private static final class Step {
        private final String name;
        private final Kind kind;
        private final int order;
        private long count;
        private double totalMs;
        private double maxMs;
        private double lastMs;
        private final double[] recent = new double[WINDOW];
        private int next;
        private int size;

        private Step(String name, Kind kind, int order) {
            this.name = name;
            this.kind = kind;
            this.order = order;
        }

        private synchronized void add(double ms) {
            count += 1;
            totalMs += ms;
            maxMs = Math.max(maxMs, ms);
            lastMs = ms;
            recent[next] = ms;
            next = (next + 1) % WINDOW;
            size = Math.min(size + 1, WINDOW);
        }

        private synchronized void clear() {
            count = 0;
            totalMs = 0;
            maxMs = 0;
            lastMs = 0;
            next = 0;
            size = 0;
        }

        private synchronized StepTiming snapshot() {
            double[] values = Arrays.copyOf(recent, size);
            Arrays.sort(values);
            double recentSum = 0;
            for (double value : values) {
                recentSum += value;
            }
            return new StepTiming(
                    name, kind, count,
                    count > 0 ? totalMs / count : 0,
                    size > 0 ? recentSum / size : 0,
                    size > 0 ? values[Math.min(size - 1, (int) Math.ceil(size * 0.95) - 1)] : 0,
                    maxMs, lastMs);
        }
    }

    public record StepTiming(
            String name, Kind kind, long count,
            double averageMs, double recentAverageMs, double p95Ms, double maxMs, double lastMs) {
    }

    public record Snapshot(
            long since, int window, long bufferingThresholdMs, long liveToleranceMs,
            Map<String, Long> counters, List<StepTiming> steps) {
    }

    /** Registers a step so it is listed in pipeline order, even before its first sample. */
    public void register(String name, Kind kind) {
        steps.computeIfAbsent(name, key -> new Step(key, kind, nextOrder.getAndIncrement()));
    }

    public void record(String name, Kind kind, long nanos) {
        register(name, kind);
        steps.get(name).add(nanos / 1_000_000.0);
    }

    public Snapshot snapshot() {
        List<StepTiming> result = new ArrayList<>();
        steps.values().stream()
                .sorted(Comparator.comparingInt(step -> step.order))
                .forEach(step -> result.add(step.snapshot()));
        Map<String, Long> counterValues = new TreeMap<>();
        for (String name : List.of(COUNT_LIVE, COUNT_BUFFERED, COUNT_OUT_OF_ORDER)) {
            counterValues.put(name, 0L);
        }
        counters.forEach((name, value) -> counterValues.put(name, value.get()));
        return new Snapshot(since, WINDOW, bufferingThreshold, liveTolerance, counterValues, result);
    }

    public void count(String name) {
        counters.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
    }

    public void reset() {
        steps.values().forEach(Step::clear);
        counters.values().forEach(counter -> counter.set(0));
        since = System.currentTimeMillis();
    }

    /** Readable handler name: nested classes keep the outer name (ComputedAttributesHandler.Early). */
    public static String nameOf(Class<?> clazz) {
        Class<?> outer = clazz.getEnclosingClass();
        return outer != null ? outer.getSimpleName() + "." + clazz.getSimpleName() : clazz.getSimpleName();
    }
}
