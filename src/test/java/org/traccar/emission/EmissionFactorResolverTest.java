package org.traccar.emission;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EmissionFactorResolverTest {

    private static EmissionFactor factor(long id, EmissionParameter parameter, String category, String period,
            double value) {
        EmissionFactor factor = new EmissionFactor();
        factor.setId(id);
        factor.setParameter(parameter.getKey());
        factor.setCategory(category);
        factor.setPeriod(period);
        factor.setFactorValue(value);
        return factor;
    }

    private static final EmissionParameter GRID = EmissionParameter.ELECTRIC_EMISSION_FACTOR;

    @Test
    public void testDefaultWithoutHistory() {
        var resolver = new EmissionFactorResolver(List.of());
        var resolved = resolver.resolve(GRID, null, LocalDate.of(2026, 3, 15));
        assertEquals(GRID.getDefaultValue(), resolved.value());
        assertNull(resolved.factor());
    }

    @Test
    public void testMostSpecificPeriodWins() {
        var resolver = new EmissionFactorResolver(List.of(
                factor(1, GRID, null, "2026", 0.10),
                factor(2, GRID, null, "2026-03", 0.20),
                factor(3, GRID, null, "2026-03-15", 0.30)));
        assertEquals(0.30, resolver.resolve(GRID, null, LocalDate.of(2026, 3, 15)).value());
        assertEquals(0.20, resolver.resolve(GRID, null, LocalDate.of(2026, 3, 16)).value());
        assertEquals(0.20, resolver.resolve(GRID, null, LocalDate.of(2026, 3, 31)).value());
        assertEquals(0.10, resolver.resolve(GRID, null, LocalDate.of(2026, 4, 1)).value());
        assertEquals(0.10, resolver.resolve(GRID, null, LocalDate.of(2026, 1, 1)).value());
    }

    @Test
    public void testCarryForwardAndBeforeHistory() {
        var resolver = new EmissionFactorResolver(List.of(
                factor(1, GRID, null, "2025", 0.10),
                factor(2, GRID, null, "2026-03", 0.20)));
        // after the last period the most recent value is kept
        assertEquals(0.20, resolver.resolve(GRID, null, LocalDate.of(2026, 7, 1)).value());
        // gap between periods: the previous one is kept
        assertEquals(0.10, resolver.resolve(GRID, null, LocalDate.of(2026, 1, 10)).value());
        // before any period: default
        assertEquals(GRID.getDefaultValue(), resolver.resolve(GRID, null, LocalDate.of(2024, 12, 31)).value());
    }

    @Test
    public void testCategoryPreferred() {
        var efficiency = EmissionParameter.DIESEL_EFFICIENCY;
        var resolver = new EmissionFactorResolver(List.of(
                factor(1, efficiency, null, "2026-03-01", 2.0),
                factor(2, efficiency, "gasShip", "2026", 1.2)));
        LocalDate day = LocalDate.of(2026, 3, 1);
        assertEquals(1.2, resolver.resolve(efficiency, "gasShip", day).value());
        assertEquals(2.0, resolver.resolve(efficiency, "gasBus", day).value());
        assertEquals(2.0, resolver.resolve(efficiency, null, day).value());
    }

    @Test
    public void testPeriodValidation() {
        assertTrue(EmissionFactor.isValidPeriod("2026"));
        assertTrue(EmissionFactor.isValidPeriod("2026-02"));
        assertTrue(EmissionFactor.isValidPeriod("2024-02-29"));
        assertFalse(EmissionFactor.isValidPeriod("2026-13"));
        assertFalse(EmissionFactor.isValidPeriod("2026-02-30"));
        assertFalse(EmissionFactor.isValidPeriod("26-02"));
        assertFalse(EmissionFactor.isValidPeriod(null));
    }

}
