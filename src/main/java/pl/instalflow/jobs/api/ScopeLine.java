package pl.instalflow.jobs.api;

import java.math.BigDecimal;
import java.util.Objects;

/** Agreed quantity and net unit price in PLN, copied without recalculation. */
public record ScopeLine(
        Type type,
        String description,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPriceNet) {

    public ScopeLine {
        Objects.requireNonNull(type, "type");
        requireText(description, "description");
        requireDecimal(quantity, "quantity", 3, true);
        requireText(unit, "unit");
        requireDecimal(unitPriceNet, "unitPriceNet", 2, false);
    }

    public enum Type { MATERIAL, LABOR }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    static void requireDecimal(BigDecimal value, String field, int scale, boolean positive) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0 || (positive && value.signum() == 0) || value.scale() > scale) {
            throw new IllegalArgumentException(field + " has invalid value or scale");
        }
    }
}
