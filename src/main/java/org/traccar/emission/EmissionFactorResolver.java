package org.traccar.emission;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fork: picks the value of each parameter for a date from the factor history. For a date and a device category:
 * <ol>
 * <li>a factor whose period contains the date wins, the most specific period first (day, then month, then
 * year);</li>
 * <li>otherwise the factor whose period ended most recently before the date is carried forward;</li>
 * <li>otherwise the parameter default applies.</li>
 * </ol>
 * At each step a factor of the device category is preferred over a factor without category. Immutable and thread
 * safe.
 */
public final class EmissionFactorResolver {

    /** Value applied on a date; {@code factor} is null when the parameter default was used. */
    public record Resolved(EmissionParameter parameter, double value, EmissionFactor factor) {
    }

    private final Map<EmissionParameter, List<EmissionFactor>> factors = new EnumMap<>(EmissionParameter.class);

    public EmissionFactorResolver(Collection<EmissionFactor> history) {
        for (EmissionParameter parameter : EmissionParameter.values()) {
            factors.put(parameter, new ArrayList<>());
        }
        for (EmissionFactor factor : history) {
            EmissionParameter.fromKey(factor.getParameter()).ifPresent(parameter -> {
                if (EmissionFactor.isValidPeriod(factor.getPeriod())) {
                    factors.get(parameter).add(factor);
                }
            });
        }
    }

    public Resolved resolve(EmissionParameter parameter, String category, LocalDate date) {
        List<EmissionFactor> candidates = factors.get(parameter);
        EmissionFactor factor = null;
        if (category != null && !category.isEmpty()) {
            factor = find(candidates, category, date);
        }
        if (factor == null) {
            factor = find(candidates, null, date);
        }
        return factor != null
                ? new Resolved(parameter, factor.getFactorValue(), factor)
                : new Resolved(parameter, parameter.getDefaultValue(), null);
    }

    public Map<EmissionParameter, Resolved> resolveAll(String category, LocalDate date) {
        Map<EmissionParameter, Resolved> result = new EnumMap<>(EmissionParameter.class);
        for (EmissionParameter parameter : EmissionParameter.values()) {
            result.put(parameter, resolve(parameter, category, date));
        }
        return result;
    }

    private static EmissionFactor find(List<EmissionFactor> candidates, String category, LocalDate date) {
        EmissionFactor covering = null;
        EmissionFactor previous = null;
        for (EmissionFactor factor : candidates) {
            if (!Objects.equals(emptyToNull(factor.getCategory()), category)) {
                continue;
            }
            LocalDate start = factor.getStart();
            LocalDate end = factor.getEnd();
            if (!date.isBefore(start) && date.isBefore(end)) {
                // longer period string = more specific (YYYY < YYYY-MM < YYYY-MM-DD)
                if (covering == null || factor.getPeriod().length() > covering.getPeriod().length()) {
                    covering = factor;
                }
            } else if (!end.isAfter(date)) {
                if (previous == null || end.isAfter(previous.getEnd())
                        || end.equals(previous.getEnd()) && start.isAfter(previous.getStart())) {
                    previous = factor;
                }
            }
        }
        return covering != null ? covering : previous;
    }

    static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

}
