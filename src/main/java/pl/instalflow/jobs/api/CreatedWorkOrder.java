package pl.instalflow.jobs.api;

import java.util.Objects;
import java.util.UUID;

public record CreatedWorkOrder(UUID id, String reference) {

    public CreatedWorkOrder {
        Objects.requireNonNull(id, "id");
        ScopeLine.requireText(reference, "reference");
    }
}
