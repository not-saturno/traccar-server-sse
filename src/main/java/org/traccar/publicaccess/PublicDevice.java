package org.traccar.publicaccess;

import org.traccar.model.Device;

import java.util.Date;

/**
 * Device fields safe to expose without login. Identifiers such as uniqueId, phone, contact and custom attributes
 * are left out on purpose.
 */
public record PublicDevice(long id, String name, String category, String status, Date lastUpdate) {

    public static PublicDevice of(Device device) {
        return new PublicDevice(
                device.getId(), device.getName(), device.getCategory(), device.getStatus(), device.getLastUpdate());
    }

}
