package org.traccar.publicaccess;

import org.traccar.model.Position;

import java.util.Date;

/**
 * Position fields safe to expose without login. Protocol attributes and network data are left out on purpose.
 */
public record PublicPosition(
        long deviceId, double latitude, double longitude, double speed, double course, Date fixTime) {

    public static PublicPosition of(Position position) {
        return new PublicPosition(
                position.getDeviceId(), position.getLatitude(), position.getLongitude(),
                position.getSpeed(), position.getCourse(), position.getFixTime());
    }

}
