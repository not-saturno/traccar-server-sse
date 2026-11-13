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
package org.traccar;

import com.google.inject.Injector;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.traccar.config.Config;
import org.traccar.database.BufferingManager;
import org.traccar.database.NotificationManager;
import org.traccar.handler.BasePositionHandler;
import org.traccar.handler.BusinessHandler;
import org.traccar.handler.ComputedAttributesHandler;
import org.traccar.handler.CopyAttributesHandler;
import org.traccar.handler.DatabaseHandler;
import org.traccar.handler.DistanceHandler;
import org.traccar.handler.DriverHandler;
import org.traccar.handler.EngineHoursHandler;
import org.traccar.handler.FilterHandler;
import org.traccar.handler.GeocoderHandler;
import org.traccar.handler.GeofenceHandler;
import org.traccar.handler.GeolocationHandler;
import org.traccar.handler.HemisphereHandler;
import org.traccar.handler.MapMatcherHandler;
import org.traccar.handler.MotionHandler;
import org.traccar.handler.OutdatedHandler;
import org.traccar.handler.PositionForwardingHandler;
import org.traccar.handler.PostProcessHandler;
import org.traccar.handler.SpeedLimitHandler;
import org.traccar.handler.TimeHandler;
import org.traccar.handler.events.AlarmEventHandler;
import org.traccar.handler.events.BaseEventHandler;
import org.traccar.handler.events.BehaviorEventHandler;
import org.traccar.handler.events.CommandResultEventHandler;
import org.traccar.handler.events.DriverEventHandler;
import org.traccar.handler.events.FuelEventHandler;
import org.traccar.handler.events.GeofenceEventHandler;
import org.traccar.handler.events.IgnitionEventHandler;
import org.traccar.handler.events.MaintenanceEventHandler;
import org.traccar.handler.events.MediaEventHandler;
import org.traccar.handler.events.MotionEventHandler;
import org.traccar.handler.events.OverspeedEventHandler;
import org.traccar.handler.events.ProximityEventHandler;
import org.traccar.handler.network.AcknowledgementHandler;
import org.traccar.helper.PositionLogger;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.timing.ProcessingTimings;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.stream.Stream;

@Singleton
@ChannelHandler.Sharable
public class ProcessingHandler extends ChannelInboundHandlerAdapter implements BufferingManager.Callback {

    private final CacheManager cacheManager;
    private final NotificationManager notificationManager;
    private final PositionLogger positionLogger;
    private final BufferingManager bufferingManager;
    private final List<BasePositionHandler> positionHandlers;
    private final List<BaseEventHandler> eventHandlers;
    private final PostProcessHandler postProcessHandler;
    private final ProcessingTimings timings;

    // Fork: nanoTime when the position reached this handler and when the buffer released it, for the timings
    private record Times(long received, long released) {}

    private record QueuedPosition(ChannelHandlerContext ctx, Position position, Times times) {}

    // Fork: arrival time while the position waits in the buffer (Position has no equals, so this is by identity)
    private final Map<Position, Long> receivedAt = Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<Long, Queue<QueuedPosition>> queues = new HashMap<>();

    private synchronized Queue<QueuedPosition> getQueue(long deviceId) {
        return queues.computeIfAbsent(deviceId, k -> new LinkedList<>());
    }

