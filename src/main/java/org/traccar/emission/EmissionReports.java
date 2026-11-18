package org.traccar.emission;

import java.util.Date;
import java.util.List;

/**
 * Fork: responses of the emission report endpoints ({@code /api/v1/reports/emissions/*}), shaped for the charts and
 * cards of the front so it does not download positions or compute anything.
 */
public final class EmissionReports {

    private EmissionReports() {
    }

    public record DeviceInfo(long id, String name, String category, PowerType powerType) {
    }

    public record Units(String distance, String consumption, String cost, String emissions, String efficiency) {

        public static Units of(PowerType type) {
            return new Units("km", type.getConsumptionUnit(), "R$", "tCO2e", type.getEfficiencyUnit());
        }

    }

    /**
     * One month or one hour. {@code consumption}, {@code cost} and {@code emissions} are the real values for the
     * vehicle power type; the diesel and electric fields hold both, real and projected.
     */
    public record Row(
            String period,
            String label,
            Date start,
            Date end,
            double distanceKm,
            double consumption,
            double cost,
            double emissions,
            double dieselLiters,
            double electricKwh,
            double dieselCost,
            double electricCost,
            double dieselEmissions,
            double electricEmissions) {

        public static Row of(String period, String label, Date start, Date end, EmissionAmounts a, PowerType type) {
            return new Row(period, label, start, end, a.distanceKm(), a.consumption(type), a.cost(type),
                    a.emissions(type), a.dieselLiters(), a.electricKwh(), a.dieselCost(), a.electricCost(),
                    a.dieselEmissions(), a.electricEmissions());
        }

    }

    /**
     * Totals of the interval. For electric vehicles {@code emissionsAvoided} and {@code savings} compare with the
     * same distance done by a diesel vehicle; for combustion vehicles they are zero. {@code efficiency} is null when
     * nothing was consumed.
     */
    public record Summary(
            double distanceKm,
            double consumption,
            double cost,
            double emissions,
            Double efficiency,
            double emissionsAvoided,
            double savings) {

        public static Summary of(EmissionAmounts total, PowerType type) {
            double consumption = total.consumption(type);
            boolean electric = type == PowerType.ELECTRIC;
            return new Summary(
                    total.distanceKm(),
                    consumption,
                    total.cost(type),
                    total.emissions(type),
                    consumption > 0 ? total.distanceKm() / consumption : null,
                    electric ? total.dieselEmissions() - total.electricEmissions() : 0,
                    electric ? total.dieselCost() - total.electricCost() : 0);
        }

    }

    /**
     * Value of a parameter over consecutive days ({@code from} and {@code to} inclusive). {@code factorId} is null
     * when the default applied.
     */
    public record FactorSegment(
            String parameter,
            String unit,
            String from,
            String to,
            double value,
            Long factorId,
            String period,
            String source) {
    }

    public record Period(String period, String label) {
    }

    public record MonthlyReport(
            DeviceInfo device,
            String timezone,
            String from,
            String to,
            Units units,
            Summary summary,
            List<Row> months,
            List<FactorSegment> factors) {
    }

    public record FleetDevice(DeviceInfo device, Units units, Summary summary, List<Row> months) {
    }

    public record FleetReport(
            String timezone,
            String from,
            String to,
            List<Period> months,
            List<FleetDevice> devices) {
    }

    public record InstantReport(
            DeviceInfo device,
            String timezone,
            Date from,
            Date to,
            Units units,
            Summary summary,
            List<Row> hours,
            List<double[]> route,
            List<FactorSegment> factors) {
    }

}
