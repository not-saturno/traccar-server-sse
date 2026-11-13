package org.traccar.database;

import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class BufferingManagerTest {

    private static Position position(long deviceId, long fixTimeOffset) {
        Position position = new Position();
        position.setDeviceId(deviceId);
        Date now = new Date();
        position.setServerTime(now);
        position.setDeviceTime(new Date(now.getTime() + fixTimeOffset));
        position.setFixTime(new Date(now.getTime() + fixTimeOffset));
        return position;
    }

    @Test
    public void testLivePositionReleasedImmediately() {
        List<Position> released = new ArrayList<>();
        var manager = new BufferingManager(new Config(), (ctx, p) -> released.add(p));

        Position live = position(1, 0);
        manager.accept(mock(ChannelHandlerContext.class), live);

        assertEquals(List.of(live), released);
    }

    @Test
    public void testHistoricalPositionBuffered() {
        List<Position> released = new ArrayList<>();
        var manager = new BufferingManager(new Config(), (ctx, p) -> released.add(p));

        manager.accept(mock(ChannelHandlerContext.class), position(1, -60_000));

        assertTrue(released.isEmpty());
    }

    @Test
    public void testOutOfOrderPositionBuffered() {
        List<Position> released = new ArrayList<>();
        var manager = new BufferingManager(new Config(), (ctx, p) -> released.add(p));

        Position newer = position(1, 0);
        manager.accept(mock(ChannelHandlerContext.class), newer);
        manager.accept(mock(ChannelHandlerContext.class), position(1, -2_000));

        assertEquals(List.of(newer), released);
    }

    @Test
    public void testLivePositionNotHeldBehindHistorical() {
        List<Position> released = new ArrayList<>();
        var manager = new BufferingManager(new Config(), (ctx, p) -> released.add(p));

        manager.accept(mock(ChannelHandlerContext.class), position(1, -60_000));
        Position live = position(1, 0);
        manager.accept(mock(ChannelHandlerContext.class), live);

        assertEquals(List.of(live), released);
    }

    @Test
    public void testAdaptiveDisabled() {
        List<Position> released = new ArrayList<>();
        Config config = new Config();
        config.setString(Keys.SERVER_BUFFERING_LIVE_TOLERANCE, "0");
        var manager = new BufferingManager(config, (ctx, p) -> released.add(p));

        manager.accept(mock(ChannelHandlerContext.class), position(1, 0));

        assertTrue(released.isEmpty());
    }

}
