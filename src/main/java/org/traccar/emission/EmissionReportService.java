package org.traccar.emission;

import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.emission.EmissionFactorResolver.Resolved;
import org.traccar.emission.EmissionReports.DeviceInfo;
import org.traccar.emission.EmissionReports.FactorSegment;
import org.traccar.emission.EmissionReports.FleetDevice;
import org.traccar.emission.EmissionReports.FleetReport;
import org.traccar.emission.EmissionReports.InstantReport;
import org.traccar.emission.EmissionReports.MonthlyReport;
import org.traccar.emission.EmissionReports.Period;
import org.traccar.emission.EmissionReports.Row;
import org.traccar.emission.EmissionReports.Summary;
import org.traccar.emission.EmissionReports.Units;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fork: emission, consumption and cost reports computed on the server from the positions of a device. Distance is
 * aggregated per hour (see {@link HourlyDistance}); each hour uses the parameters that apply to its day (see
 * {@link EmissionFactorResolver}), and months are the sum of their hours.
 */
@Singleton
public class EmissionReportService {

    private static final String[] POSITION_COLUMNS = {"fixTime", "latitude", "longitude", "attributes"};
    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_HOUR_LABEL = DateTimeFormatter.ofPattern("dd/MM HH'h'");

    private final Storage storage;
    private final EmissionFactorService factorService;
    private final Set<String> combustionCategories;
    private final double maxHourlyDistance;
    private final int routeMaxPoints;
    private final int instantMaxDays;
    private final int monthlyMaxMonths;

    @Inject
    public EmissionReportService(Config config, Storage storage, EmissionFactorService factorService) {
        this.storage = storage;
        this.factorService = factorService;
        combustionCategories = Arrays.stream(config.getString(Keys.EMISSIONS_COMBUSTION_CATEGORIES).split(","))
                .map(String::trim)
                .filter(category -> !category.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        maxHourlyDistance = config.getDouble(Keys.EMISSIONS_MAX_HOURLY_DISTANCE);
        routeMaxPoints = config.getInteger(Keys.EMISSIONS_ROUTE_MAX_POINTS);
        instantMaxDays = config.getInteger(Keys.EMISSIONS_INSTANT_MAX_DAYS);
        monthlyMaxMonths = config.getInteger(Keys.EMISSIONS_MONTHLY_MAX_MONTHS);
    }

    public PowerType getPowerType(String category) {
        return category != null && combustionCategories.contains(category)
                ? PowerType.COMBUSTION : PowerType.ELECTRIC;
    }

    private DeviceInfo info(Device device) {
        return new DeviceInfo(device.getId(), device.getName(), device.getCategory(),
                getPowerType(device.getCategory()));
    }

    private record Hours(Instant start, EmissionAmounts[] amounts, HourlyDistance distance) {
    }

    private Hours compute(
            Device device, Instant from, Instant to, ZoneId zone, int routePoints) throws StorageException {
        HourlyDistance distance = new HourlyDistance(
                from.toEpochMilli(), to.toEpochMilli(), maxHourlyDistance, routePoints);

        // last position before the interval, so the distance up to the first position inside it is counted
        Position before = storage.getObject(Position.class, new Request(
                new Columns.Include(POSITION_COLUMNS),
                new Condition.And(
                        new Condition.Equals("deviceId", device.getId()),
                        new Condition.Compare("fixTime", "<", Date.from(from))),
                new Order("fixTime", true, 1)));
        if (before != null) {
            distance.add(before);
        }
        try (Stream<Position> positions = storage.getObjectsStream(Position.class, new Request(
                new Columns.Include(POSITION_COLUMNS),
                new Condition.And(
                        new Condition.Equals("deviceId", device.getId()),
                        new Condition.Between("fixTime", Date.from(from), Date.from(to))),
                new Order("fixTime")))) {
            positions.forEach(distance::add);
        }

        double[] kilometers = distance.getKilometers();
        EmissionFactorResolver resolver = factorService.getResolver();
        Map<LocalDate, Map<EmissionParameter, Resolved>> days = new HashMap<>();
        EmissionAmounts[] amounts = new EmissionAmounts[kilometers.length];
        for (int i = 0; i < kilometers.length; i++) {
            LocalDate day = from.plusMillis(i * HourlyDistance.HOUR).atZone(zone).toLocalDate();
            var values = days.computeIfAbsent(day, key -> resolver.resolveAll(device.getCategory(), key));
            amounts[i] = EmissionAmounts.of(kilometers[i], values);
        }
        return new Hours(from, amounts, distance);
    }

    private static EmissionAmounts total(EmissionAmounts[] amounts) {
        EmissionAmounts total = EmissionAmounts.ZERO;
        for (EmissionAmounts amount : amounts) {
            total = total.plus(amount);
        }
        return total;
    }

    private void checkMonths(YearMonth from, YearMonth to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("End month is before start month");
        }
        if (ChronoUnit.MONTHS.between(from, to) >= monthlyMaxMonths) {
            throw new IllegalArgumentException("Interval must not exceed " + monthlyMaxMonths + " months");
        }
    }

    static String monthLabel(YearMonth month, Locale locale) {
        String name = month.getMonth().getDisplayName(TextStyle.SHORT, locale).replace(".", "");
        return name.toUpperCase(locale) + "/" + String.format("%02d", month.getYear() % 100);
    }

