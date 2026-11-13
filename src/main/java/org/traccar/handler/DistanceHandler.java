/*
 * Copyright 2016 - 2025 Anton Tananaev (anton@traccar.org)
 * Copyright 2015 Amila Silva
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
package org.traccar.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DistanceCalculator;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;
import org.traccar.timing.ProcessingTimings;

import jakarta.inject.Inject;

public class DistanceHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistanceHandler.class);

    private final CacheManager cacheManager;
    private final Storage storage;
    private final ProcessingTimings timings;

    private final boolean filter;
    private final int minError;
    private final int maxError;

    public DistanceHandler(Config config, CacheManager cacheManager) {
        this(config, cacheManager, null, null);
    }

    @Inject
    public DistanceHandler(Config config, CacheManager cacheManager, Storage storage, ProcessingTimings timings) {
        this.cacheManager = cacheManager;
        this.storage = storage;
        this.timings = timings;
        this.filter = config.getBoolean(Keys.COORDINATES_FILTER);
        this.minError = config.getInteger(Keys.COORDINATES_MIN_ERROR);
        this.maxError = config.getInteger(Keys.COORDINATES_MAX_ERROR);
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        double distance = 0.0;
        if (position.hasAttribute(Position.KEY_DISTANCE)) {
            distance = position.getDouble(Position.KEY_DISTANCE);
        }
        double totalDistance;
        Position last = cacheManager.getPosition(position.getDeviceId());
        if (last != null && isOutOfOrder(position, last)) {
            last = findPrevious(position, last);
        }
        if (last != null) {
            totalDistance = last.getDouble(Position.KEY_TOTAL_DISTANCE);
            if (!position.hasAttribute(Position.KEY_DISTANCE)) {
                distance = DistanceCalculator.distance(position, last);
            }
            if (filter && last.getLatitude() != 0 && last.getLongitude() != 0) {
                boolean satisfiesMin = minError == 0 || distance > minError;
                boolean satisfiesMax = maxError == 0 || distance < maxError;
                if (!satisfiesMin || !satisfiesMax) {
                    position.setValid(last.getValid());
                    position.setLatitude(last.getLatitude());
                    position.setLongitude(last.getLongitude());
                    distance = 0;
                }
            }
        } else {
            totalDistance = 0.0;
        }
        position.set(Position.KEY_DISTANCE, distance);
        position.set(Position.KEY_TOTAL_DISTANCE, totalDistance + distance);

        callback.processed(false);
    }

    private static boolean isOutOfOrder(Position position, Position last) {
        return position.getFixTime() != null && last.getFixTime() != null
                && position.getFixTime().before(last.getFixTime());
    }

    /**
     * Fork: the cached position is the latest one, so a position that arrives out of order would measure the
     * distance from a point after it. Use the stored position right before it by fix time instead (null if there
     * is none). On a storage error keep the original behavior.
     */
    private Position findPrevious(Position position, Position latest) {
        if (storage == null) {
            return latest;
        }
        if (timings != null) {
            timings.count(ProcessingTimings.COUNT_OUT_OF_ORDER);
        }
        try {
            return storage.getObject(Position.class, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("deviceId", position.getDeviceId()),
                            new Condition.Compare("fixTime", "<", position.getFixTime())),
                    new Order("fixTime", true, 1)));
        } catch (StorageException e) {
            LOGGER.warn("Failed to load previous position", e);
            return latest;
        }
    }

}
