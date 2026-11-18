package org.traccar.emission;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.Storage;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EmissionReportServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Belem");

    private static Position position(LocalDateTime time, double totalDistance) {
        Position position = new Position();
        position.setFixTime(Date.from(time.atZone(ZONE).toInstant()));
        position.set(Position.KEY_TOTAL_DISTANCE, totalDistance);
        return position;
    }

    @Test
    public void testMonthlyUsesFactorOfEachDay() throws Exception {
        Storage storage = mock(Storage.class);
        EmissionFactor march16 = new EmissionFactor();
        march16.setId(7);
        march16.setParameter(EmissionParameter.ELECTRIC_EMISSION_FACTOR.getKey());
        march16.setPeriod("2026-03-16");
        march16.setFactorValue(1.0);
        when(storage.getObjects(eq(EmissionFactor.class), any())).thenReturn(List.of(march16));
        List<Position> positions = List.of(
                position(LocalDateTime.of(2026, 3, 15, 10, 0), 0),
                position(LocalDateTime.of(2026, 3, 15, 10, 30), 10_000), // 10 km on the 15th (default factor)
                position(LocalDateTime.of(2026, 3, 16, 10, 0), 20_000), // 10 km on the 16th (factor 1.0)
                position(LocalDateTime.of(2026, 4, 1, 0, 30), 30_000)); // 10 km in April (factor carried forward)
        when(storage.getObjectsStream(eq(Position.class), any())).thenAnswer(invocation -> positions.stream());

        EmissionReportService service = new EmissionReportService(
                new Config(), storage, new EmissionFactorService(storage));
        Device device = new Device();
        device.setId(1);
        device.setName("Bus");
        device.setCategory("electricBus");

        var report = service.monthly(device, YearMonth.of(2026, 3), YearMonth.of(2026, 4), ZONE, Locale.ROOT);

        assertEquals(PowerType.ELECTRIC, report.device().powerType());
        assertEquals(2, report.months().size());
        var march = report.months().get(0);
        assertEquals("2026-03", march.period());
        assertEquals(20, march.distanceKm(), 1e-9);
        assertEquals(16, march.electricKwh(), 1e-9);
        double expected = (8 * EmissionParameter.ELECTRIC_EMISSION_FACTOR.getDefaultValue() + 8 * 1.0) / 1000;
        assertEquals(expected, march.emissions(), 1e-12);
        assertEquals(10, report.months().get(1).distanceKm(), 1e-9);
        assertEquals(30, report.summary().distanceKm(), 1e-9);
        assertEquals(1 / 0.8, report.summary().efficiency(), 1e-9);

        var grid = report.factors().stream()
                .filter(segment -> segment.parameter().equals("electricEmissionFactor")).toList();
        // the day value is carried forward until a newer period starts
        assertEquals(2, grid.size());
        assertEquals("2026-03-01", grid.get(0).from());
        assertEquals("2026-03-15", grid.get(0).to());
        assertNull(grid.get(0).factorId());
        assertEquals("2026-03-16", grid.get(1).from());
        assertEquals(7L, grid.get(1).factorId());
        assertEquals("2026-04-30", grid.get(1).to());
        assertEquals(8 * 1.0 / 1000, report.months().get(1).emissions(), 1e-12);
    }

    @Test
    public void testCombustionCategory() {
        EmissionReportService service = new EmissionReportService(
                new Config(), mock(Storage.class), new EmissionFactorService(mock(Storage.class)));
        assertEquals(PowerType.COMBUSTION, service.getPowerType("gasBus"));
        assertEquals(PowerType.COMBUSTION, service.getPowerType("gasShip"));
        assertEquals(PowerType.ELECTRIC, service.getPowerType("electricShip"));
        assertEquals(PowerType.ELECTRIC, service.getPowerType(null));
    }

    @Test
    public void testMonthLabel() {
        assertEquals("MAR/26", EmissionReportService.monthLabel(
                YearMonth.of(2026, 3), Locale.forLanguageTag("pt-BR")));
    }

}
