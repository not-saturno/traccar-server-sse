package org.traccar.api.resource.v1;

import org.traccar.api.BaseResource;
import org.traccar.emission.EmissionReportService;
import org.traccar.emission.EmissionReports;
import org.traccar.helper.model.UserUtil;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Fork (BFF): emission, consumption and cost reports ready for the report page of the front. The server streams the
 * positions and sends only the aggregated rows. Months and hour labels follow {@code timezone} (IANA name, default
 * the user or server timezone).
 */
@Path("v1/reports/emissions")
@Produces(MediaType.APPLICATION_JSON)
public class EmissionReportResource extends BaseResource {

    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("pt-BR");

    @Inject
    private EmissionReportService reportService;

    private ZoneId zone(String timezone) throws StorageException {
        if (timezone != null && !timezone.isEmpty()) {
            try {
                return ZoneId.of(timezone);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("Unknown timezone " + timezone);
            }
        }
        return UserUtil.getTimezone(permissionsService.getServer(), permissionsService.getUser(getUserId()))
                .toZoneId();
    }

    private Locale locale() throws StorageException {
        String language = UserUtil.getLanguage(permissionsService.getServer(), permissionsService.getUser(getUserId()));
        return language != null ? Locale.forLanguageTag(language.replace('_', '-')) : DEFAULT_LOCALE;
    }

    private static YearMonth month(String value, YearMonth defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Month must be YYYY-MM");
        }
    }

    private Device device(long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        Device device = storage.getObject(Device.class, new Request(
                new Columns.Include("id", "name", "category"), new Condition.Equals("id", deviceId)));
        if (device == null) {
            throw new IllegalArgumentException("Device not found");
        }
        return device;
    }

    /** Every device for administrators, the user's devices otherwise. */
    @GET
    @Path("fleet")
    public EmissionReports.FleetReport fleet(
            @QueryParam("from") String from, @QueryParam("to") String to,
            @QueryParam("timezone") String timezone) throws StorageException {
        ZoneId zone = zone(timezone);
        int year = YearMonth.now(zone).getYear();
        long userId = getUserId();
        Columns columns = new Columns.Include("id", "name", "category");
        List<Device> devices = permissionsService.notAdmin(userId)
                ? storage.getObjects(Device.class, new Request(
                        columns, new Condition.Permission(User.class, userId, Device.class), new Order("name")))
                : storage.getObjects(Device.class, new Request(columns, new Order("name")));
        return reportService.fleet(
                devices, month(from, YearMonth.of(year, 1)), month(to, YearMonth.of(year, 12)), zone, locale());
    }

    @GET
    @Path("monthly")
    public EmissionReports.MonthlyReport monthly(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") String from, @QueryParam("to") String to,
            @QueryParam("timezone") String timezone) throws StorageException {
        ZoneId zone = zone(timezone);
        int year = YearMonth.now(zone).getYear();
        return reportService.monthly(
                device(deviceId), month(from, YearMonth.of(year, 1)), month(to, YearMonth.now(zone)), zone, locale());
    }

    @GET
    @Path("instant")
    public EmissionReports.InstantReport instant(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to,
            @QueryParam("timezone") String timezone) throws StorageException {
        return reportService.instant(device(deviceId), from, to, zone(timezone));
    }

}
