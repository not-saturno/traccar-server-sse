package org.traccar.emission;

/**
 * Fork: how a vehicle is powered, derived from the device category (see {@code emissions.combustionCategories}).
 */
public enum PowerType {

    ELECTRIC("kWh", "km/kWh"),
    COMBUSTION("L", "km/L");

    private final String consumptionUnit;
    private final String efficiencyUnit;

    PowerType(String consumptionUnit, String efficiencyUnit) {
        this.consumptionUnit = consumptionUnit;
        this.efficiencyUnit = efficiencyUnit;
    }

    public String getConsumptionUnit() {
        return consumptionUnit;
    }

    public String getEfficiencyUnit() {
        return efficiencyUnit;
    }

}
