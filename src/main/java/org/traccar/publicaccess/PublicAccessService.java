package org.traccar.publicaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which features and devices are available without login.
 */
@Singleton
public class PublicAccessService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicAccessService.class);

    private final Storage storage;
    private final boolean enabled;
    private final Set<String> features;
    private final String attribute;
    private final long cacheTtl;

    private Map<Long, Device> devices = Map.of();
    private long cacheTime;

    @Inject
    public PublicAccessService(Config config, Storage storage) {
        this.storage = storage;
        enabled = config.getBoolean(Keys.PUBLIC_ENABLE);
        features = Arrays.stream(config.getString(Keys.PUBLIC_FEATURES).split(","))
                .map(String::trim)
                .filter(feature -> !feature.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        attribute = config.getString(Keys.PUBLIC_ATTRIBUTE);
        cacheTtl = config.getInteger(Keys.PUBLIC_CACHE_TTL) * 1000L;
    }

    public boolean isEnabled(String feature) {
        return enabled && features.contains(feature);
    }

    /**
     * Returns enabled devices that have the public attribute set, refreshing the cache when it is older than
     * {@code public.cacheTtl}.
     */
    public synchronized Map<Long, Device> getDevices() throws StorageException {
        long now = System.currentTimeMillis();
        if (now - cacheTime > cacheTtl) {
            devices = storage.getObjects(Device.class, new Request(new Columns.All())).stream()
                    .filter(device -> !device.getDisabled() && device.getBoolean(attribute))
                    .collect(Collectors.toUnmodifiableMap(BaseModel::getId, device -> device));
            cacheTime = now;
        }
        return devices;
    }

    /**
     * Checks a device against the cache. Falls back to the last known list if the refresh fails, so it is safe to
     * call from update listeners.
     */
    public boolean isPublic(long deviceId) {
        try {
            return getDevices().containsKey(deviceId);
        } catch (StorageException e) {
            LOGGER.warn("Public device refresh error", e);
            synchronized (this) {
                return devices.containsKey(deviceId);
            }
        }
    }

    public List<Position> getLatestPositions() throws StorageException {
        Set<Long> deviceIds = getDevices().keySet();
        return storage.getObjects(Position.class, new Request(new Columns.All(), new Condition.LatestPositions()))
                .stream()
                .filter(position -> deviceIds.contains(position.getDeviceId()))
                .toList();
    }

}
