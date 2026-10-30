package org.traccar.publicaccess;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PublicAccessServiceTest {

    private static Device device(long id, boolean isPublic, boolean disabled) {
        Device device = new Device();
        device.setId(id);
        device.setName("bus " + id);
        device.setDisabled(disabled);
        if (isPublic) {
            device.set("public", true);
        }
        return device;
    }

    private static Position position(long deviceId) {
        Position position = new Position();
        position.setDeviceId(deviceId);
        return position;
    }

    @Test
    public void testFeaturesDisabledByDefault() {
        var service = new PublicAccessService(new Config(), mock(Storage.class));
        assertFalse(service.isEnabled("devices"));
        assertFalse(service.isEnabled("stream"));
    }

    @Test
    public void testFeatureList() {
        Config config = new Config();
        config.setString(Keys.PUBLIC_ENABLE, "true");
        config.setString(Keys.PUBLIC_FEATURES, " devices , stream ");
        var service = new PublicAccessService(config, mock(Storage.class));
        assertTrue(service.isEnabled("devices"));
        assertTrue(service.isEnabled("stream"));
        assertFalse(service.isEnabled("positions"));
    }

    @Test
    public void testOnlyPublicEnabledDevices() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of(
                device(1, true, false), device(2, false, false), device(3, true, true)));
        when(storage.getObjects(eq(Position.class), any(Request.class))).thenReturn(List.of(
                position(1), position(2), position(3)));

        var service = new PublicAccessService(new Config(), storage);

        assertEquals(List.of(1L), List.copyOf(service.getDevices().keySet()));
        assertTrue(service.isPublic(1));
        assertFalse(service.isPublic(2));
        assertFalse(service.isPublic(3));
        assertEquals(1, service.getLatestPositions().size());
        assertEquals(1, service.getLatestPositions().getFirst().getDeviceId());
    }

}
