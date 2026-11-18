package org.traccar.emission;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.traccar.model.BaseModel;
import org.traccar.storage.QueryIgnore;
import org.traccar.storage.StorageName;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Fork: value of one emission/cost parameter for a period. The period is a year ({@code 2026}), a month
 * ({@code 2026-03}) or a day ({@code 2026-03-15}), interpreted in the report timezone. An optional device category
 * restricts the value to devices of that category; without it the value applies to every category.
 */
@StorageName("tc_emission_factors")
@JsonIgnoreProperties(value = "granularity", allowGetters = true)
public class EmissionFactor extends BaseModel {

    private static final Pattern PERIOD = Pattern.compile("\\d{4}(-\\d{2}(-\\d{2})?)?");

    public enum Granularity {
        YEAR,
        MONTH,
        DAY,
    }

    private String parameter;

    public String getParameter() {
        return parameter;
    }

    public void setParameter(String parameter) {
        this.parameter = parameter;
    }

    private String category;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    private String period;

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    // "value" is a reserved word in H2, so the column is factorValue; the API keeps "value"
    private double factorValue;

    @JsonProperty("value")
    public double getFactorValue() {
        return factorValue;
    }

    @JsonProperty("value")
    public void setFactorValue(double factorValue) {
        this.factorValue = factorValue;
    }

    private String source;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public static boolean isValidPeriod(String period) {
        if (period == null || !PERIOD.matcher(period).matches()) {
            return false;
        }
        try {
            periodStart(period);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static Granularity granularity(String period) {
        return switch (period.length()) {
            case 4 -> Granularity.YEAR;
            case 7 -> Granularity.MONTH;
            default -> Granularity.DAY;
        };
    }

    static LocalDate periodStart(String period) {
        return switch (granularity(period)) {
            case YEAR -> LocalDate.parse(period + "-01-01");
            case MONTH -> LocalDate.parse(period + "-01");
            case DAY -> LocalDate.parse(period);
        };
    }

    /** First day after the period. */
    static LocalDate periodEnd(String period) {
        LocalDate start = periodStart(period);
        return switch (granularity(period)) {
            case YEAR -> start.plusYears(1);
            case MONTH -> start.plusMonths(1);
            case DAY -> start.plusDays(1);
        };
    }

    @QueryIgnore
    public Granularity getGranularity() {
        return period != null ? granularity(period) : null;
    }

    @JsonIgnore
    @QueryIgnore
    public LocalDate getStart() {
        return periodStart(period);
    }

    @JsonIgnore
    @QueryIgnore
    public LocalDate getEnd() {
        return periodEnd(period);
    }

}
