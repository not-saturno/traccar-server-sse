package org.traccar.emission;

import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;

/**
 * Fork: storage of the emission factor history. The table is small and read by every report, so it is kept in
 * memory and reloaded after each change made through this service.
 */
@Singleton
public class EmissionFactorService {

    private static final int MAX_SOURCE_LENGTH = 512;
    private static final int MAX_CATEGORY_LENGTH = 128;

    private final Storage storage;

    private record Snapshot(List<EmissionFactor> factors, EmissionFactorResolver resolver) {
    }

    private volatile Snapshot snapshot;

    @Inject
    public EmissionFactorService(Storage storage) {
        this.storage = storage;
    }

    public List<EmissionFactor> getFactors() throws StorageException {
        return load().factors();
    }

    public EmissionFactorResolver getResolver() throws StorageException {
        return load().resolver();
    }

    public EmissionFactor getFactor(long id) throws StorageException {
        return getFactors().stream().filter(factor -> factor.getId() == id).findFirst().orElse(null);
    }

    public EmissionFactor add(EmissionFactor factor) throws StorageException {
        validate(factor, 0);
        factor.setId(storage.addObject(factor, new Request(new Columns.Exclude("id"))));
        invalidate();
        return factor;
    }

    public EmissionFactor update(EmissionFactor factor) throws StorageException {
        validate(factor, factor.getId());
        storage.updateObject(factor, new Request(
                new Columns.Exclude("id"), new Condition.Equals("id", factor.getId())));
        invalidate();
        return factor;
    }

    public void remove(long id) throws StorageException {
        storage.removeObject(EmissionFactor.class, new Request(new Condition.Equals("id", id)));
        invalidate();
    }

    private Snapshot load() throws StorageException {
        Snapshot current = snapshot;
        if (current == null) {
            synchronized (this) {
                current = snapshot;
                if (current == null) {
                    List<EmissionFactor> loaded = List.copyOf(storage.getObjects(EmissionFactor.class, new Request(
                            new Columns.All(), new Order("period"))));
                    current = new Snapshot(loaded, new EmissionFactorResolver(loaded));
                    snapshot = current;
                }
            }
        }
        return current;
    }

    private synchronized void invalidate() {
        snapshot = null;
    }

    private void validate(EmissionFactor factor, long id) throws StorageException {
        EmissionParameter parameter = EmissionParameter.fromKey(factor.getParameter())
                .orElseThrow(() -> new IllegalArgumentException("Unknown parameter " + factor.getParameter()));
        if (!EmissionFactor.isValidPeriod(factor.getPeriod())) {
            throw new IllegalArgumentException("Period must be YYYY, YYYY-MM or YYYY-MM-DD");
        }
        double value = factor.getFactorValue();
        if (!Double.isFinite(value) || value < 0 || parameter.requiresPositive() && value == 0) {
            throw new IllegalArgumentException("Invalid value for " + parameter.getKey());
        }
        String category = EmissionFactorResolver.emptyToNull(
                factor.getCategory() != null ? factor.getCategory().trim() : null);
        if (category != null && category.length() > MAX_CATEGORY_LENGTH) {
            throw new IllegalArgumentException("Category too long");
        }
        factor.setCategory(category);
        if (factor.getSource() != null && factor.getSource().length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException("Source too long");
        }
        boolean duplicate = getFactors().stream().anyMatch(other -> other.getId() != id
                && other.getParameter().equals(factor.getParameter())
                && other.getPeriod().equals(factor.getPeriod())
                && Objects.equals(EmissionFactorResolver.emptyToNull(other.getCategory()), category));
        if (duplicate) {
            throw new IllegalArgumentException("A value for this parameter, period and category already exists");
        }
    }

}
