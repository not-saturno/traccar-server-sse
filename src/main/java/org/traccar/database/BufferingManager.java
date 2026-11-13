/*
 * Copyright 2024 - 2025 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.database;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;
import org.traccar.timing.ProcessingTimings;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public class BufferingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BufferingManager.class);

    public interface Callback {
        void onReleased(ChannelHandlerContext context, Position position);
    }

    private static final class Holder implements Comparable<Holder> {

        private final ChannelHandlerContext context;
        private final Position position;
        private Timeout timeout;

        private Holder(ChannelHandlerContext context, Position position) {
            this.context = context;
            this.position = position;
        }

        private int compareTime(Date left, Date right) {
            if (left != null && right != null) {
                return left.compareTo(right);
            }
            return 0;
        }

        @Override
        public int compareTo(Holder other) {
            int fixTimeResult = compareTime(position.getFixTime(), other.position.getFixTime());
            if (fixTimeResult != 0) {
                return fixTimeResult;
            }

            int deviceTimeResult = compareTime(position.getDeviceTime(), other.position.getDeviceTime());
            if (deviceTimeResult != 0) {
                return deviceTimeResult;
            }

            return position.getServerTime().compareTo(other.position.getServerTime());
        }
    }

    private final Timer timer = new HashedWheelTimer();
    private final Callback callback;
    private final long threshold;

    private final Map<Long, TreeSet<Holder>> buffer = new HashMap<>();

    // Fork: adaptive buffering (see Keys.SERVER_BUFFERING_LIVE_TOLERANCE)
    private final long liveTolerance;
    private final Map<Long, Date> lastReleased = new HashMap<>();
    private final ProcessingTimings timings;

    public BufferingManager(Config config, Callback callback) {
        this(config, callback, null);
    }

    public BufferingManager(Config config, Callback callback, ProcessingTimings timings) {
        this.callback = callback;
        this.timings = timings;
        threshold = config.getLong(Keys.SERVER_BUFFERING_THRESHOLD);
        liveTolerance = config.getLong(Keys.SERVER_BUFFERING_LIVE_TOLERANCE);
    }

    private void count(String name) {
        if (timings != null) {
            timings.count(name);
        }
    }

    // Called with the buffer lock held
    private void markReleased(Position position) {
        Date fixTime = position.getFixTime();
        if (fixTime != null) {
            lastReleased.merge(position.getDeviceId(), fixTime, (a, b) -> a.after(b) ? a : b);
        }
    }

    // Called with the buffer lock held. Live = recent fix and not older than the last released position. It does not
    // wait behind held positions: a held one released later is out of order, and DistanceHandler measures it from
    // the previous position by fix time (heartbeats without a fix would otherwise hold every live position)
    private boolean isLive(Position position) {
        if (liveTolerance <= 0 || position.getFixTime() == null || position.getServerTime() == null) {
            return false;
        }
        long age = Math.abs(position.getServerTime().getTime() - position.getFixTime().getTime());
        if (age > liveTolerance) {
            return false;
        }
        Date last = lastReleased.get(position.getDeviceId());
        return last == null || !position.getFixTime().before(last);
    }

    private Timeout scheduleTimeout(Holder holder) {
        return timer.newTimeout(
                timeout -> {
                    LOGGER.debug("released {}", holder.position.getFixTime());
                    synchronized (buffer) {
                        buffer.get(holder.position.getDeviceId()).remove(holder);
                        markReleased(holder.position);
                    }
                    holder.context.executor().execute(() -> {
                        callback.onReleased(holder.context, holder.position);
                    });
                },
                threshold, TimeUnit.MILLISECONDS);
    }

    public void accept(ChannelHandlerContext context, Position position) {
        if (threshold > 0) {
            boolean live;
            synchronized (buffer) {
                live = isLive(position);
                if (live) {
                    markReleased(position);
                } else {
                    LOGGER.debug("queued {}", position.getFixTime());
                    var queue = buffer.computeIfAbsent(position.getDeviceId(), k -> new TreeSet<>());
                    Holder holder = new Holder(context, position);
                    holder.timeout = scheduleTimeout(holder);
                    queue.add(holder);
                    queue.tailSet(holder, false).forEach(h -> {
                        h.timeout.cancel();
                        h.timeout = scheduleTimeout(h);
                    });
                }
            }
            count(live ? ProcessingTimings.COUNT_LIVE : ProcessingTimings.COUNT_BUFFERED);
            if (live) {
                callback.onReleased(context, position);
            }
        } else {
            callback.onReleased(context, position);
        }
    }

}
