package pl.instalflow.jobs.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot at acceptance. All amounts are net PLN; time is an Instant.
 * Foreign objects are represented by IDs and copied values, never JPA entities.
 * plannedStartAt may be null when no date has been agreed.
 */
public record CreateFromAcceptedOffer(
        UUID sourceOfferId,
        UUID customerId,
        CustomerSnapshot customer,
        UUID serviceAddressId,
        AddressSnapshot serviceAddress,
        List<ScopeLine> scope,
        BigDecimal agreedTotalNet,
        Instant plannedStartAt,
        Instant acceptedAt,
        UUID acceptedByUserId) {

    public CreateFromAcceptedOffer {
        Objects.requireNonNull(sourceOfferId, "sourceOfferId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(serviceAddressId, "serviceAddressId");
        Objects.requireNonNull(serviceAddress, "serviceAddress");
        scope = List.copyOf(scope);
        if (scope.isEmpty()) {
            throw new IllegalArgumentException("scope must not be empty");
        }
        ScopeLine.requireDecimal(agreedTotalNet, "agreedTotalNet", 2, false);
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(acceptedByUserId, "acceptedByUserId");
    }
}