    @Inject
    public ProcessingHandler(
            Injector injector, Config config,
            CacheManager cacheManager, NotificationManager notificationManager, PositionLogger positionLogger,
            ProcessingTimings timings) {
        this.cacheManager = cacheManager;
        this.timings = timings;
        this.notificationManager = notificationManager;
        this.positionLogger = positionLogger;
        bufferingManager = new BufferingManager(config, this, timings);

        positionHandlers = Stream.of(
                ComputedAttributesHandler.Early.class,
                OutdatedHandler.class,
                TimeHandler.class,
                GeolocationHandler.class,
                HemisphereHandler.class,
                MapMatcherHandler.class,
                DistanceHandler.class,
                FilterHandler.class,
                GeofenceHandler.class,
                GeocoderHandler.class,
                SpeedLimitHandler.class,
                MotionHandler.class,
                BusinessHandler.class,
                ComputedAttributesHandler.Late.class,
                DriverHandler.class,
                CopyAttributesHandler.class,
                EngineHoursHandler.class,
                PositionForwardingHandler.class,
                DatabaseHandler.class)
                .map((clazz) -> (BasePositionHandler) injector.getInstance(clazz))
                .filter(Objects::nonNull)
                .toList();

        eventHandlers = Stream.of(
                MediaEventHandler.class,
                CommandResultEventHandler.class,
                OverspeedEventHandler.class,
                BehaviorEventHandler.class,
                FuelEventHandler.class,
                MotionEventHandler.class,
                GeofenceEventHandler.class,
                ProximityEventHandler.class,
                AlarmEventHandler.class,
                IgnitionEventHandler.class,
                MaintenanceEventHandler.class,
                DriverEventHandler.class)
                .map((clazz) -> (BaseEventHandler) injector.getInstance(clazz))
                .filter(Objects::nonNull)
                .toList();

        postProcessHandler = injector.getInstance(PostProcessHandler.class);

        timings.register(ProcessingTimings.BUFFER, ProcessingTimings.Kind.STAGE);
        timings.register(ProcessingTimings.QUEUE, ProcessingTimings.Kind.STAGE);
        positionHandlers.forEach(handler -> timings.register(
                ProcessingTimings.nameOf(handler.getClass()), ProcessingTimings.Kind.POSITION_HANDLER));
        eventHandlers.forEach(handler -> timings.register(
                ProcessingTimings.nameOf(handler.getClass()), ProcessingTimings.Kind.EVENT_HANDLER));
        timings.register(ProcessingTimings.POST_PROCESS, ProcessingTimings.Kind.STAGE);
        timings.register(ProcessingTimings.TOTAL, ProcessingTimings.Kind.STAGE);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Position position) {
            cacheManager.addDevice(position.getDeviceId(), position);
            receivedAt.put(position, System.nanoTime());
            bufferingManager.accept(ctx, position);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void onReleased(ChannelHandlerContext context, Position position) {
        long released = System.nanoTime();
        Long received = receivedAt.remove(position);
        if (received != null) {
            timings.record(ProcessingTimings.BUFFER, ProcessingTimings.Kind.STAGE, released - received);
        }
        Times times = new Times(received != null ? received : released, released);
        Queue<QueuedPosition> queue = getQueue(position.getDeviceId());
        boolean queued;
        synchronized (queue) {
            queued = !queue.isEmpty();
            queue.offer(new QueuedPosition(context, position, times));
        }
        if (!queued) {
            processPositionHandlers(context, position, times);
        }
    }

    // Fork: times each handler from the call until its callback (handlers may finish asynchronously)
    private void handleTimed(BasePositionHandler handler, Position position, BasePositionHandler.Callback callback) {
        String name = ProcessingTimings.nameOf(handler.getClass());
        long start = System.nanoTime();
        handler.handlePosition(position, filtered -> {
            timings.record(name, ProcessingTimings.Kind.POSITION_HANDLER, System.nanoTime() - start);
            callback.processed(filtered);
        });
    }

    private void processPositionHandlers(ChannelHandlerContext ctx, Position position, Times times) {
        timings.record(ProcessingTimings.QUEUE, ProcessingTimings.Kind.STAGE, System.nanoTime() - times.released());
        var iterator = positionHandlers.iterator();
        handleTimed(iterator.next(), position, new BasePositionHandler.Callback() {
            @Override
            public void processed(boolean filtered) {
                Runnable continuation = () -> {
                    if (!filtered) {
                        if (iterator.hasNext()) {
                            handleTimed(iterator.next(), position, this);
                        } else {
                            processEventHandlers(ctx, position, times);
                        }
                    } else {
                        finishedProcessing(ctx, position, true, times);
                    }
                };
                if (ctx.executor().inEventLoop()) {
                    continuation.run();
                } else {
                    ctx.executor().execute(continuation);
                }
            }
        });
    }

    private void processEventHandlers(ChannelHandlerContext ctx, Position position, Times times) {
        eventHandlers.forEach(handler -> {
            long start = System.nanoTime();
            handler.analyzePosition(
                    position, (event) -> notificationManager.updateEvents(Map.of(event, position)));
            timings.record(ProcessingTimings.nameOf(handler.getClass()),
                    ProcessingTimings.Kind.EVENT_HANDLER, System.nanoTime() - start);
        });
        finishedProcessing(ctx, position, false, times);
    }

    private void finishedProcessing(ChannelHandlerContext ctx, Position position, boolean filtered, Times times) {
        if (!filtered) {
            long start = System.nanoTime();
            postProcessHandler.handlePosition(position, ignore -> {
                long now = System.nanoTime();
                timings.record(ProcessingTimings.POST_PROCESS, ProcessingTimings.Kind.STAGE, now - start);
                timings.record(ProcessingTimings.TOTAL, ProcessingTimings.Kind.STAGE, now - times.received());
                positionLogger.log(ctx, position);
                ctx.writeAndFlush(new AcknowledgementHandler.EventHandled(position));
                processNextPosition(position.getDeviceId());
            });
        } else {
            ctx.writeAndFlush(new AcknowledgementHandler.EventHandled(position));
            processNextPosition(position.getDeviceId());
        }
        cacheManager.removeDevice(position.getDeviceId(), position);
    }

    private void processNextPosition(long deviceId) {
        Queue<QueuedPosition> queue = getQueue(deviceId);
        QueuedPosition next;
        synchronized (queue) {
            queue.poll(); // remove current position
            next = queue.peek();
        }
        if (next != null) {
            next.ctx().executor().execute(
                    () -> processPositionHandlers(next.ctx(), next.position(), next.times()));
        }
    }

}