    private List<Row> monthRows(
            Hours hours, YearMonth from, YearMonth to, ZoneId zone, Locale locale, PowerType type) {
        Map<YearMonth, EmissionAmounts> sums = new LinkedHashMap<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            sums.put(month, EmissionAmounts.ZERO);
        }
        for (int i = 0; i < hours.amounts().length; i++) {
            YearMonth month = YearMonth.from(hours.start().plusMillis(i * HourlyDistance.HOUR).atZone(zone));
            EmissionAmounts sum = sums.get(month);
            if (sum != null) {
                sums.put(month, sum.plus(hours.amounts()[i]));
            }
        }
        List<Row> rows = new ArrayList<>(sums.size());
        sums.forEach((month, amounts) -> rows.add(Row.of(
                month.toString(), monthLabel(month, locale),
                Date.from(month.atDay(1).atStartOfDay(zone).toInstant()),
                Date.from(month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()),
                amounts, type)));
        return rows;
    }

    /** Days {@code from} to {@code to} (inclusive) grouped into runs where each parameter keeps the same value. */
    public List<FactorSegment> factorTimeline(String category, LocalDate from, LocalDate to) throws StorageException {
        EmissionFactorResolver resolver = factorService.getResolver();
        List<FactorSegment> segments = new ArrayList<>();
        for (EmissionParameter parameter : EmissionParameter.values()) {
            LocalDate runStart = from;
            Resolved run = resolver.resolve(parameter, category, from);
            for (LocalDate day = from.plusDays(1); !day.isAfter(to.plusDays(1)); day = day.plusDays(1)) {
                Resolved current = day.isAfter(to) ? null : resolver.resolve(parameter, category, day);
                if (current == null || current.factor() != run.factor() || current.value() != run.value()) {
                    EmissionFactor factor = run.factor();
                    segments.add(new FactorSegment(
                            parameter.getKey(), parameter.getUnit(), runStart.toString(), day.minusDays(1).toString(),
                            run.value(), factor != null ? factor.getId() : null,
                            factor != null ? factor.getPeriod() : null, factor != null ? factor.getSource() : null));
                    runStart = day;
                    run = current;
                }
            }
        }
        return segments;
    }

    public MonthlyReport monthly(
            Device device, YearMonth from, YearMonth to, ZoneId zone, Locale locale) throws StorageException {
        checkMonths(from, to);
        Instant start = from.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = to.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusMillis(1);
        DeviceInfo info = info(device);
        Hours hours = compute(device, start, end, zone, 0);
        return new MonthlyReport(
                info, zone.getId(), from.toString(), to.toString(), Units.of(info.powerType()),
                Summary.of(total(hours.amounts()), info.powerType()),
                monthRows(hours, from, to, zone, locale, info.powerType()),
                factorTimeline(device.getCategory(), from.atDay(1), to.atEndOfMonth()));
    }

    public FleetReport fleet(
            List<Device> devices, YearMonth from, YearMonth to, ZoneId zone, Locale locale) throws StorageException {
        checkMonths(from, to);
        Instant start = from.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = to.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusMillis(1);
        List<Period> months = new ArrayList<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            months.add(new Period(month.toString(), monthLabel(month, locale)));
        }
        List<FleetDevice> result = new ArrayList<>(devices.size());
        for (Device device : devices) {
            DeviceInfo info = info(device);
            Hours hours = compute(device, start, end, zone, 0);
            result.add(new FleetDevice(
                    info, Units.of(info.powerType()), Summary.of(total(hours.amounts()), info.powerType()),
                    monthRows(hours, from, to, zone, locale, info.powerType())));
        }
        return new FleetReport(zone.getId(), from.toString(), to.toString(), months, result);
    }

    public InstantReport instant(Device device, Date from, Date to, ZoneId zone) throws StorageException {
        if (from == null || to == null || !to.after(from)) {
            throw new IllegalArgumentException("Interval end must be after its start");
        }
        if (to.getTime() - from.getTime() > instantMaxDays * 24 * HourlyDistance.HOUR) {
            throw new IllegalArgumentException("Interval must not exceed " + instantMaxDays + " days");
        }
        DeviceInfo info = info(device);
        Hours hours = compute(device, from.toInstant(), to.toInstant(), zone, routeMaxPoints);
        int count = hours.amounts().length;
        DateTimeFormatter labels = count > 24 ? DAY_HOUR_LABEL : HOUR_LABEL;
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Instant bucketStart = from.toInstant().plusMillis(i * HourlyDistance.HOUR);
            Instant bucketEnd = bucketStart.plusMillis(HourlyDistance.HOUR);
            LocalDateTime local = LocalDateTime.ofInstant(bucketStart, zone).truncatedTo(ChronoUnit.MINUTES);
            rows.add(Row.of(local.toString(), labels.format(local), Date.from(bucketStart), Date.from(bucketEnd),
                    hours.amounts()[i], info.powerType()));
        }
        LocalDate firstDay = LocalDate.ofInstant(from.toInstant(), zone);
        LocalDate lastDay = LocalDate.ofInstant(to.toInstant(), zone);
        return new InstantReport(
                info, zone.getId(), from, to, Units.of(info.powerType()),
                Summary.of(total(hours.amounts()), info.powerType()), rows, hours.distance().getRoute(),
                factorTimeline(device.getCategory(), firstDay, lastDay));
    }

}
