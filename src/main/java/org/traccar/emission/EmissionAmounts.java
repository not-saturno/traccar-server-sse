package org.traccar.emission;

import java.util.Map;

/**
 * Fork: distance, consumption, cost and emissions of a period. Both power types are always computed: the one that
 * matches the vehicle is the real value and the other is the projection used by the comparisons. Emissions in
 * tCO2e, costs in R$, diesel in L, energy in kWh.
 */
public record EmissionAmounts(
        double distanceKm,
        double dieselLiters,
        double electricKwh,
        double dieselCost,
        double electricCost,
        double dieselEmissions,
        double electricEmissions) {

    public static final EmissionAmounts ZERO = new EmissionAmounts(0, 0, 0, 0, 0, 0, 0);

    public static EmissionAmounts of(
            double distanceKm, Map<EmissionParameter, EmissionFactorResolver.Resolved> values) {
        if (distanceKm <= 0) {
            return ZERO;
        }
        double liters = distanceKm / values.get(EmissionParameter.DIESEL_EFFICIENCY).value();
        double kwh = distanceKm * values.get(EmissionParameter.ELECTRIC_CONSUMPTION).value();
        return new EmissionAmounts(
                distanceKm,
                liters,
                kwh,
                liters * values.get(EmissionParameter.DIESEL_PRICE).value(),
                kwh * values.get(EmissionParameter.ENERGY_TARIFF).value(),
                distanceKm * values.get(EmissionParameter.DIESEL_EMISSION_FACTOR).value() / 1000,
                kwh * values.get(EmissionParameter.ELECTRIC_EMISSION_FACTOR).value() / 1000);
    }

    public EmissionAmounts plus(EmissionAmounts other) {
        return new EmissionAmounts(
                distanceKm + other.distanceKm,
                dieselLiters + other.dieselLiters,
                electricKwh + other.electricKwh,
                dieselCost + other.dieselCost,
                electricCost + other.electricCost,
                dieselEmissions + other.dieselEmissions,
                electricEmissions + other.electricEmissions);
    }

    public double consumption(PowerType type) {
        return type == PowerType.COMBUSTION ? dieselLiters : electricKwh;
    }

    public double cost(PowerType type) {
        return type == PowerType.COMBUSTION ? dieselCost : electricCost;
    }

    public double emissions(PowerType type) {
        return type == PowerType.COMBUSTION ? dieselEmissions : electricEmissions;
    }

}
