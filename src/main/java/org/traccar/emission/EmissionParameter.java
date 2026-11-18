package org.traccar.emission;

import java.util.Arrays;
import java.util.Optional;

/**
 * Fork: parameters used by the emission and cost reports. The defaults are the values of the UFPA calculation
 * memorial (previously hardcoded in the front) and apply while no {@link EmissionFactor} covers a date.
 */
public enum EmissionParameter {

    DIESEL_EFFICIENCY("dieselEfficiency", "km/L", 2.5,
            "Rendimento do veículo a diesel"),
    DIESEL_EMISSION_FACTOR("dieselEmissionFactor", "kgCO2e/km", 1.44184,
            "Fator de emissão do diesel por quilômetro"),
    DIESEL_PRICE("dieselPrice", "R$/L", 5.95,
            "Preço do litro de diesel"),
    ELECTRIC_CONSUMPTION("electricConsumption", "kWh/km", 0.8,
            "Consumo de energia do veículo elétrico"),
    ELECTRIC_EMISSION_FACTOR("electricEmissionFactor", "kgCO2e/kWh", 0.3497,
            "Fator de emissão da energia elétrica (rede)"),
    ENERGY_TARIFF("energyTariff", "R$/kWh", 0.72,
            "Tarifa de energia elétrica");

    private final String key;
    private final String unit;
    private final double defaultValue;
    private final String description;

    EmissionParameter(String key, String unit, double defaultValue, String description) {
        this.key = key;
        this.unit = unit;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    public String getKey() {
        return key;
    }

    public String getUnit() {
        return unit;
    }

    public double getDefaultValue() {
        return defaultValue;
    }

    public String getDescription() {
        return description;
    }

    /** Efficiency and consumption divide or multiply distance, so they must be strictly positive. */
    public boolean requiresPositive() {
        return this == DIESEL_EFFICIENCY || this == ELECTRIC_CONSUMPTION;
    }

    public static Optional<EmissionParameter> fromKey(String key) {
        return Arrays.stream(values()).filter(parameter -> parameter.key.equals(key)).findFirst();
    }

}
